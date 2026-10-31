package at.aimon.bootstrap.spec;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.scheduling.RoutineExecutionAttributes;
import at.aimon.core.scheduling.ScheduledExecutionGuard;
import at.aimon.core.scheduling.ScheduledTaskInterruptBus;
import at.aimon.core.scheduling.ScheduledTaskPolicy;
import at.aimon.core.scheduling.UnresolvableRuntimePolicy;
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
 * A durable scheduler without a repository is the failure the stack announces as its
 * {@code scheduling-durability} degradation — a surviving trigger whose task is gone fires into "task not found",
 * which reads like durability right up to the moment it is needed. The other way round is sound: the engine rebuilds
 * the triggers from a supplied repository when it starts. The exception is one node of several whose scheduler is per
 * node and that has no execution guard seeing the others — there the engine does not rebuild them, and that is
 * announced under the same name.
 */
public final class SchedulingSpec {

    private final boolean enabled;
    private final TaskScheduler taskScheduler;
    private final TaskSchedulerFactory taskSchedulerFactory;
    private final ScheduledTaskRepository taskRepository;
    private final ScheduledTaskInterruptBus interruptBus;
    private final ScheduledExecutionGuard executionGuard;
    private final RoutineExecutionAttributes routineExecutionAttributes;
    private final ScheduledTaskPolicy taskPolicy;
    private final Boolean validateBoundRuntime;
    private final UnresolvableRuntimePolicy unresolvableRuntimePolicy;
    private final Duration reconcileInterval;

    private SchedulingSpec(Draft draft) {
        enabled = draft.enabled;
        taskScheduler = draft.taskScheduler;
        taskSchedulerFactory = draft.taskSchedulerFactory;
        taskRepository = draft.taskRepository;
        interruptBus = draft.interruptBus;
        executionGuard = draft.executionGuard;
        routineExecutionAttributes = draft.routineExecutionAttributes;
        taskPolicy = draft.taskPolicy;
        validateBoundRuntime = draft.validateBoundRuntime;
        unresolvableRuntimePolicy = draft.unresolvableRuntimePolicy;
        reconcileInterval = draft.reconcileInterval;
    }

    /**
     * Every field of this spec, open for one {@code with*} method to change one of them. Copying through a draft is
     * what keeps each wither from having to restate — and, the day a field is added, forget — all the others.
     */
    private Draft draft() {
        final Draft draft = new Draft();
        draft.enabled = enabled;
        draft.taskScheduler = taskScheduler;
        draft.taskSchedulerFactory = taskSchedulerFactory;
        draft.taskRepository = taskRepository;
        draft.interruptBus = interruptBus;
        draft.executionGuard = executionGuard;
        draft.routineExecutionAttributes = routineExecutionAttributes;
        draft.taskPolicy = taskPolicy;
        draft.validateBoundRuntime = validateBoundRuntime;
        draft.unresolvableRuntimePolicy = unresolvableRuntimePolicy;
        draft.reconcileInterval = reconcileInterval;
        return draft;
    }

    /** The mutable twin of the fields above; never escapes this class. */
    private static final class Draft {
        private boolean enabled;
        private TaskScheduler taskScheduler;
        private TaskSchedulerFactory taskSchedulerFactory;
        private ScheduledTaskRepository taskRepository;
        private ScheduledTaskInterruptBus interruptBus;
        private ScheduledExecutionGuard executionGuard;
        private RoutineExecutionAttributes routineExecutionAttributes;
        private ScheduledTaskPolicy taskPolicy;
        private Boolean validateBoundRuntime;
        private UnresolvableRuntimePolicy unresolvableRuntimePolicy;
        private Duration reconcileInterval;
    }

    private static Draft fresh(boolean enabled) {
        final Draft draft = new Draft();
        draft.enabled = enabled;
        return draft;
    }

    /** Refuses a seam on a disabled spec, naming what would never happen to it. */
    private void requireEnabled(String what) {
        if (!enabled) {
            throw new IllegalStateException(
                    "Scheduling is disabled, so " + what + ". Enable scheduling first, or drop" + " it.");
        }
    }

    /**
     * Scheduling off — no engine, no scheduler thread, nothing to drain at shutdown.
     *
     * @return the spec
     */
    public static SchedulingSpec disabled() {
        return new SchedulingSpec(fresh(false));
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
        return new SchedulingSpec(fresh(true));
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
        final Draft draft = fresh(true);
        draft.taskSchedulerFactory = taskSchedulerFactory;
        return new SchedulingSpec(draft);
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
        final Draft draft = fresh(true);
        draft.taskScheduler = taskScheduler;
        return new SchedulingSpec(draft);
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
     * {@code aimon-core} ships only the in-memory one; a durable one is {@code MongoScheduledTaskRepository} in
     * {@code aimon-session-mongodb}, or an application's own. Without this seam a finished durable
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
        requireEnabled("the task repository would never be read");
        final Draft draft = draft();
        draft.taskRepository = taskRepository;
        return new SchedulingSpec(draft);
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
        requireEnabled("the interrupt bus would never be used");
        final Draft draft = draft();
        draft.interruptBus = interruptBus;
        return new SchedulingSpec(draft);
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
     * {@code MongoScheduledExecutionGuard} in {@code aimon-session-mongodb} is one, or an application's own. The caller
     * owns it and closes it after the stack.
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
        requireEnabled("the execution guard would never be consulted");
        final Draft draft = draft();
        draft.executionGuard = executionGuard;
        return new SchedulingSpec(draft);
    }

    /**
     * Returns this spec with the host resolver asked once per routine run for that run's execution attributes.
     *
     * <p>
     * A routine has no submit call to attach per-run data to, and its execution id does not exist until it fires, so
     * this is how a host gives a routine run what {@code SubmitOptions.executionAttribute(...)} gives a turn — above
     * all the {@code environment.*} entries an {@code ExecutionEnvironmentProvider} reads as
     * {@code EnvironmentRequest.requestAttributes()}. Here for the same reason as the guard: the engine builder takes
     * one, and nothing else would pass it along from an assembled stack.
     *
     * @param routineExecutionAttributes
     *            the resolver (must not be null)
     * @return a spec carrying the resolver
     * @throws IllegalStateException
     *             when scheduling is disabled — there would be no routine to ask it for
     */
    public SchedulingSpec withRoutineExecutionAttributes(RoutineExecutionAttributes routineExecutionAttributes) {
        Objects.requireNonNull(routineExecutionAttributes, "routineExecutionAttributes must not be null");
        requireEnabled("the routine execution attributes would never be asked for");
        final Draft draft = draft();
        draft.routineExecutionAttributes = routineExecutionAttributes;
        return new SchedulingSpec(draft);
    }

    /**
     * Returns this spec with the host policy the scheduling tools consult before they schedule, list or cancel a task.
     *
     * <p>
     * A scheduled routine step runs its tool without hooks, so the moment a model asks for a schedule is the only
     * point a host's rules — which tools a routine may use, how often it may fire, which runtime it runs on — can be
     * applied. Here for the same reason as the guard: the engine builder takes one, and nothing else would pass it
     * along from an assembled stack.
     *
     * @param taskPolicy
     *            the policy (must not be null)
     * @return a spec carrying the policy
     * @throws IllegalStateException
     *             when scheduling is disabled — there would be no scheduling tool to consult it
     */
    public SchedulingSpec withTaskPolicy(ScheduledTaskPolicy taskPolicy) {
        Objects.requireNonNull(taskPolicy, "taskPolicy must not be null");
        requireEnabled("the task policy would never be consulted");
        final Draft draft = draft();
        draft.taskPolicy = taskPolicy;
        return new SchedulingSpec(draft);
    }

    /**
     * Returns this spec with registration's bound-runtime check turned on or off.
     *
     * <p>
     * On by default in the engine: registering a task whose bound runtime is not registered fails, rather than storing
     * a task that will fail at every fire. Turn it off only for a host that registers tasks before their runtime
     * exists — the same switch as {@code SchedulingEngineBuilder.validateBoundRuntime}, reachable from an assembled
     * stack.
     *
     * @param validate
     *            whether registration checks the bound runtime
     * @return a spec carrying the choice
     * @throws IllegalStateException
     *             when scheduling is disabled
     */
    public SchedulingSpec withBoundRuntimeValidation(boolean validate) {
        requireEnabled("the bound-runtime check would never run");
        final Draft draft = draft();
        draft.validateBoundRuntime = validate;
        return new SchedulingSpec(draft);
    }

    /**
     * Returns this spec with what a fire does when its bound runtime is not registered. The engine refuses
     * {@link UnresolvableRuntimePolicy#DISABLE} on a distributed stack.
     *
     * @param policy
     *            the policy (must not be null)
     * @return a spec carrying the policy
     * @throws IllegalStateException
     *             when scheduling is disabled
     */
    public SchedulingSpec withUnresolvableRuntimePolicy(UnresolvableRuntimePolicy policy) {
        Objects.requireNonNull(policy, "policy must not be null");
        requireEnabled("the unresolvable-runtime policy would never apply");
        final Draft draft = draft();
        draft.unresolvableRuntimePolicy = policy;
        return new SchedulingSpec(draft);
    }

    /**
     * Returns this spec with how often a node converges its per-node triggers on the repository. Only used where the
     * engine reconciles at all (see {@code SchedulingEngineBuilder.reconcileInterval}); {@link Duration#ZERO} turns it
     * off.
     *
     * @param interval
     *            the interval (must not be null or negative)
     * @return a spec carrying the interval
     * @throws IllegalStateException
     *             when scheduling is disabled
     */
    public SchedulingSpec withReconcileInterval(Duration interval) {
        Objects.requireNonNull(interval, "interval must not be null");
        if (interval.isNegative()) {
            throw new IllegalArgumentException("Reconcile interval must not be negative");
        }
        requireEnabled("there would be no triggers to reconcile");
        final Draft draft = draft();
        draft.reconcileInterval = interval;
        return new SchedulingSpec(draft);
    }

    /**
     * Returns the caller's choice for registration's bound-runtime check, when it made one.
     *
     * @return the choice, or empty for the engine default (on)
     */
    public Optional<Boolean> getBoundRuntimeValidation() {
        return Optional.ofNullable(validateBoundRuntime);
    }

    /**
     * Returns the caller-supplied unresolvable-runtime policy, when there is one.
     *
     * @return the policy, or empty for the engine default ({@link UnresolvableRuntimePolicy#KEEP_SCHEDULED})
     */
    public Optional<UnresolvableRuntimePolicy> getUnresolvableRuntimePolicy() {
        return Optional.ofNullable(unresolvableRuntimePolicy);
    }

    /**
     * Returns the caller-supplied reconcile interval, when there is one.
     *
     * @return the interval, or empty for the engine default
     */
    public Optional<Duration> getReconcileInterval() {
        return Optional.ofNullable(reconcileInterval);
    }

    /**
     * Returns the caller-supplied task policy, when there is one.
     *
     * @return the policy, or empty to use {@link ScheduledTaskPolicy#PERMIT_ALL}
     */
    public Optional<ScheduledTaskPolicy> getTaskPolicy() {
        return Optional.ofNullable(taskPolicy);
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

    /**
     * Returns the caller-supplied routine execution attributes resolver, when there is one.
     *
     * @return the resolver, or empty to give routine runs no execution attributes
     */
    public Optional<RoutineExecutionAttributes> getRoutineExecutionAttributes() {
        return Optional.ofNullable(routineExecutionAttributes);
    }

    @Override
    public String toString() {
        return "SchedulingSpec[enabled=" + enabled + describeScheduler()
                + (taskRepository != null ? ", custom task repository" : "")
                + (interruptBus != null ? ", custom interrupt bus" : "")
                + (executionGuard != null ? ", custom execution guard" : "")
                + (routineExecutionAttributes != null ? ", routine execution attributes" : "")
                + (taskPolicy != null ? ", task policy" : "")
                + (validateBoundRuntime != null ? ", bound-runtime check " + (validateBoundRuntime ? "on" : "off") : "")
                + (unresolvableRuntimePolicy != null ? ", " + unresolvableRuntimePolicy : "")
                + (reconcileInterval != null ? ", reconcile every " + reconcileInterval : "") + "]";
    }

    private String describeScheduler() {
        if (taskSchedulerFactory != null) {
            return ", custom scheduler factory";
        }
        return taskScheduler != null ? ", custom scheduler" : "";
    }
}
