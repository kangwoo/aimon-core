/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.core.scheduling;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import at.aimon.core.agent.AgentRuntimeRegistry;
import at.aimon.core.agent.DefaultAgentRuntimeRegistry;
import at.aimon.core.agent.quota.QuotaGuardFactory;
import at.aimon.core.base.ExternallyManaged;
import at.aimon.core.scheduling.event.ScheduledTaskEventPublisher;
import at.aimon.core.scheduling.event.SimpleScheduledTaskEventPublisher;
import at.aimon.core.scheduling.quota.StoredTaskQuotaManager;
import at.aimon.core.scheduling.quota.TaskQuotaManager;
import at.aimon.core.scheduling.repository.InMemoryScheduledTaskExecutionHistoryRepository;
import at.aimon.core.scheduling.repository.InMemoryScheduledTaskRepository;
import at.aimon.core.scheduling.repository.ScheduledTaskExecutionHistoryRepository;
import at.aimon.core.scheduling.repository.ScheduledTaskRepository;
import at.aimon.core.scheduling.scheduler.InMemoryTaskScheduler;
import at.aimon.core.scheduling.scheduler.ScheduledFire;
import at.aimon.core.scheduling.scheduler.ScheduledTaskExecutor;
import at.aimon.core.scheduling.scheduler.TaskScheduler;
import at.aimon.core.scheduling.scheduler.TaskSchedulerFactory;
import at.aimon.core.tracing.TracePayloadPolicy;
import at.aimon.core.tracing.Tracer;

/**
 * Builder for creating {@link SchedulingEngine} instances.
 *
 * <p>
 * This builder allows configuration of all engine components with sensible defaults. The
 * {@link AgentRuntimeRegistry} is provided externally and shared between the engine and other components that
 * need to register/unregister agent runtimes.
 * </p>
 *
 * <p>
 * Example usage:
 * </p>
 *
 * <pre>{@code
 * AgentRuntimeRegistry registry = new DefaultAgentRuntimeRegistry();
 * SchedulingEngine engine = SchedulingEngineBuilder.create().agentRuntimeRegistry(registry).defaultMaxQuota(20)
 *         .build();
 *
 * engine.start();
 * registry.register(agentRuntime);
 * // ... use engine
 * registry.unregister(agentRuntime.getId());
 * engine.close();
 * }</pre>
 */
public final class SchedulingEngineBuilder {

    /** How often a node with per-node triggers converges them on the repository, unless configured otherwise. */
    public static final Duration DEFAULT_RECONCILE_INTERVAL = Duration.ofMinutes(1);

    private ScheduledTaskRepository taskRepository;
    private ScheduledTaskExecutionHistoryRepository historyRepository;
    private TaskScheduler taskScheduler;
    private TaskSchedulerFactory taskSchedulerFactory;
    private ScheduledTaskEventPublisher eventPublisher;
    private TaskQuotaManager quotaManager;
    private ScheduledExecutionGuard executionGuard;
    private ScheduledTaskInterruptBus interruptBus;
    @ExternallyManaged
    private AgentRuntimeRegistry agentRuntimeRegistry;
    private int defaultMaxQuota = 10;
    private boolean multiNode;
    private Tracer tracer;
    private TracePayloadPolicy tracePayloadPolicy;
    private RoutineExecutionAttributes routineExecutionAttributes;
    private QuotaGuardFactory quotaGuardFactory;
    private boolean validateBoundRuntime = true;
    private UnresolvableRuntimePolicy unresolvableRuntimePolicy = UnresolvableRuntimePolicy.KEEP_SCHEDULED;
    private ScheduledTaskPolicy taskPolicy = ScheduledTaskPolicy.PERMIT_ALL;
    private Duration reconcileInterval = DEFAULT_RECONCILE_INTERVAL;

    private SchedulingEngineBuilder() {
    }

    /**
     * Creates a new builder instance.
     *
     * @return a new builder
     */
    public static SchedulingEngineBuilder create() {
        return new SchedulingEngineBuilder();
    }

    /**
     * Sets the task repository.
     *
     * @param repository
     *            the task repository
     * @return this builder
     */
    public SchedulingEngineBuilder taskRepository(ScheduledTaskRepository repository) {
        taskRepository = repository;
        return this;
    }

    /**
     * Sets the execution history repository.
     *
     * @param repository
     *            the history repository
     * @return this builder
     */
    public SchedulingEngineBuilder historyRepository(ScheduledTaskExecutionHistoryRepository repository) {
        historyRepository = repository;
        return this;
    }

    /**
     * Sets an already-built task scheduler.
     *
     * <p>
     * The scheduler must already dispatch firings to this engine's {@link ScheduledTaskManager}, which does not exist
     * yet when this method is called — so the caller has had to close over a mutable reference and fill it in
     * afterwards. {@link #taskSchedulerFactory(TaskSchedulerFactory)} does that for the caller and is the better
     * choice for anything but a scheduler that is already wired.
     * </p>
     *
     * @param scheduler
     *            the task scheduler
     * @return this builder
     */
    public SchedulingEngineBuilder taskScheduler(TaskScheduler scheduler) {
        taskScheduler = scheduler;
        return this;
    }

    /**
     * Sets a factory that builds the task scheduler once its executor exists.
     *
     * <p>
     * Prefer this over {@link #taskScheduler(TaskScheduler)}: the executor a scheduler has to call is the task manager,
     * and the task manager needs the scheduler. {@link #build()} is where that knot is untied, so a factory lets the
     * scheduler be built on the far side of it and receive the real executor rather than a reference that is still
     * empty.
     * </p>
     *
     * @param factory
     *            the scheduler factory
     * @return this builder
     */
    public SchedulingEngineBuilder taskSchedulerFactory(TaskSchedulerFactory factory) {
        taskSchedulerFactory = factory;
        return this;
    }

    /**
     * Sets the event publisher.
     *
     * @param publisher
     *            the event publisher
     * @return this builder
     */
    public SchedulingEngineBuilder eventPublisher(ScheduledTaskEventPublisher publisher) {
        eventPublisher = publisher;
        return this;
    }

    /**
     * Sets the quota manager.
     *
     * @param manager
     *            the quota manager
     * @return this builder
     */
    public SchedulingEngineBuilder quotaManager(TaskQuotaManager manager) {
        quotaManager = manager;
        return this;
    }

    /**
     * Sets the guard consulted before each fire, which decides whether an execution may begin.
     *
     * <p>
     * If not set, an {@link InMemoryScheduledExecutionGuard} is used: it prevents a task from overlapping itself
     * <em>on this node only</em>. In a scale-out deployment where several nodes schedule the same tasks, pass a
     * distributed guard (one backed by a shared lock/lease store, such as {@code MongoScheduledExecutionGuard} in
     * {@code aimon-session-mongodb}) so a cron time fires the task on exactly one node. The engine does not own a guard
     * and does not close it.
     * </p>
     *
     * @param guard
     *            the execution guard
     * @return this builder
     */
    public SchedulingEngineBuilder executionGuard(ScheduledExecutionGuard guard) {
        executionGuard = guard;
        return this;
    }

    /**
     * Sets the bus that carries "stop this task's runs" to the other nodes.
     *
     * <p>
     * If not set, {@link ScheduledTaskInterruptBus#LOCAL_ONLY} is used: cancelling or interrupting a task stops the
     * runs of it held by <em>this</em> node, which on a single node is all of them. In a scale-out deployment the node
     * a user cancels from is usually not the node the cron fired on, so pass a distributed implementation — otherwise
     * the run there carries on to the end of its steps against a task that no longer exists.
     * {@link InMemoryScheduledTaskInterruptBus} covers the narrower case of several engines sharing one JVM.
     * </p>
     *
     * @param bus
     *            the interrupt bus
     * @return this builder
     */
    public SchedulingEngineBuilder interruptBus(ScheduledTaskInterruptBus bus) {
        interruptBus = bus;
        return this;
    }

    /**
     * Sets the agent runtime registry.
     *
     * <p>
     * If not set, a {@link DefaultAgentRuntimeRegistry} will be created.
     * </p>
     *
     * @param registry
     *            the context registry
     * @return this builder
     */
    public SchedulingEngineBuilder agentRuntimeRegistry(@ExternallyManaged AgentRuntimeRegistry registry) {
        agentRuntimeRegistry = registry;
        return this;
    }

    /**
     * Sets the default maximum quota per agent runtime.
     *
     * <p>
     * This is only used if no custom quota manager is provided.
     * </p>
     *
     * @param quota
     *            the default maximum quota
     * @return this builder
     */
    public SchedulingEngineBuilder defaultMaxQuota(int quota) {
        if (quota <= 0) {
            throw new IllegalArgumentException("Default max quota must be positive");
        }
        defaultMaxQuota = quota;
        return this;
    }

    /**
     * Says this engine is one node of several that share the task repository.
     *
     * <p>
     * It changes three things. A fire whose bound runtime this node has not registered is skipped before the guard is
     * asked, leaving the occurrence to a node that has it; {@link UnresolvableRuntimePolicy#DISABLE} is refused, since
     * one node's miss would disable the task for all; and — the original one — whether
     * {@link SchedulingEngine#start()} schedules the tasks it finds stored. On a single node it always does. On one of
     * several it does only if the scheduler's triggers are shared by the deployment
     * ({@link TaskScheduler#isClusterWide()}) or an {@link #executionGuard} was supplied — otherwise every node would
     * rebuild every trigger and every node would fire every task, with only a node-local guard between them.
     *
     * @param multiNode
     *            whether other nodes run an engine over the same repository (default {@code false})
     * @return this builder
     */
    public SchedulingEngineBuilder multiNode(boolean multiNode) {
        this.multiNode = multiNode;
        return this;
    }

    /**
     * Sets the tracer that records each routine run as a {@code ROUTINE} span with a {@code TOOL} span per step
     * attempt, so a subagent a step starts lands in a trace. Pass the same instance the agent executors use.
     *
     * <p>
     * If not set, routine runs record nothing. The tracer is application-scoped, like the engine; the engine neither
     * owns nor closes it.
     * </p>
     *
     * @param tracer
     *            the tracer
     * @return this builder
     */
    public SchedulingEngineBuilder tracer(Tracer tracer) {
        this.tracer = tracer;
        return this;
    }

    /**
     * Sets the policy deciding whether routine step spans capture the tool result content.
     *
     * <p>
     * If not set, {@link TracePayloadPolicy#summaryOnly()} is used.
     * </p>
     *
     * @param policy
     *            the payload capture policy
     * @return this builder
     */
    public SchedulingEngineBuilder tracePayloadPolicy(TracePayloadPolicy policy) {
        this.tracePayloadPolicy = policy;
        return this;
    }

    /**
     * Sets the host resolver asked once per routine run for that run's execution attributes — what
     * {@code SubmitOptions.executionAttribute(...)} is for a session turn. Their {@code environment.*} subset reaches
     * the run's {@code EnvironmentRequest.requestAttributes()}, so a provider can place one routine's runs per task,
     * and the whole map reaches the steps' tool context.
     *
     * <p>
     * If not set, {@link RoutineExecutionAttributes#NONE} is used and routine runs carry no execution attributes.
     * </p>
     *
     * @param resolver
     *            the resolver
     * @return this builder
     */
    public SchedulingEngineBuilder routineExecutionAttributes(RoutineExecutionAttributes resolver) {
        this.routineExecutionAttributes = resolver;
        return this;
    }

    /**
     * Sets the factory that builds each routine run's <b>usage</b> quota guard &mdash; LLM tokens and cost per
     * principal or tenant, accumulated across executions &mdash; so a {@code Task}, {@code Workflow} or fork-mode skill
     * step is charged to the task's owner the way a turn's forks are charged to its caller, and stopped before its next
     * LLM call once the owner's quota is reached. Pass the same instance the agent executors use.
     *
     * <p>
     * Not to be confused with {@link #quotaManager(TaskQuotaManager)} and {@link #defaultMaxQuota(int)}, which cap
     * how many <em>tasks</em> a runtime may schedule.
     * </p>
     *
     * <p>
     * If not set, {@link QuotaGuardFactory#disabled()} is used and routine runs are not metered. The factory is
     * application-scoped, like the engine; the engine neither owns nor closes it.
     * </p>
     *
     * @param quotaGuardFactory
     *            the usage-quota guard factory
     * @return this builder
     */
    public SchedulingEngineBuilder quotaGuardFactory(QuotaGuardFactory quotaGuardFactory) {
        this.quotaGuardFactory = quotaGuardFactory;
        return this;
    }

    /**
     * Sets whether registering a task whose bound agent runtime is not in the registry is refused.
     *
     * <p>
     * On by default. Such a task used to be stored, scheduled and reported to the model as scheduled, and then fail at
     * every fire with "No agent runtime registered for binding", which nobody was there to see. With the check on,
     * {@code schedule_task} returns an error naming the runtime instead. Turn it off only for a host that registers
     * runtimes lazily <em>and</em> registers tasks before their runtime exists.
     *
     * @param validate
     *            whether to check (default {@code true})
     * @return this builder
     */
    public SchedulingEngineBuilder validateBoundRuntime(boolean validate) {
        validateBoundRuntime = validate;
        return this;
    }

    /**
     * Sets what a fire does when its task's bound runtime is not registered — keep failing at each fire (the default),
     * or disable the task and publish a {@code TaskDisabledEvent}. See {@link UnresolvableRuntimePolicy} for why the
     * default is the former.
     *
     * @param policy
     *            the policy
     * @return this builder
     */
    public SchedulingEngineBuilder unresolvableRuntimePolicy(UnresolvableRuntimePolicy policy) {
        unresolvableRuntimePolicy = Objects.requireNonNull(policy, "policy cannot be null");
        return this;
    }

    /**
     * Sets the host policy the scheduling tools consult before they schedule, list or cancel a task: which tools a
     * routine may use, how often it may fire, which runtime it is bound to. Scheduling time is the only place such
     * rules can be enforced, because routine steps run without hooks. Defaults to
     * {@link ScheduledTaskPolicy#PERMIT_ALL}.
     *
     * @param policy
     *            the policy
     * @return this builder
     */
    public SchedulingEngineBuilder taskPolicy(ScheduledTaskPolicy policy) {
        taskPolicy = Objects.requireNonNull(policy, "policy cannot be null");
        return this;
    }

    /**
     * Sets how often a node converges its triggers on the repository ({@link ScheduledTaskManager#reconcile()}).
     *
     * <p>
     * Only used where it is both needed and safe: on one node of several ({@link #multiNode(boolean)}) whose scheduler
     * holds per-node triggers ({@link TaskScheduler#isClusterWide()} is {@code false}) and which has a supplied
     * {@link #executionGuard} to keep the resulting fire-on-every-node to one run — one that can see the other nodes,
     * so not {@link InMemoryScheduledExecutionGuard} or {@link ScheduledExecutionGuard#ALLOW_ALL}. There a task
     * registered or cancelled
     * on another node reaches this one within an interval. Anywhere else nothing runs. {@link Duration#ZERO} turns it
     * off. Defaults to {@link #DEFAULT_RECONCILE_INTERVAL}.
     *
     * @param interval
     *            the interval (must not be negative)
     * @return this builder
     */
    public SchedulingEngineBuilder reconcileInterval(Duration interval) {
        Objects.requireNonNull(interval, "interval cannot be null");
        if (interval.isNegative()) {
            throw new IllegalArgumentException("Reconcile interval must not be negative");
        }
        reconcileInterval = interval;
        return this;
    }

    /**
     * Builds the scheduling engine.
     *
     * <p>
     * The scheduler is built here, from a lazy reference to the {@link ScheduledTaskManager}. This resolves the
     * circular dependency between the scheduler (which needs a task executor) and the task manager (which needs a
     * scheduler, and is itself the executor). The lazy reference is safe because it is guaranteed to be set before
     * {@link SchedulingEngine#start()} is called.
     * </p>
     *
     * <p>
     * Which scheduler gets built depends on what was configured: a {@link #taskSchedulerFactory(TaskSchedulerFactory)}
     * is invoked with the executor, a {@link #taskScheduler(TaskScheduler)} is taken as-is (it was wired by its own
     * caller), and with neither an {@link InMemoryTaskScheduler} is created.
     * </p>
     *
     * @return a new scheduling engine
     * @throws IllegalStateException
     *             if both a scheduler and a scheduler factory were configured
     */
    public SchedulingEngine build() {
        if (taskScheduler != null && taskSchedulerFactory != null) {
            throw new IllegalStateException("Configure either taskScheduler or taskSchedulerFactory, not both");
        }
        if (taskRepository == null) {
            taskRepository = new InMemoryScheduledTaskRepository();
        }
        if (historyRepository == null) {
            historyRepository = new InMemoryScheduledTaskExecutionHistoryRepository();
        }
        if (eventPublisher == null) {
            eventPublisher = new SimpleScheduledTaskEventPublisher();
        }
        if (quotaManager == null) {
            // Counted from the repository rather than kept in a ledger beside it: a ledger is emptied by a restart the
            // tasks survive, and is per node over a repository the nodes share.
            quotaManager = new StoredTaskQuotaManager(taskRepository, defaultMaxQuota);
        }
        final boolean guardSupplied = executionGuard != null;
        if (executionGuard == null) {
            executionGuard = new InMemoryScheduledExecutionGuard();
        }
        if (interruptBus == null) {
            interruptBus = ScheduledTaskInterruptBus.LOCAL_ONLY;
        }
        if (agentRuntimeRegistry == null) {
            agentRuntimeRegistry = new DefaultAgentRuntimeRegistry();
        }

        // Create routine executor with context registry for dynamic tool resolution
        final RoutineExecutor routineExecutor = new RoutineExecutor(agentRuntimeRegistry, eventPublisher);
        if (tracer != null) {
            routineExecutor.withTracer(tracer);
        }
        if (tracePayloadPolicy != null) {
            routineExecutor.withTracePayloadPolicy(tracePayloadPolicy);
        }
        if (routineExecutionAttributes != null) {
            routineExecutor.withExecutionAttributes(routineExecutionAttributes);
        }
        if (quotaGuardFactory != null) {
            routineExecutor.withQuotaGuardFactory(quotaGuardFactory);
        }

        // Resolve circular dependency: TaskScheduler needs ScheduledTaskExecutor,
        // ScheduledTaskManager needs TaskScheduler, and ScheduledTaskManager IS the executor.
        // The hole is dug once, here, so that no caller has to dig its own.
        final AtomicReference<ScheduledTaskManager> taskManagerRef = new AtomicReference<>();
        // Both halves, so a fire's slot reaches the guard: a lambda would implement only the task-id half.
        final TaskScheduler scheduler = resolveScheduler(new ScheduledTaskExecutor() {
            @Override
            public void execute(ScheduledTaskId taskId) {
                taskManagerRef.get().executeTask(taskId);
            }

            @Override
            public void execute(ScheduledFire fire) {
                taskManagerRef.get().executeFire(fire);
            }
        });

        final ScheduledTaskManager taskManager = ScheduledTaskManager.builder().taskRepository(taskRepository)
                .historyRepository(historyRepository).routineExecutor(routineExecutor).taskScheduler(scheduler)
                .eventPublisher(eventPublisher).quotaManager(quotaManager).executionGuard(executionGuard)
                .interruptBus(interruptBus).agentRuntimeRegistry(agentRuntimeRegistry)
                .validateBoundRuntime(validateBoundRuntime).unresolvableRuntimePolicy(unresolvableRuntimePolicy)
                .taskPolicy(taskPolicy).multiNode(multiNode).build();
        taskManagerRef.set(taskManager);

        // On one node of several, rebuilding the stored tasks' triggers is only safe if a trigger exists once for the
        // whole deployment, or if something that sees the other nodes stops the duplicate fires.
        final boolean rehydrateAtStart = !multiNode || scheduler.isClusterWide() || guardSupplied;
        // Converging afterwards is needed exactly where triggers are per node and other nodes change the repository,
        // and safe exactly where rebuilding them is — the supplied guard is what stops every node running every fire.
        // A supplied guard that is one of the node-local ones sees no other node, so with every node holding every
        // trigger it would let every node run every fire; reconciling is held back for it.
        final boolean guardSeesOtherNodes = guardSupplied
                && !(executionGuard instanceof InMemoryScheduledExecutionGuard)
                && executionGuard != ScheduledExecutionGuard.ALLOW_ALL;
        final Duration reconcileEvery = multiNode && !scheduler.isClusterWide() && guardSeesOtherNodes
                ? reconcileInterval
                : Duration.ZERO;
        return new SchedulingEngine(taskManager, routineExecutor, scheduler, eventPublisher, interruptBus,
                rehydrateAtStart, reconcileEvery);
    }

    private TaskScheduler resolveScheduler(ScheduledTaskExecutor executor) {
        if (taskSchedulerFactory != null) {
            final TaskScheduler created = taskSchedulerFactory.create(executor);
            if (created == null) {
                throw new IllegalStateException("Task scheduler factory returned null");
            }
            return created;
        }
        // An already-built scheduler was wired to its executor by whoever built it; this one is not ours to feed.
        return taskScheduler != null ? taskScheduler : new InMemoryTaskScheduler(executor);
    }
}
