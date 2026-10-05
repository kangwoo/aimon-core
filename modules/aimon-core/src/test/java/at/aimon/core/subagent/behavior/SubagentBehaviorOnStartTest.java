package at.aimon.core.subagent.behavior;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.InvokerType;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.base.UserLocale;
import at.aimon.core.config.hook.HookConfigMerger;
import at.aimon.core.config.hook.HookConfigSource;
import at.aimon.core.config.hook.HookRegistryApplier;
import at.aimon.core.config.hook.JacksonHookConfigParser;
import at.aimon.core.config.hook.LayeredHookConfig;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.environment.TestExecutionEnvironments;
import at.aimon.core.hook.DefaultHookExecutionManager;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.HookEventType;
import at.aimon.core.hook.event.OnStartContext;
import at.aimon.core.hook.event.OnStartHook;
import at.aimon.core.hook.event.OnStopHook;
import at.aimon.core.hook.event.SubagentStartHook;
import at.aimon.core.hook.event.SubagentStopHook;
import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.shell.ExecutionOptions;
import at.aimon.core.shell.ShellCommand;
import at.aimon.core.shell.ShellCommandResult;
import at.aimon.core.shell.VirtualShell;
import at.aimon.core.skill.hook.declarative.HostShellActionExecutor;
import at.aimon.core.subagent.DefaultSubagentExecutionManager;
import at.aimon.core.subagent.InMemorySubagentRegistry;
import at.aimon.core.subagent.Subagent;
import at.aimon.core.subagent.SubagentExecutionEnvironment;
import at.aimon.core.subagent.execution.SubagentExecutionResult;
import at.aimon.core.subagent.execution.SubagentExecutor;

/**
 * EE-73: a subagent whose name has a registered {@link SubagentBehavior} fires {@code onStart} before the behavior
 * runs, and a hook that blocks ends the fork the way it ends one that runs the ReAct loop.
 */
@DisplayName("A code-behavior subagent fires onStart, and a block stops it")
class SubagentBehaviorOnStartTest {

    private static final String REASON = "BEHAVIOR-REFUSED-3c7e";

    private final SubagentExecutor reactExecutor = mock(SubagentExecutor.class);
    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private final DefaultHookRegistry hooks = new DefaultHookRegistry();
    private final ExecutionEnvironment spawner = TestExecutionEnvironments.builder().workingDirectory("/spawner")
            .build();
    private final AtomicInteger behaviorRuns = new AtomicInteger();
    private final List<OnStartContext> onStarts = new ArrayList<>();
    private final List<String> order = new ArrayList<>();
    private final List<Boolean> onStops = new ArrayList<>();
    private final List<String> subagentStopErrors = new ArrayList<>();

    SubagentBehaviorOnStartTest() {
        hooks.register(HookEventType.SUBAGENT_START, (SubagentStartHook) context -> {
            order.add("subagentStart");
            return HookResult.success();
        });
        hooks.register(HookEventType.SUBAGENT_STOP, (SubagentStopHook) context -> {
            order.add("subagentStop");
            subagentStopErrors.add(context.isSuccess() ? "" : context.getErrorMessage().orElse(""));
            return HookResult.success();
        });
        hooks.register(HookEventType.ON_STOP, (OnStopHook) context -> {
            onStops.add(context.isSuccess());
            return HookResult.success();
        });
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    @Test
    @DisplayName("onStart fires once, after subagentStart and before the behavior, with the fork's name and goal")
    void onStartFiresBeforeTheBehavior() {
        hooks.register(HookEventType.ON_START, (OnStartHook) context -> {
            order.add("onStart");
            onStarts.add(context);
            return HookResult.success();
        });

        final SubagentExecutionResult result = manager().execute(env(), "task-1", "clock", "what time?", "");

        assertThat(result.isSuccess()).isTrue();
        assertThat(order).containsExactly("subagentStart", "onStart", "behavior", "subagentStop");
        assertThat(onStarts).hasSize(1);
        final OnStartContext context = onStarts.get(0);
        assertThat(context.getInvokerType()).isEqualTo(InvokerType.SUBAGENT);
        assertThat(context.getInvokerName()).isEqualTo("clock");
        assertThat(context.getUserMessage()).isEqualTo("what time?");
        assertThat(context.getHookRegistry()).isSameAs(hooks);
        assertThat(context.getExecutionAttributes()).containsEntry("tenant", "acme");
        // A behavior resolves no environment of its own: the hook is handed the one the behavior is handed.
        assertThat(context.getExecutionEnvironment().orElseThrow()).isSameAs(spawner);
    }

    @Test
    @DisplayName("a block ends the fork before the behavior runs, as a failure carrying the reason, with no onStop")
    void aBlockStopsTheBehavior() {
        hooks.register(HookEventType.ON_START, (OnStartHook) context -> HookResult.block(REASON));

        final SubagentExecutionResult result = manager().execute(env(), "task-1", "clock", "what time?", "");

        assertThat(behaviorRuns).hasValue(0);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).isEqualTo("Execution blocked by OnStart hook [SUBAGENT/clock]: " + REASON);
        assertThat(result.getMetadata().getIterationCount()).isZero();
        assertThat(result.getMetadata().getTokenUsage().getTotalTokens()).isZero();
        assertThat(result.getSnapshot().getConversationHistory()).isEmpty();
        assertThat(onStops).as("a fork that never started has nothing to stop").isEmpty();
        assertThat(order).containsExactly("subagentStart", "subagentStop");
        assertThat(subagentStopErrors).singleElement().asString().contains(REASON);
        verify(reactExecutor, never()).execute(any(), any());
    }

    @Test
    @DisplayName("a background behavior fork is blocked too")
    void aBlockStopsABackgroundBehavior() {
        hooks.register(HookEventType.ON_START, (OnStartHook) context -> HookResult.block(REASON));

        final SubagentExecutionResult result = manager().executeInBackground(env(), "task-bg", "clock", "go", "")
                .join();

        assertThat(behaviorRuns).hasValue(0);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("OnStart", REASON);
    }

    @Test
    @DisplayName("a hooks.json onStart command handler that exits 2 stops the behavior")
    void aHooksJsonHandlerThatExitsTwoStopsTheBehavior() throws Exception {
        final VirtualShell hostShell = mock(VirtualShell.class);
        when(hostShell.execute(any(ShellCommand.class), any(ExecutionOptions.class)))
                .thenReturn(new ShellCommandResult(2, "", REASON, Duration.ofMillis(1)));
        final LayeredHookConfig layered = LayeredHookConfig.builder()
                .put(HookConfigSource.PROJECT, new JacksonHookConfigParser().parse(
                        "{\"hooks\":{\"onStart\":[{\"hooks\":[{\"type\":\"command\",\"command\":\"./gate.sh\"}]}]}}"))
                .build();
        new HookRegistryApplier(new HostShellActionExecutor(hostShell), null, null, Map.of())
                .apply(new HookConfigMerger().merge(layered), hooks);

        final SubagentExecutionResult result = manager().execute(env(), "task-1", "clock", "go", "");

        assertThat(behaviorRuns).hasValue(0);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("OnStart", REASON);
    }

    @Test
    @DisplayName("feedback from a hook that does not block does not stop the behavior")
    void advisoryFeedbackDoesNotStopTheBehavior() {
        hooks.register(HookEventType.ON_START, (OnStartHook) context -> HookResult.withFeedback("ADVICE-91c3"));

        final SubagentExecutionResult result = manager().execute(env(), "task-1", "clock", "go", "");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getFinalAnswer()).isEqualTo("tick");
        assertThat(behaviorRuns).hasValue(1);
    }

    @Test
    @DisplayName("a manager built without a hook execution manager runs the behavior and fires nothing")
    void noHookExecutionManagerFiresNothing() {
        hooks.register(HookEventType.ON_START, (OnStartHook) context -> HookResult.block(REASON));
        final DefaultSubagentExecutionManager unhooked = new DefaultSubagentExecutionManager(reactExecutor, pool, null,
                behaviors());

        final SubagentExecutionResult result = unhooked.execute(env(), "task-1", "clock", "go", "");

        assertThat(result.isSuccess()).isTrue();
        assertThat(order).containsExactly("behavior");
    }

    private DefaultSubagentExecutionManager manager() {
        return new DefaultSubagentExecutionManager(reactExecutor, pool, new DefaultHookExecutionManager(), behaviors());
    }

    private InMemorySubagentBehaviorRegistry behaviors() {
        final InMemorySubagentBehaviorRegistry behaviors = new InMemorySubagentBehaviorRegistry();
        behaviors.register("clock", (context, request, support) -> {
            behaviorRuns.incrementAndGet();
            order.add("behavior");
            return support.success("tick");
        });
        return behaviors;
    }

    private SubagentExecutionEnvironment env() {
        final InMemorySubagentRegistry subagents = new InMemorySubagentRegistry();
        subagents.register(Subagent.builder().name("clock").systemPrompt("(code)").build());
        return SubagentExecutionEnvironment.builder().agentRuntimeId(AgentRuntimeId.of("agent:test"))
                .subagentRegistry(subagents).toolRegistry(new DefaultToolRegistry()).hookRegistry(hooks)
                .userLocale(UserLocale.createDefault()).defaultModel(LlmModel.builder().name("gpt-4").build())
                .executionEnvironment(spawner).executionAttributes(Map.of("tenant", "acme")).build();
    }
}
