/**
 * At-most-once submission: remembering that a client-supplied key was already seen, and what it produced.
 *
 * <p>
 * {@link at.aimon.core.agent.session.idempotency.IdempotencyStore} keyed by an opaque client key, holding an
 * {@link at.aimon.core.agent.session.idempotency.IdempotencyEntry} that is either
 * {@code IN_FLIGHT} or {@code DONE}. First arrival wins via
 * {@link at.aimon.core.agent.session.idempotency.IdempotencyStore#putIfAbsent putIfAbsent}, whose
 * {@link at.aimon.core.agent.session.idempotency.PutResult} tells a retry it lost the race and hands it the entry
 * that won; {@code markDone} then caches the final
 * {@link at.aimon.core.agent.AgentExecutionResult} so the retry gets an answer rather than a second execution.
 *
 * <h2>Two TTLs, and why</h2>
 *
 * <p>
 * An {@code IN_FLIGHT} entry gets a short secondary TTL on the order of a lease, refreshed by
 * {@link at.aimon.core.agent.session.idempotency.IdempotencyStore#touch touch} from the lease renewer; reaching
 * {@code DONE} switches it to the long primary TTL. That asymmetry is what makes a crashed holder recoverable: an
 * entry whose owner stopped renewing goes stale, {@code findStaleInFlight} surfaces it, and
 * {@code compareAndReset} clears it so the work can be retried. A single long TTL would pin a key to a node that no
 * longer exists.
 *
 * <h2>Why this lives in aimon-core</h2>
 *
 * <p>
 * The entry references {@link at.aimon.core.agent.session.SessionId} and
 * {@link at.aimon.core.agent.AgentExecutionResult} — the result it caches <em>is</em> a core type — so the module
 * dependency can only point one way. Deduplication by message content is deliberately not here and not in
 * {@link at.aimon.core.agent.session.inbox.SessionInbox}: dedup happens on the key the caller chose, never on what
 * the message says.
 */
package at.aimon.core.agent.session.idempotency;
