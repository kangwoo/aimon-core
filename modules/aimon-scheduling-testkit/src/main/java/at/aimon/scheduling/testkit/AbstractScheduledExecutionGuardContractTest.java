/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.scheduling.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.scheduling.ScheduledExecutionGuard;
import at.aimon.core.scheduling.ScheduledExecutionGuard.ExecutionLease;
import at.aimon.core.scheduling.ScheduledTaskId;
import at.aimon.core.scheduling.scheduler.ScheduledFire;

/**
 * What every {@link ScheduledExecutionGuard} owes the manager that consults it before each fire.
 *
 * <ul>
 * <li>Of several concurrent attempts to begin the same task, exactly one is granted.</li>
 * <li>Closing the lease releases it: the next fire of the task is granted.</li>
 * <li>Different tasks do not block each other.</li>
 * <li>A guard that {@link #dedupsOccurrences() dedups occurrences} also refuses a second fire of a slot that already
 * ran, after its lease was released — the late duplicate from another node — while granting the next slot.</li>
 * </ul>
 *
 * <p>
 * A backend joins by subclassing, handing out guards from {@link #guardFor(String)} — one per node over the same
 * backing store, or the same instance for both names for a node-local guard — and tagging itself
 * {@code @Tag("docker")} if it needs a daemon.
 */
public abstract class AbstractScheduledExecutionGuardContractTest {

    private static final int CONTENDERS = 8;

    /**
     * @param nodeName
     *            {@code "node-A"} or {@code "node-B"}
     * @return that node's guard; two names share one backing store
     */
    protected abstract ScheduledExecutionGuard guardFor(String nodeName);

    /**
     * Whether the guard remembers the {@link ScheduledFire#getSlot() slots} that began, so a released slot is not
     * granted
     * twice. {@code false} — the task-id behaviour of {@code InMemoryScheduledExecutionGuard} — skips that test.
     *
     * @return whether occurrence dedup is part of this guard's contract
     */
    protected abstract boolean dedupsOccurrences();

    @Test
    @DisplayName("of concurrent attempts to begin one task, exactly one is granted")
    void exactlyOneConcurrentAttemptWins() throws Exception {
        final ScheduledTaskId taskId = ScheduledTaskId.of("contended");
        final Instant slot = Instant.parse("2026-10-27T10:00:00Z");
        final CountDownLatch go = new CountDownLatch(1);
        final ExecutorService pool = Executors.newFixedThreadPool(CONTENDERS);
        final List<Future<Optional<ExecutionLease>>> attempts = new ArrayList<>();
        try {
            for (int i = 0; i < CONTENDERS; i++) {
                final ScheduledExecutionGuard guard = guardFor(i % 2 == 0 ? "node-A" : "node-B");
                attempts.add(pool.submit(() -> {
                    go.await();
                    return guard.tryBegin(ScheduledFire.of(taskId, slot, slot));
                }));
            }
            go.countDown();
            int granted = 0;
            for (Future<Optional<ExecutionLease>> attempt : attempts) {
                if (attempt.get(10, TimeUnit.SECONDS).isPresent()) {
                    granted++;
                }
            }
            assertThat(granted).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("closing the lease lets the next fire of the task begin")
    void closingTheLeaseReleasesIt() {
        final ScheduledTaskId taskId = ScheduledTaskId.of("released");
        final Instant first = Instant.parse("2026-10-27T10:00:00Z");
        final Instant next = first.plusSeconds(60);

        final ExecutionLease lease = guardFor("node-A").tryBegin(ScheduledFire.of(taskId, first, first)).orElseThrow();
        assertThat(guardFor("node-B").tryBegin(ScheduledFire.of(taskId, next, next)))
                .as("overlap with a run still in progress").isEmpty();
        lease.close();

        assertThat(guardFor("node-B").tryBegin(ScheduledFire.of(taskId, next, next))).isPresent();
    }

    @Test
    @DisplayName("two different tasks do not block each other")
    void tasksAreIndependent() {
        final Instant slot = Instant.parse("2026-10-27T10:00:00Z");

        assertThat(guardFor("node-A").tryBegin(ScheduledFire.of(ScheduledTaskId.of("one"), slot, slot))).isPresent();
        assertThat(guardFor("node-B").tryBegin(ScheduledFire.of(ScheduledTaskId.of("two"), slot, slot))).isPresent();
    }

    @Test
    @DisplayName("an occurrence that already ran is refused when its late duplicate arrives; the next one is granted")
    void lateDuplicateOfARunOccurrenceIsRefused() {
        Assumptions.assumeTrue(dedupsOccurrences(), "this guard keys on the task id, not on occurrences");
        final ScheduledTaskId taskId = ScheduledTaskId.of("deduped");
        final Instant slot = Instant.parse("2026-10-27T10:00:00Z");

        guardFor("node-A").tryBegin(ScheduledFire.of(taskId, slot, slot)).orElseThrow().close();

        assertThat(guardFor("node-B").tryBegin(ScheduledFire.of(taskId, slot.plusSeconds(9), slot)))
                .as("same slot, arriving late").isEmpty();
        final Instant nextSlot = slot.plusSeconds(60);
        assertThat(guardFor("node-B").tryBegin(ScheduledFire.of(taskId, nextSlot, nextSlot))).isPresent();
    }
}
