package at.aimon.session.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.store.SessionLease;

/**
 * Integration tests for {@link PostgresSessionLeaseStore} against a real Postgres container.
 */
@DisplayName("PostgresSessionLeaseStore integration")
@Tag("docker")
class PostgresSessionLeaseStoreIntegrationTest {

    private PostgresSessionLeaseStore lock;

    @BeforeEach
    void setUp() {
        PostgresTestSupport.truncateAll();
        lock = new PostgresSessionLeaseStore(PostgresTestSupport.dataSource());
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
    void extendRespectsFencingToken() throws SQLException {
        final SessionId id = SessionId.of("c-lock-2");
        final SessionLease handle = lock.tryAcquire(id, "node-A", Duration.ofSeconds(2)).orElseThrow();

        assertThat(lock.extend(handle, Duration.ofSeconds(5))).isTrue();

        // Force-delete the lock row to simulate a foreign release; fencing should detect mismatch on stale handle.
        try (Connection c = PostgresTestSupport.dataSource().getConnection(); Statement s = c.createStatement()) {
            s.execute("DELETE FROM conversation_lock WHERE conversation_id = '" + id.value() + "'");
        }

        final SessionLease stolen = lock.tryAcquire(id, "node-B", Duration.ofSeconds(5)).orElseThrow();
        assertThat(stolen.getFencingToken()).isGreaterThan(handle.getFencingToken());

        // Original handle is now stale; extend must return false.
        assertThat(lock.extend(handle, Duration.ofSeconds(5))).isFalse();
    }

    @Test
    @DisplayName("release with stale handle does not unlock the new holder")
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
    @DisplayName("fencing token strictly increases across acquire/release cycles")
    void fencingTokenMonotonic() {
        final SessionId id = SessionId.of("c-lock-4");
        final SessionLease h1 = lock.tryAcquire(id, "node-A", Duration.ofSeconds(5)).orElseThrow();
        lock.release(h1);
        final SessionLease h2 = lock.tryAcquire(id, "node-A", Duration.ofSeconds(5)).orElseThrow();
        assertThat(h2.getFencingToken()).isGreaterThan(h1.getFencingToken());
    }

    @Test
    @DisplayName("two concurrent acquirers — exactly one wins")
    void crossAcquirerRaceOneWinner() throws InterruptedException, ExecutionException {
        final SessionId id = SessionId.of("c-lock-5");
        final ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            final Future<Optional<SessionLease>> a = pool
                    .submit(() -> lock.tryAcquire(id, "node-A", Duration.ofSeconds(10)));
            final Future<Optional<SessionLease>> b = pool
                    .submit(() -> lock.tryAcquire(id, "node-B", Duration.ofSeconds(10)));
            final boolean ap = a.get().isPresent();
            final boolean bp = b.get().isPresent();
            assertThat(ap ^ bp).as("exactly one acquirer wins").isTrue();
        } finally {
            pool.shutdownNow();
        }
    }
}
