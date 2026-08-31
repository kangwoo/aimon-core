---
translated_from: docs/overview/scope-model.md
source_commit: a56317a
---

# Scope Model

Defines the **lifetime**, **ownership** and **teardown responsibility** of AIMON components.

This document is the reference for "where should this value live", "when should I close this",
and "what should I call this new type". For the meaning of each individual term see
[`glossary.en.md`](glossary.en.md); for the design background that produced this model see
[`../design/agent-execution/agent-runtime-scope.md`](../design/agent-execution/agent-runtime-scope.md).

---

## 1. The scope tiers

Component lifetimes come in **four tiers**. On top of those sit three execution units
(Execution, Turn, Iteration) — an execution unit owns no components, it merely denotes one
stretch of running.

| Scope | Representative components | Identifier | Lifetime |
|-------|--------------|--------|----------|
| **Application** | `SchedulingEngine`, `ScheduledTaskManager`, `RoutineExecutor`, `AgentRuntimeRegistry`, `SessionRecordStore`, `SessionLeaseStore`, `SessionInbox`, `SessionSignalBus`, `IdempotencyStore`, `KnowledgeStore`, `CredentialStore` | — | app start ~ shutdown |
| **Agent** | `AgentRuntime` and what it owns — `ToolRegistry` / `HookRegistry` / `McpClientManager`, `AgentEnvironmentSnapshot` | `AgentRuntimeId` (`agent:<name>[:<discriminator>]`) | per `(Agent, discriminator)`, held across sessions |
| **Session** | `SessionRecord`, `SessionTotals`, `budgetOverride`, `SessionTranscript` | `SessionId` | as long as the session exists — **persistent** |
| **Live session** | `LiveSession`, the message queue, the event publisher | (references the bound `SessionId`) | node-local, **transient** (open ~ `close()`) |
| *(execution unit)* **Execution** | one unit of agent work in general — **there may be no session** (subagent fork, skill fork, rewake replay, scheduled routine) | `ExecutionId` (issued **only for sessionless executions** — a turn is identified by `SessionId` + `TurnId`) | the concept above turn |
| *(execution unit)* **Turn** | `AgentExecutionRequest` / `AgentExecutionResult`, `ExecutionBudget` · `BudgetTracker` (not turn-only — see below) | `TurnId` (for addressing, **not persisted**) | processing one user input |
| *(execution unit)* **Iteration** | one pass of the ReAct loop (LLM call → tool execution → observation) | — | inside a turn |

> The "3-tier scope" wording in older documents is a leftover from when the persistent session
> and the node-local handle were counted in one box. Their lifetimes differ, so they are counted
> as separate tiers. Back then those two boxes were called "Conversation" and "Session" — they
> are now "Session" and "live session" (§7).

`ExecutionBudget` applies **per execution unit (turn or fork)** — not per session. The
session-wide cumulative figures are held separately by `SessionTotals`. "Turn or fork" is not
hedging but an exhaustive list: in the main sources `new BudgetTracker(` appears in exactly
**2 places**, and `OrcaAgentExecutor` (turn) and `DefaultSubagentExecutor` (fork) are both of
them. (grep reports 3, but the third is example code inside `BudgetTracker`'s javadoc.)

That turns and forks share a budget tracker this way is no accident — **a turn is one kind of
execution**, and a fork is another kind that has no session. That is exactly why you must not
write `turn` when describing something the two paths share. The rules for the three words
(`turn` / `iteration` / `execution`) are in
[`glossary.en.md` §4 › Execution units](glossary.en.md).

That `TurnId` appears in the identifier column does not promote Turn to a scope — a turn still
owns no components. This id is **purely for addressing** (aiming an interrupt at a particular
turn, and marking on an event frame which turn emitted it). It is issued at submission time and
is meaningless once the turn ends, so **it is not persisted as session state**. For the detailed
rules see the `TurnId` entry in [`glossary.en.md` §4](glossary.en.md).

---

## 2. Ownership and teardown — who creates and who closes

**Teardown responsibility** causes more accidents than lifetime does. There is one rule:
**whoever created it closes it. What you borrowed, you do not close.**

| Component | Created | Destroyed |
|---------|------|------|
| `AgentRuntime` | once at bootstrap — CLI `AgentSetupFactory`, web `LiveSessionOpener` | at app shutdown, or on explicit agent removal via `OrcaAgentRuntimeManager.destroyRuntime` |
| `McpClientManager` | when the `AgentRuntime` is created | closed explicitly by `OrcaAgentRuntime.close()` |
| `WorkflowRunner` (agent-scoped variant) | `OrcaAgentRuntimeFactory` — only when `workflowRunnerEnabled` | `OrcaAgentRuntime.close()` |
| `WorkflowRunner` (call-scoped variant) | `WorkflowTool` / `GraalJsWorkflowTool`, per call | each one's own try-with-resources |
| `VirtualShell` (the core default) | `OrcaAgentRuntimeFactory` — via `LocalShells.create()`, **only when** the assembly did not supply one through `withShell(...)` | `OrcaAgentRuntime.close()` (the `ownedShell` field) |
| `VirtualShell` (supplied by the assembly) | the caller, e.g. a sandbox assembly | **that caller** — `ownedShell` is null, so the runtime does not touch it |
| `LiveSession` | `LiveSessionFactory` / the opener | `LiveSession.close()` — **handle resources only** |
| `SchedulingEngine` / `ScheduledTaskManager` / `RoutineExecutor` | application bootstrap | app shutdown |
| `AgentRuntimeRegistry` | created **outside** `SchedulingEngine` and injected through the builder | app shutdown (`SchedulingEngine` does not own it) |

A **borrowed reference**, such as the `AgentRuntimeRegistry` held by `RoutineExecutor`, is marked
with `@ExternallyManaged` (`at.aimon.core.base`). A field carrying that annotation is
documentation — it has no runtime behaviour — but by convention **that class must not close it**.

### The marker interfaces are documentation, not automatic teardown

There are two empty markers in `at.aimon.core.base`.

| Marker | Meaning |
|------|-----|
| `AgentScoped` (`extends AutoCloseable`) | shares its lifetime with `AgentRuntime` |
| `ApplicationScoped` | app start ~ shutdown. Must not be closed along with an `AgentRuntime` |

IMPORTANT: **there is no fan-out over the markers.** `OrcaAgentRuntime.close()` does not scan for
`AgentScoped` implementations; it closes only a **hardcoded list** (`mcpClientManager`,
`workflowRunner`, `ownedShell`). If you add a new agent-scoped component holding a native
resource (a connection pool, a watcher thread), you must **add it to that list yourself**.
Otherwise it is never closed.

That the list grew from two to three is this rule's live example — `ownedShell` gets closed not
because it carries a marker but because a line was added to the body of `close()`. Of the three,
only `ownedShell` is closed **conditionally**: only when the assembly supplied no shell and the
runtime therefore built one itself (§2's table).

Not attaching a marker does not change a lifetime. `ToolRegistry` / `HookRegistry` are
agent-scoped but have no resource to close, so they do not implement `AgentScoped`.

---

## 3. Session ≠ live session

The most frequently confused pair, and the one place where getting it wrong makes **data quietly
disappear**.

```
one SessionRecord (persistent, identified by SessionId)  :  0..N LiveSession (transient, node-local)
```

The relationship is **asymmetric**. A session may have zero live handles (nobody is talking), or
several handles serving it in sequence over time (idle-TTL eviction, process restart, handoff
between nodes).

Therefore **a value that must survive a restart, an eviction or a move between nodes goes on the
record side.** That is why `SessionTotals` and `budgetOverride` sit as side fields of
`SessionRecord` in the `at.aimon.core.agent.session.store` package — with `Live` in the name they
would read as "a value that dies when the handle dies", when in fact they must outlive it.
The live session writes the pair back through `SessionRecordStore.setTotalsAndBudgetOverride`.

**Neither of the two lifetimes takes the bare word `Session`.** `Session` and `AgentSession` are
exactly the names that let these two lifetimes impersonate each other, so they are banned as type
names and `SessionNamingArchitectureTest` (`aimon-session-routing`) breaks the build.

For the comparison table and the reasoning behind multi-node scaling see
[`glossary.en.md` §2](glossary.en.md).

---

## 4. What not to do

Every item on this list is actually written into a code comment somewhere.

- **Do not call `AgentRuntime.close()` from `LiveSession.close()`.**
  Another session of the same agent may still be using that runtime.
  `DefaultLiveSession.close()` states this in a comment and cleans up handle resources only.
- **Do not close the scheduling components when an `AgentRuntime` is destroyed.**
  `SchedulingEngine` / `ScheduledTaskManager` / `RoutineExecutor` are application-scoped, and
  `ScheduledTask.boundRuntimeId` references an agent-scoped id, so the runtime still resolves
  when cron re-fires after the original session has ended.
- **Do not close `WorkflowRunner` from the application shell.** And conversely, do not leave it
  unclosed on the assumption that some other layer will — whoever created it closes it.
- **Do not close a borrowed collaborator.** `WorkflowRunner` borrows `SubagentExecutionManager`
  and the base `SubagentExecutionEnvironment`, and closes only the pool it owns.
- **Do not mint a new `AgentRuntimeId` per execution.** It is deterministic, of the form
  `agent:<name>` / `agent:<name>:<discriminator>`, and is issued via `from(Agent)` /
  `from(Agent, String)`. `generate()` **does not exist** — had it existed, a cron re-fire could
  not have resolved `boundRuntimeId`.
- **Do not use `LiveSessionStatus` as a control gate.** It is a best-effort observational
  snapshot. Whether a turn can start is decided by `offerAsync`'s `SubmitOutcome`.
- **Do not name a type exactly `Session` or `AgentSession`.** ArchUnit blocks it.
- **Do not use "conversation" as a lifetime word.** That word now means only the **exchange of
  messages with the LLM** (`getConversationHistory()`, `/compact`'s "Conversation compacted").
  To speak of a lifetime, use `Session*`.

---

## 5. When creating a new type

### 5.1 Settle the value's lifetime first

| Nature of the value | Name | Package |
|-----------|------|--------|
| Must be restored after a restart | `Session*` | `at.aimon.core.agent.session[.store\|.transcript]` |
| May disappear when the process dies | `LiveSession*` | `at.aimon.core.agent.session` |
| Collected once per agent | `Agent*` | `at.aimon.core.agent` |
| Denotes the LLM message history itself | `Transcript*` / `*Conversation*History*` | `at.aimon.core.agent.session.transcript` |

The two lifetimes sharing a prefix (`Session*` / `LiveSession*`) is deliberate — the side that is
easy to get wrong (the persistent one) gets the short name, and the node-local handle has to
spell out `Live` every time. And **neither of them is the bare word `Session`**: with that name
left empty, no type can arise that might be mistaken for either.

### 5.2 Do not infer a lifetime from the last noun in the name

IMPORTANT: **"the scope noun in the name = that type's lifetime" is false.**
`*Manager` / `*Registry` / `*Factory` / `*Repository` / `*Store` are **containers that manage an
X**, and a container's own lifetime is not X's lifetime.

| Type | Scope of what it holds | Its own scope |
|------|-----------------|-------------------|
| `SessionRecordStore` / `SessionLeaseStore` | Session | **Application** |
| `AgentRuntimeRegistry` | Agent | **Application** |
| `SessionRouter` / `LiveSessionFactory` / `LiveSessionCache` | Live session | not live session |
| `InMemoryTodoRepository` | Session (keyed by session id) | **Agent** (one per agent runtime) |

As `ApplicationScoped`'s javadoc puts it: *a scope speaks of the component's own lifetime, not of
the lifetime of its contents.*

### 5.3 Judge by **the key and the storage location**, not by the name

When you want to know a store's scope, look not at its name but at **what it is keyed by**.
`Map<AgentRuntimeId, _>` means agent-scoped; `Map<SessionId, _>` means session-scoped.
Note that, as with `InMemoryTodoRepository`, **the instance's lifetime and the key's scope can
differ** (one instance per agent, entries partitioned per session).

---

## 6. Known misnomers

These name a lifetime wrongly and have **not yet been renamed**. Watch for them when reading the
code.

- **`OnSessionStartHook` / `OnSessionEndHook`** — they fire on the opening/closing of a
  `LiveSession`, not on a session (record) starting or ending. They can fire again when the same
  session is resumed. Read literally, the names suggest "once per session", which is wrong.
- **`TranscriptManager.initialize`** — called once **per turn**, not once per session.
  Splitting it into `beginTurn` / `endTurn` was left as a separate change (noted in the
  `package-info` of `agent.session.transcript`).
- **`${AIMON_AGENT_RUNTIME_ID}`** — a render variable usable in a skill body, but an
  **agent-scoped** value. Used as a per-execution unique discriminator (something like
  `/tmp/work/${AIMON_AGENT_RUNTIME_ID}`), concurrent sessions will share one directory. To split
  per session use `${AIMON_SESSION_ID}`.
- **`conversation` in the names of persistent fields, collections and channels** — the wire key
  of `ToolContextKeys.SESSION_ID` is still `"conversationId"`, the Mongo collections are
  `conversation_locks` / `conversation_inbox` / `conversation_signals`, and the Postgres tables
  and channels are `conversation_*`. This was **deliberately frozen** (§7). Only the Java
  identifiers were renamed, so the names looking mismatched is the normal state.

The word `Session` still points at several lifetimes — the persistent `SessionRecord`, a
`LiveSessionCache` entry, `ReplSession` (one CLI run), `BrowserSession` (a Playwright context).
The list is in [`glossary.en.md` §3](glossary.en.md).

### Where a name was reused — `SessionApprovalStore`

The name `SessionApprovalStore` was **retired once and then reused with a different meaning.**
It is the most confusing point when reading old code or old documents, so here it is pinned down
in a table.

| Point in time | What `SessionApprovalStore` referred to | Key |
|------|-----------------------------------|-----|
| before the rename | the agent-wide approval store (the name was lying) | `AgentRuntimeId` |
| then | — (renamed to `AgentApprovalStore`; the name stood empty) | — |
| **now** | **the per-session approval store** (`…skill.policy.session`) | `SessionId` |

So searching by an old name maps like this.

| Old name | Current name |
|---------|----------|
| `SessionApprovalStore` (the agent-wide one) | `AgentApprovalStore` (`…skill.policy.agent`) |
| `SessionAwareSkillInvocationPolicy` | `ApprovalCachingSkillInvocationPolicy` |
| `ConversationApprovalStore` | `SessionApprovalStore` (`…skill.policy.session`) |
| `ConversationScopedSkillInvocationPolicy` | `SessionScopedSkillInvocationPolicy` |
| `ApprovalScope.CONVERSATION` | `ApprovalScope.SESSION` |

**Not one of the approval scopes changed meaning.** A decision recorded in `AgentApprovalStore`
still applies to every session of the same agent, still has no TTL, and is still not cleared by
`/clear`. The default path is still the narrow one — the per-session `SessionApprovalStore` —
and the policy chain looks there first. Undoing is still `/revoke` (`RevokeApprovalsCommand`),
with `--agent` clearing the agent-wide ones too.

A per-session approval reaches **that session and the executions that session delegated**. A
subagent fork has **no `SessionId` of its own at all** — a fork is not a session's turn, so
`DefaultSubagentExecutor` does not put `SESSION_ID` on the tool context and instead exposes the
execution identity `ExecutionId` as `EXECUTION_ID`. That is why a fork separately carries the id
of the session that launched it as `invokingSessionId` (`SkillInvocationRequest`,
`ToolContextKeys.INVOKING_SESSION_ID` — the wire key is frozen and remains
`"invokingConversationId"`). The two ids sit on **different axes** — `sessionId` is *lifetime*
(what my session is), `invokingSessionId` is *reach* (whose decisions apply). When a fork
launches another fork, it passes down **the user's** session id, not the intermediate fork's
(`InvokingSessionAccess.idToPropagate`).

> Forks used to be issued a fresh `SessionId` of their own, and to tools that id read exactly
> like a user session while actually conferring no authority at all. The only conversion left is
> `forkTranscriptLabel` — and that is a **label**, arising because `TranscriptBuffer` is typed by
> `SessionId`, not a lookup key.

---

## 7. Where the names came from

There were two renames, and the reason was the same each time — **the name was misstating the
lifetime.**

1. `AgentExecutionContext` → **`AgentRuntime`**. "context" read like a value created afresh per
   execution, when in fact it was a long-lived runtime, one per agent.
2. `Conversation` → **`SessionRecord`**, `AgentSession` → **`LiveSession`**. While the persistent
   aggregate was called a "conversation" its name was out of step with the unit users see (the
   session), and while the node-local handle was called `AgentSession` it read as the persistent
   unit. Since **the bare name `Session`** was what made the two confusable, neither side carries
   that name after the rework (§3, §5.1).

The second rework did not abolish the word "conversation" — it dropped out **only where it
denoted a lifetime**, and remains where it denotes the exchange of messages with the LLM
(`getConversationHistory()`, "Conversation compacted"). That distinction is the last prohibition
in §4.

The old ↔ new name mapping table is in
[`../migration/rename-maps.md`](../migration/rename-maps.md).
In both refactors **no wire format, DDL, channel name, key prefix or persistent field changed** —
[`../migration/frozen-names.md`](../migration/frozen-names.md) is that boundary. That is why places
remain where the Java identifier is `Session*` while the stored name is `conversation_*` (§6).

---

## Related documents

- [`glossary.en.md`](glossary.en.md) — definitions per term, and the lifetime dictionary
- [`architecture.en.md`](architecture.en.md) — reference for the core abstractions
- [`../develop/agent-session-guide.en.md`](../features/session/agent-session-guide.en.md) — the `LiveSession` API and event streaming
- [`../design/agent-execution/agent-runtime-scope.md`](../design/agent-execution/agent-runtime-scope.md) — the background to redefining agent scope
- [`../design/session/session-model.md`](../design/session/session-model.md) — the design for persisting session state
- [`../design/session/routing.md`](../design/session/routing.md) — multi-node session routing
