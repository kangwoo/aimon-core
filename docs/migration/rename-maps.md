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

| Old | New |
|-----|-----|
| `RepresentationMemoryContextProvider` (`aimon-core`) | `SnapshotMemoryContextProvider` |

`Representation` is a type only the store-backed backend has — a backend that computes its snapshot
on read never materializes one — so a class named after it was named after an implementation detail
of one backend. The constructor now takes a `MemorySnapshotReader`;
`SnapshotMemoryContextProvider.readerOver(representationStore)` builds one for callers assembling
the default backend by hand.

---

## Related documents

- [`frozen-names.md`](frozen-names.md) -- what was **not** renamed, and why that is a contract
- [`../project/api-stability.md`](../project/api-stability.md) -- what `0.x` promises about names
- [`../overview/glossary.md`](../overview/glossary.md) -- what the current names mean, and their lifetimes
- [`../overview/scope-model.md`](../overview/scope-model.md) -- the lifetime rules the first rename existed to fix
- [`../../CHANGELOG.md`](../../CHANGELOG.md) -- the release each rename shipped in
