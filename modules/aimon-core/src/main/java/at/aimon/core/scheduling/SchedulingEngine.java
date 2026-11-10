/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.core.scheduling;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.scheduling.event.ScheduledTaskEventListener;
import at.aimon.core.scheduling.event.ScheduledTaskEventPublisher;
import at.aimon.core.scheduling.scheduler.TaskScheduler;

/**
 * Main facade for the scheduling engine.
 *
 * <p>
 * This class integrates all scheduling components and provides a unified API for task scheduling, execution, and
 * monitoring.
 * </p>
 *
 * <p>
 * Example usage:
 * </p>
 *
 * <pre>{@code
 * // Create engine
 * SchedulingEngine engine = SchedulingEngineBuilder.create().defaultMaxQuota(20).build();
 *
 * // Add event listener
 * engine.addEventListener(new ScheduledTaskEventListener() {
 *     &#64;Override
 *     public void onTaskFailed(TaskFailedEvent event) {
 *         System.err.println("Task failed: " + event.getTask().getName());
 *     }
 * });
 *
 * // Start engine
 * engine.start();
 *
 * // ... application runs ...
 *
 * // Shutdown
 * engine.close();
 * }</pre>
 */
public final class SchedulingEngine implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SchedulingEngine.class);

    private final ScheduledTaskManager taskManager;
    private final RoutineExecutor routineExecutor;
    private final TaskScheduler taskScheduler;
    private final ScheduledTaskEventPublisher eventPublisher;

    /**
     * This node's registration on the interrupt bus, held so {@link #close()} can take it back.
     *
     * <p>
     * The engine is the subscriber rather than the {@link RoutineExecutor} because honouring a remote request and
     * making one must stay separate concerns. If the executor both listened and published, its own {@code shutdown()} —
     * which interrupts everything in flight here — would broadcast that to the cluster and stop runs on nodes that are
     * not shutting down at all.
     */
    private final ScheduledTaskInterruptBus.Subscription interruptSubscription;
    private final boolean rehydrateAtStart;

    /** How often {@link ScheduledTaskManager#reconcile()} runs; {@link Duration#ZERO} when it does not. */
    private final Duration reconcileInterval;

    /** Runs the reconcile pass; created at {@link #start()} only when {@link #reconcileInterval} is positive. */
    private ScheduledExecutorService reconciler;

    /**
     * Serializes {@link #pauseFires()}, {@link #resumeFires()} and the pause check in {@link #start()}. Each of them
     * touches two halves — the scheduler and the manager — and each half is thread-safe on its own, but the pair is
     * not: a pause and a resume interleaved half by half could leave the scheduler firing while the manager refuses
     * every fire, and on a clustered scheduler those occurrences would run nowhere.
     */
    private final Object fireControlLock = new Object();

    /**
     * Creates a new scheduling engine.
     *
     * <p>
     * This constructor is package-private. Use {@link SchedulingEngineBuilder} to create instances.
     * </p>
     */
    SchedulingEngine(ScheduledTaskManager taskManager, RoutineExecutor routineExecutor, TaskScheduler taskScheduler,
            ScheduledTaskEventPublisher eventPublisher, ScheduledTaskInterruptBus interruptBus) {
        this(taskManager, routineExecutor, taskScheduler, eventPublisher, interruptBus, true, Duration.ZERO);
    }

    SchedulingEngine(ScheduledTaskManager taskManager, RoutineExecutor routineExecutor, TaskScheduler taskScheduler,
            ScheduledTaskEventPublisher eventPublisher, ScheduledTaskInterruptBus interruptBus,
            boolean rehydrateAtStart) {
        this(taskManager, routineExecutor, taskScheduler, eventPublisher, interruptBus, rehydrateAtStart,
                Duration.ZERO);
    }

    SchedulingEngine(ScheduledTaskManager taskManager, RoutineExecutor routineExecutor, TaskScheduler taskScheduler,
            ScheduledTaskEventPublisher eventPublisher, ScheduledTaskInterruptBus interruptBus,
            boolean rehydrateAtStart, Duration reconcileInterval) {
        this.rehydrateAtStart = rehydrateAtStart;
        this.reconcileInterval = Objects.requireNonNull(reconcileInterval, "Reconcile interval cannot be null");

        this.taskManager = Objects.requireNonNull(taskManager, "Task manager cannot be null");
        this.routineExecutor = Objects.requireNonNull(routineExecutor, "Routine executor cannot be null");
        this.taskScheduler = Objects.requireNonNull(taskScheduler, "Task scheduler cannot be null");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "Event publisher cannot be null");
        Objects.requireNonNull(interruptBus, "Interrupt bus cannot be null");

        // Subscribe through the parameter, not the field: a method reference on `this.routineExecutor` would publish a
        // half-built `this` to a bus that may already be delivering. Honouring a request is just the local interrupt,
        // whose answer (did anything stop here?) a fan-out has nowhere to return, so it is discarded on purpose.
        // Both halves of the listener are wired: a request naming an occurrence must reach the occurrence-targeted
        // interrupt, or the default would widen it to every run of the task here.
        interruptSubscription = interruptBus.subscribe(new ScheduledTaskInterruptBus.InterruptListener() {
            @Override
            public void onInterruptRequested(ScheduledTaskId taskId, InterruptReason reason) {
                routineExecutor.interrupt(taskId, reason);
            }

            @Override
            public void onInterruptRequested(ScheduledTaskId taskId, String occurrenceKey, InterruptReason reason) {
                routineExecutor.interrupt(taskId, occurrenceKey, reason);
            }
        });

        log.debug("Scheduling engine created");
    }

    /**
     * Starts the scheduling engine.
     *
     * <p>
     * This starts the task scheduler, allowing scheduled tasks to execute, and then schedules the enabled tasks the
     * repository already holds that the scheduler does not ({@link ScheduledTaskManager#rehydrate()}) — the tasks a
     * durable repository kept across a restart. With the default in-memory repository there are none.
     * </p>
     *
     * <p>
     * The repository is read <em>before</em> the scheduler is started, so a repository that cannot be read fails the
     * start with nothing running: no scheduler accepting registrations while no stored task is scheduled.
     * </p>
     *
     * <p>
     * On one node of several whose scheduler is not shared, the stored tasks are <b>not</b> scheduled
     * ({@link #rehydratesAtStart()}).
     * </p>
     *
     * <p>
     * A {@link #pauseFires()} made before this call stays in effect: a scheduler that remembers the pause starts
     * straight into it (owned Quartz is left in standby, the in-memory scheduler skips due occurrences), and the pause
     * is asked for again right after the scheduler starts, for one that does not. Without that, a clustered scheduler
     * would acquire triggers the paused manager then refuses, and those occurrences would run nowhere.
     * </p>
     */
    public void start() {
        final List<ScheduledTask> stored = rehydrateAtStart ? taskManager.readStoredEnabled() : List.of();
        synchronized (fireControlLock) {
            taskScheduler.start();
            if (!taskManager.isAcceptingFires()) {
                // Idempotent on a scheduler that already started into the pause; for one that ignored a pause asked
                // for before it started, this closes the window as soon as it can.
                taskScheduler.pauseFiring();
                log.info("Scheduling engine starting with fires paused on this node");
            }
        }
        if (rehydrateAtStart) {
            taskManager.scheduleStored(stored);
        }
        startReconciler();
        log.info("Scheduling engine started");
    }

    /**
     * Starts the periodic {@link ScheduledTaskManager#reconcile()} pass, when this engine was built to run one. A pass
     * that throws — a repository that cannot be read for a moment — is logged and the next pass runs as planned.
     */
    private synchronized void startReconciler() {
        if (reconcileInterval.isZero() || reconciler != null) {
            return;
        }
        reconciler = Executors.newSingleThreadScheduledExecutor(r -> {
            final Thread thread = new Thread(r, "scheduling-reconciler");
            thread.setDaemon(true);
            return thread;
        });
        final long millis = reconcileInterval.toMillis();
        reconciler.scheduleWithFixedDelay(() -> {
            try {
                final int changed = taskManager.reconcile();
                if (changed > 0) {
                    log.info("Reconciled {} trigger(s) with the task repository", changed);
                }
            } catch (RuntimeException e) {
                log.warn("Trigger reconciliation failed; retrying in {}: {}", reconcileInterval, e.getMessage(), e);
            }
        }, millis, millis, TimeUnit.MILLISECONDS);
        log.info("Trigger reconciliation every {} (per-node scheduler on one node of several)", reconcileInterval);
    }

    /**
     * How often this engine converges its triggers on the repository after {@link #start()}.
     *
     * @return the interval, or {@link Duration#ZERO} when it does not reconcile
     */
    public Duration reconcileInterval() {
        return reconcileInterval;
    }

    /**
     * Whether {@link #start()} schedules the tasks it finds stored.
     *
     * <p>
     * {@code false} only for an engine built as one node of several ({@link SchedulingEngineBuilder#multiNode}) whose
     * scheduler holds per-node triggers and whose execution guard was left at the node-local default. There,
     * rebuilding the triggers would make every node fire every stored task with nothing to stop the duplicates, so
     * the engine leaves them alone: a task fires on the node that registered it, until that node restarts.
     *
     * @return whether stored tasks are scheduled at start
     */
    public boolean rehydratesAtStart() {
        return rehydrateAtStart;
    }

    /**
     * Closes the scheduling engine.
     *
     * <p>
     * This shuts down the task scheduler and routine executor gracefully.
     * </p>
     *
     * <p>
     * The bus subscription goes first, so that a stop request arriving mid-teardown cannot interleave with the
     * executor's own shutdown sweep. Nothing is lost by dropping it: the sweep that follows stops every run here
     * anyway, for a reason of its own.
     * </p>
     */
    @Override
    public void close() {
        synchronized (this) {
            if (reconciler != null) {
                reconciler.shutdownNow();
                // A pass in flight would otherwise run on against a scheduler being shut down below and log a failure
                // per task. Bounded: a pass is repository reads and trigger edits, not routine work.
                try {
                    if (!reconciler.awaitTermination(5, TimeUnit.SECONDS)) {
                        log.warn("A trigger reconciliation pass did not finish within 5s of close");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                reconciler = null;
            }
        }
        interruptSubscription.close();
        taskScheduler.shutdown();
        routineExecutor.shutdown();
        log.info("Scheduling engine stopped");
    }

    /**
     * Stops this node from starting new routine runs, leaving the runs already in progress alone — the first step of
     * draining a worker. Nothing is closed or interrupted, and the engine stays application-scoped and alive:
     * {@link #resumeFires()} undoes it.
     *
     * <p>
     * Two halves, in this order. The scheduler is asked to stop firing on this node
     * ({@link TaskScheduler#pauseFiring()}, a Quartz standby, so on a clustered store another node takes the triggers);
     * then the manager refuses every fire that still arrives, including a host's own
     * {@link ScheduledTaskManager#executeFire executeFire} calls. The manager's half runs even when the scheduler's
     * throws (the exception is then rethrown), so a failed standby never leaves the node admitting fires. Once this
     * returns — normally or not — no new run starts here, and {@link #awaitIdle(Duration)} sees every run that did.
     *
     * <p>
     * May be called before {@link #start()}: the engine then starts with fires paused.
     *
     * <p>
     * A fire the scheduler had already dispatched when it paused either got in first (and is waited for) or is refused.
     * Refused is dropped on this node; with a scheduler that could not pause (a borrowed Quartz scheduler, or one
     * without {@code pauseFiring}), a cluster-wide trigger that keeps landing here is dropped the same way — which is
     * what a {@code false} return tells the caller.
     *
     * <p>
     * Serialized with {@link #resumeFires()}: a concurrent pause and resume each take effect on both halves before the
     * other starts, so they never leave the scheduler firing into a manager that refuses.
     *
     * <p>
     * A typical drain: {@code pauseFires()}, then {@code awaitIdle(grace)}, then {@link #close()}, which interrupts
     * whatever outlived the grace period with {@code SYSTEM_SHUTDOWN} — <b>unless</b> the scheduler waits for its jobs
     * on shutdown. {@code close()} shuts the scheduler down before the routine executor, and an owned Quartz scheduler
     * waits for running jobs by default; those jobs are the routines still running, so {@code close()} blocks until
     * they end instead of interrupting them. Build it with {@code
     * QuartzTaskSchedulerBuilder.waitForJobsOnShutdown(false)} when the drain's timeout should end in an interrupt.
     * Cancellation requests from the bus are still honoured while paused.
     *
     * @return {@code true} if the scheduler stopped firing on this node; {@code false} if it cannot pause (a borrowed
     *         Quartz scheduler, or a {@link TaskScheduler} without {@code pauseFiring}). On {@code false} the node
     *         still starts no new run, but a fire the scheduler keeps handing this node is refused and, with
     *         cluster-wide triggers, that occurrence runs nowhere — pause the application's scheduler itself, or expect
     *         those occurrences to be skipped
     */
    public boolean pauseFires() {
        final boolean schedulerPaused;
        synchronized (fireControlLock) {
            try {
                schedulerPaused = taskScheduler.pauseFiring();
            } finally {
                taskManager.pauseFires();
            }
        }
        log.info("Scheduling engine paused fires on this node (scheduler paused: {}); {} routine run(s) in progress",
                schedulerPaused, routineExecutor.inFlightRunCount());
        return schedulerPaused;
    }

    /**
     * Starts accepting fires on this node again after {@link #pauseFires()}. Serialized with {@link #pauseFires()}.
     */
    public void resumeFires() {
        synchronized (fireControlLock) {
            taskManager.resumeFires();
            taskScheduler.resumeFiring();
        }
        log.info("Scheduling engine resumed fires on this node");
    }

    /**
     * Whether this node accepts fires.
     *
     * @return {@code false} between {@link #pauseFires()} and {@link #resumeFires()}
     */
    public boolean isAcceptingFires() {
        return taskManager.isAcceptingFires();
    }

    /**
     * Returns how many routine runs are in progress on this node ({@link RoutineExecutor#inFlightRunCount()}).
     *
     * @return the number of runs in progress here
     */
    public int inFlightRunCount() {
        return routineExecutor.inFlightRunCount();
    }

    /**
     * Waits, without interrupting anything, until no fire admitted on this node is still in progress and no routine
     * run is in progress here, or until {@code timeout} has passed. Call it after {@link #pauseFires()}; otherwise a
     * new run may begin the moment it returns.
     *
     * @param timeout
     *            how long to wait at most in total (must not be null or negative)
     * @return {@code true} if this node was idle when it returned, {@code false} if the timeout passed first
     * @throws IllegalArgumentException
     *             if {@code timeout} is negative
     * @throws InterruptedException
     *             if the calling thread is interrupted while waiting
     */
    public boolean awaitIdle(Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout, "Timeout cannot be null");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("Timeout cannot be negative: " + timeout);
        }
        final long deadline = System.nanoTime() + SchedulingDurations.saturatedNanos(timeout);
        if (!taskManager.awaitIdle(timeout)) {
            return false;
        }
        final long remaining = Math.max(0, deadline - System.nanoTime());
        return routineExecutor.awaitIdle(Duration.ofNanos(remaining));
    }

    /**
     * Returns the task manager.
     *
     * @return the task manager
     */
    public ScheduledTaskManager getTaskManager() {
        return taskManager;
    }

    /**
     * Adds an event listener.
     *
     * @param listener
     *            the listener to add
     */
    public void addEventListener(ScheduledTaskEventListener listener) {
        eventPublisher.addListener(listener);
    }

    /**
     * Removes an event listener.
     *
     * @param listener
     *            the listener to remove
     */
    public void removeEventListener(ScheduledTaskEventListener listener) {
        eventPublisher.removeListener(listener);
    }
}
