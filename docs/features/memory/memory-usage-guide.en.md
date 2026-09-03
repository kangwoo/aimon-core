---
translated_from: docs/features/memory/memory-usage-guide.md
source_commit: 71979ac
---

# Memory (Peer Memory) Usage Guide

> This document covers how to *use and integrate* AIMON's Honcho-analogue **peer memory** layer.
> For the design background and the internal specification, see [`peer-memory.md`](../../design/memory/peer-memory.md).

The focus here is "how do I switch memory on, which tools does it expose, and how do I assemble it myself".

## Table of contents

1. [Overview](#1-overview)
2. [The conceptual model](#2-the-conceptual-model)
3. [Getting started — switching it on from the CLI config](#3-getting-started--switching-it-on-from-the-cli-config)
4. [How it flows (lifecycle)](#4-how-it-flows-lifecycle)
5. [The four exposed tools](#5-the-four-exposed-tools)
6. [Automatic context injection](#6-automatic-context-injection)
7. [Configuration reference](#7-configuration-reference)
8. [Redaction (the secret/PII gate)](#8-redaction-the-secretpii-gate)
9. [Choosing a storage backend](#9-choosing-a-storage-backend)
10. [Assembling it programmatically (a minimal in-memory skeleton)](#10-assembling-it-programmatically-a-minimal-in-memory-skeleton)
11. [Implementing another storage backend (the SPI spec)](#11-implementing-another-storage-backend-the-spi-spec)
12. [Operational notes / troubleshooting](#12-operational-notes--troubleshooting)
13. [Related code / documents](#13-related-code--documents)

---

## 1. Overview

Peer memory is the long-term memory layer: the agent derives **facts (observations) about operators, systems and services** from conversation,
summarises them into **insights that cross sessions (representations)**, and injects those back as context for the next conversation.

| Component | Role | Honcho equivalent |
|----------|------|------------|
| **Deriver** | Derives observations from messages (LLM) | `deriver` |
| **Dialectic Engine** | Answers natural-language questions | `peer.chat()` |
| **Dreamer** | Background memory refinement/consolidation (Quartz cron) | `dreamer` |
| **Reconciler** | Reconciles conflicting observations (LLM-as-judge) | — |
| **RedactionPolicy** | The secret/PII masking gate | — |

> Short-term (within-session) conversation compaction is the responsibility of `at.aimon.core.agent.compact`. The memory layer handles **cross-session (long-term)** representation; the two complement each other.

---

## 2. The conceptual model

Every domain object is an immutable class in the `at.aimon.core.memory` package.

```
Workspace            the tenant-isolation unit (the root of multi-tenancy)
   └─ PeerView       a (Workspace, Principal) pairing — "some subject inside this workspace"
        ├─ Observation     a single observed fact (content, type, confidence, sourceMessageIds)
        └─ Representation  a snapshot of peer insight at a point in time (summary + observations + tokenCount)
```

- **`Workspace`** — `Workspace.builder().id("default").build()`
- **`PeerView`** — `PeerView.of(workspace, Principal.user("ops-bot", "Ops Bot"))`
  - `subject` = the fact's *target* / `observer` = the *one who observed it*. In the CLI's default wiring, `subject == observer` (the agent itself).
- **`Observation`** — `ObservationType` is either `EXPLICIT` (directly stated) or `DEDUCTIVE` (inferred). `confidence` lies in `[0,1]`.
- **`Representation`** — its scope is split by `isGlobal()` (observer==null, session-independent) and `isLocal()` (bound to an observer and a session).

> Every id-based store API takes a `Workspace` or a workspace-bound value object (`ObservationId`) — multi-tenant isolation is enforced at compile time (ArchUnit).

---

## 3. Getting started — switching it on from the CLI config

The easiest path is to add a `memory:` block to `aimon.yaml` (the CLI config).
All three fields (`workspaceId`, `peerId`, `storagePath`) must be filled in before memory activates (`MemoryConfig.isEnabled()`).

```yaml
memory:
  workspaceId: "default"                              # required — the multi-tenant isolation key
  peerId: "agent-default"                             # required — the agent's own peer id (observer=subject)
  peerName: "Aimon Agent"                             # optional — falls back to peerId
  storagePath: ".aimon/memory/representations.jsonl"  # required — the JSONL log (observations.jsonl is created alongside it)
  backend: "file"                                     # optional — file (default, persistent) | in-memory (volatile, dev/test)
  reconcilerEnabled: false                            # optional — opt in to the LLM-as-judge reconciler in the session-end deriver
  dreamer:                                            # optional — background consolidation (see §7 below)
    enabled: false
```

Once the config is active, `AgentSetupFactory` wires the following automatically (choosing the store implementations from the `backend` value):

- **`backend: file` (the default)** — `FileRepresentationStore` (`storagePath`) + `FileObservationStore` (`observations.jsonl`, a sibling of `storagePath`). Both survive a restart. (The workspace itself is rebuilt from this config on every boot and is not persisted in any backend.)
- **`backend: in-memory`** — `InMemoryRepresentationStore` + `InMemoryObservationStore` (lost on restart; dev/test).
- Registration of the four user-facing tools (MemorySearch / Observe / MemoryChat / MemoryRecall)
- `MemoryToolContextEnricher` — injects workspace/observer/subject/sessionId into every tool call
- `SnapshotMemoryContextProvider` — injects the insight summary into the system prompt on every turn (`SUMMARY_ONLY`)
- The **final derivation** that runs once at session end (conversation → observations)
- (when `dreamer.enabled=true`) the background consolidation job on its own dedicated Quartz scheduler

> ℹ️ **The search index**: with the file backend, `FileObservationStore` provides `semanticSearch` through the built-in `InMemoryObservationIndex` (substring matching) — the index itself lives only as long as the process (rebuilt by replaying the log on restart). If you need embedding-based vector search or multiple instances, extend along the PostgreSQL + `IndexedObservationStore` + `KnowledgeStore` path in §9.2 (not currently wired in the CLI).

> ⚠️ **Behaviour change / log growth**:
> - **The default changed** — an existing config that does not name a `backend` now runs with the default `file`, which means observations are **newly persisted** into `observations.jsonl` (previously they were in-memory and therefore volatile). To keep the old behaviour, write `backend: in-memory` explicitly.
> - **Append-only log growth** — the `File*Store`s are append logs, so `save`/`delete`/`merge` each add a line. There is no compaction or retention, so the JSONL files grow monotonically over a long deployment. In production, consider periodic cleanup (or the PostgreSQL backend in §9.2).

---

## 4. How it flows (lifecycle)

```
conversation proceeds ──▶ (session ends) the final derivation is enqueued
                     │
                     ▼
        DerivationQueueManager ──★ RedactionPolicy.redact() (masks secrets)
                     │
                     ▼
                  LlmDeriver ──▶ (optional) Reconciler.evaluate ──▶ ObservationStore.save
                     │
                     └──▶ Representation summary built ──▶ RepresentationStore.save
                                                                  │
the next conversation starts ◀── SnapshotMemoryContextProvider.provide() (injected into the system prompt)

(background) Dreamer cron ──▶ RandomWalk consolidation ──▶ SurprisalScorer ──▶ ObservationStore.merge
```

- **What triggers the deriver (CLI)**: not every message — the whole conversation history is processed in one go **when the REPL exits** (`memoryFinalDerivation`). The derivation itself runs asynchronously on a queue worker, and `AgentSetup.close()` stops the queue and drains the in-flight work.
- **The deriver's model**: in the CLI it simply uses the agent's global LLM model (`llm.model`). The queue tuning values (`DeriverProperties`) have no model-name entry at all — the deriver already carries its model from the moment it is constructed.
- **The recall path**: automatic injection (`SUMMARY_ONLY`) is cheap and happens every turn; when the full observation list is needed, the LLM calls the `MemoryRecall` tool explicitly.
- **The producer of GLOBAL representations is the Dreamer**: cross-session **GLOBAL** representations (`findLatestGlobal`, and `MemoryRecall`'s `GLOBAL` mode) are not written by the final derivation — the **Dreamer deterministically regenerates them each cycle from each subject's current observations** (with no extra LLM cost). So GLOBAL recall only has data after the Dreamer has run at least once (with `dreamer.enabled=false`, GLOBAL may stay empty).

---

## 5. The four exposed tools

All four are `AbstractTool` implementations in the `at.aimon.core.tools.memory` package, and they return `ToolResult.error()` rather than throwing on failure.

IMPORTANT: **which tools are registered follows from what the backend can do.** The assembly reads
`MemoryCapabilities.of(peerMemory)` — a set *computed* from the tier accessors rather than declared by the backend —
and registers `MemoryRecall` (SNAPSHOT), `MemorySearch` (SEARCH), `Observe` (OBSERVE) and `MemoryChat` (CHAT) one at a
time. A tool the backend cannot serve is **not registered at all**: registering it and answering "not supported" would
put it in front of the model on every execution, and the model would keep calling it, spending iterations and prompt
budget on a failure that was decidable at assembly. Each missing capability raises one startup degradation
(`memory-snapshot`, `memory-search`, `memory-chat`, `memory-observe`, `memory-ingest`).

`MemoryChat` was registered **only by the CLI** until this rule existed — `MemorySpec` had nowhere to put a
`DialecticEngine`, so a deployment assembled through the starter could not use that tool whatever its backend was.
Now it appears wherever the CHAT tier does, and `memory-chat` is raised where it does not.
All four share the following **ToolContext keys** (`MemoryToolContextKeys`) — normally filled in by `MemoryToolContextEnricher`:

| Key constant | Key name | Type | Notes |
|--------|--------|------|------|
| `WORKSPACE` | `memory.workspace` | `Workspace` | required |
| `OBSERVER` | `memory.observer` | `PeerView` | the observing/asking subject |
| `SUBJECT` | `memory.subject` | `PeerView` | falls back to observer when unset |
| `SESSION_ID` | `memory.sessionId` | `String` | optional (for correlating LOCAL scope) |

### `MemorySearch` (`MemorySearchTool`)

Keyword/semantic search over a peer's observations. For when you need raw observation snippets (+confidence) rather than a synthesised answer.

| Parameter | Type | Required | Default |
|---------|------|------|------|
| `query` | string | ✅ | — |
| `top_k` | number | | 10 (max 50) |

### `MemoryChat` (`MemoryChatTool`)

Handles natural-language questions of the "what do you know about this user?" kind through the `DialecticEngine`.

| Parameter | Type | Required | Default |
|---------|------|------|------|
| `question` | string | ✅ | — |
| `level` | string(`FAST`\|`BALANCED`\|`DEEP`) | | `BALANCED` |

### `MemoryRecall` (`MemoryRecallTool`)

Recalls the latest representation snapshot as context.

| Parameter | Type | Required | Default |
|---------|------|------|------|
| `mode` | string(`GLOBAL`\|`LOCAL`) | | `GLOBAL` |
| `max_tokens` | number | | 0 (no budget applied) |

- `LOCAL` mode requires `memory.observer`.
- When `max_tokens` is exceeded, the observations are dropped and only the summary is returned.

### `Observe` (`ObserveTool`)

Registers a single fact explicitly, bypassing the deriver (admin/system flows, data import).

| Parameter | Type | Required | Default |
|---------|------|------|------|
| `content` | string | ✅ | — |
| `type` | string(`EXPLICIT`\|`DEDUCTIVE`) | | `DEDUCTIVE` |
| `confidence` | number `[0,1]` | | 0.7 |

> When a `RedactionPolicy` is injected, `MemorySearch` and `Observe` mask their input (query/content) before storing or searching (§8).
> On top of that, the conversation entering the deriver queue is masked by the shared `MessageRedactor` across **every message (not just USER/ASSISTANT text, but the tool-result bodies of TOOL-role messages too)**, so secrets that leaked into command or log output are hidden before they reach the LLM or an observation (§8).

---

## 6. Automatic context injection

`MemoryContextProvider` is called while the agent assembles its system prompt, and contributes a memory-derived `SystemPromptPart`.
The default implementation, `SnapshotMemoryContextProvider`, stands on the backend's **SNAPSHOT tier**
(`MemorySnapshotReader`) and resolves in this order:

1. The latest **LOCAL** snapshot for `(subject, observer, sessionId)`
2. Failing that, the latest **GLOBAL** snapshot for `subject` (GLOBAL is produced by the Dreamer — it only exists after the Dreamer has run once; see §4)
3. Failing that, `Optional.empty()` → the executor omits the part (the prompt's shape is unchanged)

On the default backend that snapshot is a `Representation` out of `RepresentationStore`, but because the
provider stands on the tier, a backend that computes its snapshot on read instead of storing one works
through the same provider. `SnapshotMemoryContextProvider.readerOver(representationStore)` builds that tier
over a store.

How it renders is decided by `MemoryInjectionMode`:

- `SUMMARY_ONLY` (the CLI default) — the summary plus a one-line meta header. It costs something every turn, so it is kept cheap.
- `FULL` — summary plus observations. When a positive `maxTokens` is exceeded, the observations are dropped (the summary is always kept).

It is injected into the executor with `OrcaAgentExecutorFactory.withMemoryContextProvider(...)`. Pass `null` and the agent runs with no memory part at all.

---

## 7. Configuration reference

### 7.1 The CLI `memory` block (`MemoryConfig`)

| Key | Type | Required | Description |
|----|------|------|------|
| `workspaceId` | string | ✅ | The multi-tenant isolation key |
| `peerId` | string | ✅ | The agent's peer id (observer=subject) |
| `peerName` | string | | Display name (defaults to `peerId`) |
| `storagePath` | string | ✅ | The JSONL log path (representations.jsonl; `observations.jsonl` is created alongside it) |
| `backend` | string | | `file` (default, persistent) \| `in-memory` (volatile, dev/test). An unknown value falls back to file (with a warning) |
| `ingest` | string | | When conversation flows into memory: `off` \| `session-end` (default) \| `execution-end`. An unknown value falls back to `session-end` (with a warning) |
| `reconcilerEnabled` | bool | | Opt in to the LLM-as-judge reconciler in the session-end deriver (default false) |
| `dreamer` | object | | Background consolidation (below) |

#### `ingest` — what the three values change

| Value | When it sends | What it costs |
|----|------------|------|
| `off` | Never | Memory fills only through `Observe` calls or another process |
| `session-end` (default) | The whole transcript, once, when the REPL exits | Today's behaviour. It uses no delta, so the same message cannot go twice. In exchange, what a session learns is not available to that session |
| `execution-end` | The messages an execution added, as it ends | The deriver runs per execution (more LLM calls). In exchange, memory is usable inside the session that is producing it |

IMPORTANT: `execution-end` has one loss and it is deliberate. The delta is anchored on a **message count**
(`Message` has no stable id), so a compaction or a prompt-size recovery that replaces the history wholesale leaves
that anchor pointing nowhere. That execution then sends **nothing**, and the next one anchors afresh — cheaper than
sending a summary as if it were conversation, and cheaper than re-sending messages already ingested. The reasoning is
in [Pluggable memory backend](../../design/memory/pluggable-memory-backend.md) §7.2.

### 7.2 The `memory.dreamer` block (`MemoryDreamerConfig`)

```yaml
memory:
  # ... workspaceId/peerId/storagePath ...
  dreamer:
    enabled: true                  # the gate (default false)
    cron: "*/30 * * * *"           # 5-field cron (default: every 30 minutes)
    scorer:
      type: llm                    # llm (default) | embedding
      llm:                         # type=llm — no extra credentials needed
        model: "gpt-4o-mini"       # optional — override the judge model (default: the global LLM model)
      embedding:                   # required when type=embedding (currently OpenAI-compatible)
        apiKey: "${OPENAI_KEY}"    # required
        baseUrl: "https://api.openai.com/v1"  # optional
        model: "text-embedding-3-small"       # optional
        dimensions: 1536                       # optional
    surprisalThreshold: 0.25       # optional — pairs below the threshold are merged (default 0.25)
    walkSeedCount: 8               # optional — how many recent seeds per subject (default 8)
    neighborTopK: 8                # optional — the semanticSearch fan-out per seed (default 8)
```

- `scorer.type=llm` is always ready (it reuses the global LLM). `embedding` is **fail-soft**: without `scorer.embedding.apiKey` it is disabled and the reason is logged at startup (`notReadyReason()`).
- `cron` is the framework's common **5-field** dialect (minute hour day-of-month month day-of-week, Sunday=0). Quartz's 6-field form (`"0 */30 * * * ?"`) is **rejected at startup** — translating to Quartz is the backend's job. If your config used numeric weekdays, subtract 1 (Quartz's Friday 6 → 5 here).
- The dreamer runs on its own dedicated Quartz scheduler (RAMJobStore) so it does not contend with the foreground task scheduler.
- **It is single-node only.** A RAMJobStore is JVM-local by definition, so it does not share jobs across processes. Start two CLIs against the same workspace and consolidation runs twice — this is not something you fix by reducing the number of schedulers, it is **because the job store is not shared**; clustering would require a shared JDBC JobStore.
- Besides consolidation (merge), every dreamer cycle also (a) **regenerates each subject's GLOBAL representation from its current observations** (it is the producer of the data GLOBAL recall reads) and (b) **purges soft-deleted observations past the 30-day audit window** (`purgeSoftDeletedBefore`).

### 7.3 The Spring Boot starter's `aimon.memory.*`

`aimon-spring-boot-starter` binds **only the read path** through properties. The values are **selectors**, not booleans,
and there are no guessing defaults — memory bound to the wrong peer quietly answers with *somebody else's* history.

| Key | Value | Required | Default |
|----|-----|------|--------|
| `aimon.memory.backend` | `none` \| `in-memory` \| `supplied` | | `none` (not wired) |
| `aimon.memory.workspace-id` | string | ✅ (backend ≠ `none`) | — |
| `aimon.memory.peer-mode` | `fixed` \| `caller` | | `fixed` |
| `aimon.memory.peer-id` | string | ✅ (peer-mode=`fixed`) | — |
| `aimon.memory.injection-mode` | `SUMMARY_ONLY` \| `FULL` | | `SUMMARY_ONLY` (§6) |
| `aimon.memory.max-tokens` | int | | `0` (no cap) |
| `aimon.memory.redaction` | `default` \| `strict` \| `none` \| `supplied` | | `default` |

- `backend=supplied` uses the `RepresentationStore` / `ObservationStore` beans the application declares. Declaring just one of
  them is fine — with only the observation store you get the tools, with only the representation store you get the injection.
- When a selector and the beans **contradict each other, it refuses and names them** (`backend=none` yet a store bean exists,
  `peer-mode=caller` yet `peer-id` is set, and so on). A store that was silently ignored is indistinguishable at runtime from
  an empty one.
- `redaction` is **the one key in this slice with a quiet default**. Masking has only one safe direction, so it is a
  deliberate exception (`MemoryRedaction.DEFAULT`).

The **two things the starter does not give you** are reported out loud at startup through `RuntimeDegradations` (`MemoryAssembly`):

- **No write path** — the deriver, the derivation queue and the dreamer are not wired. The `Observe` tool does write observations,
  but there is nothing to inject unless another process looking at the same store produces the representations.
- **Under `peer-mode=caller` the memory tools are not registered** — a tool receives its observer through the
  `ToolContextEnricher` seam, and that seam carries only the session, the execution and the runtime — **not the principal**.
  Prompt injection can resolve the peer afresh on each execution, but a tool can only hold one fixed observer.

Under `fixed` mode, three of the four tools from §5 are registered: `MemoryRecall`, `MemorySearch` and `Observe`.
`MemoryChat` requires a `DialecticEngine`, so it is absent from the starter's wiring (the CLI wires it along the §7.1 path).

### 7.4 The core has no memory configuration tree

There is exactly **one** model describing a deployment's memory — `at.aimon.bootstrap.spec.MemorySpec` — and both surfaces above
(the CLI yaml `memory` block and the starter's `aimon.memory.*`) bind into it. The core has no overlapping property tree.

The only configuration value object left in the core is **`at.aimon.core.memory.deriver.DeriverProperties`**, and it holds
three things about how the derivation queue runs:

| Value | Default (`defaults()`) | Meaning |
|----|---------------------|-----|
| `workerCount` | 4 | How many worker threads drain the queue (≥ 1, otherwise construction is refused) |
| `batchMaxTokens` | 8000 | The token budget one worker may put into a single batch (≥ 1, otherwise construction is refused) |
| `pollInterval` | 500ms | How long to wait before asking an empty queue again |

There is no model-name entry — the deriver already carries its own model from the moment it is constructed.

> **A correction to the record**: this slot used to hold `at.aimon.core.memory.MemoryProperties`, which declared defaults across
> five areas — backend/deriver/dialectic/dreamer/redaction (`backend=in-memory` and so on). Only the deriver part was actually
> read; the rest were **defaults that no assembly path ever bound** — a state worse than having no value at all.
> Anyone reading the documentation took `in-memory` for the answer, while the CLI was running with `file` and the starter was
> "refusing rather than guessing". The one stage that was being read stayed on as `DeriverProperties` above; the rest was deleted
> (the design rationale: [`spring-boot-starter.md`](../../design/integration/spring-boot-starter.md) §7.2.15).

### 7.5 Choices not yet exposed through the CLI (programmatic-only)

The following can currently be chosen **only by assembling programmatically (§10/§11)** in the core and its modules; they are not yet exposed on the CLI's yaml configuration surface (a future CLI config surface is planned — this is un-exposed, not broken):

- **Choosing the redaction policy (`default`|`strict`|`none`|`custom`)** — the CLI always uses `DefaultRedactionPolicy`.
- **The PostgreSQL backend (`aimon-memory-postgres`)** — the CLI wires only `file`/`in-memory` (§9.2 is the hand-assembly path).
- **`ReActLlmDeriver`** — the CLI uses the single-shot `LlmDeriver`.

The combination the CLI currently uses is: `DefaultRedactionPolicy` + `LlmDeriver` + the configured `file`/`in-memory` backend.

---

## 8. Redaction (the secret/PII gate)

Tokens, passwords, API keys and private IPs turn up routinely in IT operations messages. Persisting them as observations is a security incident on the spot,
so `RedactionPolicy` masks by force **just before entry into the deriver queue** and on the **input to `MemorySearch`/`Observe`**.

| Policy | Class | Description |
|------|--------|------|
| `default` | `DefaultRedactionPolicy` | AWS keys, JWT/Bearer, private IPs, e-mail addresses, general secret patterns |
| `strict` | `StrictRedactionPolicy` | Blocks harder, down to look-alike keywords |
| `none` | — | **Forbidden in production** — ERROR at startup |

A masked observation leaves `redacted=true` and `redaction.categories=<CSV>` in its `metadata`.

> **Every message is masked (TOOL-role included)**: the `MessageRedactor` shared by every queue manager masks the text of **every message** in a conversation entering the deriver queue — not only USER/ASSISTANT text, but the **tool-result bodies of TOOL-role messages** as well. So secrets exposed in command output or logs are hidden before they are sent to the LLM or persisted as an observation.

---

## 9. Choosing a storage backend

All three kinds of store (`WorkspaceStore`, `ObservationStore`, `RepresentationStore`) are interfaces; you change backend by swapping the implementation.

| Backend | Module | Implementations | Use |
|--------|------|--------|------|
| In-memory | `aimon-core` | `InMemory*Store` | Development/testing only (lost on restart, OOM past ~10k entries) |
| File | `aimon-memory-file` | `File*Store` (JSONL append log + compaction + a file lock) | Single-node persistence (§9.1) |
| PostgreSQL | `aimon-memory-postgres` | `Postgres*Store` + `PostgresDerivationQueueManager` + `KnowledgeStoreOutboxRelay` | Multi-instance production (row-lock queue, outbox→KnowledgeStore) |
| MongoDB | `aimon-memory-mongodb` | `Mongo*Store` | Multi-instance production (the collections are the SSOT, soft-delete/retention) (§9.3) |

- Vector search does not get its own RAG stack; it is delegated to a `KnowledgeStore` (e.g. `aimon-knowledge-opensearch`) under `KnowledgeScope("memory.observation")`. The `ObservationStore`s of `Postgres*`/`Mongo*` are metadata-only, so their `semanticSearch` throws `UnsupportedOperationException`, and search is restored with the `IndexedObservationStore` decorator (§9.2(3)).
- When using `aimon-memory-postgres` together with `aimon-session-postgres`, a **separate DataSource** is recommended even on the same instance (schema prefixes `mem_*` vs `session_*`). The same goes for MongoDB — a separate DB is recommended (collection prefix `mem_*`).

### 9.1 The file backend (`aimon-memory-file`)

Persistence built on a **JSON-line append log**, for a single node before PostgreSQL comes in. Each of the three stores has its own log file.

```gradle
// build.gradle.kts
implementation(project(":aimon-memory-file"))
```

```java
WorkspaceStore workspaceStore = new FileWorkspaceStore(Paths.get(".aimon/memory/workspaces.jsonl"));
RepresentationStore representationStore = new FileRepresentationStore(Paths.get(".aimon/memory/representations.jsonl"));
ObservationStore observationStore = new FileObservationStore(Paths.get(".aimon/memory/observations.jsonl"));
```

How it behaves:

- **Append log + replay** — `save`/`delete`/`merge` each append one line (JSON). The constructor replays the log from the beginning to rebuild the in-memory state, so state survives a restart.
- **The fsync option** — the second argument, `fsyncOnAppend` (default `true`), controls whether every append flushes to disk. Turning it off for throughput means the last few lines can be lost in a crash.
  ```java
  new FileRepresentationStore(path, /* fsyncOnAppend */ false);
  ```
- **Search (`semanticSearch`)** — `FileObservationStore` uses `InMemoryObservationIndex` (substring matching) by default. Inject an index when you need vector search:
  ```java
  ObservationIndex index = new KnowledgeStoreObservationIndex(knowledgeStore);
  ObservationStore store = new FileObservationStore(path, index, /* fsyncOnAppend */ true);
  ```
- **Compaction (keeping the log from growing forever)** — since `save`/`delete`/`merge`/`softDelete` only append, the log grows monotonically. All three stores offer `Compactable.compact()` (an atomic rewrite down to the minimal set of lines for the current live + audit state: temp → fsync → `ATOMIC_MOVE`); when the journal passes `max(10_000, live×3)` lines it **compacts automatically**, and **at startup** a replay that finds a bloated log compacts it once → disk and restart cost become proportional to the live state rather than to the whole history.
- **A single process, enforced (an OS file lock)** — the constructor takes an exclusive `FileLock` on a sidecar `<log>.lock`. Opening the same log a second time (from another process or the same JVM) **fails immediately** with an `AimonException` — instead of corrupting silently. The stores are `AutoCloseable`, so the lock is released on `close()` (or on JVM exit). For multiple instances / scale-out, see §9.2 (PostgreSQL) or §9.3 (MongoDB).
- **Soft-delete + audit retention** — the loser of a `merge` and the target of `softDelete(id)` are not discarded immediately; they are soft-deleted into an audit window and survive replay. `purgeSoftDeletedBefore(ws, cutoff)` enforces that window.
- **A retention/compaction scheduler independent of the Dreamer** — `FileMemoryMaintenanceScheduler` periodically (every 6 hours by default) runs `purgeSoftDeletedBefore` per workspace (30 days by default) plus `RepresentationStore.deleteOlderThan` (90 days by default), then `compact()` on every store. Retention and disk are guaranteed even with the Dreamer switched off. The CLI starts it automatically under `backend: file` and stops it in `AgentSetup.close()`.
  ```java
  FileMemoryMaintenanceScheduler maintenance = new FileMemoryMaintenanceScheduler(
          workspaceStore, observationStore, representationStore,
          List.of(workspaceStore, observationStore, representationStore));
  maintenance.start();   // a daemon; call maintenance.close() on shutdown
  ```
- **`findAll` access control** — `FileWorkspaceStore.findAll(Principal)` filters through a `WorkspaceAccessPolicy` (by default `DefaultWorkspaceAccessPolicy`: everything for SYSTEM/SERVICE; for USER/GROUP, whatever is unowned or matches the `acl.owner`/`acl.members` metadata). Inject a policy into the constructor to replace it.
- The CLI's `memory.storagePath` is both the log path for `FileRepresentationStore` and the reference point for its sibling file `observations.jsonl`. **Under `backend: file` (the CLI default), `createObservationStore` also wires a `FileObservationStore`, so observations are persisted to `observations.jsonl` too.** To make observations volatile, write `backend: in-memory` explicitly.

### 9.2 The PostgreSQL backend (`aimon-memory-postgres`)

The backend for multi-instance production. The metadata lives in PostgreSQL, **the vectors in a `KnowledgeStore`**, and an outbox joins the two (design §5.2).

```gradle
// build.gradle.kts
implementation(project(":aimon-memory-postgres"))
```

**(1) Applying the schema — operator-applied.** The runtime never executes DDL. Apply it by hand, once per environment:

```bash
psql "$DATABASE_URL" -v ON_ERROR_STOP=1 \
  -f modules/aimon-memory-postgres/src/main/resources/db/postgres/V1__init.sql
```

The tables it creates: `mem_workspace`, `mem_observation`, `mem_representation`, `mem_active_work_unit` (the row-lock claim on a work unit) and `mem_outbox` (the queue that syncs embeddings to the KnowledgeStore).

**(2) Preparing the DataSource.** Every store takes a `DataSource` plus a Jackson `ObjectMapper`. A Hikari pool, for example:

```java
HikariConfig cfg = new HikariConfig();
cfg.setJdbcUrl(System.getenv("DATABASE_URL"));   // jdbc:postgresql://host:5432/aimon
cfg.setUsername(System.getenv("DB_USER"));
cfg.setPassword(System.getenv("DB_PASSWORD"));
cfg.setMaximumPoolSize(16);
DataSource dataSource = new HikariDataSource(cfg);

ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();   // JavaTimeModule and friends
```

**(3) Assembling the stores.**

```java
WorkspaceStore      workspaceStore      = new PostgresWorkspaceStore(dataSource, mapper);
RepresentationStore representationStore = new PostgresRepresentationStore(dataSource, mapper);
ObservationStore    metadataStore       = new PostgresObservationStore(dataSource, mapper);
```

> ⚠️ **`PostgresObservationStore.semanticSearch()` throws `UnsupportedOperationException`.** This store is *metadata-only* (the C3 split). Semantic search is restored by combining the metadata store with a search index through the core's `IndexedObservationStore` decorator:
>
> ```java
> ObservationIndex index = new KnowledgeStoreObservationIndex(knowledgeStore);
> ObservationStore observationStore = new IndexedObservationStore(metadataStore, index);
> // observationStore.semanticSearch(...) now works, and the metadata is still persisted in PostgreSQL.
> ```
>
> `IndexedObservationStore` is **write-through** — it syncs the index on every `save`/`delete`/`merge` and hydrates the ids a `search` returns into metadata. That is the **synchronous, direct-indexing** path, so **pick it or the outbox relay of §9.2(4), not both** (pointing both at the same `KnowledgeStore` index indexes everything twice). The outbox's advantage is transactional consistency; the decorator's is simplicity.

**(4) The embedding-sync outbox pump.** `ObservationStore.save` inserts a pending embedding into `mem_outbox` in the same transaction as `mem_observation`. A separate worker drains it and upserts/deletes into the `KnowledgeStore` (eventual consistency).

```java
KnowledgeStoreOutboxRelay relay = new KnowledgeStoreOutboxRelay(dataSource, knowledgeStore);
relay.start();                 // starts the daemon poller (idempotent)
// ... or, in a test or batch job, just once: DrainResult r = relay.drainOnce();
// on shutdown: relay.close();
```

Tuning goes through the full constructor, which takes `RelayOptions` (poll batch size, claim lease duration, poll interval, max attempts, nodeId) and the `KnowledgeScope` agent name:

```java
RelayOptions options = RelayOptions.builder().pollBatchSize(100).pollIntervalMillis(1000)
        .claimDurationSeconds(30).maxAttempts(5).nodeId("relay-1").build();
KnowledgeStoreOutboxRelay relay =
        new KnowledgeStoreOutboxRelay(dataSource, knowledgeStore, "memory.observation", options);
```

**(5) The multi-instance derivation queue.** Swap the in-memory queue for the row-lock-based one and several nodes serialise safely on the same work unit (`SELECT … FOR UPDATE SKIP LOCKED`).

```java
DerivationQueueManager queue = new PostgresDerivationQueueManager(
        dataSource, deriver, redactionPolicy, DeriverProperties.defaults());
queue.start();
```

> Each instance is issued a `holderId` (a UUID) that distinguishes claim ownership in `mem_active_work_unit`. An expired claim is stolen by another node.

### 9.3 The MongoDB backend (`aimon-memory-mongodb`)

The **multi-instance** backend for when you already run MongoDB (`aimon-session-mongodb` / `aimon-filesystem-gridfs`) and want memory in that single datastore too. The collections are the single source of truth (there is no in-memory mirror), so several nodes can share one DB.

```gradle
// build.gradle.kts
implementation(project(":aimon-memory-mongodb"))
```

**(1) Applying the schema — operator-applied.** The runtime does not execute DDL (the same policy as the PostgreSQL backend). Apply it once per cluster:

```bash
mongosh "$MONGODB_URI" modules/aimon-memory-mongodb/src/main/resources/db/mongodb/init.js
```

The collections it creates: `mem_workspace`, `mem_observation`, `mem_representation` (plus indexes matching the query patterns; observation's `(workspaceId, localId)` is unique).

**(2) Assembling the stores.** Every store takes a `MongoDatabase`.

```java
MongoDatabase db = mongoClient.getDatabase("aimon_memory");
WorkspaceStore      workspaceStore      = new MongoWorkspaceStore(db);              // findAll applies the WorkspaceAccessPolicy
RepresentationStore representationStore = new MongoRepresentationStore(db);
ObservationStore    metadataStore       = new MongoObservationStore(db);
```

**(3) Search — metadata-only (the C3 split).** `MongoObservationStore.semanticSearch()` throws `UnsupportedOperationException`. Restore search with the `IndexedObservationStore` decorator, exactly as with PostgreSQL:

```java
ObservationIndex index = new KnowledgeStoreObservationIndex(knowledgeStore);
ObservationStore observationStore = new IndexedObservationStore(metadataStore, index);
```

**How it behaves**

- **Soft-delete + retention** — observations carry a `softDeletedAt` field; `merge`/`softDelete` set it, every query excludes it, and `purgeSoftDeletedBefore(ws, cutoff)` enforces the window (the same contract as PostgreSQL).
- **Multi-tenant isolation** — every query is scoped by `workspaceId`, and `findAll` filters through the `WorkspaceAccessPolicy`.
- **Atomic `create`** — a unique `_id` detects a duplicate and refuses it with `IllegalStateException` (race-safe against concurrent creation).
- **No derivation queue** — a single node reuses the in-memory queue; when you need full multi-instance derivation serialisation, use the row-lock queue in `aimon-memory-postgres`.

> MongoDB's ↔ Java time type is millisecond-precision (a BSON date), so the `Instant`s on observations and representations are truncated to milliseconds when stored (the same level as PostgreSQL's `Timestamp`).

---

## 10. Assembling it programmatically (a minimal in-memory skeleton)

The minimal skeleton for integrating directly into an application without the CLI (a simplified form of the CLI `AgentSetupFactory` wiring).

```java
// 1) domain / identifiers
Workspace workspace = Workspace.builder().id("default").build();
PeerView observer = PeerView.of(workspace, Principal.user("agent-default", "Aimon Agent"));

// 2) stores (swap for File*/Postgres* in production)
ObservationStore observationStore = new InMemoryObservationStore();
RepresentationStore representationStore =
        new FileRepresentationStore(Paths.get(".aimon/memory/representations.jsonl"));

// 3) the security gate
RedactionPolicy redaction = new DefaultRedactionPolicy();

// 4) deriver + queue (messages → observations)
Reconciler reconciler = new DefaultReconciler(llmClient, modelName); // optional
Deriver deriver = new LlmDeriver(llmClient, observationStore, modelName, representationStore, reconciler);
DerivationQueueManager queue = new InMemoryDerivationQueueManager(
        deriver, redaction, DeriverProperties.defaults());
queue.start();

// 5) the natural-language query engine
DialecticEngine dialectic = new LlmDialecticEngine(llmClient, observationStore, modelName);

// 6) registering the tools (ToolRegistry)
registry.register(new MemorySearchTool(observationStore, redaction));
registry.register(new ObserveTool(observationStore, redaction));
registry.register(new MemoryChatTool(dialectic));
registry.register(new MemoryRecallTool(representationStore));

// 7) automatic ToolContext filling + automatic system-prompt injection
ToolContextEnricher enricher = new MemoryToolContextEnricher(workspace, observer);   // pass to the executor factory
MemoryContextProvider memoryContext = new SnapshotMemoryContextProvider(
        SnapshotMemoryContextProvider.readerOver(representationStore), workspace,
        MemoryPeerResolver.fixed(observer.getPrincipal()),
        MemoryInjectionMode.SUMMARY_ONLY, 0);                                        // withMemoryContextProvider(...)
```

Drain the in-flight derivations with `queue.stop()` on shutdown. The memory components are **application-scoped**, so keep them alive independently of `AgentRuntime` destruction and clean them up only at app shutdown or on an explicit removal.

> The real wiring, for reference: `buildMemoryWiring`, `buildMemoryDeriver`, `buildDerivationQueue`, `buildMemoryContextProvider`,
> `registerCliTools` and `buildDreamerSubsystem` in `modules/aimon-cli/.../factory/AgentSetupFactory.java`.

---

## 11. Implementing another storage backend (the SPI spec)

Adding a new backend such as DynamoDB or Cassandra is **not a refactor, it is one more implementation**
([multi-instance-design](../../../.claude/rules/multi-instance-design.md)). All you implement are the interfaces in `at.aimon.core.memory`.

> The skeleton below (§11.5) uses MongoDB as its example, but **the MongoDB backend already exists as `aimon-memory-mongodb`** (§9.3). Look at the real implementation — soft-delete/retention, the `findAll` ACL, the operator-applied `init.js`, the Testcontainers integration tests — as the reference. The spec below is the guide for building yet another backend (DynamoDB, say).

### 11.1 The interfaces to implement

| Interface | Required? | Responsibility | In-memory reference |
|-----------|------|------|--------------|
| `WorkspaceStore` | ✅ | Tenant (workspace) CRUD | `InMemoryWorkspaceStore` |
| `ObservationStore` | ✅ | Observation metadata, relations, confidence | `InMemoryObservationStore` |
| `RepresentationStore` | ✅ | Representation snapshots (append-only) | `InMemoryRepresentationStore` |
| `ObservationIndex` | optional | The search index (semantic/keyword). Separate from the metadata store | `InMemoryObservationIndex`, `KnowledgeStoreObservationIndex` |
| `DerivationQueueManager` | optional | The multi-instance derivation queue | `InMemoryDerivationQueueManager`, `PostgresDerivationQueueManager` |

For most new backends, **the three stores + (optionally) an ObservationIndex** are enough. On a single node you may keep reusing the in-memory queue.

### 11.2 The contract every store shares

- **Multi-tenant isolation (at compile time)** — every id-based method takes a `Workspace` or a workspace-bound value object (`ObservationId`). **Do not add a method with a bare `String id` parameter.** The one exception is `WorkspaceStore.findById(String)` for bootstrapping, and ArchUnit whitelists that single place.
- **Thread safety** — a store must be safe under concurrent calls.
- **Immutable returns** — the domain objects (`Observation`, `Representation`, `Workspace`) are immutable. Return lookups as an `Optional` or an immutable `List` (`List.copyOf(...)`).
- **Wrap exceptions** — do not let driver exceptions cross the module boundary; wrap them in `AimonException` (`at.aimon.core.base`). Details in [error-handling](../../../.claude/rules/error-handling.md).
- **Validate that the workspaces match** — an observation's `subject` and `observer` must belong to the same workspace; guarantee that domain invariant when storing.

### 11.3 The per-interface contract in detail

**`WorkspaceStore`**
- `create(Workspace)` — a duplicate id is refused with `IllegalStateException`.
- `findById(String)` — the raw lookup, for bootstrapping only.
- `findAll(Principal requester)` — **applies access control**. A production backend must enforce a real ACL (the in-memory one returns everything).
- `delete(Workspace)` — cascading cleanup of observations/representations is the caller's or a separate job's responsibility (or a DB FK CASCADE).

**`ObservationStore`** (`save`, `findById`, `findBySubject(limit)`, `count`, `semanticSearch`, `findByConfidenceBelow`, `findSubjects`, `delete`, `merge`)
- `confidence` lies in `[0,1]` and `type` is `EXPLICIT|DEDUCTIVE` — validate on write (a check constraint is recommended).
- `findBySubject` is newest-first and `findByConfidenceBelow` is ascending by confidence (the dreamer uses it to find consolidation candidates). `limit >= 1`.
- `findSubjects(workspace, limit)` is what the dreamer uses to walk every peer in a workspace once — order is not guaranteed, and `limit` caps it.
- `merge(winner, loser, merged)` — `merged.id` must equal `winner`. A persistent backend **soft-deletes the loser and keeps it for a 30-day audit** (the in-memory one discards it immediately). PostgreSQL implements this with a `soft_deleted_at` column.
- `semanticSearch` — **two choices**:
  1. *Do not implement it* — throw `UnsupportedOperationException` (as `PostgresObservationStore` does) and delegate search to another store that owns an `ObservationIndex`. This is the recommended C3-split path.
  2. *Combine with an index* — take an `ObservationIndex`, sync the index on every `save`/`delete`/`merge`, and hydrate the ids a `search` returns into metadata. The core's **`IndexedObservationStore`** decorator provides this write-through combination off the shelf (the in-memory reference is `InMemoryObservationStore(index)`), so a new backend need only build the metadata store and wrap it in `new IndexedObservationStore(metadataStore, index)`.

**`RepresentationStore`** (`save`, `findLatestGlobal`, `findLatestLocal`, `deleteOlderThan`)
- The append-only convention: do not modify, store a new snapshot.
- `findLatestGlobal` is the newest snapshot with `observer == null`; `findLatestLocal(subject, observer, sessionId)` matches cross-session when `sessionId == null`.
- `deleteOlderThan(workspace, cutoff)` enforces retention.

**`ObservationIndex`** (optional; `index`, `delete`, `search`)
- `index` **must overwrite** the previous entry for the same `ObservationId` (so content/confidence updates take effect).
- `search(subject, query, topK)` returns `ObservationId`s only (it owns no payload). The caller hydrates them through the metadata store.
- Always respect the workspace and subject scope: A's search must never return B's observations.

### 11.4 Creating the module

1. Create `modules/aimon-memory-mongodb/build.gradle.kts` — `implementation(project(":aimon-core"))` plus the driver dependency.

   ```gradle
   plugins {
       id("aimon.java-conventions")
       id("aimon.publishable")   // when publishing to Maven Central
   }
   dependencies {
       implementation(project(":aimon-core"))     // ❗ never api() — keeps core types from leaking
       implementation(libs.mongodb.driver)         // add it to gradle/libs.versions.toml
       implementation(libs.bundles.jackson)
       implementation(libs.slf4j.api)
   }
   ```
2. Add `include("modules:aimon-memory-mongodb")` to `settings.gradle.kts`.
3. Use the package namespace `at.aimon.memory.mongodb` (an external module gets its own namespace). Never import `at.aimon.core.<domain>.impl` directly — depend only on the core interfaces.
4. If it is to be published, add it to the publishable list in the root `build.gradle.kts`.

### 11.5 A skeleton example (`WorkspaceStore` / MongoDB)

```java
package at.aimon.memory.mongodb;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.mongodb.client.MongoCollection;
import org.bson.Document;

import at.aimon.core.base.AimonException;
import at.aimon.core.base.Principal;
import at.aimon.core.memory.Workspace;
import at.aimon.core.memory.WorkspaceStore;

/** MongoDB-backed {@link WorkspaceStore}. Thread-safe; the driver pools connections. */
public final class MongoWorkspaceStore implements WorkspaceStore {

    private final MongoCollection<Document> workspaces;   // the collection matching mem_workspace

    public MongoWorkspaceStore(MongoCollection<Document> workspaces) {
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces cannot be null");
    }

    @Override
    public Workspace create(Workspace workspace) {
        Objects.requireNonNull(workspace, "workspace cannot be null");
        try {
            if (workspaces.find(new Document("_id", workspace.getId())).first() != null) {
                throw new IllegalStateException("workspace already exists: " + workspace.getId());
            }
            workspaces.insertOne(toDocument(workspace));   // _id = workspace.getId()
            return workspace;
        } catch (RuntimeException e) {
            throw new AimonException("MongoDB error during workspace create: " + workspace.getId(), e);
        }
    }

    @Override
    public Optional<Workspace> findById(String id) {     // the bootstrap-only exception
        Objects.requireNonNull(id, "id cannot be null");
        Document doc = workspaces.find(new Document("_id", id)).first();
        return Optional.ofNullable(doc).map(MongoWorkspaceStore::fromDocument);
    }

    @Override
    public List<Workspace> findAll(Principal requester) {
        Objects.requireNonNull(requester, "requester cannot be null");
        // a production backend must apply an ACL based on the requester.
        // ...
        return List.of();
    }

    @Override
    public void delete(Workspace workspace) {
        Objects.requireNonNull(workspace, "workspace cannot be null");
        workspaces.deleteOne(new Document("_id", workspace.getId()));
    }

    private static Document toDocument(Workspace ws) { /* serialise */ return new Document(); }
    private static Workspace fromDocument(Document d) { /* deserialise */ return Workspace.builder().id(d.getString("_id")).build(); }
}
```

`ObservationStore` and `RepresentationStore` take the same shape: carry the columns and indexes of the PostgreSQL schema (`V1__init.sql`) over to MongoDB collections and indexes. In particular:
- The `(workspace_id, subject_principal_type, subject_principal_id, created_at DESC)` index on `mem_observation` → speeds up `findBySubject`.
- The `(… , confidence)` index → speeds up `findByConfidenceBelow`.
- The latest-global / latest-local indexes on representation → speed up `findLatest*`.

### 11.6 What is expected of the tests

- ≥80% unit coverage. Cover the interface contract (multi-tenant isolation, the confidence range, merge semantics, the latest-lookups) with regression tests.
- Use Testcontainers for real-DB integration (`@Tag("docker")`). Take `PostgresTestSupport` / `*IntegrationTest` in the PostgreSQL module as the pattern.
- If you implemented a multi-instance queue, cover concurrent claims, expired-claim stealing and work-unit serialisation (see the `PostgresDerivationQueueManager` tests).

---

## 12. Operational notes / troubleshooting

- **Memory won't switch on** → check that all three of `workspaceId`/`peerId`/`storagePath` are filled in (`MemoryConfig.isEnabled()`). One empty field disables the whole thing.
- **Observations vanish on restart** → check whether `memory.backend` is `in-memory`. The file backend (the default) persists next to `storagePath` in `observations.jsonl`. Note that the search index still lives only as long as the process (rebuilt by replay on restart).
- **Recall comes back empty** → in the CLI the deriver runs **at session end**. Before the first exit there may be no representation at all.
- **The dreamer isn't running** → look for `Peer memory dreamer disabled: <reason>` in the startup log. The embedding scorer is fail-soft when `apiKey` is missing.
- **A secret got stored** → `redaction.policy = none` is forbidden in production (ERROR at startup). Use `default` or `strict`.
- **Multiple instances** → the in-memory and file backends are limited to a single JVM. Scale out with `aimon-memory-postgres` (row-lock queue + outbox).

---

## 13. Related code / documents

- Design/specification: [`peer-memory.md`](../../design/memory/peer-memory.md)
- The core package: `modules/aimon-core/src/main/java/at/aimon/core/memory/`
  (`deriver/`, `dialectic/`, `dreamer/`, `reconciler/`, `redaction/`, `index/`)
- The decorator combining a metadata store with a search index: `at.aimon.core.memory.IndexedObservationStore`
- The exposed tools: `modules/aimon-core/src/main/java/at/aimon/core/tools/memory/`
- The persistence modules: `modules/aimon-memory-file/`, `modules/aimon-memory-postgres/`, `modules/aimon-memory-mongodb/`
- Single-node maintenance (file lock + compaction + retention): `at.aimon.memory.file.FileMemoryMaintenanceScheduler`, `Compactable`
- The workspace access policy: `at.aimon.core.memory.WorkspaceAccessPolicy` · `DefaultWorkspaceAccessPolicy`
- The PostgreSQL schema (operator-applied): `modules/aimon-memory-postgres/src/main/resources/db/postgres/V1__init.sql`
- The MongoDB schema (operator-applied): `modules/aimon-memory-mongodb/src/main/resources/db/mongodb/init.js`
- The multi-instance / store-separation rules: [`multi-instance-design.md`](../../../.claude/rules/multi-instance-design.md)
- The CLI wiring: `modules/aimon-cli/src/main/java/at/aimon/cli/factory/AgentSetupFactory.java`,
  with the config classes `modules/aimon-cli/src/main/java/at/aimon/cli/config/MemoryConfig.java` and `MemoryDreamerConfig.java`
- A config example: `modules/aimon-cli/src/main/resources/default-config.yaml` (the memory block's comments)
- Tool development in general: [`tool-development-guide.en.md`](../tool/tool-development-guide.en.md)
