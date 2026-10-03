package at.aimon.core.subagent.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.Environment;
import at.aimon.core.agent.budget.CompletionReason;
import at.aimon.core.agent.interrupt.NoopCancellationSignal;
import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.DefaultToolExecutionManager;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.environment.ExecutionEnvironmentProvider;
import at.aimon.core.environment.TestExecutionEnvironments;
import at.aimon.core.hook.DefaultHookExecutionManager;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.HookEventType;
import at.aimon.core.hook.event.OnStartHook;
import at.aimon.core.hook.event.OnStopHook;
import at.aimon.core.hook.event.PostToolHook;
import at.aimon.core.hook.event.PreToolHook;
import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.StopReason;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.shell.ExecutionOptions;
import at.aimon.core.shell.ShellCommand;
import at.aimon.core.shell.ShellCommandResult;
import at.aimon.core.shell.VirtualShell;
import at.aimon.core.skill.hook.action.ShellAction;
import at.aimon.core.skill.hook.declarative.DeclarativePreToolHook;
import at.aimon.core.skill.hook.declarative.DefaultShellActionExecutor;
import at.aimon.core.skill.hook.declarative.SkillHookEnv;
import at.aimon.core.skill.hook.declarative.predicate.NameOnlyPredicate;
import at.aimon.core.subagent.Subagent;
import at.aimon.core.subagent.SubagentContent;
import at.aimon.core.subagent.SubagentMetadata;
import at.aimon.core.tools.ToolContextKeys;

/**
 * EE-9 / EE-12 on the fork path: the hooks a fork fires carry <b>the fork's own</b> execution environment — the one
 * its provider resolved for it, the one its tools run in — and not the spawning execution's.
 *
 * <p>
 * The two are the same instance under the local provider, which is why these tests use a provider that answers with a
 * distinct environment: a sandbox provider that gives each fork its own. A skill-declared shell hook must then run in
 * the fork's shell, which is the whole reason the executor takes its shell from the firing context instead of holding
 * one from parse time.
 */
@DisplayName("DefaultSubagentExecutor: hooks carry the fork's own execution environment")
class DefaultSubagentExecutorHookEnvironmentTest {

    private static final TokenUsage USAGE = TokenUsage.of(5, 5, 10);

    private final VirtualShell spawnerShell = mock(VirtualShell.class);
    private final VirtualShell forkShell = mock(VirtualShell.class);
    private final ExecutionEnvironment spawnerEnvironment = TestExecutionEnvironments.builder().shell(spawnerShell)
            .workingDirectory("/spawner").build();
    private final ExecutionEnvironment forkEnvironment = TestExecutionEnvironments.builder().shell(forkShell)
            .workingDirectory("/fork").build();
    /** Stands in for a sandbox provider: every fork gets its own environment, whatever the parent's is. */
    private final ExecutionEnvironmentProvider provider = request -> forkEnvironment;

    @Test
    @DisplayName("onStart, the tool hooks and the success onStop all see the fork's environment — and so does the tool")
    void everyHookOfASuccessfulForkSeesTheForksEnvironment() {
        final StubLlmClient llm = new StubLlmClient();
        llm.responses.add(LlmResponse.of("act", List.of(ToolUse.of("t1", ProbeTool.TOOL_NAME, Map.of())), USAGE));
        llm.responses.add(LlmResponse.of("done", List.of(), USAGE));
        final List<Optional<ExecutionEnvironment>> seen = new ArrayList<>();
        final DefaultHookRegistry hooks = new DefaultHookRegistry();
        hooks.register(HookEventType.ON_START,
                (OnStartHook) context -> record(seen, context.getExecutionEnvironment()));
        hooks.register(HookEventType.PRE_TOOL,
                (PreToolHook) context -> record(seen, context.getExecutionEnvironment()));
        hooks.register(HookEventType.POST_TOOL,
                (PostToolHook) context -> record(seen, context.getExecutionEnvironment()));
        hooks.register(HookEventType.ON_STOP, (OnStopHook) context -> record(seen, context.getExecutionEnvironment()));
        final ProbeTool probe = new ProbeTool();

        final SubagentExecutionResult result = execute(llm, registryWith(probe), hooks, 10);

        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.COMPLETED);
        assertThat(seen).hasSize(4).allSatisfy(env -> assertThat(env.orElseThrow()).isSameAs(forkEnvironment));
        // One instance for hooks and tools: a hook's shell action lands where the tool's command would.
        assertThat(probe.seen).isSameAs(forkEnvironment);
    }

    @Test
    @DisplayName("the failure onStop sees the fork's environment too")
    void theFailureOnStopSeesTheForksEnvironment() {
        final StubLlmClient llm = new StubLlmClient();
        for (int i = 0; i < 5; i++) {
            llm.responses
                    .add(LlmResponse.of("act", List.of(ToolUse.of("t" + i, ProbeTool.TOOL_NAME, Map.of())), USAGE));
        }
        final List<Optional<ExecutionEnvironment>> seen = new ArrayList<>();
        final DefaultHookRegistry hooks = new DefaultHookRegistry();
        hooks.register(HookEventType.ON_STOP, (OnStopHook) context -> {
            assertThat(context.isSuccess()).isFalse();
            return record(seen, context.getExecutionEnvironment());
        });

        // maxIterations=1 with a tool-calling model: the fork ends on the failure path.
        final SubagentExecutionResult result = execute(llm, registryWith(new ProbeTool()), hooks, 1);

        assertThat(result.isSuccess()).isFalse();
        assertThat(seen).singleElement().satisfies(env -> assertThat(env.orElseThrow()).isSameAs(forkEnvironment));
    }

    @Test
    @DisplayName("the onStop of a fork whose final answer was cut at max_tokens sees the fork's environment")
    void theTruncatedOnStopSeesTheForksEnvironment() {
        final StubLlmClient llm = new StubLlmClient();
        llm.responses.add(LlmResponse.of("partial", List.of(), USAGE, StopReason.MAX_TOKENS));
        final List<Optional<ExecutionEnvironment>> seen = new ArrayList<>();
        final DefaultHookRegistry hooks = new DefaultHookRegistry();
        hooks.register(HookEventType.ON_STOP, (OnStopHook) context -> record(seen, context.getExecutionEnvironment()));

        final SubagentExecutionResult result = execute(llm, registryWith(new ProbeTool()), hooks, 10);

        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.TRUNCATED);
        assertThat(seen).singleElement().satisfies(env -> assertThat(env.orElseThrow()).isSameAs(forkEnvironment));
    }

    @Test
    @DisplayName("a skill-declared preTool shell hook runs in the fork's shell, never the spawner's")
    void aSkillShellHookRunsInTheForksShell() throws Exception {
        when(forkShell.execute(any(ShellCommand.class), any(ExecutionOptions.class)))
                .thenReturn(new ShellCommandResult(0, "", "", Duration.ofMillis(1)));
        final StubLlmClient llm = new StubLlmClient();
        llm.responses.add(LlmResponse.of("act", List.of(ToolUse.of("t1", ProbeTool.TOOL_NAME, Map.of())), USAGE));
        llm.responses.add(LlmResponse.of("done", List.of(), USAGE));
        final DefaultHookRegistry hooks = new DefaultHookRegistry();
        // Built exactly as SkillHookSetParser builds it: one executor, created at parse time, holding no shell.
        hooks.register(HookEventType.PRE_TOOL, new DeclarativePreToolHook("audit-skill", NameOnlyPredicate.ANY,
                new ShellAction("./audit.sh", Duration.ofSeconds(5)), new DefaultShellActionExecutor()));

        final SubagentExecutionResult result = execute(llm, registryWith(new ProbeTool()), hooks, 10);

        assertThat(result.isSuccess()).isTrue();
        final ArgumentCaptor<ShellCommand> command = ArgumentCaptor.forClass(ShellCommand.class);
        final ArgumentCaptor<ExecutionOptions> options = ArgumentCaptor.forClass(ExecutionOptions.class);
        verify(forkShell).execute(command.capture(), options.capture());
        verifyNoInteractions(spawnerShell);
        assertThat(command.getValue().asString()).isEqualTo("./audit.sh");
        assertThat(options.getValue().getEnvironment()).containsEntry(SkillHookEnv.AIMON_SKILL_NAME, "audit-skill")
                .containsEntry(SkillHookEnv.AIMON_TOOL_NAME, ProbeTool.TOOL_NAME);
        assertThat(options.getValue().getStdin()).contains(ProbeTool.TOOL_NAME);
    }

    @Test
    @DisplayName("a skill shell hook that vetoes (exit 2) in the fork's shell blocks the fork's tool")
    void aVetoFromTheForksShellBlocksTheTool() throws Exception {
        when(forkShell.execute(any(ShellCommand.class), any(ExecutionOptions.class)))
                .thenReturn(new ShellCommandResult(2, "", "not in this sandbox", Duration.ofMillis(1)));
        final StubLlmClient llm = new StubLlmClient();
        llm.responses.add(LlmResponse.of("act", List.of(ToolUse.of("t1", ProbeTool.TOOL_NAME, Map.of())), USAGE));
        llm.responses.add(LlmResponse.of("done", List.of(), USAGE));
        final DefaultHookRegistry hooks = new DefaultHookRegistry();
        hooks.register(HookEventType.PRE_TOOL, new DeclarativePreToolHook("guard-skill", NameOnlyPredicate.ANY,
                new ShellAction("./guard.sh", Duration.ofSeconds(5)), new DefaultShellActionExecutor()));
        final ProbeTool probe = new ProbeTool();

        execute(llm, registryWith(probe), hooks, 10);

        assertThat(probe.seen).as("the tool never ran").isNull();
    }

    private static HookResult record(List<Optional<ExecutionEnvironment>> seen,
            Optional<ExecutionEnvironment> environment) {
        seen.add(environment);
        return HookResult.success();
    }

    private SubagentExecutionResult execute(LlmClient llm, DefaultToolRegistry registry,
            DefaultHookRegistry hookRegistry, int maxIterations) {
        final Subagent subagent = Subagent.of("explorer",
                SubagentMetadata.builder().description("d").maxIterations(maxIterations).build(),
                SubagentContent.of("you are explorer"));
        final SubagentExecutionContext context = SubagentExecutionContext.builder()
                .agentRuntimeId(AgentRuntimeId.of("agent:test-1")).subagent(subagent)
                .defaultModel(LlmModel.builder().name("gpt-4").build()).toolRegistry(registry)
                .hookRegistry(hookRegistry).environment(Environment.createDefault())
                .executionEnvironment(spawnerEnvironment).executionEnvironmentProvider(provider)
                .parentCancellationSignal(NoopCancellationSignal.INSTANCE).build();
        return new DefaultSubagentExecutor(llm, new DefaultToolExecutionManager(), new DefaultHookExecutionManager())
                .execute(context, SubagentExecutionRequest.builder().taskId("task-1").goal("go").build());
    }

    private static DefaultToolRegistry registryWith(AbstractTool tool) {
        final DefaultToolRegistry registry = new DefaultToolRegistry();
        registry.register(tool);
        return registry;
    }

    /** Records the execution environment its tool context carries. */
    private static final class ProbeTool extends AbstractTool {
        static final String TOOL_NAME = "ProbeTool";

        private volatile ExecutionEnvironment seen;

        ProbeTool() {
            super(TOOL_NAME, "records the execution environment it runs in",
                    Map.of("type", "object", "properties", Map.of(), "required", List.of()));
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext context) {
            seen = context.get(ToolContextKeys.EXECUTION_ENVIRONMENT).orElse(null);
            return ToolResult.success("probed");
        }
    }

    /** Returns queued responses in order. */
    private static final class StubLlmClient implements LlmClient {
        private final Deque<LlmResponse> responses = new ArrayDeque<>();

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            return sendMessage(systemPrompt, messages, tools, modelConfig, LlmCallMetadata.empty());
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig, LlmCallMetadata metadata) {
            return responses.isEmpty() ? LlmResponse.text("unexpected-extra-call") : responses.poll();
        }

        @Override
        public String getProviderName() {
            return "Stub";
        }
    }
}
