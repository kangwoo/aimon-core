package at.aimon.core.scheduling.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import at.aimon.core.scheduling.ScheduledTaskId;
import at.aimon.core.scheduling.exception.InvalidCronExpressionException;
import at.aimon.core.scheduling.exception.TaskSchedulerException;

class InMemoryTaskSchedulerTest {

    private final List<ScheduledTaskId> executions = new CopyOnWriteArrayList<>();
    private InMemoryTaskScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new InMemoryTaskScheduler(executions::add, 2);
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdown();
    }

    @Test
    void constructorRejectsNullExecutor() {
        assertThatNullPointerException().isThrownBy(() -> new InMemoryTaskScheduler(null));
        assertThatNullPointerException().isThrownBy(() -> new InMemoryTaskScheduler(null, 1));
    }

    @Test
    void singleArgConstructorBuildsRunnableScheduler() {
        InMemoryTaskScheduler s = new InMemoryTaskScheduler(id -> {
        });
        try {
            s.start();
            s.scheduleRecurrently(ScheduledTaskId.of("ping"), "* * * * *");
            assertThat(s.exists(ScheduledTaskId.of("ping"))).isTrue();
        } finally {
            s.shutdown();
        }
    }

    @Test
    void schedulingBeforeStartIsRejected() {
        assertThatThrownBy(() -> scheduler.scheduleRecurrently(ScheduledTaskId.of("t"), "* * * * *"))
                .isInstanceOf(TaskSchedulerException.class).hasMessageContaining("not running");
    }

    @Test
    void invalidCronExpressionIsWrapped() {
        scheduler.start();
        assertThatThrownBy(() -> scheduler.scheduleRecurrently(ScheduledTaskId.of("t"), "this is not a cron"))
                .isInstanceOf(InvalidCronExpressionException.class);
    }

    @Test
    void scheduleRecurrentlyRejectsNullArgs() {
        scheduler.start();
        assertThatNullPointerException().isThrownBy(() -> scheduler.scheduleRecurrently(null, "* * * * *"));
        assertThatNullPointerException().isThrownBy(() -> scheduler.scheduleRecurrently(ScheduledTaskId.of("t"), null));
    }

    @Test
    void existsReturnsTrueAfterScheduleAndFalseAfterUnschedule() {
        scheduler.start();
        ScheduledTaskId id = ScheduledTaskId.of("backup");
        scheduler.scheduleRecurrently(id, "* * * * *");

        assertThat(scheduler.exists(id)).isTrue();

        scheduler.unschedule(id);
        assertThat(scheduler.exists(id)).isFalse();
    }

    @Test
    void unscheduleNonexistentTaskIsNoOp() {
        scheduler.start();
        scheduler.unschedule(ScheduledTaskId.of("never-scheduled"));
        assertThat(scheduler.exists(ScheduledTaskId.of("never-scheduled"))).isFalse();
    }

    @Test
    void rescheduleReplacesExistingEntry() {
        scheduler.start();
        ScheduledTaskId id = ScheduledTaskId.of("rolling");
        scheduler.scheduleRecurrently(id, "0 1 * * *");
        scheduler.scheduleRecurrently(id, "0 2 * * *");

        assertThat(scheduler.exists(id)).isTrue();
    }

    @Test
    void clearRemovesAllScheduledTasks() {
        scheduler.start();
        scheduler.scheduleRecurrently(ScheduledTaskId.of("a"), "* * * * *");
        scheduler.scheduleRecurrently(ScheduledTaskId.of("b"), "* * * * *");

        scheduler.clear();

        assertThat(scheduler.exists(ScheduledTaskId.of("a"))).isFalse();
        assertThat(scheduler.exists(ScheduledTaskId.of("b"))).isFalse();
    }

    @Test
    void existsAndUnscheduleRejectNullId() {
        scheduler.start();
        assertThatNullPointerException().isThrownBy(() -> scheduler.exists(null));
        assertThatNullPointerException().isThrownBy(() -> scheduler.unschedule(null));
    }

    @Test
    void shutdownIsIdempotent() {
        scheduler.start();
        scheduler.shutdown();
        scheduler.shutdown();
        assertThatThrownBy(() -> scheduler.scheduleRecurrently(ScheduledTaskId.of("x"), "* * * * *"))
                .isInstanceOf(TaskSchedulerException.class);
    }

    @Test
    void taskExecutorInterfaceAcceptsLambda() {
        ScheduledTaskExecutor executor = id -> executions.add(id);
        executor.execute(ScheduledTaskId.of("manual"));
        assertThat(executions).extracting(ScheduledTaskId::value).containsExactly("manual");
    }
}
