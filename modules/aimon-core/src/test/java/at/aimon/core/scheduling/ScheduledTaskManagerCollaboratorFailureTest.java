/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.core.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.base.Principal;
import at.aimon.core.scheduling.event.ScheduledTaskEventListener;
import at.aimon.core.scheduling.event.SimpleScheduledTaskEventPublisher;
import at.aimon.core.scheduling.event.TaskCancelledEvent;
import at.aimon.core.scheduling.exception.SchedulingException;
import at.aimon.core.scheduling.quota.DefaultTaskQuotaManager;
import at.aimon.core.scheduling.repository.InMemoryScheduledTaskExecutionHistoryRepository;
import at.aimon.core.scheduling.repository.InMemoryScheduledTaskRepository;
import at.aimon.core.scheduling.scheduler.ScheduledFire;
import at.aimon.core.scheduling.scheduler.TaskScheduler;

/**
 * The cluster seams are remote I/O in any distributed deployment, so they fail. Pins what the manager does then: a
 * failed broadcast never breaks the cancel or interrupt that caused it, and a failed guard skips the fire instead of
 * running it or killing the scheduler's thread.
 */
class ScheduledTaskManagerCollaboratorFailureTest {

    private final Principal alice = Principal.user("alice");
    private final InMemoryScheduledTaskRepository taskRepo = new InMemoryScheduledTaskRepository();
    private final InMemoryScheduledTaskExecutionHistoryRepository historyRepo = new InMemoryScheduledTaskExecutionHistoryRepository();
    private final SimpleScheduledTaskEventPublisher events = new SimpleScheduledTaskEventPublisher();
    private final DefaultTaskQuotaManager quota = new DefaultTaskQuotaManager(1);
    private final TaskScheduler scheduler = mock(TaskScheduler.class);
    private final RoutineExecutor routine = mock(RoutineExecutor.class);

    /** How many publishes the bus was asked for, so a test cannot pass by never reaching the failure. */
    private final List<ScheduledTaskId> publishAttempts = new ArrayList<>();

    private final ScheduledTaskInterruptBus failingBus = new ScheduledTaskInterruptBus() {
        @Override
        public void publish(ScheduledTaskId taskId, InterruptReason reason) {
            publishAttempts.add(taskId);
            throw new IllegalStateException("the broker is unreachable");
        }

        @Override
        public Subscription subscribe(InterruptListener listener) {
            return () -> {
            };
        }
    };

    private ScheduledTask task;

    @BeforeEach
    void setUp() {
        task = ScheduledTask.builder().id(ScheduledTaskId.generate()).name("t").cronExpression("* * * * *").owner(alice)
                .boundRuntimeId(AgentRuntimeId.fromName("failures")).routine(List.of(RoutineStep.of("Bash", "{}")))
                .enabled(true).build();
    }

    @Test
    @DisplayName("cancel finishes everything it owes when the bus cannot publish")
    void cancelSurvivesAFailingBus() {
        final ScheduledTaskManager manager = managerBuilder().interruptBus(failingBus).build();
        final List<TaskCancelledEvent> cancelled = new ArrayList<>();
        events.addListener(new ScheduledTaskEventListener() {
            @Override
            public void onTaskCancelled(TaskCancelledEvent event) {
                cancelled.add(event);
            }
        });
        manager.register(task);

        assertThatCode(() -> manager.cancel(task.getId(), alice)).doesNotThrowAnyException();

        assertThat(publishAttempts).containsExactly(task.getId());
        verify(scheduler).unschedule(task.getId());
        verify(routine).interrupt(task.getId(), InterruptReason.TASK_CANCELLED);
        assertThat(taskRepo.findById(task.getId())).isEmpty();
        assertThat(cancelled).hasSize(1);
        assertThat(quota.getCurrentUsage(alice)).as("the quota was given back").isZero();
    }

    @Test
    @DisplayName("interrupt still reports its local run when the bus cannot publish")
    void interruptReportsTheLocalRunThroughAFailingBus() {
        final ScheduledTaskManager manager = managerBuilder().interruptBus(failingBus).build();
        manager.register(task);
        when(routine.interrupt(task.getId(), InterruptReason.TASK_CANCELLED)).thenReturn(true);

        assertThat(manager.interrupt(task.getId(), alice)).isTrue();

        assertThat(publishAttempts).containsExactly(task.getId());
        assertThat(taskRepo.findById(task.getId())).as("interrupt leaves the schedule alone").isPresent();
    }

    @Test
    @DisplayName("interrupt with no local run returns false rather than throwing when the bus cannot publish")
    void interruptWithNoLocalRunSurvivesAFailingBus() {
        final ScheduledTaskManager manager = managerBuilder().interruptBus(failingBus).build();
        manager.register(task);

        assertThat(manager.interrupt(task.getId(), alice)).isFalse();

        assertThat(publishAttempts).containsExactly(task.getId());
    }

    @Test
    @DisplayName("a guard that throws skips the fire, on both entry points, without reaching the caller")
    void aThrowingGuardSkipsTheFire() {
        final ScheduledExecutionGuard broken = taskId -> {
            throw new SchedulingException("the lease store is down", new IllegalStateException());
        };
        final ScheduledTaskManager manager = managerBuilder().executionGuard(broken).build();
        manager.register(task);
        final Instant slot = Instant.parse("2026-10-27T10:00:00Z");

        assertThatCode(() -> manager.executeFire(ScheduledFire.of(task.getId(), slot, slot)))
                .doesNotThrowAnyException();
        assertThatCode(() -> manager.executeTask(task.getId())).doesNotThrowAnyException();

        verify(routine, never()).execute(any());
        assertThat(historyRepo.findByTaskId(task.getId())).isEmpty();
    }

    @Test
    @DisplayName("a lease that throws on close does not reach the scheduler's thread, and the run stays recorded")
    void aLeaseThatThrowsOnCloseDoesNotEscape() {
        final ScheduledExecutionGuard guard = taskId -> Optional.of(() -> {
            throw new IllegalStateException("could not release");
        });
        final ScheduledTaskManager manager = managerBuilder().executionGuard(guard).build();
        manager.register(task);
        final Instant now = Instant.parse("2026-10-27T10:00:00Z");
        when(routine.execute(any())).thenReturn(RoutineResult.success(task.getId(), List.of(), now, now));

        assertThatCode(() -> manager.executeTask(task.getId())).doesNotThrowAnyException();

        assertThat(historyRepo.findByTaskId(task.getId())).hasSize(1);
    }

    private ScheduledTaskManager.Builder managerBuilder() {
        return ScheduledTaskManager.builder().taskRepository(taskRepo).historyRepository(historyRepo)
                .routineExecutor(routine).taskScheduler(scheduler).eventPublisher(events).quotaManager(quota);
    }
}
