/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.core.scheduling.scheduler;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.scheduling.ScheduledTaskId;
import at.aimon.core.scheduling.cron.UnixCronExpression;
import at.aimon.core.scheduling.exception.TaskSchedulerException;

/**
 * In-memory implementation of {@link TaskScheduler} using ScheduledExecutorService.
 *
 * <p>
 * Schedules against {@link UnixCronExpression}, the framework's canonical dialect, which also computes each next firing
 * time. Nothing here knows how cron text is parsed.
 * </p>
 */
public class InMemoryTaskScheduler implements TaskScheduler {

    private static final Logger log = LoggerFactory.getLogger(InMemoryTaskScheduler.class);

    private final ScheduledExecutorService scheduledExecutorService;
    private final ScheduledTaskExecutor taskExecutor;
    private final Map<String, ScheduledTaskEntry> scheduledTasks = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * Creates a new scheduler with the specified task executor and default thread pool size.
     *
     * @param taskExecutor
     *            the executor for running scheduled tasks
     */
    public InMemoryTaskScheduler(ScheduledTaskExecutor taskExecutor) {
        this(taskExecutor, Runtime.getRuntime().availableProcessors());
    }

    /**
     * Creates a new scheduler with the specified task executor and thread pool size.
     *
     * @param taskExecutor
     *            the executor for running scheduled tasks
     * @param threadPoolSize
     *            the number of threads in the scheduler pool
     */
    public InMemoryTaskScheduler(ScheduledTaskExecutor taskExecutor, int threadPoolSize) {
        this.taskExecutor = Objects.requireNonNull(taskExecutor, "Task executor cannot be null");
        scheduledExecutorService = Executors.newScheduledThreadPool(threadPoolSize, r -> {
            final Thread thread = new Thread(r, "task-scheduler");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public void scheduleRecurrently(ScheduledTaskId taskId, String cronExpression) {
        Objects.requireNonNull(taskId, "Task ID cannot be null");
        Objects.requireNonNull(cronExpression, "Cron expression cannot be null");

        if (!running.get()) {
            throw new TaskSchedulerException("Scheduler is not running");
        }

        final UnixCronExpression cron = UnixCronExpression.parse(cronExpression);

        // Unschedule existing task if present
        unschedule(taskId);

        final String taskIdValue = taskId.value();
        final ScheduledTaskEntry entry = new ScheduledTaskEntry(taskIdValue, cron);
        scheduledTasks.put(taskIdValue, entry);

        scheduleNextExecution(entry);
        log.info("Scheduled task '{}' with cron '{}'", taskIdValue, cronExpression);
    }

    private void scheduleNextExecution(ScheduledTaskEntry entry) {
        // Guard on entry IDENTITY, not merely on the presence of the task-id string. If this task was
        // re-scheduled (disable/re-enable or re-register) while a previous runnable was still in flight,
        // future.cancel(false) cannot stop that in-flight runnable; its finally-block re-arm would otherwise
        // observe the NEW same-id entry via containsKey and resurrect a ghost schedule chain that fires in
        // parallel with the current one. Comparing against the live entry stops a superseded entry from
        // rescheduling itself.
        if (!running.get() || scheduledTasks.get(entry.taskId) != entry) {
            return;
        }

        final Optional<ZonedDateTime> nextExecution = entry.cron.nextExecution(ZonedDateTime.now());

        if (nextExecution.isEmpty()) {
            log.warn("No next execution time for task '{}'", entry.taskId);
            return;
        }

        Duration delay = Duration.between(ZonedDateTime.now(), nextExecution.get());
        if (delay.isNegative()) {
            delay = Duration.ZERO;
        }

        final ScheduledFuture<?> future = scheduledExecutorService.schedule(() -> {
            try {
                log.debug("Executing task '{}'", entry.taskId);
                taskExecutor.execute(ScheduledTaskId.of(entry.taskId));
            } catch (Exception e) {
                log.error("Error executing task '{}': {}", entry.taskId, e.getMessage(), e);
            } finally {
                // Schedule next execution
                scheduleNextExecution(entry);
            }
        }, delay.toMillis(), TimeUnit.MILLISECONDS);

        entry.setFuture(future);
        log.debug("Task '{}' scheduled to run at {}", entry.taskId, nextExecution.get());
    }

    @Override
    public void unschedule(ScheduledTaskId taskId) {
        Objects.requireNonNull(taskId, "Task ID cannot be null");

        final String taskIdValue = taskId.value();
        final ScheduledTaskEntry entry = scheduledTasks.remove(taskIdValue);
        if (entry != null) {
            entry.cancel();
            log.info("Unscheduled task '{}'", taskIdValue);
        }
    }

    @Override
    public boolean exists(ScheduledTaskId taskId) {
        Objects.requireNonNull(taskId, "Task ID cannot be null");
        return scheduledTasks.containsKey(taskId.value());
    }

    @Override
    public void clear() {
        scheduledTasks.values().forEach(ScheduledTaskEntry::cancel);
        scheduledTasks.clear();
        log.info("Cleared all scheduled tasks");
    }

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            log.info("Task scheduler started");
        }
    }

    @Override
    public void shutdown() {
        if (running.compareAndSet(true, false)) {
            clear();
            scheduledExecutorService.shutdown();
            try {
                if (!scheduledExecutorService.awaitTermination(30, TimeUnit.SECONDS)) {
                    scheduledExecutorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduledExecutorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
            log.info("Task scheduler shut down");
        }
    }

    /**
     * Internal entry for tracking scheduled tasks.
     */
    private static class ScheduledTaskEntry {

        private final String taskId;
        private final UnixCronExpression cron;
        private volatile ScheduledFuture<?> future;

        ScheduledTaskEntry(String taskId, UnixCronExpression cron) {
            this.taskId = taskId;
            this.cron = cron;
        }

        void setFuture(ScheduledFuture<?> future) {
            this.future = future;
        }

        void cancel() {
            if (future != null) {
                future.cancel(false);
            }
        }
    }
}
