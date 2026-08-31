/**
 * Multi-instance agent session manager primitives.
 *
 * <p>
 * This package hosts the {@link at.aimon.session.routing.SessionRouter} interface plus its public value types
 * ({@link at.aimon.session.routing.SubmitRequest}, {@link at.aimon.session.routing.SubmitDisposition},
 * {@link at.aimon.session.routing.LiveSessionCache}). The manager wraps {@link at.aimon.core.agent.session.LiveSession}
 * with the cross-node concerns required by a multi-instance / distributed deployment: distributed locking, cross-node
 * signal fan-out, mailbox hand-off for inputs that arrive at non-holder nodes, and idempotency.
 *
 * <p>
 * The storage SPIs those concerns run on are <b>not</b> here. They live in {@code aimon-core} under
 * {@link at.aimon.core.agent.session.store}, {@link at.aimon.core.agent.session.inbox},
 * {@link at.aimon.core.agent.session.signal} and {@link at.aimon.core.agent.session.idempotency}, together with
 * their in-memory reference implementations. This module is a <em>consumer</em> of those SPIs, not their owner —
 * every one of them is keyed by {@link at.aimon.core.agent.session.SessionId} and names nothing routing owns, so
 * the four distributed backends ({@code aimon-session-redis}, {@code -postgres}, {@code -mongodb}, and the
 * in-process default) implement them without depending on this module at all. Internal bridge components live in
 * {@link at.aimon.session.routing.internal}.
 *
 * <p>
 * Design reference: {@code docs/design/session/routing.md}.
 */
package at.aimon.session.routing;
