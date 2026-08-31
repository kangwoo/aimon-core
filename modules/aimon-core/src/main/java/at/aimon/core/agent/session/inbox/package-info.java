/**
 * The cross-node mailbox for a session: how input reaches a session whose holder is somewhere else.
 *
 * <p>
 * {@link at.aimon.core.agent.session.inbox.SessionInbox} is the SPI. Any node may
 * {@link at.aimon.core.agent.session.inbox.SessionInbox#deliver deliver} an
 * {@link at.aimon.core.agent.session.inbox.InboundMessage}; only the node holding the session's lease should
 * {@link at.aimon.core.agent.session.inbox.SessionInbox#collect collect}. The SPI does not enforce that — the
 * routing layer does, which is why holdership lives in
 * {@link at.aimon.core.agent.session.store.SessionLeaseStore} and not here.
 *
 * <h2>Why this lives in aimon-core</h2>
 *
 * <p>
 * This SPI is keyed by {@link at.aimon.core.agent.session.SessionId} and carries
 * {@link at.aimon.core.agent.queue.QueuedInputPriority} — both core types, so the module dependency can only point
 * one way. It sat in {@code aimon-session-base} for as long as routing was its only caller; the distributed
 * backends ({@code aimon-session-redis}, {@code -postgres}, {@code -mongodb}) implement it and had to take a
 * dependency on the routing module to see it. Moving the SPI down cuts that: wire formats, collection names and DDL
 * are untouched.
 *
 * <h2>What it is not</h2>
 *
 * <p>
 * The inbox never deduplicates by message content — that is
 * {@link at.aimon.core.agent.session.idempotency.IdempotencyStore}'s job, keyed by a client-supplied key.
 * Implementations owe two guarantees: priority-then-FIFO ordering inside {@code collect}, and atomic removal of what
 * they return.
 */
package at.aimon.core.agent.session.inbox;
