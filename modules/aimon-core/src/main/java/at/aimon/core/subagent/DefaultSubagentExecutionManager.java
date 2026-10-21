package at.aimon.core.subagent;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.AgentExecutionRequest;
import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.InvokerType;
import at.aimon.core.agent.budget.CompletionReason;
import at.aimon.core.agent.budget.ExecutionDeadline;
import at.aimon.core.agent.context.ContextEngine;
import at.aimon.core.agent.interrupt.CancellationSignal;
import at.aimon.core.agent.interrupt.DefaultInterruptCoordinator;
import at.aimon.core.agent.interrupt.InterruptCoordinator;
import at.aimon.core.agent.prompt.SystemReminderFormatter;
import at.aimon.core.agent.queue.MessageQueueManager;
import at.aimon.core.agent.queue.QueuedInput;
import at.aimon.core.agent.queue.QueuedInputPriority;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.transcript.SessionSnapshot;
import at.aimon.core.agent.session.transcript.TranscriptBuffer;
import at.aimon.core.agent.stream.AgentExecutionEvent;
import at.aimon.core.agent.stream.SubagentTaskCompleted;
import at.aimon.core.agent.tool.ToolExecutionManager;
import at.aimon.core.agent.tool.permission.AllowedTool;
import at.aimon.core.agent.tool.permission.AllowedTools;
import at.aimon.core.command.execution.ExecutionMetadata;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.hook.HookExecutionManager;
import at.aimon.core.hook.event.SubagentStartContext;
import at.aimon.core.hook.event.SubagentStopContext;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.subagent.behavior.SubagentBehavior;
import at.aimon.core.subagent.behavior.SubagentBehaviorRegistry;
import at.aimon.core.subagent.behavior.SubagentBehaviorRunner;
import at.aimon.core.subagent.exception.SubagentException;
import at.aimon.core.subagent.exception.SubagentNotFoundException;
import at.aimon.core.subagent.exception.SubagentSpawnException;
import at.aimon.core.subagent.execution.DefaultSubagentExecutor;
import at.aimon.core.subagent.execution.SubagentExecutionContext;
import at.aimon.core.subagent.execution.SubagentExecutionRequest;
import at.aimon.core.subagent.execution.SubagentExecutionResult;
import at.aimon.core.subagent.execution.SubagentExecutor;
import at.aimon.core.subagent.execution.SubagentOutputSink;
import at.aimon.core.subagent.execution.SubagentResultFormatter;
import at.aimon.core.subagent.internal.DeadlineScheduler;
import at.aimon.core.subagent.task.BackgroundTask;
import at.aimon.core.subagent.task.BackgroundTaskState;
import at.aimon.core.subagent.task.BackgroundTaskStore;
import at.aimon.core.subagent.task.InMemoryBackgroundTaskStore;
import at.aimon.core.subagent.task.NoopTaskStopSignal;
import at.aimon.core.subagent.task.RunningTaskHandle;
import at.aimon.core.subagent.task.RunningTaskRegistry;
import at.aimon.core.subagent.task.TaskHeartbeatPublisher;
import at.aimon.core.subagent.task.TaskLeaseConfig;
import at.aimon.core.subagent.task.TaskOutputStore;
import at.aimon.core.subagent.task.TaskQuery;
import at.aimon.core.subagent.task.TaskResult;
import at.aimon.core.subagent.task.TaskStopSignal;
import at.aimon.core.subagent.task.ZombieTaskReaper;
import at.aimon.core.tools.ForkAccess;
import at.aimon.core.tools.task.AgentOutputTool;
import at.aimon.core.tracing.SpanType;
import at.aimon.core.tracing.Spans;
import at.aimon.core.tracing.TracePayloadPolicy;
import at.aimon.core.tracing.Tracer;

/**
 * Handles subagent spawning and execution for CoreAgent.
 *
 * <p>
 * This class is responsible for:
 *
 * <ul>
 * <li>Parsing user input to extract subagent names and goals
 * <li>Looking up subagents in the registry
 * <li>Executing subagents through the executor
 * </ul>
 *
 * <p>
 * Subagent input format: {@code @subagent-name goal description}
 *
 * <p>
 * Thread-safe if SubagentRegistry and SubagentExecutor are thread-safe.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     SubagentHandler handler = new SubagentHandler(registry, executor);
 *
 *     // Execute subagent request
 *     String input = "@code-reviewer Review authentication module";
 *     AgentExecutionRequest request = AgentExecutionRequest.of(input);
 *     SubagentExecutionResult result = handler.execute(context, request);
 *     System.out.println(result.getSummary());
 * }
 * </pre>
 */
public final class DefaultSubagentExecutionManager implements SubagentExecutionManager, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(DefaultSubagentExecutionManager.class);

    /** Max characters kept in a background completion notification detail before truncation. */
    private static final int COMPLETION_DETAIL_MAX_CHARS = 500;

    private final SubagentExecutor subagentExecutor;
    private final ExecutorService executorService;
    /**
     * Optional. When present, {@link #execute(SubagentLaunchContext, String, String, String, String)} fires
     * SubagentStart / SubagentStop hooks around the dispatch.
     */
    private final HookExecutionManager hookExecutionManager;
    /**
     * Code-behavior dispatch: a subagent name registered here runs its {@link SubagentBehavior} instead of the ReAct
     * loop. Defaults to {@link SubagentBehaviorRegistry#empty()} so existing callers keep the unchanged data path.
     */
    private final SubagentBehaviorRegistry subagentBehaviorRegistry;
    private final SubagentBehaviorRunner subagentBehaviorRunner;

    /**
     * Durable, multi-instance-ready metadata store for background tasks. Holds {@link BackgroundTask} snapshots
     * so {@link #list(TaskQuery)} / {@link #status(String)} survive across instances when a shared backend is injected;
     * the default in-memory implementation is node-local.
     */
    private final BackgroundTaskStore taskStore;

    /**
     * Node-local registry of live execution handles for background tasks running on this instance. A stop
     * request resolves its {@link RunningTaskHandle} here to trip the per-task signal and interrupt the worker thread.
     */
    private final RunningTaskRegistry runningTasks = new RunningTaskRegistry();

    /**
     * Runs that were asked to stop and had not exited when their grace period ran out, by task id. An entry is added
     * when the grace runs out and removed when the run finally returns ({@link #lingeringRuns()}).
     */
    private final Map<String, LingeringSubagentRun> lingering = new ConcurrentHashMap<>();

    /**
     * Cross-node cancellation seam (design §4). A {@link #stop(String)} for a task with no live handle on
     * this node broadcasts here so the node that owns the running task can trip its handle. The default
     * {@link NoopTaskStopSignal} makes this a no-op, keeping single-node behaviour unchanged.
     */
    private final TaskStopSignal taskStopSignal;

    /**
     * This manager's registration on {@link #taskStopSignal}: delivers remote stop requests to {@link #onRemoteStop}.
     * Closed on {@link #close()}.
     */
    private final TaskStopSignal.Subscription stopSubscription;

    /**
     * Lease-recovery machinery (design §4), created only when a {@link TaskLeaseConfig} is injected. The publisher
     * renews the heartbeat of every task this node owns; the reaper transitions heartbeat-expired non-terminal tasks to
     * {@code FAILED}. Both are {@code null} (no threads, unchanged behaviour) when lease recovery is not opted in.
     * Closed on {@link #close()}.
     */
    private final TaskHeartbeatPublisher heartbeatPublisher;
    private final ZombieTaskReaper zombieReaper;

    /**
     * TRACE-01: tracer that records one {@code SUBAGENT} span per run, under the span the launch context's parent
     * metadata points at (the launching tool call's span). Defaults to {@link Tracer#noop()}; wired via
     * {@link #withTracer(Tracer)}. {@code volatile} because it is set after construction and read on background
     * workers.
     */
    private volatile Tracer tracer = Tracer.noop();

    /**
     * TRACE-02: whether SUBAGENT spans capture the final answer. Defaults to {@link TracePayloadPolicy#summaryOnly()};
     * {@code volatile} for the same reason as {@link #tracer}.
     */
    private volatile TracePayloadPolicy tracePayloadPolicy = TracePayloadPolicy.summaryOnly();

    /**
     * Whether each fork carries its own task id as the {@link ForkAccess#TASK_ID_ATTRIBUTE} execution attribute.
     * Defaults to {@code false}; set via {@link #withForkTaskIdAttribute()}. {@code volatile} for the same reason as
     * {@link #tracer}.
     */
    private volatile boolean forkTaskIdAttribute;

    /**
     * The executor this manager built itself — the same instance as {@link #subagentExecutor} — when it was created
     * through an {@link LlmClient} convenience constructor; {@code null} when the caller handed one in. Only an owned
     * executor receives {@link #withTracer} and {@link #withTracePayloadPolicy}: a caller's executor is never changed
     * behind its back.
     */
    private final DefaultSubagentExecutor ownedExecutor;

    /**
     * Creates a new DefaultSubagentExecutionManager.
     *
     * @param subagentExecutor
     *            The subagent executor (must not be null)
     * @param executorService
     *            The executor service for background tasks (must not be null)
     * @throws NullPointerException
     *             if any parameter is null
     */
    public DefaultSubagentExecutionManager(SubagentExecutor subagentExecutor, ExecutorService executorService) {
        this(subagentExecutor, executorService, null);
    }

    /**
     * Creates a new DefaultSubagentExecutionManager that fires SubagentStart / SubagentStop hooks via the supplied
     * {@link HookExecutionManager}.
     *
     * @param subagentExecutor
     *            The subagent executor (must not be null)
     * @param executorService
     *            The executor service for background tasks (must not be null)
     * @param hookExecutionManager
     *            The hook execution manager that fires Subagent* hooks; null disables firing (no-op)
     * @throws NullPointerException
     *             if subagentExecutor or executorService is null
     */
    public DefaultSubagentExecutionManager(SubagentExecutor subagentExecutor, ExecutorService executorService,
            HookExecutionManager hookExecutionManager) {
        this(subagentExecutor, executorService, hookExecutionManager, SubagentBehaviorRegistry.empty());
    }

    /**
     * Creates a new DefaultSubagentExecutionManager with a code-behavior registry. A subagent name registered in
     * {@code subagentBehaviorRegistry} runs its {@link SubagentBehavior} instead of the ReAct loop; all other names run
     * the unchanged data path.
     *
     * @param subagentExecutor
     *            The subagent executor (must not be null)
     * @param executorService
     *            The executor service for background tasks (must not be null)
     * @param hookExecutionManager
     *            The hook execution manager that fires Subagent* hooks; null disables firing (no-op)
     * @param subagentBehaviorRegistry
     *            The code-behavior registry; null is treated as {@link SubagentBehaviorRegistry#empty()}
     * @throws NullPointerException
     *             if subagentExecutor or executorService is null
     */
    public DefaultSubagentExecutionManager(SubagentExecutor subagentExecutor, ExecutorService executorService,
            HookExecutionManager hookExecutionManager, SubagentBehaviorRegistry subagentBehaviorRegistry) {
        this(subagentExecutor, executorService, hookExecutionManager, subagentBehaviorRegistry, null);
    }

    /**
     * Canonical constructor. {@code llmClient}, when non-null, is wrapped into the {@link SubagentBehaviorRunner}'s
     * gateway so code behaviors can call the model ({@link
     * at.aimon.core.subagent.behavior.SubagentBehaviorSupport#llmGateway()}); null disables LLM access for behaviors.
     * The {@code SubagentExecutor} is the ReAct path and is independent of this client — pass the same client used to
     * build the executor when LLM access for behaviors is wanted.
     *
     * @param subagentExecutor
     *            The subagent executor (must not be null)
     * @param executorService
     *            The executor service for background tasks (must not be null)
     * @param hookExecutionManager
     *            The hook execution manager that fires Subagent* hooks; null disables firing (no-op)
     * @param subagentBehaviorRegistry
     *            The code-behavior registry; null is treated as {@link SubagentBehaviorRegistry#empty()}
     * @param llmClient
     *            The LLM client exposed to code behaviors via the runner's gateway; null disables LLM access
     * @throws NullPointerException
     *             if subagentExecutor or executorService is null
     */
    public DefaultSubagentExecutionManager(SubagentExecutor subagentExecutor, ExecutorService executorService,
            HookExecutionManager hookExecutionManager, SubagentBehaviorRegistry subagentBehaviorRegistry,
            LlmClient llmClient) {
        this(subagentExecutor, executorService, hookExecutionManager, subagentBehaviorRegistry, llmClient,
                new InMemoryBackgroundTaskStore());
    }

    /**
     * Canonical constructor (with an explicit {@link BackgroundTaskStore}). Use this overload to inject a shared,
     * multi-instance-ready task store; the other constructors default to a node-local
     * {@link InMemoryBackgroundTaskStore}.
     *
     * @param subagentExecutor
     *            The subagent executor (must not be null)
     * @param executorService
     *            The executor service for background tasks (must not be null)
     * @param hookExecutionManager
     *            The hook execution manager that fires Subagent* hooks; null disables firing (no-op)
     * @param subagentBehaviorRegistry
     *            The code-behavior registry; null is treated as {@link SubagentBehaviorRegistry#empty()}
     * @param llmClient
     *            The LLM client exposed to code behaviors via the runner's gateway; null disables LLM access
     * @param taskStore
     *            The background task metadata store (must not be null)
     * @throws NullPointerException
     *             if subagentExecutor, executorService or taskStore is null
     */
    public DefaultSubagentExecutionManager(SubagentExecutor subagentExecutor, ExecutorService executorService,
            HookExecutionManager hookExecutionManager, SubagentBehaviorRegistry subagentBehaviorRegistry,
            LlmClient llmClient, BackgroundTaskStore taskStore) {
        // Preserve the documented contract of these explicit-taskStore constructors (@throws NPE if taskStore null);
        // the options bundle otherwise defaults a null store to an in-memory one for the no-taskStore convenience
        // ctors.
        this(subagentExecutor, hookExecutionManager, subagentBehaviorRegistry, llmClient,
                SubagentBackgroundExecutionOptions.builder().executorService(executorService)
                        .taskStore(Objects.requireNonNull(taskStore, "Background task store cannot be null")).build());
    }

    /**
     * Canonical constructor (with an explicit {@link BackgroundTaskStore}, {@link TaskStopSignal} and optional
     * {@link TaskLeaseConfig}). Use this overload in a scale-out deployment to inject a shared, multi-instance-ready
     * task store, a cross-node stop signal, and — when {@code leaseConfig} is non-null — zombie-task lease recovery;
     * the other constructors default the signal to {@link NoopTaskStopSignal} (local stops only) and leave lease
     * recovery off.
     *
     * <p>
     * This constructor subscribes to {@code taskStopSignal} for the manager's lifetime so a stop broadcast from another
     * node trips the local execution handle when this node owns the running task. The subscription is released by
     * {@link #close()}.
     *
     * <p>
     * When {@code leaseConfig} is non-null it also starts a {@link TaskHeartbeatPublisher} (renewing the lease of every
     * task this node owns) and a {@link ZombieTaskReaper} (failing heartbeat-expired non-terminal tasks whose owner is
     * presumed lost). Both daemon loops are stopped by {@link #close()}. When {@code null}, no lease threads run and
     * behaviour is byte-for-byte the prior single-node path.
     *
     * @param subagentExecutor
     *            The subagent executor (must not be null)
     * @param hookExecutionManager
     *            The hook execution manager that fires Subagent* hooks; null disables firing (no-op)
     * @param subagentBehaviorRegistry
     *            The code-behavior registry; null is treated as {@link SubagentBehaviorRegistry#empty()}
     * @param llmClient
     *            The LLM client exposed to code behaviors via the runner's gateway; null disables LLM access
     * @param options
     *            The background execution + multi-instance recovery options (executor service, task store, cross-node
     *            stop signal, optional lease config); must not be null
     * @throws NullPointerException
     *             if subagentExecutor or options is null
     */
    public DefaultSubagentExecutionManager(SubagentExecutor subagentExecutor, HookExecutionManager hookExecutionManager,
            SubagentBehaviorRegistry subagentBehaviorRegistry, LlmClient llmClient,
            SubagentBackgroundExecutionOptions options) {
        this(subagentExecutor, null, hookExecutionManager, subagentBehaviorRegistry, llmClient, options);
    }

    /**
     * The {@link LlmClient} convenience constructors' entry: the executor they just built goes in as both the executor
     * and the owned executor. The executor comes last only so this shape cannot be mistaken for the public canonical
     * constructor by overload resolution.
     */
    private DefaultSubagentExecutionManager(HookExecutionManager hookExecutionManager,
            SubagentBehaviorRegistry subagentBehaviorRegistry, LlmClient llmClient,
            SubagentBackgroundExecutionOptions options, DefaultSubagentExecutor ownedExecutor) {
        this(ownedExecutor, ownedExecutor, hookExecutionManager, subagentBehaviorRegistry, llmClient, options);
    }

    /** The shared constructor body; {@code ownedExecutor} is {@code null} unless this manager built the executor. */
    private DefaultSubagentExecutionManager(SubagentExecutor subagentExecutor, DefaultSubagentExecutor ownedExecutor,
            HookExecutionManager hookExecutionManager, SubagentBehaviorRegistry subagentBehaviorRegistry,
            LlmClient llmClient, SubagentBackgroundExecutionOptions options) {
        this.subagentExecutor = Objects.requireNonNull(subagentExecutor, "Subagent executor cannot be null");
        this.ownedExecutor = ownedExecutor;
        Objects.requireNonNull(options, "Background execution options cannot be null");
        this.executorService = options.getExecutorService();
        this.hookExecutionManager = hookExecutionManager;
        this.subagentBehaviorRegistry = subagentBehaviorRegistry != null
                ? subagentBehaviorRegistry
                : SubagentBehaviorRegistry.empty();
        this.subagentBehaviorRunner = new SubagentBehaviorRunner(llmClient, hookExecutionManager);
        this.taskStore = options.getTaskStore();
        this.taskStopSignal = options.getTaskStopSignal();
        final TaskLeaseConfig leaseConfig = options.getLeaseConfig();
        // Subscribe last: runningTasks is field-initialized before this body runs and onRemoteStop touches nothing
        // else, so a stop delivered mid-construction (impossible for loopback, async for a real bus) is safe.
        this.stopSubscription = taskStopSignal.subscribe(this::onRemoteStop);
        // §4: opt-in lease recovery. The publisher heartbeats exactly the tasks this node owns (runningTasks holds a
        // handle from submission through terminal removal), so a live node never lets its own tasks be reaped.
        if (leaseConfig != null) {
            this.heartbeatPublisher = new TaskHeartbeatPublisher(taskStore, runningTasks::taskIds, leaseConfig);
            this.zombieReaper = new ZombieTaskReaper(taskStore, leaseConfig);
            this.heartbeatPublisher.start();
            this.zombieReaper.start();
        } else {
            this.heartbeatPublisher = null;
            this.zombieReaper = null;
        }
    }

    /**
     * Creates a new DefaultSubagentExecutionManager with a default daemon thread pool.
     *
     * @param subagentExecutor
     *            The subagent executor (must not be null)
     * @throws NullPointerException
     *             if any parameter is null
     */
    public DefaultSubagentExecutionManager(SubagentExecutor subagentExecutor) {
        this(subagentExecutor, Executors.newCachedThreadPool(r -> {
            final Thread t = new Thread(r);
            t.setDaemon(true);
            t.setName("subagent-bg-" + t.getId());
            return t;
        }), null);
    }

    /** DefaultSubagentExecutionManager를 생성한다. */
    public DefaultSubagentExecutionManager(LlmClient llmClient, ToolExecutionManager toolExecutionManager,
            HookExecutionManager hookExecutionManager) {
        this(llmClient, toolExecutionManager, hookExecutionManager, SubagentBehaviorRegistry.empty());
    }

    /**
     * Creates a new DefaultSubagentExecutionManager (LlmClient convenience) with a code-behavior registry.
     *
     * @param llmClient
     *            The LLM client used to build the default {@link DefaultSubagentExecutor} (must not be null)
     * @param toolExecutionManager
     *            The tool execution manager (must not be null)
     * @param hookExecutionManager
     *            The hook execution manager; null disables Subagent* hook firing
     * @param subagentBehaviorRegistry
     *            The code-behavior registry; null is treated as {@link SubagentBehaviorRegistry#empty()}
     */
    public DefaultSubagentExecutionManager(LlmClient llmClient, ToolExecutionManager toolExecutionManager,
            HookExecutionManager hookExecutionManager, SubagentBehaviorRegistry subagentBehaviorRegistry) {
        this(hookExecutionManager, subagentBehaviorRegistry, llmClient,
                SubagentBackgroundExecutionOptions.builder().executorService(newUnboundedBackgroundExecutor())
                        .taskStore(new InMemoryBackgroundTaskStore()).build(),
                new DefaultSubagentExecutor(llmClient, toolExecutionManager, hookExecutionManager));
    }

    /**
     * Creates a new DefaultSubagentExecutionManager (LlmClient convenience) with an explicit, caller-supplied
     * {@link ExecutorService} (typically a bounded pool from {@link #newBackgroundExecutor(SubagentBackgroundConfig)},
     * and {@link BackgroundTaskStore}. This is the constructor the factory uses to bound background fan-out and to plug
     * in a multi-instance-ready task store.
     *
     * @param llmClient
     *            The LLM client used to build the default {@link DefaultSubagentExecutor} (must not be null)
     * @param toolExecutionManager
     *            The tool execution manager (must not be null)
     * @param hookExecutionManager
     *            The hook execution manager; null disables Subagent* hook firing
     * @param subagentBehaviorRegistry
     *            The code-behavior registry; null is treated as {@link SubagentBehaviorRegistry#empty()}
     * @param executorService
     *            The (typically bounded) executor service for background tasks (must not be null)
     * @param taskStore
     *            The background task metadata store (must not be null)
     */
    public DefaultSubagentExecutionManager(LlmClient llmClient, ToolExecutionManager toolExecutionManager,
            HookExecutionManager hookExecutionManager, SubagentBehaviorRegistry subagentBehaviorRegistry,
            ExecutorService executorService, BackgroundTaskStore taskStore) {
        this(hookExecutionManager, subagentBehaviorRegistry, llmClient,
                SubagentBackgroundExecutionOptions.builder().executorService(executorService)
                        .taskStore(Objects.requireNonNull(taskStore, "Background task store cannot be null")).build(),
                new DefaultSubagentExecutor(llmClient, toolExecutionManager, hookExecutionManager));
    }

    /**
     * Creates a new DefaultSubagentExecutionManager (LlmClient convenience) with an explicit
     * {@link SubagentBackgroundExecutionOptions} bundle grouping the background {@link ExecutorService},
     * {@link BackgroundTaskStore}, cross-node {@link TaskStopSignal} (design §4) and optional
     * {@link TaskLeaseConfig} zombie-recovery (design §4). This is the constructor the factory uses when a
     * scale-out bootstrap supplies a shared task store and/or a stop signal and/or lease recovery.
     *
     * @param llmClient
     *            The LLM client used to build the default {@link DefaultSubagentExecutor} (must not be null)
     * @param toolExecutionManager
     *            The tool execution manager (must not be null)
     * @param hookExecutionManager
     *            The hook execution manager; null disables Subagent* hook firing
     * @param subagentBehaviorRegistry
     *            The code-behavior registry; null is treated as {@link SubagentBehaviorRegistry#empty()}
     * @param options
     *            The background execution + multi-instance recovery options (executor service, task store, cross-node
     *            stop signal, optional lease config); must not be null
     */
    public DefaultSubagentExecutionManager(LlmClient llmClient, ToolExecutionManager toolExecutionManager,
            HookExecutionManager hookExecutionManager, SubagentBehaviorRegistry subagentBehaviorRegistry,
            SubagentBackgroundExecutionOptions options) {
        this(hookExecutionManager, subagentBehaviorRegistry, llmClient, options,
                new DefaultSubagentExecutor(llmClient, toolExecutionManager, hookExecutionManager));
    }

    /**
     * Builds a bounded daemon thread pool for background subagent execution from the given config.
     *
     * <p>
     * Replaces the previously unbounded {@code newCachedThreadPool}: at most
     * {@link SubagentBackgroundConfig#getMaxConcurrency()}
     * background subagents run at once, with a backing queue bounded by
     * {@link SubagentBackgroundConfig#getQueueCapacity()}
     * (effectively unbounded by default, so bursts queue rather than being rejected). When a finite queue saturates,
     * {@code supplyAsync} rejects and {@link #executeInBackground} settles the task as {@code FAILED} rather than
     * spawning an unbounded thread.
     *
     * @param config
     *            the background pool configuration (must not be null)
     * @return a bounded, daemon-threaded executor service
     */
    public static ExecutorService newBackgroundExecutor(SubagentBackgroundConfig config) {
        Objects.requireNonNull(config, "config cannot be null");
        final int n = config.getMaxConcurrency();
        final ThreadPoolExecutor pool = new ThreadPoolExecutor(n, n, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(config.getQueueCapacity()), backgroundThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy());
        return pool;
    }

    /**
     * Builds the legacy unbounded, cached daemon thread pool for background subagent execution. Used when no
     * {@link SubagentBackgroundConfig} is supplied (byte-for-byte the prior default behaviour) — thread count grows
     * with
     * demand and idle threads are reclaimed.
     *
     * @return an unbounded, daemon-threaded cached executor service
     */
    public static ExecutorService newUnboundedBackgroundExecutor() {
        return Executors.newCachedThreadPool(backgroundThreadFactory());
    }

    private static ThreadFactory backgroundThreadFactory() {
        return r -> {
            final Thread t = new Thread(r);
            t.setDaemon(true);
            t.setName("subagent-bg-" + t.getId());
            return t;
        };
    }

    /**
     * TRACE-01: injects the tracer that records a {@code SUBAGENT} span per run and rotates the metadata the run
     * receives so its own spans nest under it. Returns {@code this} for fluent configuration; when unset, the manager
     * keeps {@link Tracer#noop()} and behaviour is unchanged.
     *
     * <p>
     * A manager built through an {@link LlmClient} convenience constructor made its own executor, and this call sets
     * the tracer on that executor too — one call yields the whole SUBAGENT → ITERATION → {LLM, TOOL} tree. A manager
     * handed an executor sets its own tracer only: the {@link SubagentExecutor} records the run's ITERATION and TOOL
     * spans and needs its own ({@link DefaultSubagentExecutor#withTracer}), which this manager does not set behind the
     * caller's back; pass both the same instance the main executor uses. {@code OrcaAgentExecutorFactory} does this
     * for the manager it builds; a manager injected into the executor builder directly is the embedder's to wire.
     * Wiring only the manager leaves a run as a SUBAGENT span with its LLM calls directly under it.
     *
     * @param tracer
     *            the tracer (must not be null)
     * @return this manager
     */
    public DefaultSubagentExecutionManager withTracer(Tracer tracer) {
        this.tracer = Objects.requireNonNull(tracer, "tracer cannot be null");
        if (ownedExecutor != null) {
            ownedExecutor.withTracer(tracer);
        }
        return this;
    }

    /**
     * TRACE-02: injects the policy deciding whether SUBAGENT spans capture the final answer (and how much of the goal
     * they keep). When unset, the manager keeps {@link TracePayloadPolicy#summaryOnly()}. Like {@link #withTracer},
     * it reaches the executor only when this manager built that executor itself.
     *
     * @param policy
     *            the payload capture policy (must not be null)
     * @return this manager
     */
    public DefaultSubagentExecutionManager withTracePayloadPolicy(TracePayloadPolicy policy) {
        this.tracePayloadPolicy = Objects.requireNonNull(policy, "policy cannot be null");
        if (ownedExecutor != null) {
            ownedExecutor.withTracePayloadPolicy(policy);
        }
        return this;
    }

    /**
     * Makes every fork this manager launches carry its own task id as the {@link ForkAccess#TASK_ID_ATTRIBUTE}
     * execution attribute, so that several forks running at once can be told apart: by their
     * {@code SubagentStart}/{@code SubagentStop} hooks, by the hooks of the tools they call, and by an approval prompt
     * they raise. The attribute is added once at each entry point (foreground and background) before the Start hook
     * fires, and a nested fork overwrites its parent fork's id with its own. Returns {@code this} for fluent
     * configuration; when unset, a fork's execution attributes are exactly its spawner's.
     *
     * <p>
     * {@code OrcaAgentExecutorFactory} calls this only when parallel foreground tasks
     * ({@code DedicatedThreadConfig}) are enabled.
     *
     * @return this manager
     */
    public DefaultSubagentExecutionManager withForkTaskIdAttribute() {
        this.forkTaskIdAttribute = true;
        return this;
    }

    /**
     * @return {@code true} once {@link #withForkTaskIdAttribute()} was called. Package-private for tests.
     */
    boolean isForkTaskIdAttribute() {
        return forkTaskIdAttribute;
    }

    /**
     * Returns the launch context the run should use: unchanged when the task id attribute is off, otherwise a copy
     * whose execution attributes carry {@code taskId} under {@link ForkAccess#TASK_ID_ATTRIBUTE}. Called once at each
     * entry point, so the Start hook, the request handed to the executor and the Stop hook all see the same map.
     */
    private SubagentLaunchContext withTaskIdAttribute(SubagentLaunchContext launchContext, String taskId) {
        if (!forkTaskIdAttribute || taskId == null) {
            return launchContext;
        }
        final Map<String, Object> attributes = new LinkedHashMap<>(launchContext.getExecutionAttributes());
        attributes.put(ForkAccess.TASK_ID_ATTRIBUTE, taskId);
        return launchContext.toBuilder().executionAttributes(attributes).build();
    }

    /**
     * Executes a subagent from agent execution request.
     *
     * <p>
     * The subagent format is: {@code @subagent-name goal description}
     *
     * <ul>
     * <li>Subagent name: Everything after '@' until the first space (or end of string)
     * <li>Goal: Everything after the first space
     * </ul>
     *
     * @param launchContext
     *            The launch context (must not be null)
     * @param agentExecutionRequest
     *            The agent execution request containing user input and context (must not be null)
     * @param transcriptBuffer
     *            The transcript buffer (must not be null)
     * @return The subagent execution result (never null)
     * @throws NullPointerException
     *             if launchContext or agentExecutionRequest is null
     * @throws IllegalArgumentException
     *             if user input doesn't start with '@'
     */
    @Override
    public SubagentExecutionResult execute(SubagentLaunchContext launchContext,
            AgentExecutionRequest agentExecutionRequest, TranscriptBuffer transcriptBuffer) {
        Objects.requireNonNull(launchContext, "Launch context cannot be null");
        Objects.requireNonNull(agentExecutionRequest, "Agent execution request cannot be null");

        final String userInput = agentExecutionRequest.getUserInput().asText();
        final String trimmed = userInput.trim();
        if (!trimmed.startsWith("@")) {
            throw new IllegalArgumentException("Subagent request must start with '@'");
        }

        final ParsedSubagentRequest parsed = parseSubagentRequest(trimmed);

        // Generate unique task ID
        final String taskId = UUID.randomUUID().toString();

        // This overload is the only entry point handed the caller's TranscriptBuffer, so it is the only one that can
        // name the invoking session when the launch context did not already carry it. Binding it here lets the
        // subagent inherit the approvals the user granted in that session; a null memory (callers that have no
        // session) leaves the launch context untouched and the run inherits nothing.
        final SubagentLaunchContext effectiveLaunchContext = transcriptBuffer == null
                ? launchContext
                : launchContext.toBuilder().invokingSessionId(transcriptBuffer.getSessionId()).build();

        return execute(effectiveLaunchContext, taskId, parsed.name(), parsed.goal(), "");
    }

    @Override
    public SubagentExecutionResult execute(SubagentLaunchContext launchContext, String taskId, String subagentName,
            String goal, String description) {
        Objects.requireNonNull(launchContext, "Launch context cannot be null");
        // Foreground execution observes the parent execution's cancellation signal directly (the parent is a session's
        // turn on the main loop, or another fork when a subagent launches one). Its output is returned inline (not
        // tailed), so it streams to a no-op sink. The subagent is resolved by name from the launch context's registry.
        return runExecute(launchContext, taskId, SubagentTarget.byName(subagentName), goal, description,
                launchContext.getCancellationSignal(), SubagentOutputSink.NO_OP);
    }

    @Override
    public SubagentExecutionResult execute(SubagentLaunchContext launchContext, Subagent subagent, String goal) {
        Objects.requireNonNull(launchContext, "Launch context cannot be null");
        Objects.requireNonNull(subagent, "Subagent cannot be null");
        Objects.requireNonNull(goal, "Goal cannot be null");
        // Inline (code-defined) subagent: run it once in the foreground without a registry lookup. A unique task id is
        // generated for hook/attribution tracking; cancellation follows the launch context's parent-execution signal;
        // the result is returned inline, so the output streams to a no-op sink. A SubagentTarget.inline(...) makes
        // runExecute skip launchContext.getSubagentRegistry() while sharing every other forwarding/dispatch detail with
        // the name-based path.
        final String taskId = UUID.randomUUID().toString();
        return runExecute(launchContext, taskId, SubagentTarget.inline(subagent), goal, "",
                launchContext.getCancellationSignal(), SubagentOutputSink.NO_OP);
    }

    @Override
    public SubagentExecutionResult executeInline(SubagentLaunchContext launchContext, String taskId, Subagent subagent,
            String goal, String description) {
        Objects.requireNonNull(launchContext, "Launch context cannot be null");
        Objects.requireNonNull(taskId, "Task id cannot be null");
        Objects.requireNonNull(subagent, "Subagent cannot be null");
        Objects.requireNonNull(goal, "Goal cannot be null");
        Objects.requireNonNull(description, "Description cannot be null");
        // The name-based foreground path with the lookup already done: same signal, same no-op sink, same taskId and
        // description into hooks and task records. Only the resolution differs, which is the point — the caller hands
        // over a definition it has already adjusted.
        return runExecute(launchContext, taskId, SubagentTarget.inline(subagent), goal, description,
                launchContext.getCancellationSignal(), SubagentOutputSink.NO_OP);
    }

    /**
     * Shared execution body for the foreground and background paths. The effective cancellation signal is injected as
     * the subagent's parent signal: foreground passes the launch context's signal; background passes the per-task
     * coordinator's signal so a {@code Task.stop} can cancel just that task.
     *
     * @param target
     *            identifies the subagent to run: an inline (code-defined) instance used as-is, or a registry name
     *            resolved against the launch context's {@link SubagentRegistry}
     * @param cancellationSignal
     *            the effective cancellation signal to inject as the subagent's parent signal (must not be null)
     * @param outputSink
     *            the live output sink to stream progress to (must not be null; {@link SubagentOutputSink#NO_OP} for
     *            foreground)
     */
    private SubagentExecutionResult runExecute(SubagentLaunchContext requestedLaunchContext, String taskId,
            SubagentTarget target, String goal, String description, CancellationSignal cancellationSignal,
            SubagentOutputSink outputSink) {
        // Label the fork with its own task id (when enabled) before anything reads the attributes, so the Start hook,
        // the executor's request and the Stop hook see the same map.
        final SubagentLaunchContext launchContext = withTaskIdAttribute(requestedLaunchContext, taskId);
        // The deadline is anchored here, at launch, before the Start hook runs: a slow hook spends the fork's time.
        final PolicyRun policyRun = PolicyRun.anchor(launchContext);
        // Foreground path: fire SubagentStart on the calling thread, then run the body. The background path
        // (executeInBackground) fires SubagentStart itself BEFORE dispatch — so the launch is observed on the launching
        // thread, immediately and in order, rather than late on a pool worker — and calls runResolvedSubagent directly.
        fireSubagentStart(launchContext, taskId, target.name(), goal, description, cancellationSignal);
        if (policyRun.deadline == null) {
            return runResolvedSubagent(launchContext, taskId, target, goal, cancellationSignal, outputSink,
                    RunMode.foreground(), policyRun);
        }
        // A fork under a deadline runs on a thread of its own and is waited for at most its grace period past the
        // deadline (or past a cancellation), so a tool that ignores both the signal and interrupts cannot hold the
        // spawner forever. SubagentStop fires here, with whichever result the wait produced.
        final SubagentExecutionResult result = runWatched(launchContext, taskId, target.name(), cancellationSignal,
                policyRun, () -> runSubagentBody(launchContext, taskId, target, goal, cancellationSignal, outputSink,
                        RunMode.foreground(), policyRun));
        fireSubagentStop(launchContext, taskId, target.name(), result, cancellationSignal);
        return result;
    }

    /**
     * Runs {@code body} on a dedicated thread and waits for it until it returns, or until it has been asked to stop —
     * its deadline passed, or {@code cancellationSignal} tripped — and its grace period has run out since.
     *
     * <p>
     * Asked to stop, the run's thread is interrupted as well (it is this run's own thread, so the interrupt cannot land
     * on anyone else's work); the run's executor trips its own signal at the deadline. A run that has not returned when
     * the grace runs out is not waited for any longer: the caller gets a result that says so
     * ({@link SubagentExecutionResult#isExitConfirmed()} {@code false}), the run is listed by {@link #lingeringRuns()}
     * until it returns, and what it eventually returns is logged and dropped. The thread is never stopped by force.
     *
     * <p>
     * An interrupt of the waiting thread — a spawner's cancellation reaching it through the Task tool's terminator —
     * is a request to stop, not a reason to abandon the run: the wait goes on for the grace period, and the interrupt
     * is restored before this returns.
     */
    private SubagentExecutionResult runWatched(SubagentLaunchContext launchContext, String taskId, String subagentName,
            CancellationSignal cancellationSignal, PolicyRun policyRun, Supplier<SubagentExecutionResult> body) {
        final Instant startTime = Instant.now();
        final CompletableFuture<SubagentExecutionResult> outcome = new CompletableFuture<>();
        final CompletableFuture<CompletionReason> stopRequested = new CompletableFuture<>();
        final Thread worker = new Thread(() -> {
            try {
                outcome.complete(body.get());
            } catch (Throwable t) {
                outcome.completeExceptionally(t);
            }
        }, "subagent-fg-" + taskId);
        worker.setDaemon(true);
        final DeadlineScheduler.Handle timer = DeadlineScheduler.schedule(policyRun.deadline.remaining(),
                () -> stopRequested.complete(CompletionReason.WALL_CLOCK_EXCEEDED));
        final CancellationSignal.Registration cancelReg = cancellationSignal
                .onCancel(() -> stopRequested.complete(CompletionReason.INTERRUPTED));
        boolean callerInterrupted = false;
        try {
            worker.start();
            while (!outcome.isDone() && !stopRequested.isDone()) {
                try {
                    CompletableFuture.anyOf(outcome, stopRequested).get();
                } catch (InterruptedException e) {
                    callerInterrupted = true;
                    stopRequested.complete(CompletionReason.INTERRUPTED);
                } catch (ExecutionException e) {
                    // The run threw; read below from the outcome itself.
                    break;
                }
            }
            if (!outcome.isDone()) {
                worker.interrupt();
                final long graceEnd = System.nanoTime() + policyRun.policy.getExitGracePeriod().toNanos();
                while (!outcome.isDone()) {
                    final long left = graceEnd - System.nanoTime();
                    if (left <= 0) {
                        break;
                    }
                    try {
                        outcome.get(left, TimeUnit.NANOSECONDS);
                    } catch (InterruptedException e) {
                        callerInterrupted = true;
                    } catch (ExecutionException | TimeoutException e) {
                        break;
                    }
                }
            }
            if (outcome.isDone()) {
                return settled(outcome, startTime);
            }
            final CompletionReason stopReason = stopRequested.getNow(CompletionReason.INTERRUPTED);
            return linger(launchContext, taskId, subagentName, stopReason, policyRun, outcome, false, startTime);
        } finally {
            timer.close();
            cancelReg.remove();
            if (callerInterrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** The result a finished watched run produced, or a failure result for one that threw. */
    private SubagentExecutionResult settled(CompletableFuture<SubagentExecutionResult> outcome, Instant startTime) {
        try {
            return outcome.join();
        } catch (RuntimeException e) {
            final Throwable cause = e.getCause() != null ? e.getCause() : e;
            return createFailureResult(startTime, "Subagent execution error: " + cause.getMessage());
        }
    }

    /**
     * Records a run that had not exited when its grace ran out, and returns the result that says so. The record is
     * removed when the run returns; what it returns then is logged and dropped — its caller has already been answered.
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    private SubagentExecutionResult linger(SubagentLaunchContext launchContext, String taskId, String subagentName,
            CompletionReason stopReason, PolicyRun policyRun, CompletableFuture<SubagentExecutionResult> outcome,
            boolean background, Instant startTime) {
        final LingeringSubagentRun record = LingeringSubagentRun.builder().taskId(taskId).subagentName(subagentName)
                .agentRuntimeId(launchContext.getAgentRuntimeId()).stopReason(stopReason).background(background)
                .reportedAt(Instant.now()).build();
        lingering.put(taskId, record);
        log.warn(
                "Subagent '{}' (taskId={}) was asked to stop ({}) and had not exited {} later; it is left to finish"
                        + " and listed as lingering until it does",
                subagentName, taskId, stopReason, policyRun.policy.getExitGracePeriod());
        outcome.whenComplete((late, error) -> {
            lingering.remove(taskId, record);
            log.info("Lingering subagent '{}' (taskId={}) exited: {}", subagentName, taskId,
                    error != null ? error.toString() : late.getCompletionReason());
        });
        final String message = "Execution stopped: " + stopReason.name() + " — the subagent was asked to stop and had"
                + " not exited " + policyRun.policy.getExitGracePeriod() + " later; it may still be running, so its"
                + " work is not complete and must not be relaunched while it is";
        return SubagentExecutionResult
                .failure(
                        message, SessionSnapshot.of(SessionId.generate()), ExecutionMetadata.builder().iterationCount(0)
                                .tokenUsage(TokenUsage.empty()).timestamps(startTime, Instant.now()).build(),
                        stopReason, null)
                .withExitUnconfirmed();
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * What the executor enforces. A subagent backed by a code behavior enforces none, and a policy for one is refused
     * when it runs.
     */
    @Override
    public Set<SubagentPolicyCapability> supportedPolicyCapabilities() {
        return subagentExecutor.supportedPolicyCapabilities();
    }

    @Override
    public List<LingeringSubagentRun> lingeringRuns() {
        return List.copyOf(lingering.values());
    }

    /**
     * Runs the resolved subagent (or its registered code behavior) and fires the SubagentStop hook. The SubagentStart
     * hook is fired by the caller — {@link #runExecute} for the foreground path, {@link #executeInBackground} for the
     * background path — so a background launch can be observed on the launching thread rather than a pool worker.
     *
     * <p>
     * TRACE-01: the run is wrapped in a {@code SUBAGENT} span under the span the parent metadata points at, and the
     * metadata handed on is rotated to point at that span — before the executor merges it with its subagent defaults,
     * so the rotated tags are the ones inherited. Every run passes through here (foreground and background, ReAct and
     * code behavior, Task, Workflow and fork-mode skills), which is why the span opens here: a run whose subagent
     * cannot be resolved or is refused by the caller ceiling still leaves an ERROR span. The span closes before the
     * SubagentStop hook fires. A background run opens it on the worker, after the launching tool call's span has
     * closed; it still nests under that span by id, and records how long it waited for that worker as
     * {@code queuedMs}.
     *
     * @param mode
     *            whether this run is a background task, and when it was queued (recorded on the span)
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    private SubagentExecutionResult runResolvedSubagent(SubagentLaunchContext launchContext, String taskId,
            SubagentTarget target, String goal, CancellationSignal cancellationSignal, SubagentOutputSink outputSink,
            RunMode mode, PolicyRun policyRun) {
        final SubagentExecutionResult result = runSubagentBody(launchContext, taskId, target, goal, cancellationSignal,
                outputSink, mode, policyRun);
        fireSubagentStop(launchContext, taskId, target.name(), result, cancellationSignal);
        return result;
    }

    /**
     * {@link #runResolvedSubagent} without the SubagentStop hook: resolves and runs the subagent inside its SUBAGENT
     * span. Split out so a watched foreground run fires the hook with the result its wait produced, once.
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    private SubagentExecutionResult runSubagentBody(SubagentLaunchContext launchContext, String taskId,
            SubagentTarget target, String goal, CancellationSignal cancellationSignal, SubagentOutputSink outputSink,
            RunMode mode, PolicyRun policyRun) {
        final Instant startTime = Instant.now();
        final String subagentName = target.name();

        // Snapshot the volatile tracing fields once so the span's inputs and outputs follow one policy.
        final TracePayloadPolicy payloadPolicy = tracePayloadPolicy;
        final LlmCallMetadata parentMetadata = launchContext.getParentLlmCallMetadata();
        final Tracer.Span subagentSpan = openSubagentSpan(launchContext, subagentName, taskId, goal, mode,
                payloadPolicy);
        if (subagentSpan != null) {
            mode.queued().ifPresent(queued -> subagentSpan.setAttribute("queuedMs", String.valueOf(queued.toMillis())));
        }
        final LlmCallMetadata forwardedMetadata = subagentSpan != null
                ? Spans.enrichSafely(subagentSpan, parentMetadata)
                : parentMetadata;

        SubagentExecutionResult result = null;
        try {
            // Resolve the subagent: an inline (code-defined) subagent is used as-is; otherwise look it up by name in
            // the launch context's registry. The lookup stays inside this try so an unknown name still yields a failure
            // result with SubagentStart/Stop hooks fired around it (unchanged registry-path behaviour).
            final Subagent resolved = target.inline() != null
                    ? target.inline()
                    : launchContext.getSubagentRegistry().getSubagent(subagentName)
                            .orElseThrow(() -> new SubagentNotFoundException(subagentName));

            // Impose the spawning run's allow-list as a ceiling. Without this a delegation is an escalation: an agent
            // narrowed to Read, Grep could reach Bash by launching a subagent that names it, and the narrowing would
            // describe only what the agent does itself rather than what it can cause. Applied here rather than at
            // either branch below because this is the one place both of them pass through — the ReAct loop and a
            // registered code behavior receive the same narrowed definition.
            final Subagent subagent = applyCallerCeiling(launchContext, resolved);

            // A policy is enforced as written or not at all. Refused here, before the fork starts, when the executor
            // cannot enforce one of its limits, when a code behavior would run instead of the loop that enforces them,
            // or when it asks for the spawner's context engine and none was handed down.
            final Optional<SubagentBehavior> behavior = subagentBehaviorRegistry.getBehavior(subagentName);
            final ContextEngine inheritedEngine = checkPolicy(launchContext, policyRun, subagentName,
                    behavior.isPresent());

            // Build execution context (how to execute). The effective cancellation signal is forwarded so a
            // parent-initiated (or per-task stop) cancel cascades into the subagent's ReAct loop and its cooperative
            // tools. The model override (Task tool's `model` arg) is forwarded so it wins over the subagent frontmatter
            // and default model. The knowledge store/scope and tool-context enrichers are forwarded so subagent tools
            // run with the same context keys as the main-agent tools.
            final SubagentExecutionContext executionContext = SubagentExecutionContext.builder()
                    .agentRuntimeId(launchContext.getAgentRuntimeId()).subagent(subagent)
                    .toolRegistry(launchContext.getToolRegistry()).hookRegistry(launchContext.getHookRegistry())
                    .defaultModel(launchContext.getDefaultModel())
                    .modelOverride(launchContext.getModelOverride().orElse(null))
                    .parentCancellationSignal(cancellationSignal)
                    .knowledgeStore(launchContext.getKnowledgeStore().orElse(null))
                    .knowledgeScope(launchContext.getKnowledgeScope().orElse(null))
                    .toolContextEnrichers(launchContext.getToolContextEnrichers()).outputSink(outputSink)
                    .executionEnvironment(launchContext.getExecutionEnvironment().orElse(null))
                    .executionEnvironmentProvider(launchContext.getExecutionEnvironmentProvider().orElse(null))
                    .contextEngine(inheritedEngine).build();

            // Build execution request (what to execute). The parent's LLM call metadata is forwarded so the subagent
            // executor can merge it with subagent-derived defaults (component/feature) and emit attributed usage. The
            // principal is forwarded so subagent tools observe the same caller identity as the main-agent tools. When a
            // resume snapshot was loaded (Task resume=<taskId>) it is forwarded as previousSnapshot so the
            // executor rehydrates the prior transcript via TranscriptBuffer.fromSnapshot and appends the new goal.
            // The invoking session is forwarded so skill approvals the user granted in the session that
            // spawned this run apply to it: the fork mints its own session id, under which nothing was ever
            // granted, and it can never prompt for one of its own.
            final SubagentExecutionRequest request = SubagentExecutionRequest.builder().taskId(taskId).goal(goal)
                    .executionAttributes(launchContext.getExecutionAttributes()).llmCallMetadata(forwardedMetadata)
                    .principal(launchContext.getPrincipal().orElse(null))
                    .invokingSessionId(launchContext.getInvokingSessionId().orElse(null))
                    .previousSnapshot(launchContext.getPreviousSnapshot().orElse(null))
                    // The spawning turn's quota guard, so the fork is charged to the turn's keys (usage-quota §4.3).
                    .quotaGuard(launchContext.getQuotaGuard()).forkDepth(launchContext.getForkDepth())
                    // A fork-mode skill's body, rendered again in the fork's environment when that is not the
                    // spawner's (EE-81).
                    .forkGoalRenderer(launchContext.getForkGoalRenderer().orElse(null))
                    // The policy, unchanged, with its iteration and token caps as the fork's budget, and the deadline
                    // anchored at launch.
                    .executionPolicy(policyRun.policy)
                    .budget(policyRun.policy == null ? null : policyRun.policy.toBudget()).deadline(policyRun.deadline)
                    .build();

            // Execute subagent. A code behavior registered for this name REPLACES the ReAct loop, receiving the SAME
            // context/request (cancellation signal, principal, metadata, tools). When absent, the data subagent runs
            // the unchanged ReAct path — origin-agnostic execution among data subagents is preserved.
            // SubagentStart/Stop hooks and this try/catch error shaping apply to both branches.
            if (subagentSpan != null) {
                subagentSpan.setAttribute("codeBehavior", String.valueOf(behavior.isPresent()));
            }
            result = behavior.isPresent()
                    ? subagentBehaviorRunner.run(behavior.get(), executionContext, request)
                    : subagentExecutor.execute(executionContext, request);
        } catch (SubagentException e) {
            result = createFailureResult(startTime, e.getMessage());
        } catch (Exception e) {
            result = createFailureResult(startTime, "Subagent execution error: " + e.getMessage());
        } finally {
            // A real finally: an Error escaping the catches above still closes the span.
            closeSubagentSpan(subagentSpan, result, payloadPolicy);
        }
        return result;
    }

    /**
     * Refuses a policy that cannot be enforced as written, and returns the spawner's context engine when the policy
     * inherits it.
     *
     * @return the engine the fork runs under in place of its executor's own, or {@code null} to keep the executor's
     * @throws SubagentSpawnException
     *             if the policy cannot be enforced for this subagent
     */
    private ContextEngine checkPolicy(SubagentLaunchContext launchContext, PolicyRun policyRun, String subagentName,
            boolean codeBehavior) {
        final SubagentExecutionPolicy policy = policyRun.policy;
        if (policy == null) {
            return null;
        }
        if (codeBehavior) {
            throw new SubagentSpawnException(String.format(
                    "Subagent '%s' cannot run under execution policy '%s': it"
                            + " is backed by a code behavior, which enforces none of the policy's limits",
                    subagentName, policy.getRole()));
        }
        final Set<SubagentPolicyCapability> missing = policy
                .unsupportedBy(subagentExecutor.supportedPolicyCapabilities());
        if (!missing.isEmpty()) {
            throw new SubagentSpawnException(String.format(
                    "Subagent '%s' cannot run under execution policy '%s': the" + " executor does not enforce %s",
                    subagentName, policy.getRole(), missing));
        }
        if (!policy.isInheritContextEngine()) {
            return null;
        }
        return launchContext.getParentContextEngine()
                .orElseThrow(() -> new SubagentSpawnException(String.format("Subagent '%s' cannot run under execution"
                        + " policy '%s': it inherits the spawner's context engine and the launch handed down none",
                        subagentName, policy.getRole())));
    }

    /**
     * Opens the SUBAGENT span of one run under the span the launch context's parent metadata points at, with the
     * attributes every run records. The run path and the pool-rejection path both open it here, so a rejected task's
     * span has the same parent, inputs and attributes as one that ran.
     *
     * @return the span, or {@code null} when the parent metadata names no parent (tracing off)
     */
    private Tracer.Span openSubagentSpan(SubagentLaunchContext launchContext, String subagentName, String taskId,
            String goal, RunMode mode, TracePayloadPolicy payloadPolicy) {
        final Tracer.Span span = Spans
                .startChildOfLazily(tracer, launchContext.getParentLlmCallMetadata(), SpanType.SUBAGENT,
                        "subagent:" + subagentName, () -> subagentSpanInputs(subagentName, taskId, goal, payloadPolicy))
                .orElse(null);
        if (span != null) {
            span.setAttribute("invokerType", InvokerType.SUBAGENT.name());
            span.setAttribute("subagent", subagentName);
            span.setAttribute("taskId", taskId);
            span.setAttribute("background", String.valueOf(mode.background));
            span.setAttribute("resumed", String.valueOf(launchContext.getPreviousSnapshot().isPresent()));
            if (mode.rejected) {
                span.setAttribute("rejected", "true");
            }
        }
        return span;
    }

    /**
     * Records the run's outcome on its SUBAGENT span — and the fork's execution id, when the result carries one — then
     * closes it. A {@code null} span (tracing off) is ignored.
     *
     * @param result
     *            the run's result, or {@code null} when an {@link Error} escaped before one was produced
     */
    private static void closeSubagentSpan(Tracer.Span span, SubagentExecutionResult result, TracePayloadPolicy policy) {
        if (span == null) {
            return;
        }
        if (result != null) {
            result.getExecutionId().ifPresent(id -> span.setAttribute("executionId", id.value()));
        }
        recordSubagentOutcome(span, result, policy);
        span.close();
    }

    /**
     * Builds the SUBAGENT span inputs. {@code goalChars} is always recorded; the (truncated) goal text only when
     * {@code policy} captures content — the same rule the final answer follows, so a summary-only deployment keeps
     * delegated prompts out of the trace store.
     */
    private static Map<String, Object> subagentSpanInputs(String subagentName, String taskId, String goal,
            TracePayloadPolicy policy) {
        final Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("subagent", subagentName);
        inputs.put("taskId", taskId);
        inputs.put("goalChars", goal == null ? 0 : goal.length());
        if (policy.capturesContent() && goal != null) {
            inputs.put("goal", policy.truncate(goal));
        }
        return inputs;
    }

    /**
     * Records the SUBAGENT span outcome: success, status, completion reason, iterations and final-answer size always;
     * the (truncated) final answer only when {@code policy} captures content; and the run's accumulated token usage,
     * which its executor already summed, so the child LLM spans are not added up again.
     *
     * <p>
     * The status follows the rule the TURN span follows — only a genuine error is ERROR. A cancelled run is
     * INTERRUPTED; a run a budget, the iteration cap or a quota stopped is OK, with the reason in the outputs.
     *
     * @param result
     *            the run's result, or {@code null} when an {@link Error} escaped before one was produced
     */
    private static void recordSubagentOutcome(Tracer.Span span, SubagentExecutionResult result,
            TracePayloadPolicy policy) {
        if (result == null) {
            span.error("Subagent execution did not complete");
            return;
        }
        final String finalAnswer = result.getFinalAnswer();
        final Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("success", result.isSuccess());
        outputs.put("status", result.getStatus());
        outputs.put("completionReason", String.valueOf(result.getCompletionReason()));
        outputs.put("iterations", result.getIterationCount());
        outputs.put("finalAnswerChars", finalAnswer == null ? 0 : finalAnswer.length());
        if (policy.capturesContent() && finalAnswer != null) {
            outputs.put("finalAnswer", policy.truncate(finalAnswer));
        }
        if (!result.isSuccess() && result.getErrorMessage() != null) {
            outputs.put("errorMessage", result.getErrorMessage());
        }
        span.setOutputs(outputs);
        if (result.getMetadata() != null && result.getMetadata().hasTokenUsage()) {
            span.setTokenUsage(result.getMetadata().getTokenUsage());
        }
        final CompletionReason reason = result.getCompletionReason();
        if (reason == CompletionReason.INTERRUPTED) {
            span.interrupted();
        } else if (!result.isSuccess() && !isBoundedStop(reason)) {
            span.error(result.getErrorMessage());
        }
    }

    /**
     * Returns whether {@code reason} is a stop a configured bound imposed rather than a failure: the run did what it
     * was allowed to, and the outputs say which bound ended it.
     */
    private static boolean isBoundedStop(CompletionReason reason) {
        return switch (reason) {
            case MAX_ITERATIONS, TOKEN_BUDGET_EXCEEDED, COST_BUDGET_EXCEEDED, WALL_CLOCK_EXCEEDED,
                    TOOL_CALL_BUDGET_EXCEEDED, QUOTA_EXCEEDED ->
                true;
            default -> false;
        };
    }

    /**
     * Narrows a resolved subagent by the allow-list of the run spawning it, refusing the run when the two lists admit
     * nothing in common.
     *
     * <p>
     * The refusal is the reason {@link AllowedTools#intersect} returns an {@link Optional} rather than a list: an
     * empty allow-list reads as <em>unrestricted</em> everywhere in the permission package, so handing on the
     * intersection of two disjoint lists as an empty list would invert the strictest possible pairing into the
     * loosest. It names both lists, because the mismatch is a configuration error and fixing it means seeing both
     * sides.
     *
     * <p>
     * A caller that imposes no ceiling leaves the subagent exactly as resolved, which is what every caller did before
     * the ceiling existed.
     *
     * @param launchContext
     *            the launch context carrying the caller's allow-list
     * @param subagent
     *            the resolved subagent
     * @return the subagent bound by both lists
     * @throws SubagentSpawnException
     *             if the two allow-lists have nothing in common
     */
    private Subagent applyCallerCeiling(SubagentLaunchContext launchContext, Subagent subagent) {
        final List<AllowedTool> ceiling = launchContext.getCallerAllowedTools();
        if (ceiling.isEmpty()) {
            return subagent;
        }
        final List<AllowedTool> own = subagent.getAllowedTools();
        final Optional<List<AllowedTool>> effective = AllowedTools.intersect(ceiling, own);
        if (effective.isEmpty()) {
            throw new SubagentSpawnException(
                    String.format("Subagent '%s' cannot run: its allowed-tools and the caller's have nothing in common "
                            + "(caller allows %s, subagent allows %s)", subagent.getName(), ceiling, own));
        }
        return SubagentToolScope.withAllowedTools(subagent, effective.get());
    }

    /**
     * Persists the finished subagent's session snapshot keyed by {@code taskId} so a later {@code Task} invocation
     * with {@code resume=<taskId>} can reload it.
     *
     * <p>
     * Only the <b>background</b> path calls this: a foreground run's {@code taskId} is never surfaced to the caller
     * (only {@code executeInBackground} prints "Task ID: ..."), so a foreground snapshot would be unreachable — saving
     * it would be dead work that only feeds memory growth. It is also skipped for an <b>empty</b> transcript (e.g. a
     * dispatch failure such as an unknown {@code subagent_name}, whose result carries an empty snapshot); persisting
     * one would make {@code resume} silently start a fresh session instead of reporting the task unresumable.
     *
     * <p>
     * The snapshot is tagged with {@code subagentName} so resume can reject a mismatched {@code subagent_name}.
     * Best-effort: a snapshot-store failure must never change the returned result, so routine backend errors are logged
     * and swallowed. No-op when no snapshot store was configured (resume disabled).
     */
    private void saveSessionSnapshot(SubagentLaunchContext launchContext, String taskId, String subagentName,
            SubagentExecutionResult result) {
        final SessionSnapshot snapshot = result.getSnapshot();
        if (snapshot.getConversationHistory().isEmpty()) {
            // Nothing resumable to persist (dispatch failure / no iterations ran); leave the id unresumable.
            return;
        }
        launchContext.getSessionSnapshotStore().ifPresent(store -> {
            try {
                // Tag the transcript with the owning agent runtime so a later Task(resume=<taskId>) can be
                // confined to the caller's own context — one agent must not resume (and thereby read) another's
                // transcript merely by knowing its globally-unique task id.
                store.save(taskId, subagentName, launchContext.getAgentRuntimeId(), snapshot);
            } catch (RuntimeException e) {
                log.warn("Failed to save session snapshot for taskId={}: {}", taskId, e.getMessage());
            }
        });
    }

    /**
     * Persists what a background task finally produced, so {@code AgentOutput} can read it back from any node and after
     * a restart rather than only from this JVM's heap.
     *
     * <p>
     * <b>Called before the terminal state transition</b>, which is the ordering
     * {@link at.aimon.core.subagent.task.TaskResultStore} documents: a reader that observes a terminal state may then
     * conclude the result is already visible, so a terminal task with no stored result means the task produced none
     * rather than "not written yet".
     *
     * <p>
     * Nothing is fabricated for a task that produced no result — a future that completed exceptionally leaves the slot
     * empty, and {@code AgentOutput} reports the failure from the task's terminal state exactly as it did when the
     * result lived in a future. Best-effort: a store failure must never change the result the task hands back, so
     * routine backend errors are logged and swallowed. No-op when no result store was configured.
     */
    private void saveTaskResult(SubagentLaunchContext launchContext, String taskId, SubagentExecutionResult result) {
        if (result == null) {
            return;
        }
        launchContext.getTaskResultStore().ifPresent(store -> {
            try {
                store.save(taskId, TaskResult.from(result));
            } catch (RuntimeException e) {
                log.warn("Failed to save task result for taskId={}: {}", taskId, e.getMessage());
            }
        });
    }

    // Both subagent hooks fire in the spawning execution's registry. The environment they carry differs (EE-52):
    // - subagentStart carries the spawner's (SubagentLaunchContext#getExecutionEnvironment). The fork's has not been
    // resolved yet, and resolving it here would mint the fork's identity early and move the single resolution site.
    // - subagentStop carries the fork's, stamped on the result by DefaultSubagentExecutor, and falls back to the
    // spawner's on the paths that never resolved one (a code behavior, an unknown subagent, a caller-ceiling refusal,
    // an exception, a pool rejection). The fork's environment is still open: core never closes one, and the stop
    // fires before control returns to the spawning tool.
    // Both are empty when a runtime-level runner spawned the fork from no calling execution, and then the firing is
    // marked as having no execution behind it (spawnedOutsideExecution) — for subagentStop only when the fork never
    // resolved an environment either, since a fork that ran had an execution of its own. A hooks.json command with no
    // "shell" key runs on the host for such a firing. The launch context cannot say whether a calling execution
    // exists, only whether it carries an environment, so a hand-built ToolContext with no environment that calls Task
    // is read as "no execution" too (documented on HookContext#hasExecution, #273 review).
    //
    // The cancellation signal they carry is the one that governs the fork, which is the answer to "whose work is this
    // hook doing": the spawner's for a foreground fork, the per-task coordinator's for a background one — it cascades
    // from the spawner's and is also what Task.stop trips. Both events report, so the contexts hand it out only while
    // it has not tripped: a fork that ended because the signal tripped still gets its stop recorded.
    private void fireSubagentStart(SubagentLaunchContext launchContext, String taskId, String subagentName, String goal,
            String description, CancellationSignal governingSignal) {
        if (hookExecutionManager == null) {
            return;
        }
        try {
            final SubagentStartContext ctx = SubagentStartContext.builder().invokerType(InvokerType.MAIN_AGENT)
                    .invokerName(subagentName).hookRegistry(launchContext.getHookRegistry())
                    .executionEnvironment(launchContext.getExecutionEnvironment().orElse(null))
                    .spawnedOutsideExecution(launchContext.getExecutionEnvironment().isEmpty())
                    .executionCancellation(governingSignal).subagentName(subagentName).taskId(taskId).goal(goal)
                    .description(description).executionAttributes(launchContext.getExecutionAttributes()).build();
            hookExecutionManager.executeSubagentStart(ctx);
        } catch (Exception e) {
            log.warn("SubagentStart hook failed for subagent '{}', taskId={}: {}", subagentName, taskId,
                    e.getMessage());
        }
    }

    private void fireSubagentStop(SubagentLaunchContext launchContext, String taskId, String subagentName,
            SubagentExecutionResult result, CancellationSignal governingSignal) {
        if (hookExecutionManager == null) {
            return;
        }
        try {
            final Optional<ExecutionEnvironment> environment = result.getExecutionEnvironment()
                    .or(launchContext::getExecutionEnvironment);
            final SubagentStopContext ctx = SubagentStopContext.builder().invokerType(InvokerType.MAIN_AGENT)
                    .invokerName(subagentName).hookRegistry(launchContext.getHookRegistry())
                    .executionEnvironment(environment.orElse(null)).spawnedOutsideExecution(environment.isEmpty())
                    .executionCancellation(governingSignal).subagentName(subagentName).taskId(taskId)
                    .success(result.isSuccess()).errorMessage(result.isSuccess() ? null : result.getErrorMessage())
                    .executionAttributes(launchContext.getExecutionAttributes()).build();
            hookExecutionManager.executeSubagentStop(ctx);
        } catch (Exception e) {
            log.warn("SubagentStop hook failed for subagent '{}', taskId={}: {}", subagentName, taskId, e.getMessage());
        }
    }

    @Override
    public CompletableFuture<SubagentExecutionResult> executeInBackground(SubagentLaunchContext requestedLaunchContext,
            String taskId, String subagentName, String goal, String description) {
        Objects.requireNonNull(requestedLaunchContext, "Launch context cannot be null");
        // Same labelling as the foreground entry point (runExecute), for the same reason.
        final SubagentLaunchContext launchContext = withTaskIdAttribute(requestedLaunchContext, taskId);
        final Instant startTime = Instant.now();
        // Anchored at launch: the wait for a worker counts against the deadline, and a task whose deadline passes in
        // the
        // queue does not start when a worker finally takes it.
        final PolicyRun policyRun = PolicyRun.anchor(launchContext);

        // Record a durable PENDING snapshot up front so the task is listable/queryable even while it waits for a
        // worker (bounded pool). The worker flips it to RUNNING when it actually starts.
        final BackgroundTask snapshot = BackgroundTask.builder().taskId(taskId).subagentName(subagentName)
                .description(description).state(BackgroundTaskState.PENDING).startTime(startTime)
                .lastHeartbeat(startTime).owner(launchContext.getPrincipal().orElse(null))
                .agentRuntimeId(launchContext.getAgentRuntimeId()).build();
        taskStore.put(snapshot);

        // Per-task interrupt coordinator whose signal is injected as the subagent's parent signal, so Task.stop can
        // cancel just this task. Cascade the launch context's parent-execution signal into it so a parent cancel still
        // propagates.
        final InterruptCoordinator coordinator = new DefaultInterruptCoordinator();
        // Cascade the launch context's parent-execution signal into this task's coordinator. Retain the registration so
        // both terminal paths (finalizeBackgroundTask and the pool-rejection path) can deregister it — otherwise each
        // background Task launched in an execution would leave a listener on the per-execution signal, accumulating
        // one closed coordinator per task for the rest of that execution.
        final CancellationSignal.Registration parentCancelReg = launchContext.getCancellationSignal().onCancel(
                () -> coordinator.requestInterrupt(at.aimon.core.agent.interrupt.InterruptReason.PARENT_CANCELLED));
        final RunningTaskHandle handle = new RunningTaskHandle(taskId, coordinator);
        runningTasks.register(handle);

        // Bind a live output sink to the shared task output store (when configured) so the AgentOutput tool can tail
        // this background task's progress. Absent a store the sink is a no-op (no regression). The store's append is
        // thread-safe, so parallel tool-result callbacks streaming to it are safe.
        final TaskOutputStore outputStore = launchContext.getTaskOutputStore().orElse(null);
        final SubagentOutputSink outputSink = outputStore != null
                ? text -> outputStore.append(taskId, text)
                : SubagentOutputSink.NO_OP;

        // Fire SubagentStart on THIS (launching) thread, before the task is queued to a worker, so the launch is
        // observed immediately and in turn order (e.g. the CLI's SubagentLaunchDisplayHook) — including when the
        // bounded pool later rejects the task. The worker then runs runResolvedSubagent, which does NOT re-fire start.
        fireSubagentStart(launchContext, taskId, subagentName, goal, description, handle.getSignal());

        // Taken after the Start hook and the PENDING write, immediately before the hand-off, so the span's queuedMs
        // counts only the wait for a worker — not a slow hook.
        final RunMode backgroundMode = RunMode.background(Instant.now());
        final CompletableFuture<SubagentExecutionResult> future;
        try {
            future = CompletableFuture.supplyAsync(() -> {
                // The worker's first statement: the queue wait ends here, before the RUNNING write (a network round
                // trip with a remote task store) that the span would otherwise count as waiting for a worker.
                final RunMode startedMode = backgroundMode.startedAt(Instant.now());
                handle.attachWorker(Thread.currentThread());
                taskStore.transition(taskId, BackgroundTaskState.RUNNING);
                // Background tasks are always dispatched by registered name. SubagentStart already fired above.
                final SubagentExecutionResult result = runResolvedSubagent(launchContext, taskId,
                        SubagentTarget.byName(subagentName), goal, handle.getSignal(), outputSink, startedMode,
                        policyRun);
                // Persist the finished transcript so Task(resume=<taskId>) can continue it. Background-only —
                // a foreground run's taskId is never surfaced, so its snapshot would be unreachable.
                saveSessionSnapshot(launchContext, taskId, subagentName, result);
                return result;
            }, executorService);
        } catch (RejectedExecutionException rex) {
            // Bounded pool saturated: settle as FAILED rather than spawning an unbounded thread.
            log.warn("Background subagent task '{}' rejected: pool saturated ({})", taskId, rex.getMessage());
            final SubagentExecutionResult failure = SubagentExecutionResult
                    .emptyFailure("Background task rejected: subagent pool saturated", startTime);
            // The task never ran, but it was launched — SubagentStart fired — so it leaves a SUBAGENT span, closed
            // before the Stop hook as on the path that ran. First and isolated, so nothing the tracer does can keep
            // the settlement below from running.
            traceRejectedRun(launchContext, taskId, subagentName, goal, failure);
            // Save before the terminal transition, per the TaskResultStore ordering contract: a reader that sees
            // FAILED must already be able to see why.
            saveTaskResult(launchContext, taskId, failure);
            taskStore.transition(taskId, BackgroundTaskState.FAILED);
            runningTasks.remove(taskId);
            // SubagentStart fired on the launching thread above; balance it with a Stop for this reject-before-run path
            // (the runResolvedSubagent path fires its own Stop). Advisory; failures are swallowed inside
            // fireSubagentStop. Fired before the coordinator is closed and the cascade is deregistered, as on the
            // path that ran: after either, the task's signal can no longer trip, and an interrupt of the spawning
            // execution would not stop the hook's command.
            fireSubagentStop(launchContext, taskId, subagentName, failure, handle.getSignal());
            coordinator.close();
            parentCancelReg.remove();
            // This path never registers a whenComplete finalizer, so notify the parent here. It is mutually
            // exclusive with the finalizeBackgroundTask path, so the completion is still signalled exactly once.
            notifyParentOfCompletion(launchContext, taskId, subagentName, BackgroundTaskState.FAILED, failure, rex);
            return CompletableFuture.completedFuture(failure);
        }

        handle.attachFuture(future);
        watchBackgroundExit(launchContext, taskId, subagentName, policyRun, future);
        future.whenComplete((result, error) -> {
            try {
                finalizeBackgroundTask(launchContext, handle, coordinator, taskId, subagentName, result, error);
            } finally {
                // Deregister the parent-cancel cascade listener so it does not accumulate on the per-execution signal.
                parentCancelReg.remove();
            }
        });

        // The returned future is the caller's handle for this dispatch only. Nothing node-local retains it: what a
        // later AgentOutput call reads is the TaskResultStore entry saved by finalizeBackgroundTask before the
        // terminal transition, which is what lets the result outlive this JVM and be read from another node.
        return future;
    }

    /**
     * For a background task under a deadline: when the deadline and the grace period after it have both passed and the
     * task has still not returned, lists it as lingering until it does. Its worker is still occupied and its state
     * still {@code RUNNING} — both true — and this is what tells a caller that the deadline was not enough to end it.
     */
    private void watchBackgroundExit(SubagentLaunchContext launchContext, String taskId, String subagentName,
            PolicyRun policyRun, CompletableFuture<SubagentExecutionResult> future) {
        if (policyRun.deadline == null) {
            return;
        }
        final DeadlineScheduler.Handle watch = DeadlineScheduler
                .schedule(policyRun.deadline.remaining().plus(policyRun.policy.getExitGracePeriod()), () -> {
                    if (!future.isDone()) {
                        linger(launchContext, taskId, subagentName, CompletionReason.WALL_CLOCK_EXCEEDED, policyRun,
                                future, true, Instant.now());
                    }
                });
        future.whenComplete((result, error) -> watch.close());
    }

    /**
     * Leaves the SUBAGENT span of a background task the pool refused: same parent, inputs and attributes as a run,
     * {@code rejected=true}, no {@code queuedMs} (it never entered the queue) and the rejection's failure as its
     * outcome.
     */
    private void traceRejectedRun(SubagentLaunchContext launchContext, String taskId, String subagentName, String goal,
            SubagentExecutionResult failure) {
        try {
            final TracePayloadPolicy payloadPolicy = tracePayloadPolicy;
            closeSubagentSpan(
                    openSubagentSpan(launchContext, subagentName, taskId, goal, RunMode.rejected(), payloadPolicy),
                    failure, payloadPolicy);
        } catch (RuntimeException e) {
            log.warn("Tracing the rejected background task '{}' failed: {}", taskId, e.getMessage());
        }
    }

    /**
     * Records the terminal state of a background task and releases its node-local execution resources. A task that was
     * asked to stop (or whose per-task signal was tripped by a parent cascade) settles to
     * {@link BackgroundTaskState#KILLED}; a thrown/failed result settles to {@link BackgroundTaskState#FAILED};
     * otherwise {@link BackgroundTaskState#COMPLETED}. The store's guarded transition makes this idempotent with any
     * eager transition.
     *
     * <p>
     * The task's result is persisted first (see {@link #saveTaskResult}) so that the terminal state, once visible, is
     * never observed ahead of what produced it. After recording the terminal state, notifies the launching agent of the
     * outcome (best-effort; see {@link #notifyParentOfCompletion}). This runs exactly once per task (a single
     * {@code whenComplete} per future).
     */
    private void finalizeBackgroundTask(SubagentLaunchContext launchContext, RunningTaskHandle handle,
            InterruptCoordinator coordinator, String taskId, String subagentName, SubagentExecutionResult result,
            Throwable error) {
        try {
            // Before the terminal transition, so that observing a terminal state implies the result is readable.
            saveTaskResult(launchContext, taskId, result);
            final boolean cancelled = handle.isStopRequested() || handle.getSignal().isCancelled();
            final BackgroundTaskState finalState;
            if (cancelled) {
                finalState = BackgroundTaskState.KILLED;
            } else if (error != null || result == null || !result.isSuccess()) {
                finalState = BackgroundTaskState.FAILED;
            } else {
                finalState = BackgroundTaskState.COMPLETED;
            }
            // Notify with the AUTHORITATIVE state the store actually holds, not the locally-computed finalState — a
            // task the ZombieTaskReaper already flipped to FAILED (or one reaped and then evicted under
            // terminal-retention overflow) must not be mis-reported as COMPLETED. See resolveNotifiedState.
            final BackgroundTaskState notifiedState = resolveNotifiedState(taskStore, taskId, finalState);
            notifyParentOfCompletion(launchContext, taskId, subagentName, notifiedState, result, error);
        } finally {
            coordinator.close();
            runningTasks.remove(taskId);
        }
    }

    /**
     * Resolves the terminal state to report to the launching agent, preferring the store's authoritative record over
     * the locally-computed {@code finalState}. The store's {@code transition} is a no-op once a task is terminal, so a
     * task the {@link at.aimon.core.subagent.task.ZombieTaskReaper} already flipped to {@code FAILED} under lease
     * recovery must be reported as {@code FAILED}, not {@code COMPLETED}. Three cases:
     *
     * <ol>
     * <li>our transition applied (the task was still non-terminal) — report the state we just recorded;
     * <li>the transition was a no-op but the record survives (already terminal) — report the store's state;
     * <li>the record is gone — another actor made it terminal and it was then evicted under terminal-retention
     * overflow. Never claim {@code COMPLETED} for a task the system already treated as dead: trust a
     * locally-authoritative {@code KILLED}/{@code FAILED}, otherwise settle conservatively to {@code FAILED}.
     * </ol>
     *
     * @param taskStore
     *            the background task store
     * @param taskId
     *            the background task id
     * @param finalState
     *            the terminal state computed from this node's local view of the completion
     * @return the terminal state to report to the parent
     */
    static BackgroundTaskState resolveNotifiedState(BackgroundTaskStore taskStore, String taskId,
            BackgroundTaskState finalState) {
        final Optional<BackgroundTask> applied = taskStore.transition(taskId, finalState);
        if (applied.isPresent()) {
            return applied.get().getState();
        }
        final Optional<BackgroundTask> current = taskStore.find(taskId);
        if (current.isPresent()) {
            return current.get().getState();
        }
        return finalState == BackgroundTaskState.COMPLETED ? BackgroundTaskState.FAILED : finalState;
    }

    /**
     * Notifies the launching agent that a background subagent task has settled, over two complementary channels.
     * Both are best-effort and fully guarded so a notification failure can never disturb task finalization:
     *
     * <ul>
     * <li><b>Message queue (guaranteed, model-facing):</b> when the launch context carries a
     * {@link MessageQueueManager},
     * a
     * {@link QueuedInputPriority#NEXT}-priority notification scoped to the parent {@link AgentRuntimeId} is
     * enqueued, so the parent's ReAct loop drains and injects it on its next iteration.
     * <li><b>Stream event (best-effort, observability):</b> when the launch context carries a parent event sink, a
     * {@link SubagentTaskCompleted} event is emitted for live CLI display / web SSE. It is dropped when no listener is
     * attached (the parent is idle) — the queued notification remains the guaranteed path.
     * </ul>
     *
     * <p>
     * Called exactly once per task: from {@link #finalizeBackgroundTask} on the normal path, or from the pool-rejection
     * path — the two are mutually exclusive, so no de-duplication flag is required.
     *
     * @param launchContext
     *            the launch context (carries the parent's queue, event sink, and agent runtime id)
     * @param taskId
     *            the background task id
     * @param subagentName
     *            the subagent that ran the task
     * @param finalState
     *            the terminal task state
     * @param result
     *            the execution result, or {@code null} when the task threw before producing one
     * @param error
     *            the throwable that terminated the task, or {@code null} on a normal settle
     */
    private void notifyParentOfCompletion(SubagentLaunchContext launchContext, String taskId, String subagentName,
            BackgroundTaskState finalState, SubagentExecutionResult result, Throwable error) {
        final SubagentTaskCompleted.Outcome outcome = toOutcome(finalState);
        final String detail = completionDetail(outcome, result, error);

        launchContext.getMessageQueueManager().ifPresent(queue -> enqueueCompletionNotification(queue,
                launchContext.getAgentRuntimeId(), taskId, subagentName, outcome, detail));
        launchContext.getParentEventSink().ifPresent(sink -> emitCompletionEvent(sink,
                launchContext.getAgentRuntimeId(), taskId, subagentName, outcome, detail));
    }

    /** Maps a terminal {@link BackgroundTaskState} to the stream event's {@link SubagentTaskCompleted.Outcome}. */
    private static SubagentTaskCompleted.Outcome toOutcome(BackgroundTaskState finalState) {
        switch (finalState) {
            case COMPLETED :
                return SubagentTaskCompleted.Outcome.COMPLETED;
            case KILLED :
                return SubagentTaskCompleted.Outcome.KILLED;
            default :
                return SubagentTaskCompleted.Outcome.FAILED;
        }
    }

    /**
     * Derives a short, already-truncated human detail: the subagent's summary for a completion, or the error message
     * (falling back to the throwable) for a failure/kill. May be {@code null} when no detail is available.
     */
    private static String completionDetail(SubagentTaskCompleted.Outcome outcome, SubagentExecutionResult result,
            Throwable error) {
        final String raw;
        if (outcome == SubagentTaskCompleted.Outcome.COMPLETED) {
            // A reason line goes at the head, not after the summary: truncateDetail keeps the first 500 chars, so the
            // end-of-answer marker of a long answer cut at max_tokens would not survive it (L-25).
            final String summary = result != null ? result.getSummary() : null;
            raw = result == null
                    ? null
                    : SubagentResultFormatter.completionReasonLine(result.getCompletionReason(), result.isSuccess())
                            .map(line -> summary == null || summary.isBlank() ? line : line + " — " + summary)
                            .orElse(summary);
        } else if (result != null && result.getErrorMessage() != null && !result.getErrorMessage().isBlank()) {
            raw = result.getErrorMessage();
        } else if (error != null) {
            raw = error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();
        } else {
            raw = null;
        }
        // The detail is free-form subagent output; neutralize any reserved <system-reminder> markers it may contain
        // before it becomes part of a queued notification body, so the drain-path wrap() can never reject the whole
        // notification (and drop co-drained siblings) over one subagent's text.
        return truncateDetail(raw == null ? null : SystemReminderFormatter.sanitizeBody(raw));
    }

    /** Trims and length-bounds a completion detail so a notification/event stays compact. */
    private static String truncateDetail(String text) {
        if (text == null) {
            return null;
        }
        final String trimmed = text.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() <= COMPLETION_DETAIL_MAX_CHARS) {
            return trimmed;
        }
        return trimmed.substring(0, COMPLETION_DETAIL_MAX_CHARS) + "… (truncated)";
    }

    /** Enqueues a {@code NEXT}-priority completion notification for the parent, swallowing any failure. */
    private void enqueueCompletionNotification(MessageQueueManager queue, AgentRuntimeId agentRuntimeId, String taskId,
            String subagentName, SubagentTaskCompleted.Outcome outcome, String detail) {
        try {
            final QueuedInput notification = QueuedInput.builder()
                    .inputText(completionMessageBody(taskId, subagentName, outcome, detail))
                    .priority(QueuedInputPriority.NEXT).agentRuntimeId(agentRuntimeId).sourceAgentId(subagentName)
                    .metadata(Map.of(SubagentTaskCompleted.QUEUED_KIND_KEY, SubagentTaskCompleted.QUEUED_KIND,
                            SubagentTaskCompleted.QUEUED_TASK_ID_KEY, taskId, SubagentTaskCompleted.QUEUED_OUTCOME_KEY,
                            outcome.name()))
                    .build();
            queue.enqueue(notification);
            log.debug("Enqueued background task completion notification: taskId={} subagent={} outcome={}", taskId,
                    subagentName, outcome);
        } catch (Exception e) {
            log.warn("Failed to enqueue background task completion notification for taskId={}: {}", taskId,
                    e.getMessage());
        }
    }

    /** Emits a {@link SubagentTaskCompleted} event to the parent's event sink, swallowing any failure. */
    private void emitCompletionEvent(Consumer<AgentExecutionEvent> sink, AgentRuntimeId agentRuntimeId, String taskId,
            String subagentName, SubagentTaskCompleted.Outcome outcome, String detail) {
        try {
            sink.accept(SubagentTaskCompleted.builder().timestamp(Instant.now()).agentRuntimeId(agentRuntimeId)
                    .taskId(taskId).subagentName(subagentName).outcome(outcome).detail(detail).build());
        } catch (Exception e) {
            log.warn("Failed to emit SubagentTaskCompleted event for taskId={}: {}", taskId, e.getMessage());
        }
    }

    /**
     * Builds the plain-text body of the queued completion notification. Intentionally free of angle-bracket markup: the
     * drain path ({@code OrcaAgentExecutor#injectQueuedMessages}) XML-escapes and wraps this body in a
     * {@code <system-reminder>} block, so embedded tags would render as escaped text.
     */
    private static String completionMessageBody(String taskId, String subagentName,
            SubagentTaskCompleted.Outcome outcome, String detail) {
        final StringBuilder body = new StringBuilder();
        body.append("Background subagent task ").append(outcome.name().toLowerCase(Locale.ROOT)).append(".\n");
        body.append("Task ID: ").append(taskId).append('\n');
        body.append("Subagent: ").append(subagentName).append('\n');
        body.append("Outcome: ").append(outcome.name());
        if (detail != null) {
            body.append('\n').append(outcome == SubagentTaskCompleted.Outcome.COMPLETED ? "Summary: " : "Error: ")
                    .append(detail);
        }
        body.append("\nRetrieve the full result with the ").append(AgentOutputTool.TOOL_NAME)
                .append(" tool using taskId='").append(taskId).append("'.");
        return body.toString();
    }

    @Override
    public boolean stop(String taskId) {
        Objects.requireNonNull(taskId, "taskId cannot be null");
        final Optional<RunningTaskHandle> handleOpt = runningTasks.find(taskId);
        if (handleOpt.isPresent()) {
            // Cooperative cancellation on the owning node: trip the per-task signal and interrupt the worker. The task
            // settles to KILLED in finalizeBackgroundTask once its ReAct loop / behavior unwinds.
            handleOpt.get().requestStop();
            return true;
        }
        // No live handle on this node. In a scale-out deployment the task may be running on another instance: when the
        // shared store shows it known and non-terminal, broadcast a cross-node stop so the owning node trips its
        // handle. With the default NoopTaskStopSignal this branch is effectively unreachable on a single node (a
        // non-terminal task always has its handle registered here before its taskId becomes observable), so behaviour
        // is unchanged.
        final boolean stoppableElsewhere = taskStore.find(taskId).filter(task -> !task.getState().isTerminal())
                .isPresent();
        if (stoppableElsewhere) {
            taskStopSignal.broadcastStop(taskId);
            return true;
        }
        // Unknown or already terminal (handle evicted): nothing to stop.
        return false;
    }

    /**
     * Delivers a cross-node stop request to this node's execution handles. Invoked by {@link #taskStopSignal} for
     * <em>every</em> stop broadcast (including ones this node originated). It is local-only and never re-broadcasts, so
     * a broadcast cannot loop: only the node that actually owns the running task finds a handle and trips it; every
     * other node no-ops.
     *
     * @param taskId
     *            the id of the task to stop on this node (never null)
     */
    private void onRemoteStop(String taskId) {
        runningTasks.find(taskId).ifPresent(RunningTaskHandle::requestStop);
    }

    @Override
    public List<BackgroundTask> list(TaskQuery query) {
        Objects.requireNonNull(query, "query cannot be null");
        return taskStore.list(query);
    }

    @Override
    public Optional<BackgroundTask> status(String taskId) {
        Objects.requireNonNull(taskId, "taskId cannot be null");
        return taskStore.find(taskId);
    }

    /**
     * Parses subagent request string to extract subagent name and goal.
     *
     * @param input
     *            The input string starting with '@'
     * @return Parsed subagent request
     * @throws IllegalArgumentException
     *             if input format is invalid
     */
    private ParsedSubagentRequest parseSubagentRequest(String input) {
        // Remove leading @
        final String subagentPart = input.substring(1);

        // Extract subagent name (first token, separated by space or tab)
        int spaceIndex = -1;
        for (int i = 0; i < subagentPart.length(); i++) {
            final char c = subagentPart.charAt(i);
            if (c == ' ' || c == '\t') {
                spaceIndex = i;
                break;
            }
        }

        final String subagentName;
        final String goal;

        if (spaceIndex > 0) {
            subagentName = subagentPart.substring(0, spaceIndex);
            goal = subagentPart.substring(spaceIndex + 1).trim();
        } else {
            subagentName = subagentPart;
            goal = "";
        }

        if (subagentName.isEmpty()) {
            throw new IllegalArgumentException("Subagent name cannot be empty");
        }

        if (goal.isEmpty()) {
            throw new IllegalArgumentException("Subagent goal cannot be empty");
        }

        return new ParsedSubagentRequest(subagentName, goal);
    }

    /**
     * Creates a failure result for subagent execution. The triggering exception's message is already folded into
     * {@code errorMessage}; the exception itself is not carried on the value object.
     *
     * @param startTime
     *            The execution start time
     * @param errorMessage
     *            The error message
     * @return The failure result
     */
    private SubagentExecutionResult createFailureResult(Instant startTime, String errorMessage) {
        return SubagentExecutionResult.emptyFailure(errorMessage, startTime);
    }

    /**
     * Shuts down the executor service, releasing background execution resources.
     *
     * <p>
     * Waits up to 5 seconds for running tasks to complete before forcing shutdown.
     */
    @Override
    public void close() {
        // Detach from the cross-node stop signal first so no remote stop is dispatched into a manager being torn down.
        // Best-effort: a throwing subscription close must not block executor shutdown.
        try {
            stopSubscription.close();
        } catch (RuntimeException e) {
            log.warn("Failed to close task stop subscription: {}", e.toString());
        }
        // Stop the lease daemons (no-ops when lease recovery was not opted in). Best-effort so a throwing close never
        // blocks executor shutdown.
        if (heartbeatPublisher != null) {
            try {
                heartbeatPublisher.close();
            } catch (RuntimeException e) {
                log.warn("Failed to close heartbeat publisher: {}", e.toString());
            }
        }
        if (zombieReaper != null) {
            try {
                zombieReaper.close();
            } catch (RuntimeException e) {
                log.warn("Failed to close zombie reaper: {}", e.toString());
            }
        }
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Gets the subagent executor.
     *
     * @return The subagent executor (never null)
     */
    public SubagentExecutor getSubagentExecutor() {
        return subagentExecutor;
    }

    @Override
    public String toString() {
        return "SubagentHandler{" + "executor=" + subagentExecutor + '}';
    }

    /**
     * Identifies which subagent {@link #runExecute} should run, unifying the two dispatch paths so the shared body
     * keeps a single {@code name} for hooks/behavior lookup while carrying an optional pre-resolved instance.
     *
     * @param name
     *            the subagent name used for hook firing and behavior-registry lookup (never null)
     * @param inline
     *            the pre-resolved inline subagent to run directly, or {@code null} to resolve {@code name} against the
     *            launch context's {@link SubagentRegistry}
     */
    private record SubagentTarget(String name, Subagent inline) {
        private SubagentTarget {
            Objects.requireNonNull(name, "Subagent name cannot be null");
        }

        /** A subagent identified by registry name, resolved lazily inside {@link #runExecute}. */
        static SubagentTarget byName(String name) {
            return new SubagentTarget(name, null);
        }

        /** A pre-resolved inline (code-defined) subagent, run without a registry lookup. */
        static SubagentTarget inline(Subagent subagent) {
            return new SubagentTarget(subagent.getName(), subagent);
        }
    }

    /**
     * How a run was dispatched, as its SUBAGENT span records it: in the foreground, from the background queue (with the
     * instant it was handed to the pool, so the span can say how long it waited for a worker), or refused by the pool
     * before it ever ran.
     */
    private static final class RunMode {
        private static final RunMode FOREGROUND = new RunMode(false, false, null, null);
        private static final RunMode REJECTED = new RunMode(true, true, null, null);

        private final boolean background;
        private final boolean rejected;
        /** When the run was handed to the pool; {@code null} unless it went through the queue. */
        private final Instant enqueuedAt;
        /** When a worker picked the run up; {@code null} until {@link #startedAt} records it. */
        private final Instant workerStartedAt;

        private RunMode(boolean background, boolean rejected, Instant enqueuedAt, Instant workerStartedAt) {
            this.background = background;
            this.rejected = rejected;
            this.enqueuedAt = enqueuedAt;
            this.workerStartedAt = workerStartedAt;
        }

        static RunMode foreground() {
            return FOREGROUND;
        }

        static RunMode background(Instant enqueuedAt) {
            return new RunMode(true, false, Objects.requireNonNull(enqueuedAt, "enqueuedAt cannot be null"), null);
        }

        static RunMode rejected() {
            return REJECTED;
        }

        /** Returns this queued run as picked up by a worker at {@code workerStart}. */
        RunMode startedAt(Instant workerStart) {
            return new RunMode(background, rejected, enqueuedAt,
                    Objects.requireNonNull(workerStart, "workerStart cannot be null"));
        }

        /**
         * Returns how long the run waited for a worker, or empty when it never queued (a foreground run has no wait to
         * report, which is not the same as a wait of zero).
         */
        Optional<Duration> queued() {
            return enqueuedAt == null || workerStartedAt == null
                    ? Optional.empty()
                    : Optional.of(Duration.between(enqueuedAt, workerStartedAt));
        }
    }

    /**
     * A launch's execution policy and the deadline anchored for it at launch: the earlier of the policy's wall clock
     * and the spawner's deadline. Both {@code null} for a launch without a policy, which keeps every path exactly as
     * it was; a spawner's deadline alone does not bound a fork that has no policy of its own.
     */
    private static final class PolicyRun {
        private static final PolicyRun NONE = new PolicyRun(null, null);

        private final SubagentExecutionPolicy policy;
        private final ExecutionDeadline deadline;

        private PolicyRun(SubagentExecutionPolicy policy, ExecutionDeadline deadline) {
            this.policy = policy;
            this.deadline = deadline;
        }

        static PolicyRun anchor(SubagentLaunchContext launchContext) {
            final SubagentExecutionPolicy policy = launchContext.getExecutionPolicy().orElse(null);
            if (policy == null) {
                return NONE;
            }
            final ExecutionDeadline own = policy.getMaxWallClock().map(ExecutionDeadline::after).orElse(null);
            final ExecutionDeadline parent = launchContext.getParentDeadline().orElse(null);
            return new PolicyRun(policy, own == null ? parent : own.earlierOf(parent));
        }
    }

    /**
     * Represents a parsed subagent request with name and goal.
     *
     * @param name
     *            The subagent name (never null or empty)
     * @param goal
     *            The goal description (never null or empty)
     */
    private record ParsedSubagentRequest(String name, String goal) {
        private ParsedSubagentRequest {
            Objects.requireNonNull(name, "Subagent name cannot be null");
            Objects.requireNonNull(goal, "Goal cannot be null");
            if (name.isEmpty()) {
                throw new IllegalArgumentException("Subagent name cannot be empty");
            }
            if (goal.isEmpty()) {
                throw new IllegalArgumentException("Goal cannot be empty");
            }
        }
    }
}
