# Rename Maps

Search here for a name that no longer resolves.

Two refactors renamed a large number of Java symbols. This page is the old-name -> new-name lookup
for both, plus the two smaller renames that came with them.

**Nothing here is a data migration.** Every rename stops at the Java symbol boundary -- the wire
format, DDL, channel names, key prefixes and persisted fields were deliberately left alone, and
[`frozen-names.md`](frozen-names.md) is the list of what that covers. A node running the new jars
interoperates with the stored state and the live traffic of a node running the old ones.

> **Why this is a document and not a changelog section.** It used to live under `[Unreleased]` in
> [`CHANGELOG.md`](../../CHANGELOG.md), which is the wrong shelf for it: a changelog entry is written
> once and never revisited, while this table is *consulted* long after the release that produced it
> and *grows* whenever another rename lands. Left where it was, the first release to ship would have
> buried it under a version heading, and every citation of it would have become "go read the 0.2.0
> section" -- getting worse with each release. Adding to it is required rather than optional; see
> [`MAINTAINERS.md`](../../MAINTAINERS.md).

---

## `Conversation` / `AgentSession` → `SessionRecord` / `LiveSession`

The persistent aggregate was called `Conversation` and the node-local handle that ran turns against
it was called `AgentSession`. Both names were wrong in a way that cost data: "session" is what a user
resumes across a restart, so state that had to survive eviction kept getting parked on the thing named
`Session*` — the handle that dies with the process.

**Neither lifetime is called `Session`.** `Session` and `AgentSession` are both banned as type names,
enforced by `SessionNamingArchitectureTest` (production classes only). The rule bans the names, not
the token — `SessionRecord`, `SessionTotals` and `LiveSessionStatus` are all correct. The payoff is
in prose: with no type called `Session`, the phrase "the session" in a comment has no referent to be
quietly wrong about.

**"Conversation" is not a retired word.** It was demoted from a *lifetime* word to the *LLM
message-exchange* word and stays in that role — `SessionSnapshot.getConversationHistory()`,
`/compact`'s "Conversation compacted", the summarization prompt. A new type about message history
should be `Transcript*`; a new type about lifetime should be `Session*`.

*The durable record and its stores*

| Old | New |
|-----|-----|
| `Conversation` | `SessionRecord` |
| `ConversationView` | `SessionRecordView` |
| `ConversationRepository` | `SessionRecordStore` |
| `InMemoryConversationRepository` | `InMemorySessionRecordStore` |
| `ConversationId` | `SessionId` |
| `ConversationTotals` | `SessionTotals` |
| `ConversationStore` | `SessionStore` |
| `DefaultConversationStore` | `DefaultSessionStore` |
| `ConversationLeaseStore` | `SessionLeaseStore` |
| `InMemoryConversationLeaseStore` | `InMemorySessionLeaseStore` |
| `ConversationLease` | `SessionLease` |
| `ConversationCheckpointMailbox` | `SessionCheckpointMailbox` |
| `ConversationLeaseException` | `SessionLeaseException` |
| `ConversationNotHeldException` | `SessionNotHeldException` |

*The transcript* — types about message history took transcript vocabulary rather than `Session*`.

| Old | New |
|-----|-----|
| `ConversationTranscript` | `SessionTranscript` |
| `ConversationSnapshot` | `SessionSnapshot` |
| `ConversationMemory` | `TranscriptBuffer` |
| `ConversationManager` | `TranscriptManager` |
| `DefaultConversationManager` | `DefaultTranscriptManager` |

*The node-local handle and multi-node routing*

| Old | New |
|-----|-----|
| `AgentSession` | `LiveSession` |
| `DefaultAgentSession` | `DefaultLiveSession` |
| `AgentSessionFactory` | `LiveSessionFactory` |
| `AgentSessionOptions` | `LiveSessionOptions` |
| `AgentSessionStatus` | `LiveSessionStatus` |
| `AgentSessionOpener` | `LiveSessionOpener` |
| `LocalSessionCache` | `LiveSessionCache` |
| `AgentSessionManager` | `SessionRouter` |
| `DefaultAgentSessionManager` | `DefaultSessionRouter` |
| `AgentSessionManagerBuilder` | `SessionRouterBuilder` |
| `AgentSessionManagerConfig` | `SessionRouterConfig` |

`AgentSessionManager` became `SessionRouter` rather than `LiveSessionManager` because what it manages
is not the handles — it routes a request for a **session** to whichever node holds it, and the local
handle cache is one collaborator among several.

*Inbox, signals and leases per backend*

| Old | New |
|-----|-----|
| `ConversationInbox` | `SessionInbox` |
| `InMemoryConversationInbox` / `MongoConversationInbox` / `PostgresConversationInbox` / `RedisConversationInbox` | `InMemorySessionInbox` / `MongoSessionInbox` / `PostgresSessionInbox` / `RedisSessionInbox` |
| `ConversationInboxException` | `SessionInboxException` |
| `ConversationSignal` / `ConversationSignalBus` | `SessionSignal` / `SessionSignalBus` |
| `MongoConversationSignalBus` / `PostgresConversationSignalBus` | `MongoSessionSignalBus` / `PostgresSessionSignalBus` |
| `ConversationSignalBusException` | `SessionSignalBusException` |
| `ConversationSignalCodec` / `ConversationSignalRowCodec` | `SessionSignalCodec` / `SessionSignalRowCodec` |
| `MongoConversationLock` / `PostgresConversationLock` / `RedisConversationLock` | `MongoSessionLeaseStore` / `PostgresSessionLeaseStore` / `RedisSessionLeaseStore` |

*Subagent transcript snapshots*

| Old | New |
|-----|-----|
| `ConversationSnapshotStore` | `SessionSnapshotStore` |
| `InMemoryConversationSnapshotStore` | `InMemorySessionSnapshotStore` |
| `VfsConversationSnapshotStore` | `VfsSessionSnapshotStore` |
| `ScopedConversationSnapshotStore` | `ScopedSessionSnapshotStore` |
| `ConversationSnapshotCodec` | `SessionSnapshotCodec` |
| `JsonConversationSnapshotCodec` | `JsonSessionSnapshotCodec` |
| `ConversationCodecException` | `SessionSnapshotCodecException` |
| `ResumableConversation` | `ResumableSession` |

*Packages (import-breaking)*

| Old | New |
|-----|-----|
| `at.aimon.core.agent.conversation` | split into `at.aimon.core.agent.session.store` (record, lease, totals, mailbox) and `at.aimon.core.agent.session.transcript` (transcript, snapshot, buffer, manager) |
| `at.aimon.core.agent.conversation.store` | `at.aimon.core.agent.session.store` |
| `at.aimon.core.agent.conversation.exception` | `at.aimon.core.agent.session.exception` |
| `at.aimon.core.skill.policy.conversation` | `at.aimon.core.skill.policy.session` |

The `agent.conversation` package no longer exists. `SessionId` moved up to
`at.aimon.core.agent.session` — it is the join key both lifetimes share.

*Accessors and methods*

| Old | New |
|-----|-----|
| `getConversationId()` | `getSessionId()` — on every event, envelope, request, context and query that carried it |
| `getConversationIdValue()` | `getSessionIdValue()` |
| `getConversationTotals()` / `setConversationTotals(...)` | `getSessionTotals()` / `setSessionTotals(...)` |
| `ConversationRepository.listConversationIds()` | `SessionRecordStore.listSessionIds()` |
| `SessionRouter.deleteConversation(...)` / `releaseConversation(...)` / `yieldConversation(...)` | `deleteSession(...)` / `releaseSession(...)` / `yieldSession(...)` |
| `interrupt(conversationId, [turnId,] reason)` | `interrupt(sessionId, [turnId,] reason)` |
| `getConversationMemory()` | `getTranscriptBuffer()` |
| `getConversationManager()` | `getTranscriptManager()` |
| `CommandExecutionContext.Builder.sessionContext(...)` | `transcriptBuffer(...)` — the setter lied about both the type and the scope |
| `OrcaAgentExecutionRequest.Builder.previousConversation(...)` / `getPreviousConversation()` | `previousSnapshot(...)` / `getPreviousSnapshot()` |
| `OrcaAgentExecutionResult.getConversation()` | `getSnapshot()` |
| `getInvokingConversationId()` / builder `invokingConversationId(...)` | `getInvokingSessionId()` / `invokingSessionId(...)` |
| `purgeConversationApprovals(...)` | `purgeSessionApprovals(...)` |
| `getConversationApprovalStore()` / `withConversationApprovalStore(...)` | `getSessionApprovalStore()` / `withSessionApprovalStore(...)` |
| `getConversationSnapshotStore()` / `withConversationSnapshotStoreFactory(...)` | `getSessionSnapshotStore()` / `withSessionSnapshotStoreFactory(...)` |
| `maxTrackedConversations(...)` | `maxTrackedSessions(...)` |
| `ToolContextKeys.CONVERSATION_ID` / `INVOKING_CONVERSATION_ID` | `SESSION_ID` / `INVOKING_SESSION_ID` (key **strings** frozen) |

## `AgentExecutionContext` → `AgentRuntime`

`AgentExecutionContext` read like a per-execution value. It is the opposite: exactly one instance
lives per `(Agent, discriminator)`, owns the agent's long-lived machinery (`ToolRegistry`,
`HookRegistry`, `McpClientManager`, MCP connections), and survives every session running against that
agent.

| Old | New |
|-----|-----|
| `AgentExecutionContext` | `AgentRuntime` |
| `AgentExecutionContextId` | `AgentRuntimeId` |
| `AgentExecutionContextRegistry` | `AgentRuntimeRegistry` |
| `DefaultAgentExecutionContextRegistry` | `DefaultAgentRuntimeRegistry` |
| `OrcaAgentExecutionContext` | `OrcaAgentRuntime` |
| `OrcaAgentExecutionContextFactory` | `OrcaAgentRuntimeFactory` |
| `OrcaAgentExecutionContextManager` | `OrcaAgentRuntimeManager` |
| `AgentExecutionHookRegistrar` | `AgentRuntimeHookRegistrar` |
| `RewakeCapableContext` | `RewakeCapableRuntime` |
| `ContextScoped` | `AgentScoped` |

`AgentRuntimeId` keeps its format and factories: `agent:<name>` / `agent:<name>:<discriminator>`,
issued via `from(Agent)` / `from(Agent, String)`. There is still no `generate()`.

| Old | New |
|-----|-----|
| `getContextId()` / `getExecutionContextId()` / `getAgentExecutionContextId()` | `getAgentRuntimeId()` — on `AgentExecutionEvent`, `BackgroundTask`, `OnSessionStartContext`, `OnSessionEndContext`, `PendingTurn`, `QueuedInput`, `ResumableSession`, `RewakeEnvelope`, `RunQuery`, `SkillInvocationRequest`, `SubagentExecutionContext`, `SubagentExecutionEnvironment`, `TaskQuery`, `ToolContextEnrichmentInfo`, `WorkflowRun` |
| `ScheduledTask.getBoundContextId()` / `Builder.boundContextId(...)` | `getBoundRuntimeId()` / `Builder.boundRuntimeId(...)` |
| Builder setters `contextId(...)` / `executionContextId(...)` / `agentExecutionContextId(...)` | `agentRuntimeId(...)` |
| `OrcaAgentRuntimeManager.getOrCreateContext(...)` / `getContext(...)` / `destroyContext(...)` | `getOrCreateRuntime(...)` / `getRuntime(...)` / `destroyRuntime(...)` |
| `SchedulingEngineBuilder.contextRegistry(...)` | `agentRuntimeRegistry(...)` |
| `OrcaAgentRuntimeManager.Builder.contextRegistry(...)` / `contextFactory(...)` | `agentRuntimeRegistry(...)` / `agentRuntimeFactory(...)` |
| `AgentSetupFactory.Builder.contextRegistry(...)` | `agentRuntimeRegistry(...)` |
| `TaskQuery.byContext(...)` / `RunQuery.byContext(...)` | `byAgentRuntime(...)` |
| `PendingTurnRegistry.listByContext(...)` | `listByAgentRuntime(...)` |

Also renamed for the same reason: `SessionContext` → **`AgentEnvironmentSnapshot`**,
`SessionContextProvider` → `AgentEnvironmentSnapshotProvider`, `DefaultSessionContextProvider` →
`DefaultAgentEnvironmentSnapshotProvider`, all moved from `at.aimon.core.agent.session` to
`at.aimon.core.agent`. The type was never session-scoped — it is memoized by `AgentRuntimeId`.
Likewise `RenderContext.getSessionId()` → `getAgentRuntimeId()` and the skill-body variable
`${AIMON_SESSION_ID}` → `${AIMON_AGENT_RUNTIME_ID}`; the old accessor and setter remain as
`@Deprecated` delegates onto the same field. **This value is agent-scoped** — do not use it as a
per-run discriminator; use `${AIMON_EXECUTION_ID}`.

**`AIMON_AGENT_RUNTIME_ID`** is the env var exported to declarative shell hooks. The old
`AIMON_AGENT_EXECUTION_CONTEXT_ID` is **still exported alongside it** with the same value; the
constant is `@Deprecated` and the alias will be dropped in a future release.

Deliberately *not* renamed: the generic `String` scope keys on `TodoRepository`, `KnowledgeScope`,
`WikiScope` and `ScopeFilter` are not `AgentRuntimeId`s and keep `getContextId()`; `ToolContext`,
`ToolExecutionContext` and `SubagentExecutionContext` are different concepts.

## Skill approval stores — the name `SessionApprovalStore` is reused

**Read this table before trusting a memory of that name.** It first belonged to the agent-scoped
store, was freed by renaming that store to `AgentApprovalStore`, and then taken by the new
session-scoped one.

| Old | New | Keyed by |
|-----|-----|----------|
| `SessionApprovalStore` (`…skill.policy.session`, the *agent-wide* one) | `AgentApprovalStore` (`…skill.policy.agent`) | `AgentRuntimeId` |
| `InMemorySessionApprovalStore` (likewise) | `InMemoryAgentApprovalStore` | `AgentRuntimeId` |
| `SessionAwareSkillInvocationPolicy` | `ApprovalCachingSkillInvocationPolicy` | — |
| `ConversationApprovalStore` | **`SessionApprovalStore`** (`…skill.policy.session`) | `SessionId` |
| `InMemoryConversationApprovalStore` | `InMemorySessionApprovalStore` | `SessionId` |
| `ConversationAwareSkillInvocationPolicy` | `SessionScopedSkillInvocationPolicy` | — |
| `ApprovalScope.CONVERSATION` | `ApprovalScope.SESSION` | — |
| `InvokingConversationAccess` | `InvokingSessionAccess` | — |
| `OrcaAgentRuntimeFactory.withSessionApprovalStore(...)` (agent-wide) | `withAgentApprovalStore(...)` | — |
| `OrcaProviderDependencies.getSessionApprovalStore()` / `sessionApprovalStore(...)` (agent-wide) | `getAgentApprovalStore()` / `agentApprovalStore(...)` | — |

Approval *semantics* did not change: `y` is scoped to the session, `a` (or `--agent`) to the whole
agent with no TTL and no clearing on `/clear`, `/revoke` clears the narrow scope and `/revoke --agent`
clears both.

## The session SPIs moved into `aimon-core`, and the module is now `aimon-session-routing`

`aimon-session-base` held two unrelated things: multi-node routing, and five storage SPIs a
distributed session needs. Every SPI in the second group is keyed by `SessionId` and names nothing
routing owns, yet `aimon-session-{redis,postgres,mongodb}` each depended on the routing module purely
to see them. After the move all three have **zero** references to routing in main sources and demote
it to `testImplementation`; routing itself loses its last Jackson usage.

| Was | Is |
|-----|-----|
| `at.aimon.session.base.spi.SessionRecordCodec` | `at.aimon.core.agent.session.store.SessionRecordCodec` |
| `at.aimon.session.base.spi.StoredSessionRecord` | `at.aimon.core.agent.session.store.StoredSessionRecord` |
| `at.aimon.session.base.spi.StoredAgentExecutionResult` | `at.aimon.core.agent.session.store.StoredAgentExecutionResult` |
| `at.aimon.session.base.spi.SessionInbox` | `at.aimon.core.agent.session.inbox.SessionInbox` |
| `at.aimon.session.base.inbox.InboundMessage` | `at.aimon.core.agent.session.inbox.InboundMessage` |
| `at.aimon.session.base.inbox.InboundMessageId` | `at.aimon.core.agent.session.inbox.InboundMessageId` |
| `at.aimon.session.base.spi.inmemory.InMemorySessionInbox` | `at.aimon.core.agent.session.inbox.InMemorySessionInbox` |
| `at.aimon.session.base.spi.SessionSignal` | `at.aimon.core.agent.session.signal.SessionSignal` |
| `at.aimon.session.base.spi.SessionSignalBus` | `at.aimon.core.agent.session.signal.SessionSignalBus` |
| `at.aimon.session.base.spi.inmemory.InMemorySignalBus` | `at.aimon.core.agent.session.signal.InMemorySignalBus` |
| `at.aimon.session.base.spi.IdempotencyStore` | `at.aimon.core.agent.session.idempotency.IdempotencyStore` |
| `at.aimon.session.base.spi.IdempotencyEntry` | `at.aimon.core.agent.session.idempotency.IdempotencyEntry` |
| `at.aimon.session.base.spi.PutResult` | `at.aimon.core.agent.session.idempotency.PutResult` |
| `at.aimon.session.base.spi.inmemory.InMemoryIdempotencyStore` | `at.aimon.core.agent.session.idempotency.InMemoryIdempotencyStore` |
| `at.aimon.session.base.exception.IdempotencyConflictException` | `at.aimon.core.agent.session.exception.IdempotencyConflictException` |
| `at.aimon.session.base.exception.IdempotencyStoreException` | `at.aimon.core.agent.session.exception.IdempotencyStoreException` |
| `at.aimon.session.base.exception.SessionInboxException` | `at.aimon.core.agent.session.exception.SessionInboxException` |
| `at.aimon.session.base.exception.SessionSignalBusException` | `at.aimon.core.agent.session.exception.SessionSignalBusException` |

What remains — `SessionRouter`, `LiveSessionCache`, `LiveSessionOpener`, `SessionRouterBuilder`,
`SubmitRequest`, `SubmitDisposition`, `ClusterSessionStatus`, `DeploymentMode`, `SessionMetrics` —
keeps its simple names and moves package `at.aimon.session.base` → **`at.aimon.session.routing`**.
This is the module's second rename and not a wobble: `aimon-session-web` → `aimon-session-base` said
only what the module *was not*; `routing` is the first name that describes its contents.

---

## Memory moves to a service-tier backend seam

The mistake being corrected is a name that described the one backend that happened to exist rather
than the thing being named. The seam is now `PeerMemory` and its five capability tiers; the storage
interfaces keep every signature and become the default backend's materials. Design:
[`pluggable-memory-backend.md`](../design/memory/pluggable-memory-backend.md).

| Old | New | Value |
|-----|-----|-------|
| `RepresentationMemoryContextProvider` (`aimon-core`) | `SnapshotMemoryContextProvider` | — |
| `MemoryAssembly.CAPABILITY_WRITE_PATH` (`aimon-bootstrap`) | `MemoryAssembly.CAPABILITY_INGEST` | `"memory-write-path"` → `"memory-ingest"` |

`Representation` is a type only the store-backed backend has — a backend that computes its snapshot
on read never materializes one — so a class named after it was named after an implementation detail
of one backend. The constructor now takes a `MemorySnapshotReader`;
`SnapshotMemoryContextProvider.readerOver(representationStore)` builds one for callers assembling
the default backend by hand.

The degradation key changed **value** as well as name, because "write path" named a direction while
the thing that is missing is a capability — `INGEST` — that now has four siblings with keys of their
own (`memory-snapshot`, `memory-search`, `memory-chat`, `memory-observe`). It counts as public API
because a deployment reads it back with `stack.degradations().has(...)`; in-tree every reference went
through the constant, so nothing here broke, and what breaks is external code that hard-codes the
literal. Degradation keys are **not** in [`frozen-names.md`](frozen-names.md): they live only as long
as the process and are never stored.

---

## The file memory backend moves into `aimon-core`

`aimon-memory-file` stopped being a module. Its classes are unchanged -- same names, same signatures, same
JSONL on disk -- and they now live in `aimon-core` under `at.aimon.core.memory.file`, beside the
`InMemory*Store`s they are the durable counterpart to.

This is a package move, not a redesign, and it happened for a subtractive reason: the two distributed memory
backends (`aimon-memory-mongodb`, `aimon-memory-postgres`) were **removed** rather than moved, leaving one
node-local store implementation alone in a module of its own. Distributed memory is now a separate service
consumed through `PeerMemory` ([aimon-memory](https://github.com/kangwoo/aimon-memory)); see
[`../design/memory/pluggable-memory-backend.md`](../design/memory/pluggable-memory-backend.md) §4.3.

| Old | New |
|-----|-----|
| `at.aimon.memory.file.FileObservationStore` | `at.aimon.core.memory.file.FileObservationStore` |
| `at.aimon.memory.file.FileRepresentationStore` | `at.aimon.core.memory.file.FileRepresentationStore` |
| `at.aimon.memory.file.FileWorkspaceStore` | `at.aimon.core.memory.file.FileWorkspaceStore` |
| `at.aimon.memory.file.FileMemoryMaintenanceScheduler` | `at.aimon.core.memory.file.FileMemoryMaintenanceScheduler` |
| `at.aimon.memory.file.Compactable` | `at.aimon.core.memory.file.Compactable` |
| `at.aimon.memory.file.internal.*` | `at.aimon.core.memory.file.internal.*` |
| dependency `at.aimon.core:aimon-memory-file` | none -- it is in `at.aimon.core:aimon-core` |

Not `at.aimon.core.memory.impl.file`, and that is a decision rather than an oversight -- the memory domain has
no `.impl` split at all today (`InMemory*Store` and `StoreBackedPeerMemory` sit in `at.aimon.core.memory`),
`aimon-cli` still assembles these classes by name, and an `.impl` package with no ArchUnit rule behind it is a
label rather than a boundary. The reasoning, and the fact that this package **joins** the eventual `.impl` move
rather than pre-empting it, is in that design document's §4.2.

**Nothing on disk changed.** A log written by `aimon-memory-file` is read by this package without conversion:
the JSON Lines format, the field names inside each record, the sidecar `<log>.lock`, and the compaction
temp-file swap are all identical. That is the usual rule here -- see
[`frozen-names.md`](frozen-names.md) -- applied to a file format instead of a DDL.

## Two memory backend modules were removed, not renamed

Searching for these will find nothing, and that is the answer rather than a missing row:

| Gone | What took its place |
|-----|-----|
| `aimon-memory-postgres` (`at.aimon.memory.postgres.*`) | [aimon-memory](https://github.com/kangwoo/aimon-memory): `aimon-memory-store` for the tables, `aimon-memory-worker` for `PostgresDerivationQueueManager`'s queue, pgvector for `KnowledgeStoreOutboxRelay`'s index |
| `aimon-memory-mongodb` (`at.aimon.memory.mongodb.*`) | the same service |

IMPORTANT: this is **removal, not migration**. Every other table on this page is a rename that left the stored
data alone; this one leaves no stored data to read. The service's schema is a different design keyed on
`(workspace, observer, observed)`, and nothing migrates `mem_*` into it. A deployment on either module keeps
running on the last release that contained it, or starts empty on the service.

---

## Related documents

- [`frozen-names.md`](frozen-names.md) -- what was **not** renamed, and why that is a contract
- [`../project/api-stability.md`](../project/api-stability.md) -- what `0.x` promises about names
- [`../overview/glossary.md`](../overview/glossary.md) -- what the current names mean, and their lifetimes
- [`../overview/scope-model.md`](../overview/scope-model.md) -- the lifetime rules the first rename existed to fix
- [`../../CHANGELOG.md`](../../CHANGELOG.md) -- the release each rename shipped in
