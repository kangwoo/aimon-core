package at.aimon.core.subagent.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("DeadlineScheduler")
class DeadlineSchedulerTest {

    @Test
    @DisplayName("an action runs once when its delay passes")
    void runsOnce() throws Exception {
        final CountDownLatch ran = new CountDownLatch(1);
        final DeadlineScheduler.Handle handle = DeadlineScheduler.schedule(Duration.ZERO, ran::countDown);

        assertThat(ran.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(handle.hasFired()).isTrue();
        handle.close();
    }

    @Test
    @DisplayName("a handle closed first never runs its action, however short the delay")
    void closedNeverRuns() throws Exception {
        final AtomicInteger runs = new AtomicInteger();
        final CountDownLatch gate = new CountDownLatch(1);
        // Occupy the timer thread so the action under test is due but cannot start before close().
        final DeadlineScheduler.Handle blocker = DeadlineScheduler.schedule(Duration.ZERO, () -> {
            try {
                gate.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        final DeadlineScheduler.Handle handle = DeadlineScheduler.schedule(Duration.ZERO, runs::incrementAndGet);

        handle.close();
        gate.countDown();
        final CountDownLatch after = new CountDownLatch(1);
        DeadlineScheduler.schedule(Duration.ZERO, after::countDown);
        assertThat(after.await(10, TimeUnit.SECONDS)).isTrue();

        assertThat(runs).hasValue(0);
        assertThat(handle.hasFired()).isFalse();
        blocker.close();
    }

    @Test
    @DisplayName("an action that throws does not stop the timer")
    void throwingActionIsIsolated() throws Exception {
        DeadlineScheduler.schedule(Duration.ZERO, () -> {
            throw new IllegalStateException("boom");
        });
        final CountDownLatch ran = new CountDownLatch(1);
        DeadlineScheduler.schedule(Duration.ZERO, ran::countDown);

        assertThat(ran.await(10, TimeUnit.SECONDS)).isTrue();
    }
}
