package at.aimon.session.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.store.SessionLease;
import io.lettuce.core.api.StatefulRedisConnection;

/**
 * Integration tests for {@link RedisSessionLeaseStore} against a real Redis container.
 */
@DisplayName("RedisSessionLeaseStore integration")
@Tag("docker")
class RedisSessionLeaseStoreIntegrationTest {

    private StatefulRedisConnection<String, String> connection;
    private RedisSessionLeaseStore lock;

    @BeforeEach
    void setUp() {
        RedisTestSupport.flushAll();
        connection = RedisTestSupport.connect();
        lock = new RedisSessionLeaseStore(connection);
    }

    @AfterEach
    void tearDown() {
        if (connection != null) {
            connection.close();
        }
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
    void extendRespectsFencingToken() {
        final SessionId id = SessionId.of("c-lock-2");
        final SessionLease handle = lock.tryAcquire(id, "node-A", Duration.ofSeconds(2)).orElseThrow();

        assertThat(lock.extend(handle, Duration.ofSeconds(5))).isTrue();

        // Force-release as another holder by manipulating the underlying key — fencing should detect mismatch.
        connection.sync().del("aimon:conv:lock:" + id.value());
        final SessionLease stolen = lock.tryAcquire(id, "node-B", Duration.ofSeconds(5)).orElseThrow();
        assertThat(stolen.getFencingToken()).isGreaterThan(handle.getFencingToken());

        // Original handle is now stale; extend must return false.
        assertThat(lock.extend(handle, Duration.ofSeconds(5))).isFalse();
    }

    @Test
    @DisplayName("release with stale handle is silent no-op (does not unlock the new holder)")
    void releaseHonorsFencing() {
        final SessionId id = SessionId.of("c-lock-3");
        final SessionLease stale = lock.tryAcquire(id, "node-A", Duration.ofSeconds(1)).orElseThrow();

        // Wait past the 1s lease for natural expiry; another holder takes over.
        try {
            Thread.sleep(1_200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }

        final SessionLease live = lock.tryAcquire(id, "node-B", Duration.ofSeconds(10)).orElseThrow();
        // Stale release must NOT unlock 'live' — fencing rejects it.
        lock.release(stale);
        assertThat(lock.tryAcquire(id, "node-C", Duration.ofSeconds(1))).isEmpty();

        // Live release works.
        lock.release(live);
        assertThat(lock.tryAcquire(id, "node-D", Duration.ofSeconds(1))).isPresent();
    }

    @Test
    @DisplayName("fencing token strictly increases across acquire/release cycles")
    void fencingTokenMonotonic() {
        final SessionId id = SessionId.of("c-lock-4");
        final SessionLease h1 = lock.tryAcquire(id, "node-A", Duration.ofSeconds(5)).orElseThrow();
        lock.release(h1);
        final SessionLease h2 = lock.tryAcquire(id, "node-A", Duration.ofSeconds(5)).orElseThrow();
        assertThat(h2.getFencingToken()).isGreaterThan(h1.getFencingToken());
    }

    @Test
    @DisplayName("a contended acquire issues exactly one token — losers never bump the fencing counter")
    void contendedAcquireIssuesOneToken() throws Exception {
        final SessionId id = SessionId.of("c-lock-5");
        final int contenders = 8;
        final CountDownLatch start = new CountDownLatch(1);
        final ExecutorService pool = Executors.newFixedThreadPool(contenders);
        try {
            final List<Future<Optional<SessionLease>>> attempts = new ArrayList<>();
            for (int i = 0; i < contenders; i++) {
                final String holderId = "node-" + i;
                final Callable<Optional<SessionLease>> attempt = () -> {
                    start.await();
                    return lock.tryAcquire(id, holderId, Duration.ofSeconds(30));
                };
                attempts.add(pool.submit(attempt));
            }
            start.countDown();

            final List<SessionLease> won = new ArrayList<>();
            for (Future<Optional<SessionLease>> attempt : attempts) {
                attempt.get(15, TimeUnit.SECONDS).ifPresent(won::add);
            }
            assertThat(won).hasSize(1);

            // The acquire is a single Lua script, so the counter is bumped only by the acquirer that actually stamped
            // the lock. A token issued and then not applied would leave the counter ahead of the live lease — and,
            // worse, in flight to be stamped later, behind a token that has already come and gone. Two round trips
            // (INCR from the client, then SET NX) cannot hold this: every loser bumps too.
            assertThat(connection.sync().get("aimon:conv:lock:fence:" + id.value()))
                    .isEqualTo(String.valueOf(won.get(0).getFencingToken()));
        } finally {
            pool.shutdownNow();
        }
    }
}
