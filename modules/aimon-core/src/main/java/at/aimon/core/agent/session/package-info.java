/**
 * Node-local handles that run turns against a durable session.
 *
 * <h2>Overview</h2>
 *
 * <p>
 * A <b>session</b> is durable and keyed by {@link at.aimon.core.agent.session.SessionId}; a
 * {@link at.aimon.core.agent.session.LiveSession} is the disposable, in-process handle through which a caller runs
 * ReAct turns against one. The handle is not the session, and the relationship is asymmetric:
 *
 * <pre>
 * one SessionRecord (durable, keyed by SessionId)  :  0..N LiveSession (transient, node-local)
 * </pre>
 *
 * <p>
 * A session exists with zero live handles (nobody is talking to it right now), and over its lifetime it may be served
 * by many handles in sequence — after idle-TTL eviction, after a process restart, or after a cross-node handoff in a
 * scale-out deployment. Anything that must survive those events belongs to the durable side, not to the handle: see
 * {@link at.aimon.core.agent.session.store.SessionTotals} and the record that
 * {@link at.aimon.core.agent.session.store.SessionRecordStore} holds, both of which live in the
 * {@link at.aimon.core.agent.session.store} package for exactly this reason.
 *
 * <h2>Key Types</h2>
 *
 * <ul>
 * <li>{@link at.aimon.core.agent.session.LiveSession} — the multi-turn facade bound to a single
 * {@link at.aimon.core.agent.session.SessionId}. Submit turns via {@code submit} / {@code submitAsync} /
 * {@code offerAsync}, observe them via {@code events()}, inspect them via {@code status()}.
 * <li>{@link at.aimon.core.agent.session.DefaultLiveSession} — the default implementation, driving an
 * {@link at.aimon.core.agent.AgentExecutor} over an agent-scoped {@link at.aimon.core.agent.AgentRuntime}.
 * <li>{@link at.aimon.core.agent.session.LiveSessionFactory} — opens sessions: resolves the agent from an
 * {@link at.aimon.core.agent.AgentRegistry} and pairs it with a caller-supplied agent runtime.
 * <li>{@link at.aimon.core.agent.session.LiveSessionOptions} — immutable per-session configuration (default
 * {@link at.aimon.core.agent.budget.ExecutionBudget}, locale, source agent id).
 * <li>{@link at.aimon.core.agent.session.LiveSessionStatus} — best-effort observability snapshot (phase, queue depth,
 * live turn progress, session totals). Diagnostics only, never a control gate.
 * <li>{@link at.aimon.core.agent.session.SubmitOutcome} — whether {@code offerAsync} executed the input directly or
 * deferred it onto the mid-turn injection queue.
 * <li>{@link at.aimon.core.agent.session.OpenAttributes} — caller-domain attributes threaded to a custom session
 * opener, consumed on cache miss only.
 * </ul>
 *
 * <h2>Lifecycle</h2>
 *
 * <p>
 * {@link at.aimon.core.agent.session.LiveSession#close()} releases <b>session-scoped</b> resources only. It must not
 * close the {@link at.aimon.core.agent.AgentRuntime}, which is <b>agent-scoped</b> and shared by every
 * session targeting the same agent, nor application-scoped components such as {@code SchedulingEngine} /
 * {@code ScheduledTaskManager} / {@code RoutineExecutor}. See {@code CLAUDE.md} "Scope &amp; Scheduling Lifecycle".
 *
 * <h2>Thread Safety</h2>
 *
 * <p>
 * Sessions are intended to be used serially — one turn at a time. Implementations are not required to be thread-safe;
 * callers multiplexing a session across threads must synchronize externally.
 *
 * @see at.aimon.core.agent.session.store
 * @see at.aimon.core.agent.AgentEnvironmentSnapshot
 */
package at.aimon.core.agent.session;
