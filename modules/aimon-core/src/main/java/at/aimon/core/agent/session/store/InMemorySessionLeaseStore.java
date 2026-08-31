package at.aimon.core.agent.session.store;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

import at.aimon.core.agent.session.SessionId;

/**
 * Single-process {@link SessionLeaseStore} backed by a {@link ConcurrentMap}.
 *
 * <p>
 * The default for single-node deployments and the backend every unit test uses. Lease expiry is evaluated lazily at
 * each
 * entry point — there is no background sweeper, so an expired entry lingers in the map until somebody asks about it.
 *
 * <p>
 * Fencing tokens come from one process-global monotonic counter shared by all sessions. That is stronger than the
 * SPI requires (which is per-session monotonicity) and it is why {@link #release} may delete the entry outright:
 * the
 * counter does not live in the entry, so removing it cannot walk a token backwards.
 *
 * <p>
 * Formerly {@code at.aimon.session.base.spi.inmemory.InMemoryConversationLock}. Behaviour is unchanged except for the
 * added {@link #findHolder}.
 */
public final class InMemorySessionLeaseStore implements SessionLeaseStore {

    private final ConcurrentMap<SessionId, Entry> entries = new ConcurrentHashMap<>();
    private final AtomicLong fencingTokens = new AtomicLong();
    private final Clock clock;

    public InMemorySessionLeaseStore() {
        this(Clock.systemUTC());
    }

    /**
     * @param clock
     *            the clock used to stamp acquisitions and evaluate expiry (must not be null); tests inject a fixed or
     *            adjustable clock to drive lease expiry without sleeping
     */
    public InMemorySessionLeaseStore(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public Optional<SessionLease> tryAcquire(SessionId id, String holderId, Duration lease) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(holderId, "holderId must not be null");
        Objects.requireNonNull(lease, "lease must not be null");

        final Instant now = clock.instant();
        while (true) {
            final Entry existing = entries.get(id);
            // Expiry only — holder identity is deliberately not compared, so a second acquire from the same node
            // loses exactly as a different node would. See SessionLeaseStore's class javadoc.
            if (existing != null && existing.expiresAt.isAfter(now)) {
                return Optional.empty();
            }
            final long token = fencingTokens.incrementAndGet();
            final Entry candidate = new Entry(holderId, token, now, lease, now.plus(lease));
            if (existing == null) {
                if (entries.putIfAbsent(id, candidate) == null) {
                    return Optional.of(toLease(id, candidate));
                }
            } else if (entries.replace(id, existing, candidate)) {
                return Optional.of(toLease(id, candidate));
            }
        }
    }

    @Override
    public Optional<LeaseHolder> findHolder(SessionId id) {
        Objects.requireNonNull(id, "id must not be null");

        final Entry existing = entries.get(id);
        if (existing == null || !existing.expiresAt.isAfter(clock.instant())) {
            return Optional.empty();
        }
        return Optional.of(LeaseHolder.builder().holderId(existing.holderId).fencingToken(existing.fencingToken)
                .expiresAt(existing.expiresAt).build());
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * Token comparison only, with no expiry predicate: a lease that lapsed without a successor is renewed and thereby
     * resurrected. That is within the SPI contract — {@code extend} is not a liveness check — and it matches the
     * Postgres
     * and Mongo backends. Callers that need liveness use {@link #findHolder}.
     */
    @Override
    public boolean extend(SessionLease lease, Duration duration) {
        Objects.requireNonNull(lease, "lease must not be null");
        Objects.requireNonNull(duration, "duration must not be null");

        final Entry existing = entries.get(lease.getSessionId());
        if (existing == null || existing.fencingToken != lease.getFencingToken()) {
            return false;
        }
        final Entry renewed = new Entry(existing.holderId, existing.fencingToken, existing.acquiredAt, duration,
                clock.instant().plus(duration));
        return entries.replace(lease.getSessionId(), existing, renewed);
    }

    @Override
    public void release(SessionLease lease) {
        Objects.requireNonNull(lease, "lease must not be null");
        entries.computeIfPresent(lease.getSessionId(),
                (id, existing) -> existing.fencingToken == lease.getFencingToken() ? null : existing);
    }

    private static SessionLease toLease(SessionId id, Entry entry) {
        return SessionLease.builder().sessionId(id).holderId(entry.holderId).fencingToken(entry.fencingToken)
                .acquiredAt(entry.acquiredAt).lease(entry.lease).build();
    }

    private static final class Entry {

        private final String holderId;
        private final long fencingToken;
        private final Instant acquiredAt;
        private final Duration lease;
        private final Instant expiresAt;

        Entry(String holderId, long fencingToken, Instant acquiredAt, Duration lease, Instant expiresAt) {
            this.holderId = holderId;
            this.fencingToken = fencingToken;
            this.acquiredAt = acquiredAt;
            this.lease = lease;
            this.expiresAt = expiresAt;
        }
    }
}
