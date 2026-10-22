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
        final DeadlineScheduler.Handle handle = DeadlineScheduler.schedule(Duration.ofMillis(300),
                runs::incrementAndGet);

        handle.close();
        // A later timer firing proves the timer has passed the closed one's due time.
        final CountDownLatch after = new CountDownLatch(1);
        DeadlineScheduler.schedule(Duration.ofMillis(600), after::countDown);
        assertThat(after.await(10, TimeUnit.SECONDS)).isTrue();

        assertThat(runs).hasValue(0);
        assertThat(handle.hasFired()).isFalse();
    }

    @Test
    @DisplayName("close() waits for an action that is running, so nothing of it lands after close returns")
    void closeWaitsForARunningAction() throws Exception {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger finished = new AtomicInteger();
        final DeadlineScheduler.Handle handle = DeadlineScheduler.schedule(Duration.ZERO, () -> {
            entered.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            finished.incrementAndGet();
        });
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();

        final CountDownLatch closed = new CountDownLatch(1);
        final Thread closer = new Thread(() -> {
            handle.close();
            closed.countDown();
        });
        closer.start();
        assertThat(closed.await(200, TimeUnit.MILLISECONDS)).as("close() must wait for the action").isFalse();
        release.countDown();
        assertThat(closed.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(finished).hasValue(1);
        assertThat(handle.hasFired()).isTrue();
        handle.close();
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
