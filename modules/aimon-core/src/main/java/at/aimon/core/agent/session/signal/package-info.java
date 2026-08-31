/**
 * Cross-node pub/sub for a single session: interrupts, evictions, doorbells and event frames.
 *
 * <p>
 * {@link at.aimon.core.agent.session.signal.SessionSignalBus} fans a
 * {@link at.aimon.core.agent.session.signal.SessionSignal} out to every node subscribed to a
 * {@link at.aimon.core.agent.session.SessionId}. Subscriptions are per session, not per node, so a signal reaches
 * whoever currently holds the session without the sender knowing who that is.
 *
 * <h2>Delivery contract</h2>
 *
 * <p>
 * Publication is at-least-once: receivers must tolerate the same signal arriving twice. Handlers run on the bus's
 * delivery thread and must not block — long work belongs on the subscriber's own dispatcher. Splitting the
 * high-frequency observability kinds ({@code EVENT}, {@code STATUS}) away from the control kinds
 * ({@code INTERRUPT}, {@code EVICT}, {@code MESSAGE_ENQUEUED}) is a fan-out optimization implementations may make —
 * the Redis bus does, on two channel namespaces — and never a routing constraint at this SPI level. Subscribers
 * receive every kind and filter on {@code getKind()}.
 *
 * <h2>Why this lives in aimon-core</h2>
 *
 * <p>
 * Same reason as the sibling SPIs: the bus is keyed by {@link at.aimon.core.agent.session.SessionId} and its
 * payloads reference core types, so the four backends had to depend on the routing module purely to see an
 * interface that named nothing routing owns. The move is source-level only — channel names, key prefixes and the
 * frozen {@code conversationId} wire key are all untouched (see the CHANGELOG's "Not changed" list).
 */
package at.aimon.core.agent.session.signal;
