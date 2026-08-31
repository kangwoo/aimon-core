package at.aimon.core.subagent;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.AgentExecutionRequest;
import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.InvokerType;
import at.aimon.core.agent.interrupt.CancellationSignal;
import at.aimon.core.agent.interrupt.DefaultInterruptCoordinator;
import at.aimon.core.agent.interrupt.InterruptCoordinator;
import at.aimon.core.agent.prompt.SystemReminderFormatter;
import at.aimon.core.agent.queue.MessageQueueManager;
import at.aimon.core.agent.queue.QueuedInput;
import at.aimon.core.agent.queue.QueuedInputPriority;
import at.aimon.core.agent.session.transcript.SessionSnapshot;
import at.aimon.core.agent.session.transcript.TranscriptBuffer;
import at.aimon.core.agent.stream.AgentExecutionEvent;
import at.aimon.core.agent.stream.SubagentTaskCompleted;
import at.aimon.core.agent.tool.ToolExecutionManager;
import at.aimon.core.hook.HookExecutionManager;
import at.aimon.core.hook.event.SubagentStartContext;
import at.aimon.core.hook.event.SubagentStopContext;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.subagent.behavior.SubagentBehavior;
import at.aimon.core.subagent.behavior.SubagentBehaviorRegistry;
import at.aimon.core.subagent.behavior.SubagentBehaviorRunner;
import at.aimon.core.subagent.exception.SubagentException;
import at.aimon.core.subagent.exception.SubagentNotFoundException;
import at.aimon.core.subagent.execution.DefaultSubagentExecutor;
import at.aimon.core.subagent.execution.SubagentExecutionContext;
import at.aimon.core.subagent.execution.SubagentExecutionRequest;
import at.aimon.core.subagent.execution.SubagentExecutionResult;
import at.aimon.core.subagent.execution.SubagentExecutor;
import at.aimon.core.subagent.execution.SubagentOutputSink;
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
import at.aimon.core.tools.task.AgentOutputTool;

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
     * Optional. When present, {@link #execute(SubagentExecutionEnvironment, String, String, String, String)} fires
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
     * Lease-recovery machinery (design §4), created only when a {@link TaskLeaseConfig} is injected. The
     * publisher renews the heartbeat of every task this node owns; the reaper transitions heartbeat-expired
     * non-terminal
     * tasks to {@code FAILED}. Both are {@code null} (no threads, unchanged behaviour) when lease recovery is not opted
     * in. Closed on {@link #close()}.
     */
    private final TaskHeartbeatPublisher heartbeatPublisher;
    private final ZombieTaskReaper zombieReaper;

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
     * gateway
     * so code behaviors can call the model
     * ({@link at.aimon.core.subagent.behavior.SubagentBehaviorSupport#llmGateway()});
     * null disables LLM access for behaviors. The {@code SubagentExecutor} is the ReAct path and is independent of this
     * client — pass the same client used to build the executor when LLM access for behaviors is wanted.
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
     * task
     * store, a cross-node stop signal, and — when {@code leaseConfig} is non-null — zombie-task lease recovery; the
     * other
     * constructors default the signal to {@link NoopTaskStopSignal} (local stops only) and leave lease recovery off.
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
        this.subagentExecutor = Objects.requireNonNull(subagentExecutor, "Subagent executor cannot be null");
        Objects.requireNonNull(options, "Background execution options cannot be null");
        this.executorService = options.getExecutorService();
        this.hookExecutionManager = hookExecutionManager;
        this.subagentBehaviorRegistry = subagentBehaviorRegistry != null
                ? subagentBehaviorRegistry
                : SubagentBehaviorRegistry.empty();
        this.subagentBehaviorRunner = new SubagentBehaviorRunner(llmClient);
        this.taskStore = options.getTaskStore();
        this.taskStopSignal = options.getTaskStopSignal();
        final TaskLeaseConfig leaseConfig = options.getLeaseConfig();
        // Subscribe last: runningTasks is field-initialized before this body runs and onRemoteStop touches nothing
        // else, so a stop delivered mid-construction (impossible for loopback, async for a real bus) is safe.
        this.stopSubscription = taskStopSignal.subscribe(this::onRemoteStop);
        // §4: opt-in lease recovery. The publisher heartbeats exactly the tasks this node owns (runningTasks
        // holds
        // a handle from submission through terminal removal), so a live node never lets its own tasks be reaped.
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
        this(new DefaultSubagentExecutor(llmClient, toolExecutionManager, hookExecutionManager),
                newUnboundedBackgroundExecutor(), hookExecutionManager, subagentBehaviorRegistry, llmClient);
    }

    /**
     * Creates a new DefaultSubagentExecutionManager (LlmClient convenience) with an explicit, caller-supplied
     * {@link ExecutorService} (typically a bounded pool from {@link #newBackgroundExecutor(SubagentBackgroundConfig)},
     * and {@link BackgroundTaskStore}. This is the constructor the factory uses to bound background fan-out
     * and
     * to plug in a multi-instance-ready task store.
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
        this(new DefaultSubagentExecutor(llmClient, toolExecutionManager, hookExecutionManager), executorService,
                hookExecutionManager, subagentBehaviorRegistry, llmClient, taskStore);
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
        this(new DefaultSubagentExecutor(llmClient, toolExecutionManager, hookExecutionManager), hookExecutionManager,
                subagentBehaviorRegistry, llmClient, options);
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
     * @param env
     *            The execution environment (must not be null)
     * @param agentExecutionRequest
     *            The agent execution request containing user input and context (must not be null)
     * @param transcriptBuffer
     *            The transcript buffer (must not be null)
     * @return The subagent execution result (never null)
     * @throws NullPointerException
     *             if env or agentExecutionRequest is null
     * @throws IllegalArgumentException
     *             if user input doesn't start with '@'
     */
    @Override
    public SubagentExecutionResult execute(SubagentExecutionEnvironment env,
            AgentExecutionRequest agentExecutionRequest, TranscriptBuffer transcriptBuffer) {
        Objects.requireNonNull(env, "Execution environment cannot be null");
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
        // name the invoking session when the environment did not already carry it. Binding it here lets the
        // subagent inherit the approvals the user granted in that session; a null memory (callers that have no
        // session) leaves the environment untouched and the run inherits nothing.
        final SubagentExecutionEnvironment effectiveEnv = transcriptBuffer == null
                ? env
                : env.toBuilder().invokingSessionId(transcriptBuffer.getSessionId()).build();

        return execute(effectiveEnv, taskId, parsed.name(), parsed.goal(), "");
    }

    @Override
    public SubagentExecutionResult execute(SubagentExecutionEnvironment env, String taskId, String subagentName,
            String goal, String description) {
        Objects.requireNonNull(env, "Execution environment cannot be null");
        // Foreground execution observes the parent execution's cancellation signal directly (the parent is a session's
        // turn on the main loop, or another fork when a subagent launches one). Its output is returned
        // inline (not tailed), so it streams to a no-op sink. The subagent is resolved by name from the environment's
        // registry.
        return runExecute(env, taskId, SubagentTarget.byName(subagentName), goal, description,
                env.getCancellationSignal(), SubagentOutputSink.NO_OP);
    }

    @Override
    public SubagentExecutionResult execute(SubagentExecutionEnvironment env, Subagent subagent, String goal) {
        Objects.requireNonNull(env, "Execution environment cannot be null");
        Objects.requireNonNull(subagent, "Subagent cannot be null");
        Objects.requireNonNull(goal, "Goal cannot be null");
        // Inline (code-defined) subagent: run it once in the foreground without a registry lookup. A unique task id is
        // generated for hook/attribution tracking; cancellation follows the environment's parent-execution signal; the
        // result is returned inline, so the output streams to a no-op sink. A SubagentTarget.inline(...) makes
        // runExecute skip env.getSubagentRegistry() while sharing every other forwarding/dispatch detail with the
        // name-based path.
        final String taskId = UUID.randomUUID().toString();
        return runExecute(env, taskId, SubagentTarget.inline(subagent), goal, "", env.getCancellationSignal(),
                SubagentOutputSink.NO_OP);
    }

    /**
     * Shared execution body for the foreground and background paths. The effective cancellation signal is injected as
     * the subagent's parent signal: foreground passes the environment's signal; background passes the per-task
     * coordinator's signal so a {@code Task.stop} can cancel just that task.
     *
     * @param target
     *            identifies the subagent to run: an inline (code-defined) instance used as-is, or a registry name
     *            resolved against the environment's {@link SubagentRegistry}
     * @param cancellationSignal
     *            the effective cancellation signal to inject as the subagent's parent signal (must not be null)
     * @param outputSink
     *            the live output sink to stream progress to (must not be null; {@link SubagentOutputSink#NO_OP} for
     *            foreground)
     */
    private SubagentExecutionResult runExecute(SubagentExecutionEnvironment env, String taskId, SubagentTarget target,
            String goal, String description, CancellationSignal cancellationSignal, SubagentOutputSink outputSink) {
        // Foreground path: fire SubagentStart on the calling thread, then run the body. The background path
        // (executeInBackground) fires SubagentStart itself BEFORE dispatch — so the launch is observed on the launching
        // thread, immediately and in order, rather than late on a pool worker — and calls runResolvedSubagent directly.
        fireSubagentStart(env, taskId, target.name(), goal, description);
        return runResolvedSubagent(env, taskId, target, goal, cancellationSignal, outputSink);
    }

    /**
     * Runs the resolved subagent (or its registered code behavior) and fires the SubagentStop hook. The SubagentStart
     * hook is fired by the caller — {@link #runExecute} for the foreground path, {@link #executeInBackground} for the
     * background path — so a background launch can be observed on the launching thread rather than a pool worker.
     */
    private SubagentExecutionResult runResolvedSubagent(SubagentExecutionEnvironment env, String taskId,
            SubagentTarget target, String goal, CancellationSignal cancellationSignal, SubagentOutputSink outputSink) {
        final Instant startTime = Instant.now();
        final String subagentName = target.name();

        SubagentExecutionResult result;
        try {
            // Resolve the subagent: an inline (code-defined) subagent is used as-is; otherwise look it up by name in
            // the environment's registry. The lookup stays inside this try so an unknown name still yields a failure
            // result with SubagentStart/Stop hooks fired around it (unchanged registry-path behaviour).
            final Subagent subagent = target.inline() != null
                    ? target.inline()
                    : env.getSubagentRegistry().getSubagent(subagentName)
                            .orElseThrow(() -> new SubagentNotFoundException(subagentName));

            // Build execution context (how to execute). The effective cancellation signal is forwarded so a
            // parent-initiated (or per-task stop) cancel cascades into the subagent's ReAct loop and its cooperative
            // tools. The model override (Task tool's `model` arg) is forwarded so it wins over the subagent frontmatter
            // and default model. The knowledge store/scope and tool-context enrichers are forwarded so subagent tools
            // run with the same context keys as the main-agent tools.
            final SubagentExecutionContext executionContext = SubagentExecutionContext.builder()
                    .agentRuntimeId(env.getAgentRuntimeId()).subagent(subagent).environment(env.getEnvironment())
                    .toolRegistry(env.getToolRegistry()).hookRegistry(env.getHookRegistry())
                    .defaultModel(env.getDefaultModel()).modelOverride(env.getModelOverride().orElse(null))
                    .parentCancellationSignal(cancellationSignal).knowledgeStore(env.getKnowledgeStore().orElse(null))
                    .knowledgeScope(env.getKnowledgeScope().orElse(null))
                    .toolContextEnrichers(env.getToolContextEnrichers()).outputSink(outputSink).build();

            // Build execution request (what to execute). The parent's LLM call metadata is forwarded so the subagent
            // executor can merge it with subagent-derived defaults (component/feature) and emit attributed usage. The
            // principal is forwarded so subagent tools observe the same caller identity as the main-agent tools. When a
            // resume snapshot was loaded (Task resume=<taskId>) it is forwarded as previousSnapshot so the
            // executor rehydrates the prior transcript via TranscriptBuffer.fromSnapshot and appends the new goal.
            // The invoking session is forwarded so skill approvals the user granted in the session that
            // spawned this run apply to it: the fork mints its own session id, under which nothing was ever
            // granted, and it can never prompt for one of its own.
            final SubagentExecutionRequest request = SubagentExecutionRequest.builder().taskId(taskId).goal(goal)
                    .executionAttributes(env.getExecutionAttributes()).llmCallMetadata(env.getParentLlmCallMetadata())
                    .principal(env.getPrincipal().orElse(null))
                    .invokingSessionId(env.getInvokingSessionId().orElse(null))
                    .previousSnapshot(env.getPreviousSnapshot().orElse(null)).build();

            // Execute subagent. A code behavior registered for this name REPLACES the ReAct loop, receiving the SAME
            // context/request (cancellation signal, principal, metadata, tools). When absent, the data subagent runs
            // the
            // unchanged ReAct path — origin-agnostic execution among data subagents is preserved. SubagentStart/Stop
            // hooks and this try/catch error shaping apply to both branches.
            final Optional<SubagentBehavior> behavior = subagentBehaviorRegistry.getBehavior(subagentName);
            result = behavior.isPresent()
                    ? subagentBehaviorRunner.run(behavior.get(), executionContext, request)
                    : subagentExecutor.execute(executionContext, request);
        } catch (SubagentException e) {
            result = createFailureResult(startTime, e.getMessage());
        } catch (Exception e) {
            result = createFailureResult(startTime, "Subagent execution error: " + e.getMessage());
        }

        fireSubagentStop(env, taskId, subagentName, result);
        return result;
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
    private void saveSessionSnapshot(SubagentExecutionEnvironment env, String taskId, String subagentName,
            SubagentExecutionResult result) {
        final SessionSnapshot snapshot = result.getSnapshot();
        if (snapshot.getConversationHistory().isEmpty()) {
            // Nothing resumable to persist (dispatch failure / no iterations ran); leave the id unresumable.
            return;
        }
        env.getSessionSnapshotStore().ifPresent(store -> {
            try {
                // Tag the transcript with the owning agent runtime so a later Task(resume=<taskId>) can be
                // confined to the caller's own context — one agent must not resume (and thereby read) another's
                // transcript merely by knowing its globally-unique task id.
                store.save(taskId, subagentName, env.getAgentRuntimeId(), snapshot);
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
    private void saveTaskResult(SubagentExecutionEnvironment env, String taskId, SubagentExecutionResult result) {
        if (result == null) {
            return;
        }
        env.getTaskResultStore().ifPresent(store -> {
            try {
                store.save(taskId, TaskResult.from(result));
            } catch (RuntimeException e) {
                log.warn("Failed to save task result for taskId={}: {}", taskId, e.getMessage());
            }
        });
    }

    private void fireSubagentStart(SubagentExecutionEnvironment env, String taskId, String subagentName, String goal,
            String description) {
        if (hookExecutionManager == null) {
            return;
        }
        try {
            final SubagentStartContext ctx = SubagentStartContext.builder().invokerType(InvokerType.MAIN_AGENT)
                    .invokerName(subagentName).hookRegistry(env.getHookRegistry()).environment(env.getEnvironment())
                    .subagentName(subagentName).taskId(taskId).goal(goal).description(description)
                    .executionAttributes(env.getExecutionAttributes()).build();
            hookExecutionManager.executeSubagentStart(ctx);
        } catch (Exception e) {
            log.warn("SubagentStart hook failed for subagent '{}', taskId={}: {}", subagentName, taskId,
                    e.getMessage());
        }
    }

    private void fireSubagentStop(SubagentExecutionEnvironment env, String taskId, String subagentName,
            SubagentExecutionResult result) {
        if (hookExecutionManager == null) {
            return;
        }
        try {
            final SubagentStopContext ctx = SubagentStopContext.builder().invokerType(InvokerType.MAIN_AGENT)
                    .invokerName(subagentName).hookRegistry(env.getHookRegistry()).environment(env.getEnvironment())
                    .subagentName(subagentName).taskId(taskId).success(result.isSuccess())
                    .errorMessage(result.isSuccess() ? null : result.getErrorMessage())
                    .executionAttributes(env.getExecutionAttributes()).build();
            hookExecutionManager.executeSubagentStop(ctx);
        } catch (Exception e) {
            log.warn("SubagentStop hook failed for subagent '{}', taskId={}: {}", subagentName, taskId, e.getMessage());
        }
    }

    @Override
    public CompletableFuture<SubagentExecutionResult> executeInBackground(SubagentExecutionEnvironment env,
            String taskId, String subagentName, String goal, String description) {
        Objects.requireNonNull(env, "Execution environment cannot be null");
        final Instant startTime = Instant.now();

        // Record a durable PENDING snapshot up front so the task is listable/queryable even while it waits for a
        // worker (bounded pool). The worker flips it to RUNNING when it actually starts.
        final BackgroundTask snapshot = BackgroundTask.builder().taskId(taskId).subagentName(subagentName)
                .description(description).state(BackgroundTaskState.PENDING).startTime(startTime)
                .lastHeartbeat(startTime).owner(env.getPrincipal().orElse(null)).agentRuntimeId(env.getAgentRuntimeId())
                .build();
        taskStore.put(snapshot);

        // Per-task interrupt coordinator whose signal is injected as the subagent's parent signal, so Task.stop can
        // cancel just this task. Cascade the environment's parent-execution signal into it so a parent cancel still
        // propagates.
        final InterruptCoordinator coordinator = new DefaultInterruptCoordinator();
        // Cascade the environment's parent-execution signal into this task's coordinator. Retain the registration so
        // both terminal paths (finalizeBackgroundTask and the pool-rejection path) can deregister it — otherwise each
        // background Task launched in an execution would leave a listener on the per-execution signal, accumulating
        // one closed coordinator per task for the rest of that execution.
        final CancellationSignal.Registration parentCancelReg = env.getCancellationSignal().onCancel(
                () -> coordinator.requestInterrupt(at.aimon.core.agent.interrupt.InterruptReason.PARENT_CANCELLED));
        final RunningTaskHandle handle = new RunningTaskHandle(taskId, coordinator);
        runningTasks.register(handle);

        // Bind a live output sink to the shared task output store (when configured) so the AgentOutput tool can
        // tail
        // this background task's progress. Absent a store the sink is a no-op (no regression). The store's append is
        // thread-safe, so parallel tool-result callbacks streaming to it are safe.
        final TaskOutputStore outputStore = env.getTaskOutputStore().orElse(null);
        final SubagentOutputSink outputSink = outputStore != null
                ? text -> outputStore.append(taskId, text)
                : SubagentOutputSink.NO_OP;

        // Fire SubagentStart on THIS (launching) thread, before the task is queued to a worker, so the launch is
        // observed immediately and in turn order (e.g. the CLI's SubagentLaunchDisplayHook) — including when the
        // bounded
        // pool later rejects the task. The worker then runs runResolvedSubagent, which does NOT re-fire start.
        fireSubagentStart(env, taskId, subagentName, goal, description);

        final CompletableFuture<SubagentExecutionResult> future;
        try {
            future = CompletableFuture.supplyAsync(() -> {
                handle.attachWorker(Thread.currentThread());
                taskStore.transition(taskId, BackgroundTaskState.RUNNING);
                // Background tasks are always dispatched by registered name. SubagentStart already fired above.
                final SubagentExecutionResult result = runResolvedSubagent(env, taskId,
                        SubagentTarget.byName(subagentName), goal, handle.getSignal(), outputSink);
                // Persist the finished transcript so Task(resume=<taskId>) can continue it. Background-only —
                // a foreground run's taskId is never surfaced, so its snapshot would be unreachable.
                saveSessionSnapshot(env, taskId, subagentName, result);
                return result;
            }, executorService);
        } catch (RejectedExecutionException rex) {
            // Bounded pool saturated: settle as FAILED rather than spawning an unbounded thread.
            log.warn("Background subagent task '{}' rejected: pool saturated ({})", taskId, rex.getMessage());
            final SubagentExecutionResult failure = SubagentExecutionResult
                    .emptyFailure("Background task rejected: subagent pool saturated", startTime);
            // Save before the terminal transition, per the TaskResultStore ordering contract: a reader that sees
            // FAILED must already be able to see why.
            saveTaskResult(env, taskId, failure);
            taskStore.transition(taskId, BackgroundTaskState.FAILED);
            runningTasks.remove(taskId);
            coordinator.close();
            parentCancelReg.remove();
            // SubagentStart fired on the launching thread above; balance it with a Stop for this reject-before-run path
            // (the runResolvedSubagent path fires its own Stop). Advisory; failures are swallowed inside
            // fireSubagentStop.
            fireSubagentStop(env, taskId, subagentName, failure);
            // This path never registers a whenComplete finalizer, so notify the parent here. It is mutually
            // exclusive with the finalizeBackgroundTask path, so the completion is still signalled exactly once.
            notifyParentOfCompletion(env, taskId, subagentName, BackgroundTaskState.FAILED, failure, rex);
            return CompletableFuture.completedFuture(failure);
        }

        handle.attachFuture(future);
        future.whenComplete((result, error) -> {
            try {
                finalizeBackgroundTask(env, handle, coordinator, taskId, subagentName, result, error);
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
    private void finalizeBackgroundTask(SubagentExecutionEnvironment env, RunningTaskHandle handle,
            InterruptCoordinator coordinator, String taskId, String subagentName, SubagentExecutionResult result,
            Throwable error) {
        try {
            // Before the terminal transition, so that observing a terminal state implies the result is readable.
            saveTaskResult(env, taskId, result);
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
            notifyParentOfCompletion(env, taskId, subagentName, notifiedState, result, error);
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
     * <li><b>Message queue (guaranteed, model-facing):</b> when the environment carries a {@link MessageQueueManager},
     * a
     * {@link QueuedInputPriority#NEXT}-priority notification scoped to the parent {@link AgentRuntimeId} is
     * enqueued, so the parent's ReAct loop drains and injects it on its next iteration.
     * <li><b>Stream event (best-effort, observability):</b> when the environment carries a parent event sink, a
     * {@link SubagentTaskCompleted} event is emitted for live CLI display / web SSE. It is dropped when no listener is
     * attached (the parent is idle) — the queued notification remains the guaranteed path.
     * </ul>
     *
     * <p>
     * Called exactly once per task: from {@link #finalizeBackgroundTask} on the normal path, or from the pool-rejection
     * path — the two are mutually exclusive, so no de-duplication flag is required.
     *
     * @param env
     *            the subagent execution environment (carries the parent's queue, event sink, and context id)
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
    private void notifyParentOfCompletion(SubagentExecutionEnvironment env, String taskId, String subagentName,
            BackgroundTaskState finalState, SubagentExecutionResult result, Throwable error) {
        final SubagentTaskCompleted.Outcome outcome = toOutcome(finalState);
        final String detail = completionDetail(outcome, result, error);

        env.getMessageQueueManager().ifPresent(queue -> enqueueCompletionNotification(queue, env.getAgentRuntimeId(),
                taskId, subagentName, outcome, detail));
        env.getParentEventSink().ifPresent(
                sink -> emitCompletionEvent(sink, env.getAgentRuntimeId(), taskId, subagentName, outcome, detail));
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
            raw = result != null ? result.getSummary() : null;
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
                    .metadata(Map.of("kind", "subagent-task-completed", "taskId", taskId, "outcome", outcome.name()))
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
        // handle.
        // With the default NoopTaskStopSignal this branch is effectively unreachable on a single node (a non-terminal
        // task always has its handle registered here before its taskId becomes observable), so behaviour is unchanged.
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
     *            environment's {@link SubagentRegistry}
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
