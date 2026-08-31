package at.aimon.core.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.base.Principal;
import at.aimon.core.scheduling.event.ScheduledTaskEvent;
import at.aimon.core.scheduling.event.ScheduledTaskEventListener;
import at.aimon.core.scheduling.event.SimpleScheduledTaskEventPublisher;
import at.aimon.core.scheduling.event.TaskCancelledEvent;
import at.aimon.core.scheduling.event.TaskRegisteredEvent;
import at.aimon.core.scheduling.exception.InvalidCronExpressionException;
import at.aimon.core.scheduling.exception.QuotaExceededException;
import at.aimon.core.scheduling.exception.TaskNotFoundException;
import at.aimon.core.scheduling.exception.UnauthorizedTaskAccessException;
import at.aimon.core.scheduling.quota.DefaultTaskQuotaManager;
import at.aimon.core.scheduling.repository.InMemoryScheduledTaskExecutionHistoryRepository;
import at.aimon.core.scheduling.repository.InMemoryScheduledTaskRepository;
import at.aimon.core.scheduling.scheduler.TaskScheduler;

class ScheduledTaskManagerTest {

    private final Principal alice = Principal.user("alice");
    private final Principal bob = Principal.user("bob");
    private final AgentRuntimeId ctx = AgentRuntimeId.fromName("orca");

    private InMemoryScheduledTaskRepository taskRepo;
    private InMemoryScheduledTaskExecutionHistoryRepository historyRepo;
    private TaskScheduler scheduler;
    private SimpleScheduledTaskEventPublisher publisher;
    private DefaultTaskQuotaManager quota;
    private RoutineExecutor routine;
    private ScheduledTaskManager manager;
    private List<ScheduledTaskEvent> events;

    @BeforeEach
    void setUp() {
        taskRepo = new InMemoryScheduledTaskRepository();
        historyRepo = new InMemoryScheduledTaskExecutionHistoryRepository();
        scheduler = mock(TaskScheduler.class);
        publisher = new SimpleScheduledTaskEventPublisher();
        quota = new DefaultTaskQuotaManager(2);
        routine = mock(RoutineExecutor.class);
        events = new ArrayList<>();
        publisher.addListener(new ScheduledTaskEventListener() {
            @Override
            public void onTaskRegistered(TaskRegisteredEvent event) {
                events.add(event);
            }

            @Override
            public void onTaskCancelled(TaskCancelledEvent event) {
                events.add(event);
            }
        });
        manager = new ScheduledTaskManager(taskRepo, historyRepo, routine, scheduler, publisher, quota);
    }

    @Test
    void constructorRejectsNullDependencies() {
        assertThatNullPointerException()
                .isThrownBy(() -> new ScheduledTaskManager(null, historyRepo, routine, scheduler, publisher, quota));
        assertThatNullPointerException()
                .isThrownBy(() -> new ScheduledTaskManager(taskRepo, null, routine, scheduler, publisher, quota));
        assertThatNullPointerException()
                .isThrownBy(() -> new ScheduledTaskManager(taskRepo, historyRepo, null, scheduler, publisher, quota));
        assertThatNullPointerException()
                .isThrownBy(() -> new ScheduledTaskManager(taskRepo, historyRepo, routine, null, publisher, quota));
        assertThatNullPointerException()
                .isThrownBy(() -> new ScheduledTaskManager(taskRepo, historyRepo, routine, scheduler, null, quota));
        assertThatNullPointerException()
                .isThrownBy(() -> new ScheduledTaskManager(taskRepo, historyRepo, routine, scheduler, publisher, null));
    }

    @Test
    void registerEnabledTaskPersistsAndSchedulesAndPublishesEvent() {
        ScheduledTask task = newTask(alice, "* * * * *", true);

        manager.register(task);

        assertThat(taskRepo.findById(task.getId())).contains(task);
        verify(scheduler).scheduleRecurrently(task.getId(), "* * * * *");
        assertThat(events).hasSize(1).first().isInstanceOf(TaskRegisteredEvent.class);
    }

    @Test
    void registerDisabledTaskDoesNotSchedule() {
        ScheduledTask task = newTask(alice, "* * * * *", false);

        manager.register(task);

        verify(scheduler, never()).scheduleRecurrently(any(), any());
    }

    @Test
    void registerInvalidCronWrapsException() {
        ScheduledTask task = newTask(alice, "totally not a cron", true);

        assertThatThrownBy(() -> manager.register(task)).isInstanceOf(InvalidCronExpressionException.class);
        assertThat(taskRepo.findById(task.getId())).isEmpty();
    }

    @Test
    void registerEnforcesQuota() {
        manager.register(newTask(alice, "* * * * *", true));
        manager.register(newTask(alice, "* * * * *", true));

        assertThatThrownBy(() -> manager.register(newTask(alice, "* * * * *", true)))
                .isInstanceOf(QuotaExceededException.class);
    }

    @Test
    void listingMethodsDelegateToRepository() {
        ScheduledTask aliceEnabled = newTask(alice, "* * * * *", true);
        ScheduledTask aliceDisabled = newTask(alice, "* * * * *", false);
        ScheduledTask bobEnabled = newTask(bob, "* * * * *", true);
        manager.register(aliceEnabled);
        manager.register(aliceDisabled);
        manager.register(bobEnabled);

        assertThat(manager.listAll()).hasSize(3);
        assertThat(manager.listByOwner(alice)).containsExactlyInAnyOrder(aliceEnabled, aliceDisabled);
        assertThat(manager.listEnabledByOwner(alice)).containsExactly(aliceEnabled);
    }

    @Test
    void listingMethodsRejectNullOwner() {
        assertThatNullPointerException().isThrownBy(() -> manager.listByOwner(null));
        assertThatNullPointerException().isThrownBy(() -> manager.listEnabledByOwner(null));
    }

    @Test
    void getByIdReturnsTaskForOwner() {
        ScheduledTask task = newTask(alice, "* * * * *", true);
        manager.register(task);

        assertThat(manager.getById(task.getId(), alice)).isEqualTo(task);
    }

    @Test
    void getByIdRejectsNullArgs() {
        assertThatNullPointerException().isThrownBy(() -> manager.getById(null, alice));
        assertThatNullPointerException().isThrownBy(() -> manager.getById(ScheduledTaskId.of("x"), null));
    }

    @Test
    void getByIdThrowsTaskNotFoundForUnknownId() {
        assertThatThrownBy(() -> manager.getById(ScheduledTaskId.of("missing"), alice))
                .isInstanceOf(TaskNotFoundException.class);
    }

    @Test
    void getByIdThrowsUnauthorizedWhenPrincipalDoesNotOwnTask() {
        ScheduledTask task = newTask(alice, "* * * * *", true);
        manager.register(task);

        assertThatThrownBy(() -> manager.getById(task.getId(), bob))
                .isInstanceOf(UnauthorizedTaskAccessException.class);
    }

    @Test
    void cancelUnschedulesAndRemovesTaskAndDecrementsQuota() {
        ScheduledTask task = newTask(alice, "* * * * *", true);
        manager.register(task);
        events.clear();

        // Pre-existing history is also wiped.
        historyRepo.save(ScheduledTaskExecutionHistory.builder().id("h").taskId(task.getId())
                .status(ScheduledTaskExecutionHistory.Status.SUCCESS).startedAt(Instant.now())
                .completedAt(Instant.now()).build());

        manager.cancel(task.getId(), alice);

        verify(scheduler).unschedule(task.getId());
        assertThat(taskRepo.findById(task.getId())).isEmpty();
        assertThat(historyRepo.findByTaskIdOrderByStartedAtDesc(task.getId(), 10)).isEmpty();
        assertThat(events).hasSize(1).first().isInstanceOf(TaskCancelledEvent.class);

        // Quota decremented — alice can register up to 2 more.
        manager.register(newTask(alice, "* * * * *", false));
        manager.register(newTask(alice, "* * * * *", false));
    }

    @Test
    void getHistoryReturnsRepositoryRecordsForOwner() {
        ScheduledTask task = newTask(alice, "* * * * *", true);
        manager.register(task);

        ScheduledTaskExecutionHistory h1 = ScheduledTaskExecutionHistory.builder().id("h1").taskId(task.getId())
                .status(ScheduledTaskExecutionHistory.Status.SUCCESS).startedAt(Instant.now())
                .completedAt(Instant.now()).build();
        historyRepo.save(h1);

        assertThat(manager.getHistory(task.getId(), alice, 5)).hasSize(1).first().isEqualTo(h1);
        assertThatThrownBy(() -> manager.getHistory(task.getId(), bob, 5))
                .isInstanceOf(UnauthorizedTaskAccessException.class);
    }

    @Test
    void setEnabledIsNoOpWhenAlreadyInDesiredState() {
        ScheduledTask task = newTask(alice, "* * * * *", true);
        manager.register(task);

        ScheduledTask returned = manager.setEnabled(task.getId(), alice, true);

        assertThat(returned).isEqualTo(task);
        // Initial schedule call only — no second one.
        verify(scheduler, times(1)).scheduleRecurrently(eq(task.getId()), any());
    }

    @Test
    void setEnabledFromTrueToFalseUnschedules() {
        ScheduledTask task = newTask(alice, "* * * * *", true);
        manager.register(task);

        ScheduledTask updated = manager.setEnabled(task.getId(), alice, false);

        assertThat(updated.isEnabled()).isFalse();
        assertThat(taskRepo.findById(task.getId()).orElseThrow().isEnabled()).isFalse();
        verify(scheduler).unschedule(task.getId());
    }

    @Test
    void setEnabledFromFalseToTrueSchedules() {
        ScheduledTask task = newTask(alice, "* * * * *", false);
        manager.register(task);

        ScheduledTask updated = manager.setEnabled(task.getId(), alice, true);

        assertThat(updated.isEnabled()).isTrue();
        verify(scheduler).scheduleRecurrently(task.getId(), "* * * * *");
    }

    @Test
    void validateCronExpressionRejectsNullOrInvalid() {
        assertThatNullPointerException().isThrownBy(() -> manager.validateCronExpression(null));
        assertThatThrownBy(() -> manager.validateCronExpression("not a cron"))
                .isInstanceOf(InvalidCronExpressionException.class);
        // Valid expression — no exception.
        manager.validateCronExpression("* * * * *");
    }

    @Test
    void executeTaskRunsRoutineAndPersistsHistoryAndUpdatesLastRun() {
        ScheduledTask task = newTask(alice, "* * * * *", true);
        manager.register(task);

        RoutineResult result = RoutineResult.success(task.getId(), List.of(), Instant.now(),
                Instant.now().plusMillis(10));
        when(routine.execute(task)).thenReturn(result);

        manager.executeTask(task.getId());

        ScheduledTask reloaded = taskRepo.findById(task.getId()).orElseThrow();
        assertThat(reloaded.getLastExecutedAt()).isPresent();
        assertThat(historyRepo.findByTaskIdOrderByStartedAtDesc(task.getId(), 5)).hasSize(1);
    }

    @Test
    void executeTaskSkipsWhenTaskNotFound() {
        manager.executeTask(ScheduledTaskId.of("ghost"));
        verify(routine, never()).execute(any());
    }

    @Test
    void executeTaskSkipsDisabledTask() {
        ScheduledTask task = newTask(alice, "* * * * *", false);
        manager.register(task);

        manager.executeTask(task.getId());
        verify(routine, never()).execute(any());
    }

    @Test
    void executeTaskSwallowsRoutineExceptions() {
        ScheduledTask task = newTask(alice, "* * * * *", true);
        manager.register(task);

        when(routine.execute(task)).thenThrow(new RuntimeException("boom"));

        // Must not throw out of executeTask; failure is logged and swallowed.
        manager.executeTask(task.getId());

        // No history saved — the exception aborts before persistence.
        assertThat(historyRepo.findByTaskIdOrderByStartedAtDesc(task.getId(), 5)).isEmpty();
    }

    @Test
    void executeTaskSkipsWhenGuardDeniesOverlappingRun() {
        ScheduledTask task = newTask(alice, "* * * * *", true);
        taskRepo.save(task);

        // A guard that denies, simulating an execution of this task already in progress (overlap or another node).
        final ScheduledExecutionGuard denyingGuard = id -> java.util.Optional.empty();
        final ScheduledTaskManager guarded = new ScheduledTaskManager(taskRepo, historyRepo, routine, scheduler,
                publisher, quota, denyingGuard);

        guarded.executeTask(task.getId());

        verify(routine, never()).execute(any());
        assertThat(historyRepo.findByTaskIdOrderByStartedAtDesc(task.getId(), 5)).isEmpty();
    }

    @Test
    void executeTaskReleasesGuardLeaseSoTheNextFireCanRun() {
        ScheduledTask task = newTask(alice, "* * * * *", true);
        manager.register(task);

        RoutineResult result = RoutineResult.success(task.getId(), List.of(), Instant.now(),
                Instant.now().plusMillis(10));
        when(routine.execute(task)).thenReturn(result);

        // The default in-memory guard grants, runs, and releases on completion; a second sequential fire runs again.
        manager.executeTask(task.getId());
        manager.executeTask(task.getId());

        verify(routine, times(2)).execute(task);
    }

    /**
     * Unscheduling only governs future fires, so without this the word "cancel" was a half-truth: a routine that was
     * mid-step carried on doing work — writing files, calling out to systems — on behalf of a task that had just been
     * deleted underneath it.
     */
    @Test
    void cancelAlsoStopsARunAlreadyInFlight() {
        ScheduledTask task = newTask(alice, "* * * * *", true);
        manager.register(task);

        manager.cancel(task.getId(), alice);

        verify(routine).interrupt(task.getId(), InterruptReason.TASK_CANCELLED);
    }

    /**
     * The narrower half: stopping the run in progress without ending the task. The distinction is the whole point of
     * the second method, so both halves are asserted — the run is signalled, and the schedule is left alone.
     */
    @Test
    void interruptStopsTheRunWithoutTouchingTheSchedule() {
        ScheduledTask task = newTask(alice, "* * * * *", true);
        manager.register(task);
        events.clear();

        when(routine.interrupt(task.getId(), InterruptReason.TASK_CANCELLED)).thenReturn(true);

        assertThat(manager.interrupt(task.getId(), alice)).isTrue();

        verify(routine).interrupt(task.getId(), InterruptReason.TASK_CANCELLED);
        verify(scheduler, never()).unschedule(task.getId());
        assertThat(taskRepo.findById(task.getId())).isPresent();
        assertThat(events).isEmpty();
    }

    /**
     * "Nothing was running here" is an answer, not a failure — in a scale-out deployment it is how a caller learns the
     * run belongs to another node.
     */
    @Test
    void interruptReportsWhenNothingWasRunning() {
        ScheduledTask task = newTask(alice, "* * * * *", true);
        manager.register(task);

        when(routine.interrupt(any(), any())).thenReturn(false);

        assertThat(manager.interrupt(task.getId(), alice)).isFalse();
        assertThat(taskRepo.findById(task.getId())).isPresent();
    }

    /** Stopping someone else's work is as much of a decision as deleting it, so it is gated the same way. */
    @Test
    void interruptRejectsATaskOwnedBySomeoneElse() {
        ScheduledTask task = newTask(alice, "* * * * *", true);
        manager.register(task);

        assertThatThrownBy(() -> manager.interrupt(task.getId(), bob))
                .isInstanceOf(UnauthorizedTaskAccessException.class);

        verify(routine, never()).interrupt(any(), any());
    }

    @Test
    void interruptRejectsAnUnknownTask() {
        assertThatThrownBy(() -> manager.interrupt(ScheduledTaskId.generate(), alice))
                .isInstanceOf(TaskNotFoundException.class);
    }

    private ScheduledTask newTask(Principal owner, String cron, boolean enabled) {
        return ScheduledTask.builder().id(ScheduledTaskId.generate()).name("t").cronExpression(cron).owner(owner)
                .boundRuntimeId(ctx).routine(List.of(RoutineStep.of("Bash", "{}"))).enabled(enabled).build();
    }
}
