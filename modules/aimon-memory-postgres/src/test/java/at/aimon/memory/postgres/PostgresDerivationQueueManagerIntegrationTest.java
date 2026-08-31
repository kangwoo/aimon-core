package at.aimon.memory.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import at.aimon.core.base.Principal;
import at.aimon.core.llm.Message;
import at.aimon.core.memory.PeerView;
import at.aimon.core.memory.Workspace;
import at.aimon.core.memory.deriver.DerivationContext;
import at.aimon.core.memory.deriver.DerivationResult;
import at.aimon.core.memory.deriver.DerivationTask;
import at.aimon.core.memory.deriver.DerivationWorkUnit;
import at.aimon.core.memory.deriver.Deriver;
import at.aimon.core.memory.deriver.DeriverProperties;
import at.aimon.core.memory.redaction.DefaultRedactionPolicy;

/**
 * Verifies that {@link PostgresDerivationQueueManager} actually enforces the
 * per-work-unit serialization invariant against a real Postgres
 * (Testcontainers).
 *
 * <p>
 * Two angles:
 * <ul>
 * <li>{@link #onlyOneClaimWinnerUnderContention()} hammers the SQL primitive
 * directly — N threads race for the same row, exactly 1 wins.</li>
 * <li>{@link #concurrentWorkersSerializePerWorkUnitButParallelizeAcrossUnits()}
 * exercises the worker loop end-to-end: tasks for the same work unit are
 * processed sequentially, tasks for different units may overlap.</li>
 * </ul>
 */
@DisplayName("PostgresDerivationQueueManager integration")
@Tag("docker")
class PostgresDerivationQueueManagerIntegrationTest {

    private static final String WORKSPACE_ID = "ws-queue";
    private static final Workspace WORKSPACE = Workspace.builder().id(WORKSPACE_ID).displayName("ws-queue")
            .createdAt(Instant.now()).build();

    private DataSource dataSource;

    @BeforeEach
    void setUp() throws Exception {
        PostgresTestSupport.truncateAll();
        dataSource = PostgresTestSupport.dataSource();
        seedWorkspace();
    }

    @AfterEach
    void tearDown() {
        // Each test stops its own queue manager; nothing else to clean up.
    }

    @Test
    @DisplayName("tryAcquireClaim under contention picks exactly one winner")
    void onlyOneClaimWinnerUnderContention() throws Exception {
        // Spin up many independent queue managers (each gets its own holderId) and have them all
        // race for the same work unit at the same instant.
        final int contenders = 16;
        final List<PostgresDerivationQueueManager> managers = new ArrayList<>(contenders);
        for (int i = 0; i < contenders; i++) {
            managers.add(newManager(new RecordingDeriver(), 1));
        }
        final DerivationWorkUnit unit = workUnit("session-claim", "observer-claim");

        final CountDownLatch start = new CountDownLatch(1);
        final ExecutorService pool = Executors.newFixedThreadPool(contenders);
        try {
            final List<Future<Boolean>> futures = new ArrayList<>(contenders);
            for (PostgresDerivationQueueManager mgr : managers) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return mgr.tryAcquireClaim(unit);
                }));
            }

            start.countDown();

            int winners = 0;
            for (Future<Boolean> f : futures) {
                if (Boolean.TRUE.equals(f.get(10, TimeUnit.SECONDS))) {
                    winners++;
                }
            }

            assertThat(winners).as("exactly one contender may hold the claim row").isEqualTo(1);
            assertThat(claimRowCount(unit)).as("DB row count for the work unit").isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("expired claim is stealable by a different holder")
    void expiredClaimIsStealable() throws Exception {
        final PostgresDerivationQueueManager incumbent = newManager(new RecordingDeriver(), 1, Duration.ofMillis(50));
        final PostgresDerivationQueueManager challenger = newManager(new RecordingDeriver(), 1);
        final DerivationWorkUnit unit = workUnit("session-expire", "observer-expire");

        assertThat(incumbent.tryAcquireClaim(unit)).isTrue();
        // Challenger cannot steal while the incumbent's lease is fresh.
        assertThat(challenger.tryAcquireClaim(unit)).isFalse();

        // Wait past the 50ms lease and try again.
        Thread.sleep(120L);
        assertThat(challenger.tryAcquireClaim(unit)).as("challenger steals expired claim").isTrue();
    }

    @Test
    @DisplayName("releaseClaim only removes a row owned by this holder")
    void releaseClaimRespectsHolder() throws Exception {
        final PostgresDerivationQueueManager owner = newManager(new RecordingDeriver(), 1);
        final PostgresDerivationQueueManager other = newManager(new RecordingDeriver(), 1);
        final DerivationWorkUnit unit = workUnit("session-release", "observer-release");

        assertThat(owner.tryAcquireClaim(unit)).isTrue();
        // A different holder calling releaseClaim must not delete the row.
        other.releaseClaim(unit);
        assertThat(claimRowCount(unit)).isEqualTo(1);

        owner.releaseClaim(unit);
        assertThat(claimRowCount(unit)).isZero();
    }

    @Test
    @DisplayName("workers serialize tasks per work unit but overlap across units")
    void concurrentWorkersSerializePerWorkUnitButParallelizeAcrossUnits() throws Exception {
        final ConcurrencyTrackingDeriver deriver = new ConcurrencyTrackingDeriver();
        final PostgresDerivationQueueManager manager = newManager(deriver, 4);
        manager.start();
        try {
            final int tasksPerUnit = 6;
            final DerivationWorkUnit unitA = workUnit("session-a", "observer-a");
            final DerivationWorkUnit unitB = workUnit("session-b", "observer-b");

            for (int i = 0; i < tasksPerUnit; i++) {
                manager.enqueue(taskForUnit(unitA, "msg A-" + i));
                manager.enqueue(taskForUnit(unitB, "msg B-" + i));
            }

            // Wait until both work units have completed all their tasks. The 30s upper bound is
            // generous: each task sleeps 30ms inside the deriver.
            final long deadline = System.currentTimeMillis() + 30_000L;
            while (deriver.completionCount() < tasksPerUnit * 2 && System.currentTimeMillis() < deadline) {
                Thread.sleep(50L);
            }
        } finally {
            manager.stop();
        }

        assertThat(deriver.completionCount()).as("all enqueued tasks completed").isEqualTo(2 * 6 /* tasksPerUnit */);
        assertThat(deriver.maxConcurrencyFor(workUnit("session-a", "observer-a")))
                .as("unit A never had two workers running concurrently").isEqualTo(1);
        assertThat(deriver.maxConcurrencyFor(workUnit("session-b", "observer-b")))
                .as("unit B never had two workers running concurrently").isEqualTo(1);
    }

    private PostgresDerivationQueueManager newManager(Deriver deriver, int workers) {
        return newManager(deriver, workers, Duration.ofMinutes(1));
    }

    private PostgresDerivationQueueManager newManager(Deriver deriver, int workers, Duration lease) {
        final DeriverProperties props = DeriverProperties.of(workers, 8000, Duration.ofMillis(50));
        return new PostgresDerivationQueueManager(dataSource, deriver, new DefaultRedactionPolicy(), props, lease);
    }

    private static DerivationWorkUnit workUnit(String sessionId, String observerId) {
        return DerivationWorkUnit.of(WORKSPACE_ID, sessionId, Principal.Type.USER, observerId);
    }

    private static DerivationTask taskForUnit(DerivationWorkUnit unit, String text) {
        final PeerView observer = PeerView.of(WORKSPACE, Principal.builder().type(unit.getObserverType())
                .id(unit.getObserverId()).displayName(unit.getObserverId()).build());
        return DerivationTask.builder().workspace(WORKSPACE).sessionId(unit.getSessionId()).observer(observer)
                .messages(List.of(Message.user(text))).build();
    }

    private void seedWorkspace() throws Exception {
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO mem_workspace (id, display_name, created_at) VALUES (?, ?, now())")) {
            ps.setString(1, WORKSPACE_ID);
            ps.setString(2, WORKSPACE_ID);
            ps.executeUpdate();
        }
    }

    private int claimRowCount(DerivationWorkUnit unit) throws Exception {
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM mem_active_work_unit "
                        + "WHERE workspace_id = ? AND session_id = ? AND observer_principal_type = ? "
                        + "AND observer_principal_id = ?")) {
            ps.setString(1, unit.getWorkspaceId());
            ps.setString(2, unit.getSessionId());
            ps.setString(3, unit.getObserverType().name());
            ps.setString(4, unit.getObserverId());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /** No-op deriver — used by the claim primitive tests where the worker loop never runs. */
    private static final class RecordingDeriver implements Deriver {
        @Override
        public DerivationResult derive(DerivationContext context) {
            return DerivationResult.empty();
        }
    }

    /**
     * Test double that records, per work unit, the maximum observed concurrent invocations of
     * {@link #derive(DerivationContext)}. Sleeps briefly while inside derive() so any unintended
     * parallelism on the same work unit has time to manifest.
     */
    private static final class ConcurrencyTrackingDeriver implements Deriver {
        private final ConcurrentHashMap<DerivationWorkUnit, AtomicInteger> active = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<DerivationWorkUnit, AtomicInteger> peak = new ConcurrentHashMap<>();
        private final AtomicInteger completions = new AtomicInteger();

        @Override
        public DerivationResult derive(DerivationContext context) {
            final DerivationWorkUnit unit = DerivationWorkUnit.of(context.getWorkspace(), context.getSessionId(),
                    context.getObserver());
            final AtomicInteger activeCounter = active.computeIfAbsent(unit, k -> new AtomicInteger());
            final AtomicInteger peakCounter = peak.computeIfAbsent(unit, k -> new AtomicInteger());
            final int now = activeCounter.incrementAndGet();
            peakCounter.updateAndGet(prev -> Math.max(prev, now));
            try {
                Thread.sleep(30L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            activeCounter.decrementAndGet();
            completions.incrementAndGet();
            return DerivationResult.empty();
        }

        int maxConcurrencyFor(DerivationWorkUnit unit) {
            final AtomicInteger counter = peak.get(unit);
            return counter == null ? 0 : counter.get();
        }

        int completionCount() {
            return completions.get();
        }

        @SuppressWarnings("unused")
        Map<DerivationWorkUnit, Integer> snapshot() {
            return Map.copyOf(peak.entrySet().stream()
                    .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, e -> e.getValue().get())));
        }
    }
}
