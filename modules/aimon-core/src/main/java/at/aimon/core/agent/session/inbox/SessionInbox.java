package at.aimon.core.agent.session.inbox;

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
 *
 * <p>
 * <b>Removed is not the same as returned.</b> Every implementation deletes an entry from its backend before the
 * codec rebuilds it, so an entry this build cannot decode is already gone by the time anyone knows. Such an entry
 * must not take the rest of the batch with it: it is reported through {@link CollectedBatch#getUnreadable()} and
 * the atomic-removal promise above still holds for everything the call touched.
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
     * Atomically removes every message with priority &le; {@code maxPriority} for {@code id}, and returns those it
     * could rebuild in priority-then-FIFO order alongside those it could not.
     *
     * <p>
     * A single entry this build cannot decode must not cost the rest of the batch. Implementations guard the decode
     * <b>per entry</b> and catch {@link RuntimeException} there — the boundary is the entry, not the exception type,
     * because the value objects an envelope rebuilds ({@link at.aimon.core.base.Principal.Type}, {@code Instant})
     * signal a refusal with exceptions of their own. A failed entry is reported as an {@link UnreadableEntry} and
     * logged at {@code WARN} naming the session; it is never re-thrown out of this method.
     *
     * @param id
     *            the session (must not be null)
     * @param maxPriority
     *            inclusive ceiling — {@code NOW} returns only NOW, {@code LATER} returns all tiers
     * @return what the call removed, split into decoded and undecodable (never null; may be empty)
     * @throws SessionInboxException
     *             on backend failure — a decode failure is not one
     */
    CollectedBatch collect(SessionId id, QueuedInputPriority maxPriority);

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
