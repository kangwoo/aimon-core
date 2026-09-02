package at.aimon.core.agent.session.idempotency;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import at.aimon.core.agent.AgentExecutionResult;
import at.aimon.core.agent.session.inbox.SessionInbox;

/**
 * Idempotency store SPI per design §9.2.
 *
 * <p>
 * Tracks {@link IdempotencyEntry}s keyed by client-supplied idempotency key. Provides atomic
 * {@link #putIfAbsent(String, IdempotencyEntry, Duration) putIfAbsent} for first-arrival detection,
 * {@link #markDone(String, AgentExecutionResult) markDone} for caching the final result, a pair that hands an entry's
 * holder between nodes as a turn is forwarded and then picked up ({@link #releaseHolder}, {@link #acquireHolder}), and
 * a small set of operations needed by the holder-loss sweeper ({@link #touch}, {@link #compareAndReset},
 * {@link #findStaleInFlight}).
 */
public interface IdempotencyStore {

    /**
     * Insert {@code entry} only if no entry exists for {@code key}. Atomic.
     *
     * <p>
     * For {@link IdempotencyEntry.Status#IN_FLIGHT} entries, {@code ttl} is the secondary TTL (~lease length, ~30s);
     * upon {@link #markDone}, the implementation switches to the longer primary TTL (typically 24h).
     *
     * @param key
     *            the idempotency key (must not be null)
     * @param entry
     *            the candidate entry (must not be null)
     * @param ttl
     *            initial TTL (must not be null)
     * @return {@link PutResult#inserted()} on success, or {@link PutResult#existing(IdempotencyEntry)} when an entry
     *         already exists
     */
    PutResult putIfAbsent(String key, IdempotencyEntry entry, Duration ttl);

    /**
     * Transition an in-flight entry to {@link IdempotencyEntry.Status#DONE} and cache its result. Refreshes TTL to the
     * primary value.
     *
     * @param key
     *            the idempotency key (must not be null)
     * @param result
     *            the final agent execution result (must not be null)
     */
    void markDone(String key, AgentExecutionResult result);

    /**
     * Look up an entry by key.
     *
     * @param key
     *            the idempotency key (must not be null)
     * @return the entry, or empty when none exists or it has expired
     */
    Optional<IdempotencyEntry> find(String key);

    /**
     * Refresh the secondary TTL of an in-flight entry. Called from the lease renewer.
     *
     * @param key
     *            the idempotency key (must not be null)
     * @param holderId
     *            the calling holder; the touch is silently ignored when this does not match the entry's holder
     * @return {@code true} when the touch was applied
     */
    boolean touch(String key, String holderId);

    /**
     * Hand an in-flight entry back to the "reserved, nobody executing" state: keep the entry (and therefore the
     * reservation on the key), but clear its {@code holderId} and extend its TTL to {@code ttl}.
     *
     * <p>
     * Called when a submit reserved the key and then lost the session lock, so the turn was forwarded to whichever
     * node holds it. The entry must survive that hand-off — deleting it would let a client retry re-execute the same
     * input, and would make the eventual {@link #markDone} on the holder a no-op so the result is never cached — but it
     * must stop looking like a live in-flight turn, because no node is touching it while it waits in the inbox and the
     * holder-loss sweeper would otherwise declare a healthy session lost. See {@link IdempotencyEntry}'s invariant
     * list.
     *
     * <p>
     * {@code ttl} should cover the expected inbox wait rather than the lease length; the reservation is what stops
     * duplicate execution, and it is worth more than a promptly-reclaimed key.
     *
     * @param key
     *            the idempotency key (must not be null)
     * @param expectedHolderId
     *            the holder that reserved the key; the call is silently ignored when this does not match (must not be
     *            null)
     * @param ttl
     *            new TTL for the reserved entry (must not be null)
     * @return {@code true} when the entry was handed back
     */
    boolean releaseHolder(String key, String expectedHolderId, Duration ttl);

    /**
     * Take a reservation nobody is executing: the holderless {@code IN_FLIGHT} entry {@link #releaseHolder} left for a
     * message queued in a {@link SessionInbox}, claimed by the node that has just collected that message and is about
     * to run it. On success the entry names {@code holderId} and its TTL is re-armed to {@code ttl}.
     *
     * <p>
     * This is {@link #releaseHolder}'s inverse, and it exists so that a forwarded turn is covered by the same
     * holder-loss detection as a locally executed one. A holderless reservation is invisible to
     * {@link #findStaleInFlight} by design — nobody touches it while it waits, so it is stale by construction — and
     * that is right up until some node takes the message out of the inbox and starts running it. From that moment the
     * turn is exactly as mortal as a local one, but with nothing naming the node running it its death was reported by
     * no sweeper, and the caller waiting on the forward only learned of it when its own deadline lapsed minutes later.
     * Naming a holder here is what puts the entry back in the sweeper's view for as long as it is being executed.
     *
     * <p>
     * The take-over must be atomic: two nodes racing on the same reservation must produce exactly one winner. It
     * applies to an {@code IN_FLIGHT} entry with no holder and to nothing else —
     *
     * <ul>
     * <li>no entry, or one whose TTL has lapsed as far as {@link #find} is concerned — {@code false}. Nothing is
     * reserved, so there is nothing to take over.
     * <li>{@code DONE} — {@code false}. Some node already produced a result under this key, and putting a cached
     * answer back into an executing state would make it look unfinished to every later reader.
     * <li>{@code IN_FLIGHT} with a holder — {@code false}, including when that holder equals {@code holderId}. A named
     * holder means a turn is executing somewhere and this caller is not it. (Reserver ids are minted per attempt, so a
     * caller meeting its own is not a case that arises.)
     * </ul>
     *
     * <p>
     * {@code ttl} is the secondary TTL rather than the long one the entry was carrying: from here on the caller's
     * lease renewer {@link #touch}es it, so it belongs on the same short clock as any other executing turn. That is
     * what makes the entry go quiet — and the sweeper notice — when the caller dies.
     *
     * @param key
     *            the idempotency key (must not be null)
     * @param holderId
     *            the holder to record: the per-attempt reserver id this caller will {@link #touch} with (must not be
     *            null)
     * @param ttl
     *            the secondary TTL to re-arm the entry with (must not be null)
     * @return {@code true} when this caller took the reservation over
     */
    boolean acquireHolder(String key, String holderId, Duration ttl);

    /**
     * Drop a reservation that no longer has a turn behind it: the holderless {@code IN_FLIGHT} entry
     * {@link #releaseHolder} left for a message queued in a {@link SessionInbox}, whose turn has since failed
     * terminally.
     *
     * <p>
     * This is {@link #compareAndReset}'s counterpart for the forwarded path, and it exists because that method cannot
     * reach these entries — it matches on a holder, and a queued reservation deliberately has none. Without it a
     * failed forwarded turn leaves its key reserved for the whole forward TTL, and the client's retry is told it
     * collapsed onto an attempt that is still running when in fact that attempt is dead: the key stops being
     * replayable at exactly the moment the caller was told to retry it.
     *
     * <p>
     * Only ever called once the failure has been announced, by the node that ran (or refused) the message, and only
     * for a message already taken out of the at-most-once inbox — so no other node can still run it and nothing is
     * lost by freeing the key. Deleting rather than marking it failed is the point: a cached failure would make every
     * later retry of that key inherit it.
     *
     * <p>
     * Implementations must ignore an entry that is {@code DONE} or that has a holder. Both mean something other than
     * this reservation now owns the key — a result worth replaying, or a live turn elsewhere — and neither is this
     * caller's to erase.
     *
     * @param key
     *            the idempotency key (must not be null)
     * @return {@code true} when a holderless in-flight entry was removed
     */
    boolean discardReservation(String key);

    /**
     * Atomically reset (or transition to FAILED) a stale in-flight entry held by {@code expectedHolderId}. Two
     * sweepers racing on the same stale entry must produce exactly one winner.
     *
     * @param key
     *            the idempotency key (must not be null)
     * @param expectedHolderId
     *            the holder id observed by the calling sweeper (must not be null)
     * @return {@code true} when this caller won the race and reset the entry
     */
    boolean compareAndReset(String key, String expectedHolderId);

    /**
     * Enumerate in-flight entries whose {@code lastTouchedAt} is older than {@code cutoff}.
     *
     * <p>
     * Implementations may use SCAN + per-entry TTL inspection (Redis) or a simple map walk (in-memory). Used by the
     * holder-loss sweeper.
     *
     * <p>
     * <strong>Entries with no holder must be excluded.</strong> A holderless {@code IN_FLIGHT} entry is a reservation
     * for a turn queued in a {@link SessionInbox} (see {@link #releaseHolder}); nobody executes it, so nobody
     * {@link #touch}es it, so it is stale by construction rather than by failure. The exclusion normally closes on its
     * own — the node that takes the message out of the inbox names itself with {@link #acquireHolder} before running
     * it — but that take-over may be refused or may fail, and such a turn then executes against a holderless entry
     * that this scan will not report. That is a deliberate trade rather than an invariant: a false holder loss evicts
     * a healthy turn and makes a client's retry execute twice, whereas a missed one costs the caller its forward
     * deadline. Implementations must not try to narrow it by guessing which holderless entries are live. Returning
     * one tells the sweeper a
     * healthy session lost its holder, and the session is evicted out from under it. Implementations that page
     * results should apply the filter before the limit, so reservations cannot crowd live turns out of a batch.
     *
     * @param cutoff
     *            entries with {@code lastTouchedAt < cutoff} are returned (must not be null)
     * @return matching entries with a holder (never null; may be empty)
     */
    List<IdempotencyEntry> findStaleInFlight(Instant cutoff);
}
