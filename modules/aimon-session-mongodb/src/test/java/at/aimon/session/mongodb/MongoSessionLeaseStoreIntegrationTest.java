package at.aimon.session.mongodb;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.store.SessionLease;
import at.aimon.session.mongodb.internal.DocumentKeys;

/**
 * Integration tests for {@link MongoSessionLeaseStore} against a real MongoDB replica-set container.
 *
 * <p>
 * Note on fencing-token semantics (design §4.1): the counter is strictly monotonic per session for the whole
 * lifetime of that session, matching the Redis and postgres backends. Both directions are asserted below —
 * monotonicity within a live chain (acquire → lease-expire → steal → ...) and across release-then-reacquire cycles.
 */
@DisplayName("MongoSessionLeaseStore integration")
@Tag("docker")
class MongoSessionLeaseStoreIntegrationTest {

    private MongoSessionLeaseStore lock;

    @BeforeEach
    void setUp() {
        MongoTestSupport.dropAndApplyDdl();
        lock = new MongoSessionLeaseStore(MongoTestSupport.sharedDatabase(), DocumentKeys.COLL_LOCKS,
                Clock.systemUTC());
    }

    @Test
    @DisplayName("tryAcquire succeeds for a fresh conversation and is rejected for the same id")
    void tryAcquireBlocksConcurrentHolder() {
        final SessionId id = SessionId.of("c-lock-1");
        final Optional<SessionLease> first = lock.tryAcquire(id, "node-A", Duration.ofSeconds(10));
        final Optional<SessionLease> second = lock.tryAcquire(id, "node-B", Duration.ofSeconds(10));
        assertThat(first).isPresent();
        assertThat(second).isEmpty();
    }

    @Test
    @DisplayName("extend renews the lease and fails when fencing token mismatches")
    void extendRespectsFencingToken() throws InterruptedException {
        final SessionId id = SessionId.of("c-lock-2");
        final SessionLease handle = lock.tryAcquire(id, "node-A", Duration.ofSeconds(2)).orElseThrow();

        assertThat(lock.extend(handle, Duration.ofSeconds(5))).isTrue();

        // Wait past the new 5s — actually use a 1s lease so the lease-expiry steal path fires quickly.
        final SessionLease h1 = lock.tryAcquire(SessionId.of("c-lock-2b"), "node-A", Duration.ofSeconds(1))
                .orElseThrow();
        Thread.sleep(1_200);
        final SessionLease stolen = lock.tryAcquire(SessionId.of("c-lock-2b"), "node-B", Duration.ofSeconds(5))
                .orElseThrow();
        // Within a live chain (acquire → lease-expire → steal), the token must strictly increase.
        assertThat(stolen.getFencingToken()).isGreaterThan(h1.getFencingToken());

        // Original h1 handle is now stale; extend must return false.
        assertThat(lock.extend(h1, Duration.ofSeconds(5))).isFalse();
    }

    @Test
    @DisplayName("release with stale handle is silent no-op (does not unlock the new holder)")
    void releaseHonorsFencing() throws InterruptedException {
        final SessionId id = SessionId.of("c-lock-3");
        final SessionLease stale = lock.tryAcquire(id, "node-A", Duration.ofSeconds(1)).orElseThrow();

        // Wait past the 1s lease for natural expiry; another holder takes over.
        Thread.sleep(1_200);

        final SessionLease live = lock.tryAcquire(id, "node-B", Duration.ofSeconds(10)).orElseThrow();
        // Stale release must NOT unlock 'live' — fencing rejects it.
        lock.release(stale);
        assertThat(lock.tryAcquire(id, "node-C", Duration.ofSeconds(1))).isEmpty();

        // Live release works.
        lock.release(live);
        assertThat(lock.tryAcquire(id, "node-D", Duration.ofSeconds(1))).isPresent();
    }

    @Test
    @DisplayName("fencing token strictly increases within a live acquire-chain (steal-on-expiry)")
    void fencingTokenMonotonicWithinLiveChain() throws InterruptedException {
        final SessionId id = SessionId.of("c-lock-4");
        final SessionLease h1 = lock.tryAcquire(id, "node-A", Duration.ofMillis(500)).orElseThrow();
        Thread.sleep(700);
        final SessionLease h2 = lock.tryAcquire(id, "node-B", Duration.ofMillis(500)).orElseThrow();
        assertThat(h2.getFencingToken()).isGreaterThan(h1.getFencingToken());
        Thread.sleep(700);
        final SessionLease h3 = lock.tryAcquire(id, "node-C", Duration.ofSeconds(5)).orElseThrow();
        assertThat(h3.getFencingToken()).isGreaterThan(h2.getFencingToken());
    }

    @Test
    @DisplayName("fencing token keeps increasing across release-then-reacquire cycles")
    void fencingTokenSurvivesRelease() {
        final SessionId id = SessionId.of("c-lock-5");
        final SessionLease h1 = lock.tryAcquire(id, "node-A", Duration.ofSeconds(5)).orElseThrow();
        assertThat(h1.getFencingToken()).isEqualTo(1L);

        lock.release(h1);
        final SessionLease h2 = lock.tryAcquire(id, "node-B", Duration.ofSeconds(5)).orElseThrow();
        assertThat(h2.getFencingToken()).as("release must not reset the counter — a stale handle would pass fencing")
                .isGreaterThan(h1.getFencingToken());

        lock.release(h2);
        final SessionLease h3 = lock.tryAcquire(id, "node-C", Duration.ofSeconds(5)).orElseThrow();
        assertThat(h3.getFencingToken()).isGreaterThan(h2.getFencingToken());
    }

    @Test
    @DisplayName("release frees the lock immediately for the next acquirer")
    void releaseFreesLockWithoutWaitingForLeaseExpiry() {
        final SessionId id = SessionId.of("c-lock-6");
        final SessionLease h1 = lock.tryAcquire(id, "node-A", Duration.ofMinutes(10)).orElseThrow();
        assertThat(lock.tryAcquire(id, "node-B", Duration.ofSeconds(5))).isEmpty();

        lock.release(h1);

        // The lease was nowhere near expiry: only the release itself can have made the lock available.
        assertThat(lock.tryAcquire(id, "node-B", Duration.ofSeconds(5))).isPresent();
    }

    @Test
    @DisplayName("extend on a released handle fails")
    void extendAfterReleaseFails() {
        final SessionId id = SessionId.of("c-lock-7");
        final SessionLease h1 = lock.tryAcquire(id, "node-A", Duration.ofMinutes(10)).orElseThrow();
        lock.release(h1);
        assertThat(lock.extend(h1, Duration.ofMinutes(10))).isFalse();
    }
}
