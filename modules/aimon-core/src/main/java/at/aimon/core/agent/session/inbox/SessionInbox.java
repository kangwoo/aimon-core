package at.aimon.core.agent.session.inbox;

import java.util.List;

import at.aimon.core.agent.queue.QueuedInputPriority;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.exception.SessionInboxException;
import at.aimon.core.agent.session.idempotency.IdempotencyStore;

/**
 * Cross-node mailbox SPI per routing design §5.6.
 *
 * <p>
 * Any node can {@link #deliver(InboundMessage) deliver} a message into a session's inbox. Only the lock holder
 * for that session should {@link #collect(SessionId, QueuedInputPriority) collect}; the interface does not
 * enforce that, the manager flow (§7.1, §7.2) does.
 *
 * <p>
 * Implementations must preserve priority-then-FIFO ordering inside {@code collect} and remove returned entries
 * atomically. Idempotency / dedup is delegated to {@link IdempotencyStore} — this SPI never deduplicates by message
 * content.
 */
public interface SessionInbox {

    /**
     * Append {@code message} to its session's inbox.
     *
     * @param message
     *            the envelope (must not be null). The implementation typically assigns the {@link InboundMessageId} —
     *            the {@code message.id} field on entry is unused; the returned id is authoritative.
     * @return the stable id assigned by this implementation
     * @throws SessionInboxException
     *             on backend failure
     */
    InboundMessageId deliver(InboundMessage message);

    /**
     * Atomically removes and returns up to all messages with priority &le; {@code maxPriority} for {@code id}, in
     * priority-then-FIFO order.
     *
     * @param id
     *            the session (must not be null)
     * @param maxPriority
     *            inclusive ceiling — {@code NOW} returns only NOW, {@code LATER} returns all tiers
     * @return collected messages (never null; may be empty)
     * @throws SessionInboxException
     *             on backend failure
     */
    List<InboundMessage> collect(SessionId id, QueuedInputPriority maxPriority);

    /**
     * Quick check without dequeuing.
     *
     * @param id
     *            the session (must not be null)
     * @return {@code true} when the inbox has zero pending messages for {@code id}
     */
    boolean isEmpty(SessionId id);

    /**
     * Drop every pending message for {@code id} (called from {@code releaseSession}).
     *
     * @param id
     *            the session (must not be null)
     */
    void purge(SessionId id);
}
