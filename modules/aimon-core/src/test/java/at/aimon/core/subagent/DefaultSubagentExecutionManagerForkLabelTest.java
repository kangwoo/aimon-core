package at.aimon.core.subagent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.interrupt.InterruptBehavior;
import at.aimon.core.agent.prompt.SystemPromptParts;
import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.ConcurrencyBehavior;
import at.aimon.core.agent.tool.DedicatedThreadConfig;
import at.aimon.core.agent.tool.DedicatedThreadDispatcher;
import at.aimon.core.agent.tool.DedicatedThreadPermits;
import at.aimon.core.agent.tool.DefaultParallelToolDispatcher;
import at.aimon.core.agent.tool.DefaultToolExecutionManager;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.agent.tool.SideEffectLevel;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.hook.DefaultHookExecutionManager;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.HookEventType;
import at.aimon.core.hook.event.SubagentStartContext;
import at.aimon.core.hook.event.SubagentStopContext;
import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmCancellation;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.subagent.behavior.InMemorySubagentBehaviorRegistry;
import at.aimon.core.subagent.execution.DefaultSubagentExecutor;
import at.aimon.core.subagent.execution.SubagentExecutionResult;
import at.aimon.core.tools.ForkAccess;
import at.aimon.core.tools.ToolContextKeys;
import at.aimon.core.tools.task.TaskTool;

/**
 * Parallel foreground tasks, manager and fork-executor side: a fork's depth reaches its tools and its dispatcher, and
 * — only when enabled — each fork carries its own task id in its execution attributes, the same value in the
 * {@code SubagentStart} hook, the fork's tool contexts and the {@code SubagentStop} hook.
 */
@DisplayName("DefaultSubagentExecutionManager — fork depth and task id attribute")
class DefaultSubagentExecutionManagerForkLabelTest {

    private static final LlmModel MODEL = LlmModel.builder().name("gpt-4").build();
    private static final Map<String, Object> SPAWNER_ATTRIBUTES = Map.of("spawner", "turn");

    private final ExecutorService bgPool = Executors.newSingleThreadExecutor();
    private ScriptedLlmClient llm;
    private InMemorySubagentRegistry subagents;
    private DefaultToolRegistry tools;
    private DefaultHookRegistry hooks;
    private ProbeTool probe;
    private final List<SubagentStartContext> starts = new CopyOnWriteArrayList<>();
    private final List<SubagentStopContext> stops = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        llm = new ScriptedLlmClient();
        subagents = new InMemorySubagentRegistry();
        subagents.register(Subagent.builder().name("explorer").systemPrompt("explore").maxIterations(4).build());
        subagents.register(Subagent.builder().name("inner").systemPrompt("inner").maxIterations(3).build());
        tools = new DefaultToolRegistry();
        probe = new ProbeTool();
        tools.register(probe);
        hooks = new DefaultHookRegistry();
        hooks.register(HookEventType.SUBAGENT_START, context -> {
            starts.add(context);
            return HookResult.success();
        });
        hooks.register(HookEventType.SUBAGENT_STOP, context -> {
            stops.add(context);
            return HookResult.success();
        });
    }

    @AfterEach
    void tearDown() {
        bgPool.shutdownNow();
    }

    // ── fork depth ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the launch context's fork depth reaches the fork's tools as FORK_DEPTH")
    void forkDepthReachesTools() {
        llm.responses.add(LlmResponse.of("", List.of(ToolUse.of("p1", "Probe", Map.of()))));
        llm.responses.add(LlmResponse.text("done"));

        manager(executor()).execute(launch().toBuilder().forkDepth(2).build(), "task-1", "explorer", "go", "d");

        assertThat(probe.contexts).singleElement()
                .satisfies(c -> assertThat(c.get(ToolContextKeys.FORK_DEPTH)).contains(2));
    }

    @Test
    @DisplayName("with maxForkDepth 2 a depth-1 fork fans its dedicated tools out; with the default it stays sequential")
    void forkFanOutFollowsMaxForkDepth() {
        final SlowTool slow = new SlowTool(2);
        tools.register(slow);
        final List<ToolUse> twoSlow = List.of(ToolUse.of("s1", "Slow", Map.of()), ToolUse.of("s2", "Slow", Map.of()));

        llm.responses.add(LlmResponse.of("", twoSlow));
        llm.responses.add(LlmResponse.text("done"));
        final DedicatedThreadConfig deep = DedicatedThreadConfig.builder().enabled(true).maxForkDepth(2).build();
        final SubagentExecutionResult fanned = manager(
                executor().withParallelToolDispatcher(new DedicatedThreadDispatcher(
                        DefaultParallelToolDispatcher.sequential(), deep, DedicatedThreadPermits.of(deep))))
                .execute(launch(), "task-1", "explorer", "go", "d");

        assertThat(fanned.isSuccess()).isTrue();
        assertThat(slow.threads).hasSize(2).doesNotHaveDuplicates();
        assertThat(slow.barrierBroken).isEmpty();

        slow.threads.clear();
        final SlowTool sequentialSlow = new SlowTool(1);
        tools.register(sequentialSlow);
        llm.responses.add(LlmResponse.of("", twoSlow));
        llm.responses.add(LlmResponse.text("done"));
        final DedicatedThreadConfig shallow = DedicatedThreadConfig.enabled(4);
        manager(executor().withParallelToolDispatcher(new DedicatedThreadDispatcher(
                DefaultParallelToolDispatcher.sequential(), shallow, DedicatedThreadPermits.of(shallow))))
                .execute(launch(), "task-2", "explorer", "go", "d");

        // Default maxForkDepth 1: the fork (depth 1) is not allowed to fan out — both calls on one thread.
        assertThat(sequentialSlow.threads).hasSize(2);
        assertThat(Set.copyOf(sequentialSlow.threads)).hasSize(1);
    }

    // ── task id attribute ───────────────────────────────────────────────────────

    @Test
    @DisplayName("off (default): Start, the fork's tools and Stop see exactly the spawner's attributes")
    void offLeavesAttributesUnchanged() {
        llm.responses.add(LlmResponse.of("", List.of(ToolUse.of("p1", "Probe", Map.of()))));
        llm.responses.add(LlmResponse.text("done"));
        final DefaultSubagentExecutionManager manager = manager(executor());

        manager.execute(launch(), "task-1", "explorer", "go", "d");

        assertThat(manager.isForkTaskIdAttribute()).isFalse();
        assertThat(starts).singleElement()
                .satisfies(s -> assertThat(s.getExecutionAttributes()).isEqualTo(SPAWNER_ATTRIBUTES));
        assertThat(stops).singleElement()
                .satisfies(s -> assertThat(s.getExecutionAttributes()).isEqualTo(SPAWNER_ATTRIBUTES));
        assertThat(probe.contexts).singleElement().satisfies(c -> {
            assertThat(c.get(ToolContextKeys.EXECUTION_ATTRIBUTES_KEY)).contains(SPAWNER_ATTRIBUTES);
            assertThat(ForkAccess.taskIdOf(c)).isEmpty();
        });
    }

    @Test
    @DisplayName("on: Start, the fork's tools and Stop all carry the fork's own task id; the spawner's map is untouched")
    void onLabelsEveryConsumerWithTheSameId() {
        llm.responses.add(LlmResponse.of("", List.of(ToolUse.of("p1", "Probe", Map.of()))));
        llm.responses.add(LlmResponse.text("done"));
        final SubagentLaunchContext launch = launch();

        manager(executor()).withForkTaskIdAttribute().execute(launch, "task-1", "explorer", "go", "d");

        assertThat(starts).singleElement().satisfies(s -> {
            assertThat(ForkAccess.taskIdOf(s.getExecutionAttributes())).contains("task-1").contains(s.getTaskId());
            assertThat(s.getExecutionAttributes()).containsAllEntriesOf(SPAWNER_ATTRIBUTES);
        });
        assertThat(stops).singleElement()
                .satisfies(s -> assertThat(ForkAccess.taskIdOf(s.getExecutionAttributes())).contains("task-1"));
        assertThat(probe.contexts).singleElement()
                .satisfies(c -> assertThat(ForkAccess.taskIdOf(c)).contains("task-1"));
        assertThat(launch.getExecutionAttributes()).isEqualTo(SPAWNER_ATTRIBUTES);
    }

    @Test
    @DisplayName("on, background entry point: Start and Stop carry the task id")
    void onLabelsBackgroundRuns() throws Exception {
        llm.responses.add(LlmResponse.text("done"));

        manager(executor()).withForkTaskIdAttribute().executeInBackground(launch(), "task-bg", "explorer", "go", "d")
                .get(5, TimeUnit.SECONDS);

        assertThat(starts).singleElement()
                .satisfies(s -> assertThat(ForkAccess.taskIdOf(s.getExecutionAttributes())).contains("task-bg"));
        assertThat(stops).singleElement()
                .satisfies(s -> assertThat(ForkAccess.taskIdOf(s.getExecutionAttributes())).contains("task-bg"));
    }

    @Test
    @DisplayName("on, background pool rejection: the Stop hook still carries the task id")
    void onLabelsRejectedBackgroundRuns() {
        final ExecutorService closed = Executors.newSingleThreadExecutor();
        closed.shutdown();
        final DefaultSubagentExecutionManager manager = new DefaultSubagentExecutionManager(executor(), closed,
                new DefaultHookExecutionManager(), new InMemorySubagentBehaviorRegistry(), llm)
                .withForkTaskIdAttribute();

        manager.executeInBackground(launch(), "task-rej", "explorer", "go", "d");

        assertThat(starts).singleElement()
                .satisfies(s -> assertThat(ForkAccess.taskIdOf(s.getExecutionAttributes())).contains("task-rej"));
        assertThat(stops).singleElement()
                .satisfies(s -> assertThat(ForkAccess.taskIdOf(s.getExecutionAttributes())).contains("task-rej"));
    }

    @Test
    @DisplayName("on, nested: a fork launched from a fork carries its own id; the outer fork keeps its id")
    void nestedForkOverwritesWithItsOwnId() {
        final DefaultSubagentExecutionManager manager = manager(executor()).withForkTaskIdAttribute();
        tools.register(new TaskTool(MODEL, subagents, tools, hooks, manager));
        // outer#1: Probe then Task(inner); inner#1: Probe; inner#2: answer; outer#2: answer.
        llm.responses.add(LlmResponse.of("", List.of(ToolUse.of("p-outer", "Probe", Map.of()), ToolUse.of("t1", "Task",
                Map.of("subagent_name", "inner", "prompt", "dig", "description", "dig deeper")))));
        llm.responses.add(LlmResponse.of("", List.of(ToolUse.of("p-inner", "Probe", Map.of()))));
        llm.responses.add(LlmResponse.text("inner done"));
        llm.responses.add(LlmResponse.text("outer done"));

        manager.execute(launch(), "task-outer", "explorer", "go", "d");

        assertThat(starts).hasSize(2);
        final SubagentStartContext innerStart = starts.stream().filter(s -> s.getSubagentName().equals("inner"))
                .findFirst().orElseThrow();
        assertThat(innerStart.getTaskId()).isNotEqualTo("task-outer");
        assertThat(ForkAccess.taskIdOf(innerStart.getExecutionAttributes())).contains(innerStart.getTaskId());
        final SubagentStopContext innerStop = stops.stream().filter(s -> s.getSubagentName().equals("inner"))
                .findFirst().orElseThrow();
        assertThat(ForkAccess.taskIdOf(innerStop.getExecutionAttributes())).contains(innerStart.getTaskId());

        assertThat(probe.contexts).hasSize(2);
        assertThat(ForkAccess.taskIdOf(probe.contexts.get(0))).contains("task-outer");
        assertThat(ForkAccess.taskIdOf(probe.contexts.get(1))).contains(innerStart.getTaskId());
        // And the inner fork is one deeper than the outer one.
        assertThat(probe.contexts.get(0).get(ToolContextKeys.FORK_DEPTH)).contains(1);
        assertThat(probe.contexts.get(1).get(ToolContextKeys.FORK_DEPTH)).contains(2);
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private DefaultSubagentExecutor executor() {
        return new DefaultSubagentExecutor(llm, new DefaultToolExecutionManager(), new DefaultHookExecutionManager());
    }

    private DefaultSubagentExecutionManager manager(DefaultSubagentExecutor executor) {
        return new DefaultSubagentExecutionManager(executor, bgPool, new DefaultHookExecutionManager(),
                new InMemorySubagentBehaviorRegistry(), llm);
    }

    private SubagentLaunchContext launch() {
        return SubagentLaunchContext.builder().agentRuntimeId(AgentRuntimeId.of("agent:test"))
                .subagentRegistry(subagents).toolRegistry(tools).hookRegistry(hooks).defaultModel(MODEL)
                .executionAttributes(SPAWNER_ATTRIBUTES).build();
    }

    /** Records the context of every call. Read-only so no approval is involved. */
    private static final class ProbeTool extends AbstractTool {
        private final List<ToolContext> contexts = new CopyOnWriteArrayList<>();

        ProbeTool() {
            super("Probe", "Captures its tool context", Map.of("type", "object", "properties", Map.of()));
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext context) {
            contexts.add(context);
            return ToolResult.success("probed");
        }

        @Override
        public SideEffectLevel getSideEffectLevel() {
            return SideEffectLevel.READ_ONLY;
        }
    }

    /** A DEDICATED_THREAD tool whose calls meet at a barrier sized to how many are expected to run together. */
    private static final class SlowTool extends AbstractTool {
        private final CyclicBarrier barrier;
        private final List<Thread> threads = new CopyOnWriteArrayList<>();
        private final Set<String> barrierBroken = ConcurrentHashMap.newKeySet();

        SlowTool(int parties) {
            super("Slow", "Waits for its siblings", Map.of("type", "object", "properties", Map.of()));
            this.barrier = new CyclicBarrier(parties);
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext context) {
            threads.add(Thread.currentThread());
            try {
                barrier.await(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                barrierBroken.add(e.toString());
            }
            return ToolResult.success("slow done");
        }

        @Override
        public ConcurrencyBehavior getConcurrencyBehavior() {
            return ConcurrencyBehavior.DEDICATED_THREAD;
        }

        @Override
        public InterruptBehavior getInterruptBehavior() {
            return InterruptBehavior.COOPERATIVE;
        }

        @Override
        public SideEffectLevel getSideEffectLevel() {
            return SideEffectLevel.READ_ONLY;
        }
    }

    /** Returns scripted responses in order. */
    private static final class ScriptedLlmClient implements LlmClient {
        private final List<LlmResponse> responses = Collections.synchronizedList(new ArrayList<>());

        @Override
        public LlmResponse sendMessage(SystemPromptParts systemPromptParts, List<Message> messages,
                List<ToolDefinition> tools, LlmModel modelConfig, LlmCallMetadata metadata,
                LlmCancellation cancellation) {
            return next();
        }

        @Override
        public LlmResponse sendMessage(SystemPromptParts systemPromptParts, List<Message> messages,
                List<ToolDefinition> tools, LlmModel modelConfig, LlmCallMetadata metadata) {
            return next();
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            return next();
        }

        private LlmResponse next() {
            synchronized (responses) {
                return responses.isEmpty() ? LlmResponse.text("(script exhausted)") : responses.remove(0);
            }
        }

        @Override
        public String getProviderName() {
            return "Scripted";
        }
    }
}
