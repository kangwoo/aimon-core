---
translated_from: docs/features/agent-execution/context-engine-guide.md
source_commit: 6b71be0
---

# Context Engine Guide — shrinking the context of long conversations

When an agent's conversation grows longer than the model window, the `ContextEngine` is what decides **what the LLM
is sent**. This document covers which of the two engines to choose and how to turn it on. The design rationale is in
[`context-engine.md`](../../design/agent-execution/context-engine.md) and
[`session-log.md`](../../design/session/session-log.md).

---

## 1. The two engines

| engine | What it does | When to choose it |
|--------|--------------|-------------------|
| `default` (default) | At the model's auto-compact threshold, replaces the **whole view** with one summary. Exactly the behaviour so far | Conversations usually end inside the window |
| `rolling` | Keeps the **head** (the session's first request) and the **tail** (the recent conversation) verbatim and summarizes only the middle. Compacts earlier and in smaller steps, and **updates** the summary each time. Registers the `SessionHistory` tool | One session runs several windows long — operations conversations, long investigations |

Neither engine **touches the log** (in the version-2 write format). A compaction only records in the view state "which
range was replaced by which summary"; the original stays in the record (or a sealed segment). That is why memory
ingest receives the original rather than a summary.

## 2. Turning it on

`rolling` requires the **version-2 log write format** — the summarized range has to be written to the view state, and
a version-1 record cannot hold it. Choosing `rolling` on a node that writes version 1 **fails startup.**

IMPORTANT: The write format is switched across the whole cluster in two steps. First deploy a build that **can read**
version 2 to every node (still writing `v1`), and only once that deployment is complete switch to `v2`. A record
already written as version 2 is written as version 2 again, even by a version-1 write node.

### 2.1 Spring Boot starter

```yaml
aimon:
  session:
    log-write-format: v2    # v1 (default) | v2
  context:
    engine: rolling         # default (default) | rolling — the value when the agent names none
```

### 2.2 Per agent — AGENT.md

```yaml
---
name: ops
context-engine: rolling     # default | rolling. Unset means the deployment's default
---
```

The key is kebab-case, like `allowed-tools`. Writing `contextEngine` is not ignored; parsing fails — ignoring it would
leave the agent on the default engine while its author believes it rolls.

### 2.3 Non-Spring assembly — `AimonStackSpec`

```java
AimonStackSpec.builder()
        .session(SessionSpec.builder().logWriteFormat(SessionLogFormat.V2).build())
        .executor(ExecutorSpec.builder().contextEngine(ContextEngineKind.ROLLING).build())
        // ...
        .build();
```

An assembly using `OrcaAgentRuntimeFactory` directly passes the same values through `withSessionLogWriteFormat(...)`
and `withContextEngine(...)`. To build the engine yourself and change its ratios, use `RollingContextEngine.builder()`
(§4).

## 3. What `rolling` does

```
view:  [ head ][ boundary summary ][ verbatim ........ ][ tail ...... ]
```

- **When** — it compacts at `min(0.6 × effective window, auto-compact threshold)`. A budget-forced pass compacts at a
  line `warningBuffer` lower. Every size includes the system prompt
- **What first** — it first tries hiding the large tool results (500 tokens or more by default) in the range about to be
  absorbed behind `[tool result elided: seq=N]`. If that alone brings the view under the threshold, nothing is
  summarized
- **Where to cut** — it retreats from the tail budget (20% of the window) to half of it to the last legal cut, and
  takes the first whose expected size is under the threshold. If even the best cannot get under, it does not compact
  and only warns. Only at the blocking limit does it put the head into the summary too
- **The summary** — **updated** from the previous summary plus the newly absorbed original. `Primary Request and
  Intent`, `Key decisions and constraints` and `Pending Tasks` are cumulative sections
- **Falling back** — for a model whose small window or large system prompt means even a just-compacted view cannot get
  under the warning line, that call is handled by the `default` engine, with one WARN per model. A version-1 log is
  handled the same way

`/compact` skips only the threshold decision and cuts the same way. If another compaction of the same session is in
progress, it does not wait and shows the failure.

The restore hooks that re-attach recent files and the skill list after a compaction (`RecentFilesRestoreHook`,
`InvokedSkillsRestoreHook`) are not registered — the tail is already verbatim, and re-attached files pile up in the
tail and bring the next compaction forward.

## 4. Tuning

These are values of `RollingContextEngine.Builder`. They are not exposed as starter properties.

| Value | Default | Meaning |
|-------|---------|---------|
| `autoCompactRatio` | 0.6 | Where compaction starts, relative to the effective window |
| `headTokenRatio` | 0.05 | Cap on the head's conversation part. Beyond it the head is empty and the summary takes over |
| `tailTokenRatio` | 0.20 | The tail budget kept verbatim |
| `summaryTokenRatio` | 0.08 | The summary length asked for |
| `minTailRatio` | 0.05 | The minimum tail used to judge "can this model sustain rolling" |
| `pruneMinTokens` | 500 | The smallest tool result worth hiding |
| `summaryModel` | the call's model | A model for summaries only. Must be of the same provider |

A compaction's `CompactionMetadata` carries `kind` (`PRUNE`/`ROLLING`/`FULL`/`FALLBACK`), the head, span and tail
tokens, the summary tokens and the absorbed seq range — tune by reading these.

## 5. The `SessionHistory` tool

Registered when `rolling` is wired. With it the agent reads back originals the view no longer shows.

| Input | Meaning |
|-------|---------|
| `seq` | The original of that message — the N of `[tool result elided: seq=N]` |
| `query` | A case-insensitive substring search, most recent first |
| `limit` | How many search results (default 5, at most 20) |

- The scope is **the current session's conversation entries** only — `SYNTHETIC` entries the runtime injected,
  anything before `/clear`, and other sessions are not visible
- Sealed ranges are read too. A segment that cannot be read is reported as "could not be read"
- A search scans only the most recent 1 million tokens, and says so in the result when it stopped there
- Results are the matching message with two on either side, each message cut to 2000 characters

## Related documents

- [`context-engine.md`](../../design/agent-execution/context-engine.md) — the engine's design and rationale
- [`session-log.md`](../../design/session/session-log.md) — the append-only log, view state, sealing, write-format
  migration
- [`compaction.md`](../../design/agent-execution/compaction.md) — the part that produces summaries
- [Embedding guide §4](../../getting-started/embedding-agent-in-application.en.md) — every starter property
