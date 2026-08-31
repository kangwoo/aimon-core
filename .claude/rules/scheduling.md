---
paths:
  - "modules/aimon-core/src/**/scheduling/**/*.java"
  - "modules/aimon-scheduling-*/src/**/*.java"
  - "modules/aimon-core/src/**/agent/**/*.java"
---

# Scheduling Lifecycle Rules

## Scope Model — four scopes, plus three execution units

| Scope | Examples | Identifier | Lifetime |
|-------|----------|------------|----------|
| **Application** | `SchedulingEngine`, `ScheduledTaskManager`, `RoutineExecutor`, `AgentRuntimeRegistry`, `SessionRecordStore`, `SessionLeaseStore`, `SessionInbox`, `SessionSignalBus`, `IdempotencyStore`, `KnowledgeStore`, `CredentialStore` | — | App startup → shutdown |
| **Agent** | `AgentRuntime` and the components it owns (`ToolRegistry`, `HookRegistry`, `McpClientManager`), `AgentEnvironmentSnapshot` | `AgentRuntimeId` (`agent:<name>[:<discriminator>]`) | Per `(Agent, discriminator)`; survives across sessions |
| **Session** | `SessionRecord`, `SessionTotals`, `budgetOverride`, `SessionTranscript` | `SessionId` | Per `SessionId`; **durable** |
| **Live session** | `LiveSession`, message queue, event publisher | (references the bound `SessionId`) | Node-local, **transient** (open → `close()`) |
| *(unit)* **Execution** | One agent run in general — **may have no session** (subagent fork, skill fork, rewake replay, scheduled routine) | `ExecutionId` (minted for **session-less** runs only; a turn is identified by `SessionId` + `TurnId`) | Superset of a turn |
| *(unit)* **Turn** | `AgentExecutionRequest` / `AgentExecutionResult`, `ExecutionBudget` · `BudgetTracker` (not turn-exclusive — see below) | `TurnId` (addressing only, **not persisted**) | One user input |
| *(unit)* **Iteration** | One ReAct pass (LLM call → tool execution → observation) | — | Inside a turn |

## Critical: turn / iteration / execution are not interchangeable
- **turn** = the processing of one user input in a session (`executor.execute(runtime, request)`); **iteration** = one ReAct pass; **execution** = one agent run in general, which **may have no session**.
- A turn is one *kind* of execution, not a synonym for it. When describing something both paths share — cancellation signals, budget trackers — write `execution`, never `turn`.
- `ExecutionBudget` / `BudgetTracker` apply **per execution unit (turn or fork)**, not per session (session totals live in `SessionTotals`). "Turn or fork" is exhaustive, not a hedge: `new BudgetTracker(` appears in exactly **2** main-source places — `OrcaAgentExecutor` (turn) and `DefaultSubagentExecutor` (fork). (grep returns 3; the third is example code inside `BudgetTracker`'s own javadoc.)
- `assistant turn` / `user turn` are allowed — the qualifier makes them LLM message-role vocabulary. A bare `turn` always means one user input.
- Full rule: `docs/overview/glossary.md` §4 › 실행 단위.

## Critical: session ≠ live session (1 : 0..N)
- A `SessionRecord` is the durable aggregate keyed by `SessionId`; a `LiveSession` is the node-local handle that runs turns against it.
- One session may have **zero** live sessions (nobody is talking to it) and may be served by **many** over time (idle-TTL eviction, restart, cross-node handoff).
- State that must survive those events belongs to the record, not the handle — this is why `SessionTotals` and `budgetOverride` live on the `SessionRecord` in `at.aimon.core.agent.session.store`. A live session writes both back as one pair through `SessionRecordStore.setTotalsAndBudgetOverride`.
- Naming a new type: durable → `Session*` in `agent.session[.store|.transcript]`; dies with the process → `LiveSession*` in `agent.session`; collected once per agent → `Agent*` in `agent`.
- **No type may be named exactly `Session` or `AgentSession`.** Both are the names that let the two lifetimes be confused for each other; `SessionNamingArchitectureTest` fails the build on either.
- "Conversation" is still a legitimate word — it means the **LLM message exchange** (`getConversationHistory()`, "Conversation compacted"), never a lifetime.
- See `docs/overview/glossary.md` for the full lifetime table and the distinct meanings of "session" in this codebase.

## Critical: Application-Level Components
- `SchedulingEngine`, `ScheduledTaskManager`, `RoutineExecutor` are **application-scoped (long-lived)**
- **Never close scheduling components when an `AgentRuntime` is destroyed**

## Critical: AgentRuntime is Agent-Scoped (NOT Session-Scoped)
- `AgentRuntime` lifetime is bound to `(Agent, discriminator)`, not to a session.
- `AgentRuntimeId` is derived deterministically: `agent:<name>` or `agent:<name>:<discriminator>`. Use `AgentRuntimeId.from(Agent)` / `from(Agent, String)` factories. `generate()` does NOT exist.
- `LiveSession.close()` must NOT call `AgentRuntime.close()` — the runtime is shared across the agent's sessions.
- `AgentRuntime.close()` is invoked only at application shutdown or explicit agent removal.
- `ScheduledTask.boundRuntimeId` references the agent-scoped runtime id, so cron re-fires after the originating session ends will still resolve the runtime from `AgentRuntimeRegistry`.

## Ownership Rules
- `AgentRuntimeRegistry` is created **outside** `SchedulingEngine` and injected via builder
- `SchedulingEngine` does NOT own the registry — it only references it
- Agent-runtime registration happens in bootstrap (CLI: `AgentSetupFactory`; Web: `LiveSessionOpener`), via `OrcaAgentRuntimeManager.getOrCreateRuntime(bundle, ...)`. Do NOT couple registration to per-open paths.

## Multi-Instance Design
- Scheduling implementations must support clustered/distributed environments
- `aimon-scheduling-quartz` provides the Quartz-based distributed scheduler
- Core defines scheduling interfaces; implementations are in separate modules
- Agent-derived runtime ids are stable across instances, enabling cron re-fires to resolve the same agent runtime on any node.
