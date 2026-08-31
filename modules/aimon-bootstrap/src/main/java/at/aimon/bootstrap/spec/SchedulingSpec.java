package at.aimon.bootstrap.spec;

import java.util.Objects;
import java.util.Optional;

import at.aimon.core.scheduling.ScheduledExecutionGuard;
import at.aimon.core.scheduling.ScheduledTaskInterruptBus;
import at.aimon.core.scheduling.repository.ScheduledTaskRepository;
import at.aimon.core.scheduling.scheduler.TaskScheduler;
import at.aimon.core.scheduling.scheduler.TaskSchedulerFactory;

/**
 * Declares whether the stack runs a scheduling engine, and on which scheduler.
 *
 * <p>
 * Scheduling is off by default. It is not free: the engine starts a scheduler thread pool, and its shutdown
 * waits for running jobs, which is one of the two unbounded contributors to stack shutdown time. A stack that
 * never registers a cron task should not pay for either.
 *
 * <h2>The registry it shares</h2>
 *
 * <p>
 * When enabled, the stack always injects <b>its own</b> {@code AgentRuntimeRegistry} into the engine builder.
 * That is not a stylistic choice: the builder silently defaults to a private, empty registry when none is
 * given, and an engine holding a different registry from the one the runtimes register into resolves every
 * {@code ScheduledTask.boundRuntimeId} to nothing. The failure surfaces at cron fire time — long after
 * bootstrap looked healthy.
 *
 * <h2>Two halves of durability</h2>
 *
 * <p>
 * A scheduled task survives a restart only if <em>both</em> halves do: the trigger, which lives in the scheduler,
 * and the task record it names, which lives in a {@link ScheduledTaskRepository}. The scheduler half is chosen by
 * the {@code enabled(...)} variants above; the record half is {@link #withTaskRepository(ScheduledTaskRepository)}.
 * Supplying one and not the other is the failure the stack announces as its {@code scheduling-durability}
 * degradation — a surviving trigger whose task is gone fires into "task not found", which reads like durability
 * right up to the moment it is needed.
 */
public final class SchedulingSpec {

    private final boolean enabled;
    private final TaskScheduler taskScheduler;
    private final TaskSchedulerFactory taskSchedulerFactory;
    private final ScheduledTaskRepository taskRepository;
    private final ScheduledTaskInterruptBus interruptBus;
    private final ScheduledExecutionGuard executionGuard;

    private SchedulingSpec(boolean enabled, TaskScheduler taskScheduler, TaskSchedulerFactory taskSchedulerFactory,
            ScheduledTaskRepository taskRepository, ScheduledTaskInterruptBus interruptBus,
            ScheduledExecutionGuard executionGuard) {
        this.enabled = enabled;
        this.taskScheduler = taskScheduler;
        this.taskSchedulerFactory = taskSchedulerFactory;
        this.taskRepository = taskRepository;
        this.interruptBus = interruptBus;
        this.executionGuard = executionGuard;
    }

    /**
     * Scheduling off — no engine, no scheduler thread, nothing to drain at shutdown.
     *
     * @return the spec
     */
    public static SchedulingSpec disabled() {
        return new SchedulingSpec(false, null, null, null, null, null);
    }

    /**
     * Scheduling on with the engine's default in-memory scheduler.
     *
     * <p>
     * The default scheduler does not survive a restart and does not coordinate across nodes. A multi-instance
     * deployment wants {@link #enabled(TaskSchedulerFactory)} with a clustered implementation instead.
     *
     * @return the spec
     */
    public static SchedulingSpec enabled() {
        return new SchedulingSpec(true, null, null, null, null, null);
    }

    /**
     * Scheduling on, backed by a scheduler the stack builds from this factory (for example a clustered Quartz
     * scheduler).
     *
     * <p>
     * A factory rather than a scheduler, because a scheduler needs the executor it dispatches to and that executor is
     * part of the engine the stack has not built yet. Supplying the factory lets the stack call it at the one moment
     * the executor exists. Building the scheduler beforehand means closing over an empty reference, and getting it
     * wrong is silent — triggers fire and each firing dies.
     *
     * @param taskSchedulerFactory
     *            the scheduler factory (must not be null)
     * @return the spec
     */
    public static SchedulingSpec enabled(TaskSchedulerFactory taskSchedulerFactory) {
        Objects.requireNonNull(taskSchedulerFactory, "taskSchedulerFactory must not be null");
        return new SchedulingSpec(true, null, taskSchedulerFactory, null, null, null);
    }

    /**
     * Scheduling on, backed by a caller-supplied scheduler that is already wired to its executor.
     *
     * <p>
     * Prefer {@link #enabled(TaskSchedulerFactory)} unless the scheduler genuinely predates this stack: a scheduler
     * handed over here must already dispatch to <em>this</em> stack's task manager, which the caller cannot have had a
     * reference to.
     *
     * @param taskScheduler
     *            the scheduler (must not be null)
     * @return the spec
     */
    public static SchedulingSpec enabled(TaskScheduler taskScheduler) {
        Objects.requireNonNull(taskScheduler, "taskScheduler must not be null");
        return new SchedulingSpec(true, taskScheduler, null, null, null, null);
    }

    /**
     * Returns this spec with the repository the engine keeps its task records in.
     *
     * <p>
     * A separate wither rather than another {@code enabled(...)} overload, because the repository is orthogonal to
     * the scheduler: every one of the three variants above can be paired with it, and overloads would have to
     * enumerate the product.
     *
     * <p>
     * Nothing in this repository is durable — {@code InMemoryScheduledTaskRepository} is the only implementation
     * that ships — so this seam exists for an application that wrote its own. Without it a finished durable
     * implementation could not be reached at all: {@code SchedulingEngineBuilder} takes one, but nothing between an
     * application and that builder passed it along, which left hand-building the engine (and giving up the stack's
     * ordered teardown) as the only route.
     *
     * @param taskRepository
     *            the repository the task manager reads and writes (must not be null)
     * @return a spec carrying the repository
     * @throws IllegalStateException
     *             when scheduling is disabled — there would be no engine to read it
     */
    public SchedulingSpec withTaskRepository(ScheduledTaskRepository taskRepository) {
        Objects.requireNonNull(taskRepository, "taskRepository must not be null");
        if (!enabled) {
            throw new IllegalStateException("Scheduling is disabled, so the task repository would never be read."
                    + " Enable scheduling first, or drop the repository.");
        }
        return new SchedulingSpec(true, taskScheduler, taskSchedulerFactory, taskRepository, interruptBus,
                executionGuard);
    }

    /**
     * Returns this spec with the bus that carries "stop this task's runs" to the other nodes.
     *
     * <p>
     * Without one, cancelling a task stops the runs of it held by the node the cancel was entered on and no others,
     * because the engine's in-flight registry is node-local. In a scale-out deployment that node is usually not the
     * one the cron fired on, so the run there keeps working through its remaining steps against a task that has just
     * been deleted.
     *
     * <p>
     * Here for the same reason as {@link #withTaskRepository(ScheduledTaskRepository)}: a distributed implementation
     * that had nowhere to be passed would be unreachable from an assembled stack, leaving hand-building the engine as
     * the only route to a feature whose whole audience is multi-node deployments.
     *
     * @param interruptBus
     *            the bus the engine publishes stop requests to and subscribes for them (must not be null)
     * @return a spec carrying the bus
     * @throws IllegalStateException
     *             when scheduling is disabled — there would be no engine to publish or subscribe
     */
    public SchedulingSpec withInterruptBus(ScheduledTaskInterruptBus interruptBus) {
        Objects.requireNonNull(interruptBus, "interruptBus must not be null");
        if (!enabled) {
            throw new IllegalStateException("Scheduling is disabled, so the interrupt bus would never be used."
                    + " Enable scheduling first, or drop the bus.");
        }
        return new SchedulingSpec(true, taskScheduler, taskSchedulerFactory, taskRepository, interruptBus,
                executionGuard);
    }

    /**
     * Returns whether the stack builds and starts a scheduling engine.
     *
     * @return {@code true} when scheduling is on
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Returns the caller-supplied scheduler, when there is one.
     *
     * @return the scheduler, or empty to use the engine default
     */
    public Optional<TaskScheduler> getTaskScheduler() {
        return Optional.ofNullable(taskScheduler);
    }

    /**
     * Returns the caller-supplied scheduler factory, when there is one.
     *
     * @return the factory, or empty to use {@link #getTaskScheduler()} or the engine default
     */
    public Optional<TaskSchedulerFactory> getTaskSchedulerFactory() {
        return Optional.ofNullable(taskSchedulerFactory);
    }

    /**
     * Returns the caller-supplied task repository, when there is one.
     *
     * @return the repository, or empty to use the engine default (in-memory)
     */
    public Optional<ScheduledTaskRepository> getTaskRepository() {
        return Optional.ofNullable(taskRepository);
    }

    /**
     * Returns this spec with the guard consulted before each fire.
     *
     * <p>
     * The engine's default prevents a task from overlapping itself on this node only, so in a cluster several nodes'
     * schedulers can fire the same task at the same cron time. A guard backed by a shared lock or lease store grants
     * the fire to exactly one of them — the core-level line of defence behind whatever the scheduler backend does.
     *
     * <p>
     * Here for the same reason as {@link #withTaskRepository(ScheduledTaskRepository)} and
     * {@link #withInterruptBus(ScheduledTaskInterruptBus)}: {@code SchedulingEngineBuilder} has always taken one, but
     * nothing between an application and that builder passed it along, so a written distributed guard could not reach
     * an assembled stack at all.
     *
     * @param executionGuard
     *            the guard asked whether an execution may begin (must not be null)
     * @return a spec carrying the guard
     * @throws IllegalStateException
     *             when scheduling is disabled — there would be no engine to consult it
     */
    public SchedulingSpec withExecutionGuard(ScheduledExecutionGuard executionGuard) {
        Objects.requireNonNull(executionGuard, "executionGuard must not be null");
        if (!enabled) {
            throw new IllegalStateException("Scheduling is disabled, so the execution guard would never be consulted."
                    + " Enable scheduling first, or drop the guard.");
        }
        return new SchedulingSpec(true, taskScheduler, taskSchedulerFactory, taskRepository, interruptBus,
                executionGuard);
    }

    /**
     * Returns the caller-supplied interrupt bus, when there is one.
     *
     * @return the bus, or empty to use the engine default (this node only)
     */
    public Optional<ScheduledTaskInterruptBus> getInterruptBus() {
        return Optional.ofNullable(interruptBus);
    }

    /**
     * Returns the caller-supplied execution guard, when there is one.
     *
     * @return the guard, or empty to use the engine default (this node only)
     */
    public Optional<ScheduledExecutionGuard> getExecutionGuard() {
        return Optional.ofNullable(executionGuard);
    }

    @Override
    public String toString() {
        return "SchedulingSpec[enabled=" + enabled + describeScheduler()
                + (taskRepository != null ? ", custom task repository" : "")
                + (interruptBus != null ? ", custom interrupt bus" : "")
                + (executionGuard != null ? ", custom execution guard" : "") + "]";
    }

    private String describeScheduler() {
        if (taskSchedulerFactory != null) {
            return ", custom scheduler factory";
        }
        return taskScheduler != null ? ", custom scheduler" : "";
    }
}
