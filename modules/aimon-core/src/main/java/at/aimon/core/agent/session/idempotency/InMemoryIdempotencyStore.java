package at.aimon.core.agent.session.idempotency;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import at.aimon.core.agent.AgentExecutionResult;

/**
 * Single-process {@link IdempotencyStore} backed by a {@link ConcurrentMap}.
 *
 * <p>
 * Expiry is checked lazily at every {@link #find} / {@link #putIfAbsent} call. Suitable for {@code SINGLE_NODE}
 * deployments and unit tests; not optimized for high churn.
 */
public final class InMemoryIdempotencyStore implements IdempotencyStore {

    /** Default primary TTL applied to entries promoted to {@code DONE} when no override is supplied. */
    public static final Duration DEFAULT_PRIMARY_TTL = Duration.ofHours(24);

    private final ConcurrentMap<String, Stored> entries = new ConcurrentHashMap<>();
    private final Clock clock;
    private final Duration primaryTtl;

    /** Constructs a store with a system UTC clock and the {@link #DEFAULT_PRIMARY_TTL primary TTL default}. */
    public InMemoryIdempotencyStore() {
        this(Clock.systemUTC(), DEFAULT_PRIMARY_TTL);
    }

    /**
     * Constructs a store with the given clock and the {@link #DEFAULT_PRIMARY_TTL primary TTL default}.
     *
     * @param clock
     *            the clock used for expiry decisions
     */
    public InMemoryIdempotencyStore(Clock clock) {
        this(clock, DEFAULT_PRIMARY_TTL);
    }

    /**
     * Constructs a store with the given clock and primary TTL.
     *
     * @param clock
     *            the clock used for expiry decisions
     * @param primaryTtl
     *            the TTL applied when an entry is promoted to {@code DONE} via {@link #markDone}
     */
    public InMemoryIdempotencyStore(Clock clock, Duration primaryTtl) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.primaryTtl = Objects.requireNonNull(primaryTtl, "primaryTtl must not be null");
    }

    @Override
    public PutResult putIfAbsent(String key, IdempotencyEntry entry, Duration ttl) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(entry, "entry must not be null");
        Objects.requireNonNull(ttl, "ttl must not be null");
        final Instant now = clock.instant();
        evictExpired(key, now);
        final Stored proposed = new Stored(entry, now.plus(ttl));
        final Stored prior = entries.putIfAbsent(key, proposed);
        return prior == null ? PutResult.inserted() : PutResult.existing(prior.entry);
    }

    @Override
    public void markDone(String key, AgentExecutionResult result) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(result, "result must not be null");
        final Instant now = clock.instant();
        entries.computeIfPresent(key, (k, prev) -> {
            final IdempotencyEntry done = IdempotencyEntry.builder().key(prev.entry.getKey())
                    .sessionId(prev.entry.getSessionId()).inputHash(prev.entry.getInputHash())
                    .status(IdempotencyEntry.Status.DONE).result(result).createdAt(prev.entry.getCreatedAt())
                    .lastTouchedAt(now).build();
            return new Stored(done, now.plus(primaryTtl));
        });
    }

    @Override
    public Optional<IdempotencyEntry> find(String key) {
        Objects.requireNonNull(key, "key must not be null");
        evictExpired(key, clock.instant());
        return Optional.ofNullable(entries.get(key)).map(s -> s.entry);
    }

    @Override
    public boolean touch(String key, String holderId) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(holderId, "holderId must not be null");
        final Instant now = clock.instant();
        final boolean[] applied = {false};
        entries.computeIfPresent(key, (k, prev) -> {
            if (prev.entry.getStatus() != IdempotencyEntry.Status.IN_FLIGHT
                    || !prev.entry.getHolderId().map(holderId::equals).orElse(false)) {
                return prev;
            }
            applied[0] = true;
            final IdempotencyEntry refreshed = IdempotencyEntry.builder().key(prev.entry.getKey())
                    .sessionId(prev.entry.getSessionId()).inputHash(prev.entry.getInputHash())
                    .status(prev.entry.getStatus()).holderId(holderId).createdAt(prev.entry.getCreatedAt())
                    .lastTouchedAt(now).build();
            return new Stored(refreshed, now.plus(Duration.between(prev.entry.getLastTouchedAt(), prev.expiresAt)));
        });
        return applied[0];
    }

    @Override
    public boolean releaseHolder(String key, String expectedHolderId, Duration ttl) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(expectedHolderId, "expectedHolderId must not be null");
        Objects.requireNonNull(ttl, "ttl must not be null");
        final Instant now = clock.instant();
        final boolean[] applied = {false};
        entries.computeIfPresent(key, (k, prev) -> {
            if (prev.entry.getStatus() != IdempotencyEntry.Status.IN_FLIGHT
                    || !prev.entry.getHolderId().map(expectedHolderId::equals).orElse(false)) {
                return prev;
            }
            applied[0] = true;
            final IdempotencyEntry reserved = IdempotencyEntry.builder().key(prev.entry.getKey())
                    .sessionId(prev.entry.getSessionId()).inputHash(prev.entry.getInputHash())
                    .status(IdempotencyEntry.Status.IN_FLIGHT).createdAt(prev.entry.getCreatedAt()).lastTouchedAt(now)
                    .build();
            return new Stored(reserved, now.plus(ttl));
        });
        return applied[0];
    }

    @Override
    public boolean acquireHolder(String key, String holderId, Duration ttl) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(holderId, "holderId must not be null");
        Objects.requireNonNull(ttl, "ttl must not be null");
        final Instant now = clock.instant();
        // Before the take-over, so a lapsed reservation reads as absent here exactly as it does from find(). Without
        // it a drain pass could revive a key whose submitter's forward has long since given up on it.
        evictExpired(key, now);
        final boolean[] applied = {false};
        entries.computeIfPresent(key, (k, prev) -> {
            // Holderless IN_FLIGHT only: an entry with a holder is a turn executing somewhere else, and a DONE one is
            // a result that must stay replayable.
            if (prev.entry.getStatus() != IdempotencyEntry.Status.IN_FLIGHT || prev.entry.getHolderId().isPresent()) {
                return prev;
            }
            applied[0] = true;
            final IdempotencyEntry claimed = IdempotencyEntry.builder().key(prev.entry.getKey())
                    .sessionId(prev.entry.getSessionId()).inputHash(prev.entry.getInputHash())
                    .status(IdempotencyEntry.Status.IN_FLIGHT).holderId(holderId).createdAt(prev.entry.getCreatedAt())
                    .lastTouchedAt(now).build();
            return new Stored(claimed, now.plus(ttl));
        });
        return applied[0];
    }

    @Override
    public boolean discardReservation(String key) {
        Objects.requireNonNull(key, "key must not be null");
        final boolean[] discarded = {false};
        entries.computeIfPresent(key, (k, prev) -> {
            // Holderless IN_FLIGHT only: an entry with a holder is a live turn somewhere, and a DONE one is a result
            // that must stay replayable.
            if (prev.entry.getStatus() == IdempotencyEntry.Status.IN_FLIGHT && prev.entry.getHolderId().isEmpty()) {
                discarded[0] = true;
                return null;
            }
            return prev;
        });
        return discarded[0];
    }

    @Override
    public boolean compareAndReset(String key, String expectedHolderId) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(expectedHolderId, "expectedHolderId must not be null");
        final boolean[] won = {false};
        entries.computeIfPresent(key, (k, prev) -> {
            if (prev.entry.getStatus() == IdempotencyEntry.Status.IN_FLIGHT
                    && prev.entry.getHolderId().map(expectedHolderId::equals).orElse(false)) {
                won[0] = true;
                return null;
            }
            return prev;
        });
        return won[0];
    }

    @Override
    public List<IdempotencyEntry> findStaleInFlight(Instant cutoff) {
        Objects.requireNonNull(cutoff, "cutoff must not be null");
        final List<IdempotencyEntry> out = new ArrayList<>();
        for (Stored s : entries.values()) {
            // A holderless IN_FLIGHT entry is a reservation waiting in the inbox, not a live turn: nobody touches it,
            // so it always looks stale, and reporting it would have the sweeper declare a healthy session lost.
            if (s.entry.getStatus() == IdempotencyEntry.Status.IN_FLIGHT && s.entry.getHolderId().isPresent()
                    && s.entry.getLastTouchedAt().isBefore(cutoff)) {
                out.add(s.entry);
            }
        }
        return out;
    }

    private void evictExpired(String key, Instant now) {
        entries.computeIfPresent(key, (k, prev) -> prev.expiresAt.isBefore(now) ? null : prev);
    }

    private static final class Stored {
        final IdempotencyEntry entry;
        final Instant expiresAt;

        Stored(IdempotencyEntry entry, Instant expiresAt) {
            this.entry = entry;
            this.expiresAt = expiresAt;
        }
    }
}
