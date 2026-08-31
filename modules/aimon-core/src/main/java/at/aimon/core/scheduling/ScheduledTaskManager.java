/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.core.scheduling;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.base.Principal;
import at.aimon.core.scheduling.cron.UnixCronExpression;
import at.aimon.core.scheduling.event.ScheduledTaskEventPublisher;
import at.aimon.core.scheduling.event.TaskCancelledEvent;
import at.aimon.core.scheduling.event.TaskRegisteredEvent;
import at.aimon.core.scheduling.exception.InvalidCronExpressionException;
import at.aimon.core.scheduling.exception.QuotaExceededException;
import at.aimon.core.scheduling.exception.TaskNotFoundException;
import at.aimon.core.scheduling.exception.UnauthorizedTaskAccessException;
import at.aimon.core.scheduling.quota.TaskQuotaManager;
import at.aimon.core.scheduling.repository.ScheduledTaskExecutionHistoryRepository;
import at.aimon.core.scheduling.repository.ScheduledTaskRepository;
import at.aimon.core.scheduling.scheduler.TaskScheduler;

/**
 * Service for managing scheduled tasks.
 *
 * <p>
 * Provides operations for registering, listing, and cancelling scheduled tasks with quota enforcement and authorization
 * checks.
 * </p>
 */
public class ScheduledTaskManager {

    private static final Logger log = LoggerFactory.getLogger(ScheduledTaskManager.class);

    private final ScheduledTaskRepository taskRepository;
    private final ScheduledTaskExecutionHistoryRepository historyRepository;
    private final RoutineExecutor routineExecutor;
    private final TaskScheduler taskScheduler;
    private final ScheduledTaskEventPublisher eventPublisher;
    private final TaskQuotaManager quotaManager;
    private final ScheduledExecutionGuard executionGuard;
    private final ScheduledTaskInterruptBus interruptBus;

    /**
     * ScheduledTaskManager를 생성한다. Uses the default in-memory {@link ScheduledExecutionGuard} (prevents overlapping
     * executions of the same task on this instance).
     */
    public ScheduledTaskManager(ScheduledTaskRepository taskRepository,
            ScheduledTaskExecutionHistoryRepository historyRepository, RoutineExecutor routineExecutor,
            TaskScheduler taskScheduler, ScheduledTaskEventPublisher eventPublisher, TaskQuotaManager quotaManager) {
        this(builder().taskRepository(taskRepository).historyRepository(historyRepository)
                .routineExecutor(routineExecutor).taskScheduler(taskScheduler).eventPublisher(eventPublisher)
                .quotaManager(quotaManager));
    }

    /**
     * ScheduledTaskManager를 생성한다. Accepts a custom {@link ScheduledExecutionGuard} — inject a distributed guard for
     * cluster-wide single-execution semantics in a scale-out deployment. Stop requests reach this node only
     * ({@link ScheduledTaskInterruptBus#LOCAL_ONLY}).
     */
    public ScheduledTaskManager(ScheduledTaskRepository taskRepository,
            ScheduledTaskExecutionHistoryRepository historyRepository, RoutineExecutor routineExecutor,
            TaskScheduler taskScheduler, ScheduledTaskEventPublisher eventPublisher, TaskQuotaManager quotaManager,
            ScheduledExecutionGuard executionGuard) {
        this(builder().taskRepository(taskRepository).historyRepository(historyRepository)
                .routineExecutor(routineExecutor).taskScheduler(taskScheduler).eventPublisher(eventPublisher)
                .quotaManager(quotaManager).executionGuard(executionGuard));
    }

    private ScheduledTaskManager(Builder builder) {
        taskRepository = Objects.requireNonNull(builder.taskRepository, "Task repository cannot be null");
        historyRepository = Objects.requireNonNull(builder.historyRepository, "History repository cannot be null");
        routineExecutor = Objects.requireNonNull(builder.routineExecutor, "Routine executor cannot be null");
        taskScheduler = Objects.requireNonNull(builder.taskScheduler, "Task scheduler cannot be null");
        eventPublisher = Objects.requireNonNull(builder.eventPublisher, "Event publisher cannot be null");
        quotaManager = Objects.requireNonNull(builder.quotaManager, "Quota manager cannot be null");
        executionGuard = Objects.requireNonNull(builder.executionGuard, "Execution guard cannot be null");
        interruptBus = Objects.requireNonNull(builder.interruptBus, "Interrupt bus cannot be null");
    }

    /**
     * Returns a builder, which is the only way to reach the collaborators the constructors above do not take.
     *
     * <p>
     * The constructors stop at seven parameters because Checkstyle does, and the eighth — a
     * {@link ScheduledTaskInterruptBus} — is exactly the one a multi-node deployment has to supply. Rather than pick
     * which cluster seam gets to be the last positional argument, both of them live here.
     *
     * @return a new builder with the single-node defaults already in place
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Fluent builder for {@link ScheduledTaskManager}.
     *
     * <p>
     * The four required collaborators have no defaults and are null-checked on {@link #build()}. The two that do have
     * defaults are the seams a cluster replaces — {@link ScheduledExecutionGuard} and
     * {@link ScheduledTaskInterruptBus} — and both default to the single-node answer, so a deployment that never
     * scales out never has to name them.
     */
    public static final class Builder {

        private ScheduledTaskRepository taskRepository;
        private ScheduledTaskExecutionHistoryRepository historyRepository;
        private RoutineExecutor routineExecutor;
        private TaskScheduler taskScheduler;
        private ScheduledTaskEventPublisher eventPublisher;
        private TaskQuotaManager quotaManager;
        private ScheduledExecutionGuard executionGuard = new InMemoryScheduledExecutionGuard();
        private ScheduledTaskInterruptBus interruptBus = ScheduledTaskInterruptBus.LOCAL_ONLY;

        private Builder() {
        }

        /**
         * Sets the repository holding the task records.
         *
         * @param repository
         *            the task repository
         * @return this builder
         */
        public Builder taskRepository(ScheduledTaskRepository repository) {
            taskRepository = repository;
            return this;
        }

        /**
         * Sets the repository holding execution history.
         *
         * @param repository
         *            the history repository
         * @return this builder
         */
        public Builder historyRepository(ScheduledTaskExecutionHistoryRepository repository) {
            historyRepository = repository;
            return this;
        }

        /**
         * Sets the executor that runs a task's routine.
         *
         * @param executor
         *            the routine executor
         * @return this builder
         */
        public Builder routineExecutor(RoutineExecutor executor) {
            routineExecutor = executor;
            return this;
        }

        /**
         * Sets the scheduler the manager registers and unregisters triggers with.
         *
         * @param scheduler
         *            the task scheduler
         * @return this builder
         */
        public Builder taskScheduler(TaskScheduler scheduler) {
            taskScheduler = scheduler;
            return this;
        }

        /**
         * Sets the publisher lifecycle events are emitted on.
         *
         * @param publisher
         *            the event publisher
         * @return this builder
         */
        public Builder eventPublisher(ScheduledTaskEventPublisher publisher) {
            eventPublisher = publisher;
            return this;
        }

        /**
         * Sets the quota manager enforcing the per-owner ceiling.
         *
         * @param manager
         *            the quota manager
         * @return this builder
         */
        public Builder quotaManager(TaskQuotaManager manager) {
            quotaManager = manager;
            return this;
        }

        /**
         * Sets the guard consulted before each fire. Defaults to {@link InMemoryScheduledExecutionGuard}, which
         * prevents a task from overlapping itself on this node only.
         *
         * @param guard
         *            the execution guard
         * @return this builder
         */
        public Builder executionGuard(ScheduledExecutionGuard guard) {
            executionGuard = guard;
            return this;
        }

        /**
         * Sets the bus stop requests are published to. Defaults to {@link ScheduledTaskInterruptBus#LOCAL_ONLY}, which
         * reaches this node and no other.
         *
         * @param bus
         *            the interrupt bus
         * @return this builder
         */
        public Builder interruptBus(ScheduledTaskInterruptBus bus) {
            interruptBus = bus;
            return this;
        }

        /**
         * Builds the manager.
         *
         * @return a new manager (never null)
         * @throws NullPointerException
         *             if any collaborator without a default was left unset
         */
        public ScheduledTaskManager build() {
            return new ScheduledTaskManager(this);
        }
    }

    /**
     * Registers a new scheduled task.
     *
     * <p>
     * Either the task is stored, quota-charged and (if enabled) scheduled, or none of the three happened — a scheduler
     * that refuses the schedule leaves no trace of the attempt behind.
     *
     * @param task
     *            the task to register
     * @return the registered task
     * @throws QuotaExceededException
     *             if the owner's quota is exceeded
     * @throws InvalidCronExpressionException
     *             if the cron expression is invalid, or if the installed scheduler cannot express it
     */
    public ScheduledTask register(ScheduledTask task) {
        Objects.requireNonNull(task, "Task cannot be null");

        // Validate cron expression
        validateCronExpression(task.getCronExpression());

        // Check quota
        quotaManager.checkQuota(task.getOwner());

        // Save task
        taskRepository.save(task);
        quotaManager.incrementUsage(task.getOwner());

        // Schedule if enabled. A backend may refuse an expression this manager accepted — validation here answers "is
        // this a legal schedule", which is not the same question as "can the installed backend express it" (Quartz, for
        // one, has no shape for a day-of-month and a day-of-week together). Undo the two writes above rather than
        // leaving a stored task that will never fire and a quota unit charged for it.
        if (task.isEnabled()) {
            try {
                scheduleTask(task);
            } catch (RuntimeException e) {
                taskRepository.deleteById(task.getId());
                quotaManager.decrementUsage(task.getOwner());
                throw e;
            }
        }

        log.info("Registered task '{}' for owner {}", task.getId(), task.getOwner());
        eventPublisher.publish(new TaskRegisteredEvent(task));

        return task;
    }

    /**
     * Lists all tasks owned by a principal.
     *
     * @param owner
     *            the owning principal
     * @return list of tasks
     */
    public List<ScheduledTask> listByOwner(Principal owner) {
        Objects.requireNonNull(owner, "Owner cannot be null");
        return taskRepository.findByOwner(owner);
    }

    /**
     * Lists all enabled tasks owned by a principal.
     *
     * @param owner
     *            the owning principal
     * @return list of enabled tasks
     */
    public List<ScheduledTask> listEnabledByOwner(Principal owner) {
        Objects.requireNonNull(owner, "Owner cannot be null");
        return taskRepository.findByOwnerAndEnabledTrue(owner);
    }

    /**
     * Lists all tasks.
     *
     * @return list of all tasks
     */
    public List<ScheduledTask> listAll() {
        return taskRepository.findAll();
    }

    /**
     * Gets a task by ID, checking authorization.
     *
     * @param taskId
     *            the task ID
     * @param principal
     *            the principal requesting access
     * @return the task
     * @throws TaskNotFoundException
     *             if the task doesn't exist
     * @throws UnauthorizedTaskAccessException
     *             if the principal doesn't own the task
     */
    public ScheduledTask getById(ScheduledTaskId taskId, Principal principal) {
        Objects.requireNonNull(taskId, "Task ID cannot be null");
        Objects.requireNonNull(principal, "Principal cannot be null");

        final ScheduledTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new TaskNotFoundException(taskId.value()));

        if (!task.getOwner().equals(principal)) {
            throw new UnauthorizedTaskAccessException(taskId.value(), principal);
        }

        return task;
    }

    /**
     * Cancels a scheduled task: it stops firing, its record is deleted, and any run of it already in progress is
     * interrupted — here directly, and on every other node through the {@link ScheduledTaskInterruptBus}.
     *
     * <p>
     * That last part is what makes the word honest. Unscheduling only governs future fires, so a routine that was
     * mid-step when its owner cancelled it used to keep going — writing files, calling out to systems — for as long
     * as its remaining steps took, against a task that no longer existed. Stopping the run is therefore not an extra
     * courtesy on top of cancelling; it is part of it.
     *
     * <p>
     * Interrupting is a request, not a join: this returns once the signal is tripped, and a run needs until its
     * current step yields to actually unwind (see
     * {@link RoutineExecutor#interrupt(ScheduledTaskId, at.aimon.core.agent.interrupt.InterruptReason)}). Reaching
     * another node adds a second delay on top of that one, since the request has to travel. So the deletes below can
     * and do land while a run is still winding down somewhere. Nothing here waits for that; the write-back on the far
     * side is what is conditional ({@link ScheduledTaskRepository#updateIfPresent}), so a run finishing after the
     * delete records nothing rather than recreating what was deleted — and that guarantee is node-agnostic, which is
     * what lets this method decline to wait.
     *
     * @param taskId
     *            the task ID
     * @param principal
     *            the principal requesting cancellation
     * @throws TaskNotFoundException
     *             if the task doesn't exist
     * @throws UnauthorizedTaskAccessException
     *             if the principal doesn't own the task
     */
    public void cancel(ScheduledTaskId taskId, Principal principal) {
        final ScheduledTask task = getById(taskId, principal);

        // Unschedule
        taskScheduler.unschedule(taskId);

        // Stop work already under way. Ordered after unschedule so a cron re-fire cannot slip a fresh run in behind
        // the interrupt. It is deliberately NOT ordered so that the interrupted run finishes before the deletes
        // below — that race is unwinnable by ordering, and is closed instead at the other end: the run writes its
        // task back through updateIfPresent, which does nothing once the delete has happened (see executeTask).
        routineExecutor.interrupt(taskId, InterruptReason.TASK_CANCELLED);
        broadcastStop(taskId, InterruptReason.TASK_CANCELLED);

        // Delete from repository
        taskRepository.deleteById(taskId);
        historyRepository.deleteByTaskId(taskId);

        // Decrement quota
        quotaManager.decrementUsage(task.getOwner());

        log.info("Cancelled task '{}' by {}", taskId, principal);
        eventPublisher.publish(new TaskCancelledEvent(task));
    }

    /**
     * Stops a task's in-flight run while leaving its schedule alone — it will fire again at its next cron time.
     *
     * <p>
     * The narrower half of {@link #cancel(ScheduledTaskId, Principal)}, and the project's vocabulary is the reason
     * the two are separate calls rather than a flag: <em>interrupt</em> cuts short what is running, <em>cancel</em>
     * ends the thing outright. A task wedged on one bad run is the case for this one — the run is the problem, the
     * schedule is not.
     *
     * @param taskId
     *            the task whose current run should be stopped
     * @param principal
     *            the principal requesting the interrupt
     * @return {@code true} if a run was signalled <b>on this instance</b>. {@code false} does not mean nothing was
     *         stopped: the request is also broadcast to the other nodes, and a fan-out has no answer to report back,
     *         so a run held elsewhere is stopped without this ever returning true
     * @throws TaskNotFoundException
     *             if the task doesn't exist
     * @throws UnauthorizedTaskAccessException
     *             if the principal doesn't own the task
     */
    public boolean interrupt(ScheduledTaskId taskId, Principal principal) {
        // Authorization first: stopping someone else's work is as much of a decision as deleting it.
        getById(taskId, principal);

        final boolean signalled = routineExecutor.interrupt(taskId, InterruptReason.TASK_CANCELLED);
        broadcastStop(taskId, InterruptReason.TASK_CANCELLED);
        if (signalled) {
            log.info("Interrupted in-flight run of task '{}' by {}", taskId, principal);
        } else {
            log.info("Task '{}' has no run in progress on this instance; interrupt requested by {} was broadcast only",
                    taskId, principal);
        }
        return signalled;
    }

    /**
     * Asks the other nodes to stop their runs of {@code taskId}, and never lets that ask break the caller.
     *
     * <p>
     * The bus is remote I/O in any distributed implementation, so publishing can fail. What has already happened when
     * it does — the local run tripped, the schedule removed, the record about to be deleted — is not undone by a
     * broker being unreachable, and cancelling would be a strange thing to refuse on those grounds. The failure is
     * therefore logged and swallowed: the cost is a run elsewhere that stops late (at its next step boundary, once its
     * task is gone) rather than promptly, which is exactly where this feature started.
     */
    private void broadcastStop(ScheduledTaskId taskId, InterruptReason reason) {
        try {
            interruptBus.publish(taskId, reason);
        } catch (RuntimeException e) {
            log.warn("Failed to broadcast the stop request for task '{}' ({}); runs on other nodes will only stop at"
                    + " their next step boundary: {}", taskId, reason, e.getMessage(), e);
        }
    }

    /**
     * Gets recent execution history for a task.
     *
     * @param taskId
     *            the task ID
     * @param principal
     *            the principal requesting access
     * @param limit
     *            maximum number of records
     * @return list of execution history records
     * @throws TaskNotFoundException
     *             if the task doesn't exist
     * @throws UnauthorizedTaskAccessException
     *             if the principal doesn't own the task
     */
    public List<ScheduledTaskExecutionHistory> getHistory(ScheduledTaskId taskId, Principal principal, int limit) {
        // Check authorization
        getById(taskId, principal);

        return historyRepository.findByTaskIdOrderByStartedAtDesc(taskId, limit);
    }

    /**
     * Enables or disables a task.
     *
     * @param taskId
     *            the task ID
     * @param principal
     *            the principal requesting the change
     * @param enabled
     *            the new enabled status
     * @return the updated task
     * @throws TaskNotFoundException
     *             if the task doesn't exist
     * @throws UnauthorizedTaskAccessException
     *             if the principal doesn't own the task
     */
    public ScheduledTask setEnabled(ScheduledTaskId taskId, Principal principal, boolean enabled) {
        final ScheduledTask task = getById(taskId, principal);

        if (task.isEnabled() == enabled) {
            return task;
        }

        final ScheduledTask updatedTask = task.withEnabled(enabled);
        taskRepository.save(updatedTask);

        if (enabled) {
            scheduleTask(updatedTask);
        } else {
            taskScheduler.unschedule(taskId);
        }

        log.info("Task '{}' {} by {}", taskId, enabled ? "enabled" : "disabled", principal);
        return updatedTask;
    }

    /**
     * Validates a cron expression against the framework's canonical dialect.
     *
     * <p>
     * The dialect is {@link UnixCronExpression} — five fields, Sunday {@code 0} — and this is the same check every
     * backend applies on the way in, so an expression accepted here is one the configured {@link TaskScheduler} can
     * schedule. That was not always true: the check here and the check in the Quartz backend once used different
     * dialects whose accepted sets did not overlap at all, and registration against that backend failed for every
     * expression a caller could write.
     *
     * @param cronExpression
     *            the cron expression to validate
     * @throws InvalidCronExpressionException
     *             if the expression is invalid
     */
    public void validateCronExpression(String cronExpression) {
        Objects.requireNonNull(cronExpression, "Cron expression cannot be null");
        UnixCronExpression.parse(cronExpression);
    }

    private void scheduleTask(ScheduledTask task) {
        taskScheduler.scheduleRecurrently(task.getId(), task.getCronExpression());
    }

    /**
     * Executes the task identified by {@code taskId}. This is the attach point for a {@link TaskScheduler}'s
     * {@link at.aimon.core.scheduling.scheduler.ScheduledTaskExecutor}: when a trigger fires, the scheduler calls back
     * here.
     *
     * <p>
     * It is public because an externally supplied scheduler is wired from application code in a different package —
     * {@code .taskExecutor(taskId -> taskManager.executeTask(taskId))} is the documented wiring for
     * {@code QuartzTaskSchedulerBuilder} and would not compile otherwise.
     * </p>
     *
     * <p>
     * The call never throws: a fire is skipped when the {@link ScheduledExecutionGuard} denies the lease (an execution
     * of the same task is already in progress) or when the task is missing or disabled, and a routine failure is logged
     * rather than propagated — a scheduler thread must not be torn down by one bad task.
     * </p>
     *
     * @param taskId
     *            the task to execute
     */
    public void executeTask(ScheduledTaskId taskId) {
        // Idempotency guard: skip if this task is already being executed (overlap / duplicate or multi-node re-fire).
        // The lease is released when the execution finishes (try-with-resources below).
        final Optional<ScheduledExecutionGuard.ExecutionLease> lease = executionGuard.tryBegin(taskId);
        if (lease.isEmpty()) {
            log.info("Task '{}' skipped: an execution is already in progress (duplicate fire prevented)", taskId);
            return;
        }

        try (ScheduledExecutionGuard.ExecutionLease held = lease.get()) {
            final ScheduledTask task = taskRepository.findById(taskId).orElse(null);
            if (task == null || !task.isEnabled()) {
                log.warn("Task '{}' not found or disabled, skipping execution", taskId);
                return;
            }

            final RoutineResult result = routineExecutor.execute(task);

            // Update last executed time. Conditional, not a blind save: the task can be cancelled while this run is
            // in flight — that is now the normal case rather than a rare one, since cancelling interrupts the run —
            // and a plain save would recreate the record cancel() just deleted. What that leaves behind is an
            // unscheduled task that never fires again yet is still listed and still found by id, with its quota unit
            // already refunded. A false here means the task is gone, so the run's outcome has nowhere to go.
            final ScheduledTask updatedTask = task.withLastExecutedAt(Instant.now());
            if (!taskRepository.updateIfPresent(updatedTask)) {
                log.info("Task '{}' was deleted while its run was in progress; last-executed and history writes "
                        + "skipped", taskId);
                return;
            }

            // Save history
            final String historyId = UUID.randomUUID().toString();
            final ScheduledTaskExecutionHistory history = ScheduledTaskExecutionHistory.fromRoutineResult(historyId,
                    result);
            historyRepository.save(history);

            // The two repositories have no shared transaction, so a cancel landing between the guard above and this
            // write leaves a history row for a task that no longer exists. Such a row is unreachable — getHistory
            // authorizes through getById, which throws once the task is gone — so it is a leak rather than a wrong
            // answer, but it is cheap to sweep and there is nothing else that ever would.
            if (!taskRepository.existsById(taskId)) {
                historyRepository.deleteByTaskId(taskId);
            }

        } catch (Exception e) {
            log.error("Error executing task '{}': {}", taskId, e.getMessage(), e);
        }
    }
}
