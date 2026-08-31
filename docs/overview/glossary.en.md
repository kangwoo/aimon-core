---
translated_from: docs/overview/glossary.md
source_commit: a9821d44
---

# Glossary

The core terms that recur through the AIMON codebase, and their **lifetimes**.
The same word points at different things at different layers, so use this
document as the reference when naming a new type or reading someone else's code.

---

## 1. At a glance — the lifetime tiers

| Scope | Representative types | Identifier | Lifetime |
|-------|----------|--------|----------|
| **Application** | `SchedulingEngine`, `ScheduledTaskManager`, `RoutineExecutor`, `AgentRuntimeRegistry`, `SessionRecordStore`, `SessionLeaseStore`, `SessionInbox`, `SessionSignalBus`, `IdempotencyStore`, `KnowledgeStore`, `CredentialStore` | — | app start ~ shutdown |
| **Agent** | `AgentRuntime` and what it owns — `ToolRegistry` / `HookRegistry` / `McpClientManager`, `AgentEnvironmentSnapshot` | `AgentRuntimeId` (`agent:<name>[:<discriminator>]`) | held across sessions |
| **Session** | `SessionRecord`, `SessionTranscript`, `SessionTotals`, `budgetOverride` | `SessionId` | as long as the session exists — **persistent** |
| **Live session** | `LiveSession`, the message queue, the event publisher | (bound `SessionId`) | inside one node's process, **transient** |
| **Execution** | one unit of agent work in general — **there may be no session** (subagent fork, skill fork, rewake replay, scheduled routine) | `ExecutionId` (issued **only for sessionless executions** — §4) | the concept above turn |
| **Turn** | `AgentExecutionRequest` / `AgentExecutionResult`, `ExecutionBudget` · `BudgetTracker` (not turn-only — §4) | `TurnId` (for addressing, **not persisted**) | processing one user input |
| **Iteration** | one pass of the ReAct loop (LLM call + tool execution) | — | inside a turn |

IMPORTANT: in the **Session** and **Live session** rows of that table **there is no type called
`Session` or `AgentSession`.** Neither name tells you which of the two lifetimes it is, which
caused real confusion, so both are banned and `SessionNamingArchitectureTest` breaks the build.
The persistent side is `Session*`; the node-local handle is `LiveSession*`.

Ownership and teardown responsibility (who creates and who closes), the marker interfaces, and
the rules for placing a new type are in [`scope-model.en.md`](scope-model.en.md). This document covers
**what the terms mean**; `scope-model.md` covers **the lifetime rules**.

For the full background see [`design/agent-execution/agent-runtime-scope.md`](../design/agent-execution/agent-runtime-scope.md).

---

## 2. Session vs live session — the most frequently confused pair

**They are not synonyms.** The relationship is asymmetric.

```
one SessionRecord (persistent, identified by SessionId)  :  0..N LiveSession (transient, node-local)
```

| | `SessionRecord` | `LiveSession` |
|---|---|---|
| What it is | the **persistent aggregate** holding the message history | the **node-local handle** that runs turns |
| Storage | `SessionRecordStore` (Mongo/Postgres/Redis/in-memory) | not stored — the JVM heap |
| Identity | `SessionId` | references the bound `SessionId`; has no id of its own |
| Count | 1 | may be 0 (nobody is talking) or N over time |
| Destruction | explicit deletion | idle-TTL eviction, process restart, handoff to another node |

**Why it matters.** A live session disappears and is recreated at any time. State that has to
survive a restart, an eviction or a move between nodes must therefore live on the **record**
side:

- `SessionTotals` — cumulative statistics for completed turns. Opening a new handle with the same `SessionId` restores them rather than starting from zero.
- `budgetOverride` — an `ExecutionBudget` changed at runtime (e.g. the REPL's `/budget`). On resume it beats the opener's default.

Both are side fields of `SessionRecord` and live in `at.aimon.core.agent.session.store`. Putting
`LiveSession` in the name would read as "a value that dies with the handle", which these are
not. The live session carries these two not through a narrow SPI but by **holding
`SessionRecordStore` directly** — reading from the record once when opening, and writing both
values back as **absolute values, as a pair**, via `setTotalsAndBudgetOverride`.

The asymmetry is also what makes scaling to multiple nodes without sticky routing possible — see
[`design/session/routing.md`](../design/session/routing.md) for the details.

### When naming a new type

| Nature of the value | Word to use | Package to put it in |
|-----------|---------------|------------|
| Must be restored after a restart | `Session*` | `agent.session.store` (the transcript itself in `agent.session.transcript`) |
| May disappear with the process | `LiveSession*` | `agent.session` |
| Collected once per agent | `Agent*` | `agent` |

The asymmetry may catch your eye — the persistent side is `Session*` while only the transient
side carries the `Live` qualifier. That is deliberate. To a user, "session" is the thing that
continues across restarts, so the unmarked name goes to the persistent side and only the object
currently running that session on this node gets a marker.

### "conversation" is not a retired word

Reading this rework as having abolished "conversation" will lead you to coin new wrong names.
The word dropped out **only where it denoted a lifetime**, and it remains exactly where it
denotes the **exchange of messages with the LLM** — `SessionSnapshot.getConversationHistory()`,
`/compact`'s "Conversation compacted", the compaction prompt wording. For a new type dealing
with message history, `Transcript*` or `*ConversationHistory*` is right; to speak of a lifetime,
`Session*` is right.

---

## 3. The many uses of the word "Session"

In AIMON, `Session` points at no fewer than five different lifetimes. Never guess a lifetime
from this word alone, without context.

| Type | What it actually means | Lifetime |
|------|----------|------|
| `SessionRecord` (`aimon-core`) | the persistent aggregate identified by `SessionId` | until explicitly deleted — **persistent** |
| `LiveSession` (`aimon-core`) | the node-local handle running turns for that one session | open ~ `close()` |
| a `LiveSessionCache` entry (`aimon-session-routing`) | the `LiveSession` above, cached by the multi-node routing and caching layer (`SessionRouter`) | until idle TTL or `maxEntries` eviction |
| `ReplSession` (`aimon-cli`) | one interactive run of the CLI process | CLI start ~ exit |
| `BrowserSession` (`aimon-browser-playwright`) | a Playwright `BrowserContext` plus the active Page | the browser context's lifetime |

It is precisely this ambiguity that makes bare `Session` and `AgentSession` unusable as type
names — neither tells you which of the five rows above it is.
`SessionNamingArchitectureTest` enforces it.

### Known misnomers

- `OnSessionStartHook` / `OnSessionEndHook` — they fire on the opening/closing of a `LiveSession`
  (not on a session starting). They can fire again when the same session is resumed.
- `TranscriptManager.initialize` — called once **per turn**, not once per session.
- `conversation` in persistent names — the Java identifiers were renamed to `Session*`, but the
  wire keys (`"conversationId"`), the Mongo collections (`conversation_locks` and friends) and
  the Postgres tables and channels (`conversation_*`) were **deliberately frozen**. Looking
  mismatched is the normal state.

### A resolved misnomer — the skill approval stores

The approval stores were renamed twice, and in the process **the name `SessionApprovalStore` was
reused with a different meaning**. This is the most confusing point when reading old code.

| Old name | Current name | Actual key |
|---------|----------|---------|
| `SessionApprovalStore` (the name was lying) | `AgentApprovalStore` (`…policy.agent`) | `AgentRuntimeId` |
| `SessionAwareSkillInvocationPolicy` | `ApprovalCachingSkillInvocationPolicy` | — |
| `ConversationApprovalStore` | **`SessionApprovalStore`** (`…policy.session`) | `SessionId` |
| `ConversationScopedSkillInvocationPolicy` | `SessionScopedSkillInvocationPolicy` | — |
| `ApprovalScope.CONVERSATION` | `ApprovalScope.SESSION` | — |

The user chooses how far an approval reaches, and **the renaming changed none of those
meanings**.

| The user's answer | Store | Reach |
|-------------|--------|----------|
| allow just this turn | `PendingApprovalStore` (`…policy.pending`) | that turn |
| always allow in this session (`y`) | `SessionApprovalStore` (`…policy.session`) | that `SessionId` **and the executions that session delegated** (subagent forks, skill forks, foreground workflows) — gone when the session is released or deleted |
| always allow for this agent (`a`) | `AgentApprovalStore` (`…policy.agent`) | **every session** of that `AgentRuntimeId`, no TTL, not cleared even by `/clear` |

The policy chain looks at **the narrowest first** — pending → session → agent → rules. Undoing
follows the same boundary: `/revoke` clears only this session's approvals, `/revoke --agent`
clears the agent-wide ones too (`RevokeApprovalsCommand`).

A subagent fork shares the parent's `AgentRuntimeId` but has **no `SessionId` at all** — a fork
is not a session's turn, so `SESSION_ID` is not put on the tool context and only `EXECUTION_ID`
is. `SkillInvocationRequest.getSessionId()` is therefore empty throughout the fork path, and the
fork carries the id of the session that launched it separately as `invokingSessionId`, which the
session policy looks up — without it every skill invocation from a fork would fall through to
the rule fallback's `ASK`, and a fork has no channel to ask on, which makes it effectively
`DENY`. The inheritance is **bidirectional** (denials are inherited too).

---

## 4. Dictionary of core types

### The Agent tier

- **`Agent`** — an agent's configuration (name, system prompt, model, max iterations). An immutable definition holding no execution state.
- **`AgentRuntime`** — one agent's execution environment (tool registry, hook registry, MCP clients and so on).
  **agent-scoped** — do not create one per session.
- **`AgentRuntimeId`** — `agent:<name>` or `agent:<name>:<discriminator>`. Derived deterministically, so a cron
  re-fire or a different node produces the same value. Issued via `from(Agent)` / `from(Agent, String)`;
  `generate()` does not exist.
- **`discriminator`** — a string appended to the context id when you want to split the same `Agent` definition by tenant, user and so on.
- **`AgentEnvironmentSnapshot`** — an immutable value holding the working directory, the snapshot time, the `Environment`, and a user extension map.
  Memoized by `AgentRuntimeId`, so it is **agent-scoped** (not re-collected per session).
  `AgentEnvironmentSnapshotProvider` guarantees collect-once.
- **`AgentExecutor`** — the executor that takes a context plus a request and runs the ReAct loop. The default implementation is `OrcaAgentExecutor`.

### The Session tier (persistent)

The package splits five ways under `at.aimon.core.agent.session` — `store` (records, leases,
stores, the wire codec), `transcript` (message history), `inbox` (the cross-node mailbox),
`signal` (cross-node pub/sub), and `idempotency` (at-most-once submission). The last three were
moved down into core from `aimon-session-base`, with the result that the distributed backends
(`aimon-session-{redis,postgres,mongodb}`) are implemented against `aimon-core` alone, without
the routing module. It was a source-level move, so no wire format, key prefix or DDL changed.

- **`SessionId`** — the session's persistent identifier. Message history, leases, the inbox, approvals and event frames all join on this value.
- **`SessionRecord`** — a `SessionTranscript` plus side fields (`sessionTotals`, `budgetOverride`,
  `compactionFailureCount`, `agentRef`). `SessionRecordView` is its read-only view.
- **`SessionTranscript`** — an **immutable** value holding the system prompt plus the message history. `withSystemPrompt` /
  `append` return a new instance.
- **`SessionRecordStore`** — the record store abstraction. Swapping the implementation switches in-memory ↔ Mongo/Postgres/Redis.
  What it holds is session-scoped, but **the store itself is application-scoped** — it outlives every session.
- **`SessionLeaseStore`** — the shared authority on which node holds which session (holder election plus fencing).
  Also application-scoped.
- **`SessionStore`** — a **node-scoped** composite putting one door in front of the two stores above. Its `claim` performs
  lease election → agent binding validation → record provisioning, **in that order** (a node that loses the election never
  touches the record, so no distributed transaction is needed). It tracks the leases this node holds, so `records()` can
  fence writes without threading a fencing token per caller down the ReAct call chain — if one JVM has two managers, you
  must build **two stores over the same two backends** (sharing is forbidden).
- **`SessionSnapshot`** — an immutable snapshot of the transcript (`sessionId` + system prompt +
  `getConversationHistory()`), carrying **no side fields at all**. That is why `SessionRecordStore.mergeFromSnapshot`
  restores the four — `compactionFailureCount` / `agentRef` / `sessionTotals` / `budgetOverride` — from the existing record when saving.
- **`SessionTotals`** — the cumulative totals of completed turns (turn count, iterations, tokens). Turns in progress are excluded.
- **`SessionRecordStore.setTotalsAndBudgetOverride`** — the single atomic primitive by which a live session writes back the two
  side fields it owns (`sessionTotals`, `budgetOverride`). It takes **absolute values, not deltas**, so a duplicate call does not
  count a turn twice. A no-op if the record does not exist. A narrow SPI called `ConversationStatePersistence` once stood here, but
  once the live session came to own the record directly, narrowing for ISP became pointless and it was deleted.
- **`SessionRecordCodec`** (`…session.store`) — the encoding shared by distributed `SessionRecordStore` backends.
  The neutral document is `StoredSessionRecord` and the result projection is `StoredAgentExecutionResult`. The transcript
  half is delegated to the snapshot codec in `at.aimon.core.subagent.task.codec` (that package name records its first
  consumer, it is not a constraint).
- **`SessionInbox`** (`…session.inbox`) — the session's cross-node mailbox. Any node may `deliver` and only the node holding
  the lease may `collect` — that rule is enforced by the routing layer, not by the SPI.
  Application-scoped.
- **`SessionSignalBus`** (`…session.signal`) — fans a `SessionSignal` (INTERRUPT · EVICT · MESSAGE_ENQUEUED · EVENT · STATUS …)
  out to every node subscribed to one `SessionId`. Publication is at-least-once, so a receiver must tolerate the same signal
  arriving twice. Application-scoped.
- **`IdempotencyStore`** (`…session.idempotency`) — guarantees at-most-once submission under a client-chosen key, and detects
  a lost holder through a short secondary TTL on `IN_FLIGHT` entries. It **does not dedupe by message content** — that is
  neither `SessionInbox`'s job nor this store's. Application-scoped.

### The Live session tier (transient)

- **`LiveSession`** — the facade for running turns via `submit` / `submitAsync` / `offerAsync`, observing via `events()`,
  and diagnosing via `status()`. `close()` cleans up **handle resources only** — it must not close
  the `AgentRuntime` or the scheduling components.
- **`LiveSessionOptions`** — the handle's default `ExecutionBudget`, locale, and source agent id. If the record has a
  `budgetOverride`, that beats this default.
- **`LiveSessionStatus`** — a best-effort observational snapshot. **Do not use it as a control gate** (the state can change
  between reading it and acting on it). Whether a turn can start is decided by `offerAsync`'s `SubmitOutcome`.
- **`SubmitOutcome`** — whether the input ran immediately (`EXECUTED`) or was queued (`QUEUED`).
- **`OpenAttributes`** — caller domain attributes passed to the opener only when the handle is opened (only on a cache miss).
  For per-turn metadata use `SubmitOptions`.
- **`SessionRouter` / `LiveSessionCache` / `LiveSessionOpener`** (`aimon-session-routing`) — the multi-node layer that sends a
  request to the session's current holder, caches the handle, and opens a new one on a cache miss. The `Session` in the names
  refers to what is being routed (the persistent session); `LiveSession` refers to the cached object.

### Execution units

IMPORTANT: **`turn` · `iteration` and `execution` are not interchangeable** — one word, one meaning.
This applies to names, comments, logs and user-visible strings alike.

| Word | Meaning | Unit |
|------|-----|------|
| **turn** | the processing of one user input that arrived in a session | one `executor.execute(runtime, request)` |
| **iteration** | one pass of the ReAct loop (LLM call → tool execution → observation) | inside a turn |
| **execution** | one unit of agent work in general — **there may be no session** (subagent fork, skill fork, rewake replay, scheduled routine) | the concept above turn |

A sessionless execution has no `SessionId` at all, and `ExecutionId` is its identity (§3). So
**not every execution is a turn; a turn is one kind of execution** — when describing something
the two paths share (the cancellation signal, the budget tracker), write `execution`, not `turn`.

There is one exception: `assistant turn` / `user turn` are standard LLM API vocabulary for a
transcript's **message role**, so they are allowed. But **the qualifier is mandatory** — bare
`turn` always means the first row of the table.

- **Turn** — the whole processing of one user input. The session-wide cumulative figures are held separately by `SessionTotals`.
- **`TurnId`** (`at.aimon.core.agent.session`) — the identifier of one turn. A turn is not a scope that owns components but an
  execution unit, so this id is **for addressing** — aiming an interrupt at a particular turn (`interrupt(sessionId, turnId, reason)`)
  and marking which turn emitted a cross-node `SignalKind.EVENT` frame. It is issued at submission time via `TurnId.generate()` and
  is meaningless once the turn ends — **do not persist it as session state**. The **absence** of an id is not "unknown → drop" but
  **the old meaning** (an interrupt is live-session scoped; an event is delivered session-wide). It is a different type from
  `skill.policy.pending.PendingTurnId`, which is issued only for a turn *suspended* awaiting approval and serves as `/approve`'s
  handle (two unrelated identifiers pointing at the same turn).
- **Iteration** — one pass of the ReAct loop within a turn (LLM call → tool execution → observation).
- **`ExecutionBudget` / `BudgetTracker`** — the iteration, token and time caps **per execution unit (turn or fork)**, and their
  live measurement. Not per session. "Turn or fork" is not hedging but an exhaustive list — in the main sources
  `new BudgetTracker(` appears in exactly **2 places**: `OrcaAgentExecutor` (turn) and `DefaultSubagentExecutor` (fork).
  (grep reports 3, but the third is example code inside `BudgetTracker`'s javadoc, not a construction site.)

### Extension points

- **`Tool`** — the unit through which an agent interacts with the outside world. `execute()` returns a `ToolResult` rather than throwing.
- **`Skill`** — a declarative package bundling prompts, tools and hooks (the Agent Skills standard plus AIMON extensions).
- **`Hook`** — a lifecycle intervention point (`OnStart`, `PreTool`, `PostTool`, `OnStop`, `OnSessionStart/End` …).
- **`Subagent`** — a sub-agent run in an isolated context inside a parent turn.
- **`Principal`** (`at.aimon.core.base`) — the representation of identity (user / group / system / service).

### Scheduling

- **`ScheduledTask`** — a cron or one-shot scheduled task. Its `boundRuntimeId` references an **agent-scoped** id, so the runtime
  still resolves when it re-fires after the original session has ended.
- **`SchedulingEngine` / `ScheduledTaskManager` / `RoutineExecutor`** — **application-scoped**.
  Do not close them along with an `AgentRuntime` or the end of a live session.

---

## Related documents

- [`scope-model.en.md`](scope-model.en.md) — the lifetime, ownership and teardown rules
- [`architecture.en.md`](architecture.en.md) — reference for the core abstractions
- [`../develop/agent-session-guide.en.md`](../features/session/agent-session-guide.en.md) — the `LiveSession` API and event streaming
- [`../design/agent-execution/agent-runtime-scope.md`](../design/agent-execution/agent-runtime-scope.md) — the background to redefining agent scope
- [`../design/session/session-model.md`](../design/session/session-model.md) — the design for persisting session state
