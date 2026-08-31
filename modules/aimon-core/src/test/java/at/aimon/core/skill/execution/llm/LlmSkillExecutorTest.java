package at.aimon.core.skill.execution.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.ExecutionId;
import at.aimon.core.agent.tool.DefaultToolExecutionManager;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.agent.tool.Tool;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.agent.tool.exception.ToolPermissionViolationException;
import at.aimon.core.agent.tool.permission.AllowedTool;
import at.aimon.core.agent.tool.permission.PermissionSubject;
import at.aimon.core.agent.tool.permission.ToolPermissionSubjectAware;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;
import at.aimon.core.llm.tool.SimpleTool;
import at.aimon.core.skill.ExecutionMode;
import at.aimon.core.skill.Skill;
import at.aimon.core.skill.SkillContent;
import at.aimon.core.skill.SkillMetadata;
import at.aimon.core.skill.execution.SkillExecutionContext;
import at.aimon.core.skill.execution.SkillExecutionRequest;
import at.aimon.core.skill.execution.SkillExecutionResult;
import at.aimon.core.skill.execution.SkillToolDispatcher;
import at.aimon.core.skill.fork.NoOpSkillForkExecutor;
import at.aimon.core.skill.fork.SkillForkExecutor;
import at.aimon.core.skill.fork.SkillForkOutcome;
import at.aimon.core.skill.render.DefaultSkillContentRenderer;
import at.aimon.core.tools.ToolContextKeys;

@DisplayName("LlmSkillExecutor Tests")
class LlmSkillExecutorTest {

    private MockLlmClient mockLlmClient;
    private DefaultSkillContentRenderer renderer;
    private List<Tool> allTools;
    private LlmSkillExecutor executor;

    @BeforeEach
    void setUp() {
        mockLlmClient = new MockLlmClient();
        renderer = new DefaultSkillContentRenderer();
        allTools = List.of(SimpleTool.of(ToolDefinition.of("Bash", "Execute bash command", Map.of("type", "object"))),
                SimpleTool.of(ToolDefinition.of("Read", "Read file", Map.of("type", "object"))),
                SimpleTool.of(ToolDefinition.of("Write", "Write file", Map.of("type", "object"))));
        executor = new LlmSkillExecutor(mockLlmClient, renderer, new DefaultToolExecutionManager());
    }

    /**
     * The run id a skill invocation used to invent for itself. It is mandatory now precisely so that the executor
     * cannot: what it minted was a {@code SessionId}, indistinguishable from a real user session.
     */
    private static ExecutionId runId() {
        return ExecutionId.generate("skill:test");
    }

    private SkillExecutionResult executeSkill(Skill skill, String arguments) {
        ToolRegistry toolRegistry = new DefaultToolRegistry();
        for (Tool tool : allTools) {
            toolRegistry.register(tool);
        }
        toolRegistry.register(new MockBashTool());

        SkillExecutionContext context = SkillExecutionContext.builder().skill(skill)
                .defaultModel(LlmModel.builder().build()).toolRegistry(toolRegistry).executionId(runId()).build();

        List<String> argsList = arguments == null || arguments.isEmpty() ? List.of() : List.of(arguments);
        SkillExecutionRequest request = SkillExecutionRequest.builder().rawArguments(arguments == null ? "" : arguments)
                .arguments(argsList).build();

        return executor.execute(context, request);
    }

    @Test
    @DisplayName("Should execute skill successfully")
    void shouldExecuteSkillSuccessfully() {
        mockLlmClient.setResponse(LlmResponse.text("I've completed the task"));

        Skill skill = createSimpleSkill("Do something");

        SkillExecutionResult result = executeSkill(skill, "test arguments");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResponse()).isEqualTo("I've completed the task");
        assertThat(result.getError()).isEmpty();
    }

    @Test
    @DisplayName("Should add rendered body to conversation as user message")
    void shouldAddRenderedBodyAsUserMessage() {
        mockLlmClient.setResponse(LlmResponse.text("Response"));

        Skill skill = createSimpleSkill("Task");
        executeSkill(skill, "args");

        List<Message> messages = mockLlmClient.getLastCalledMessages();
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).getContent()).contains("Task");
    }

    @Test
    @DisplayName("Should format !`cmd` and @file tokens via renderer")
    void shouldFormatToolCallTokens() {
        mockLlmClient.setResponse(LlmResponse.text("Done"));

        Skill skill = createSimpleSkill("Status: !`git status` and inspect @README.md");
        executeSkill(skill, "");

        List<Message> messages = mockLlmClient.getLastCalledMessages();
        assertThat(messages.get(0).getContent()).contains("Bash(git status)").contains("Read(README.md)");
    }

    @Test
    @DisplayName("Should interpolate $ARGUMENTS via renderer")
    void shouldInterpolateArguments() {
        mockLlmClient.setResponse(LlmResponse.text("Done"));

        Skill skill = createSimpleSkill("Do $ARGUMENTS");
        executeSkill(skill, "something important");

        List<Message> messages = mockLlmClient.getLastCalledMessages();
        Message userMessage = messages.get(0);
        assertThat(userMessage.getContent()).contains("something important").doesNotContain("$ARGUMENTS");
    }

    @Test
    @DisplayName("Should append ARGUMENTS trailer when no positional placeholders are present")
    void shouldAppendArgumentsTrailerWhenNoPlaceholders() {
        mockLlmClient.setResponse(LlmResponse.text("Done"));

        Skill skill = createSimpleSkill("Plain body");
        executeSkill(skill, "extra context");

        List<Message> messages = mockLlmClient.getLastCalledMessages();
        assertThat(messages.get(0).getContent()).contains("Plain body").contains("ARGUMENTS: extra context");
    }

    @Test
    @DisplayName("Should filter tools based on skill restrictions")
    void shouldFilterToolsBasedOnRestrictions() {
        mockLlmClient.setResponse(LlmResponse.text("Done"));

        Skill skill = Skill.builder().name("test")
                .metadata(SkillMetadata.builder().name("test").description("Test").allowedTools("Read").build())
                .content(SkillContent.of("Task")).build();

        executeSkill(skill, "");

        List<ToolDefinition> calledTools = mockLlmClient.getLastCalledTools();
        assertThat(calledTools).hasSize(1);
        assertThat(calledTools.get(0).getName()).isEqualTo("Read");
    }

    @Test
    @DisplayName("Should expose all tools when skill has no restrictions")
    void shouldExposeAllToolsWhenNoRestrictions() {
        mockLlmClient.setResponse(LlmResponse.text("Done"));

        Skill skill = createSimpleSkill("Task");
        executeSkill(skill, "");

        List<ToolDefinition> calledTools = mockLlmClient.getLastCalledTools();
        assertThat(calledTools).hasSize(3);
    }

    @Test
    @DisplayName("Should reject tool usage outside the allow-list")
    void shouldRejectUnauthorizedToolUsage() {
        ToolUse unauthorizedTool = ToolUse.of("id", "Write", Map.of());
        mockLlmClient.setResponse(LlmResponse.of("Using Write", List.of(unauthorizedTool)));

        Skill skill = Skill.builder().name("test")
                .metadata(SkillMetadata.builder().name("test").description("Test").allowedTools("Read").build())
                .content(SkillContent.of("Task")).build();

        SkillExecutionResult result = executeSkill(skill, "");

        assertThat(result.isFailure()).isTrue();
        assertThat(result.getResponse()).contains("Permission denied");
        assertThat(result.getError()).get().isInstanceOf(ToolPermissionViolationException.class);
    }

    @Test
    @DisplayName("Should allow permitted tool usage")
    void shouldAllowPermittedToolUsage() {
        ToolUse readTool = ToolUse.of("id", "Read", Map.of());
        mockLlmClient.setResponse(LlmResponse.of("Reading", List.of(readTool)));

        Skill skill = Skill.builder().name("test")
                .metadata(SkillMetadata.builder().name("test").description("Test").allowedTools("Read").build())
                .content(SkillContent.of("Task")).build();

        SkillExecutionResult result = executeSkill(skill, "");

        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("Should reject Bash command not matching pattern")
    void shouldRejectBashNotMatchingPattern() {
        ToolUse gitPushTool = ToolUse.of("id", "Bash", Map.of("command", "git push"));
        mockLlmClient.setResponse(LlmResponse.of("Pushing", List.of(gitPushTool)));

        Skill skill = Skill.builder().name("test").metadata(SkillMetadata.builder().name("test").description("Test")
                .allowedToolsList(List.of("Bash(git add:*)")).build()).content(SkillContent.of("Task")).build();

        SkillExecutionResult result = executeSkill(skill, "");

        assertThat(result.isFailure()).isTrue();
        assertThat(result.getResponse()).contains("Permission denied");
    }

    @Test
    @DisplayName("Should propagate LLM errors as failure result")
    void shouldHandleLlmErrors() {
        mockLlmClient.setError(new IOException("API connection failed"));

        Skill skill = createSimpleSkill("Task");

        SkillExecutionResult result = executeSkill(skill, "");

        assertThat(result.isFailure()).isTrue();
        assertThat(result.getResponse()).contains("Skill execution failed");
        assertThat(result.getError()).isPresent();
    }

    @Test
    @DisplayName("Should handle multiple tool uses in one response")
    void shouldHandleMultipleToolUses() {
        ToolUse t1 = ToolUse.of("id1", "Read", Map.of());
        ToolUse t2 = ToolUse.of("id2", "Bash", Map.of("command", "git status"));
        mockLlmClient.setResponse(LlmResponse.of("Using tools", List.of(t1, t2)));

        SkillExecutionResult result = executeSkill(createSimpleSkill("Task"), "");

        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("Should record metadata with iteration count and token usage")
    void shouldRecordMetadata() {
        mockLlmClient.setResponse(LlmResponse.text("Done"));

        SkillExecutionResult result = executeSkill(createSimpleSkill("Task"), "");

        assertThat(result.getMetadata()).isPresent();
        assertThat(result.getMetadata().get().getIterationCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("Should reject null context and request")
    void shouldRejectNullArguments() {
        SkillExecutionRequest request = SkillExecutionRequest.builder().build();
        assertThatThrownBy(() -> executor.execute(null, request)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Context cannot be null");

        SkillExecutionContext context = SkillExecutionContext.builder().skill(createSimpleSkill("Task"))
                .defaultModel(LlmModel.builder().build()).toolRegistry(new DefaultToolRegistry()).executionId(runId())
                .build();
        assertThatThrownBy(() -> executor.execute(context, null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Request cannot be null");
    }

    @Test
    @DisplayName("Should reject null constructor arguments")
    void shouldRejectNullConstructorArguments() {
        assertThatThrownBy(() -> new LlmSkillExecutor(null, renderer, new DefaultToolExecutionManager()))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("LLM client cannot be null");
        assertThatThrownBy(() -> new LlmSkillExecutor(mockLlmClient, null, new DefaultToolExecutionManager()))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("Skill content renderer cannot be null");
        assertThatThrownBy(() -> new LlmSkillExecutor(mockLlmClient, renderer, null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("Tool execution manager cannot be null");
        assertThatThrownBy(() -> new LlmSkillExecutor(mockLlmClient, renderer, new DefaultToolExecutionManager(), null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("Fork executor cannot be null");
    }

    @Test
    @DisplayName("Fork-mode skill should delegate rendered body to SkillForkExecutor and return its final answer")
    void forkModeShouldDelegateToForkExecutor() {
        final RecordingSkillForkExecutor recordingFork = new RecordingSkillForkExecutor(
                SkillForkOutcome.success("subagent answer"));
        final LlmSkillExecutor forkExecutor = new LlmSkillExecutor(mockLlmClient, renderer,
                new DefaultToolExecutionManager(), recordingFork);

        final Skill skill = createForkSkill("Forked body $ARGUMENTS", "researcher");

        final ToolContext toolContext = ToolContext.builder()
                .put(ToolContextKeys.AGENT_RUNTIME_ID, AgentRuntimeId.fromName("ctx-1")).build();
        final SkillExecutionContext context = SkillExecutionContext.builder().skill(skill)
                .defaultModel(LlmModel.builder().build()).toolRegistry(new DefaultToolRegistry()).executionId(runId())
                .toolContext(toolContext).build();
        final SkillExecutionRequest request = SkillExecutionRequest.builder().rawArguments("payload").build();

        final SkillExecutionResult result = forkExecutor.execute(context, request);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResponse()).isEqualTo("subagent answer");
        assertThat(recordingFork.lastSkill).isSameAs(skill);
        assertThat(recordingFork.lastGoal).contains("Forked body").contains("payload");
        assertThat(recordingFork.lastToolContext).isSameAs(toolContext);
        assertThat(mockLlmClient.getLastCalledMessages()).isNull();
    }

    @Test
    @DisplayName("Fork-mode failure should surface as SkillExecutionResult.failure with error message")
    void forkModeFailureShouldSurfaceAsFailure() {
        final RecordingSkillForkExecutor recordingFork = new RecordingSkillForkExecutor(
                SkillForkOutcome.failure("unknown subagent 'researcher'"));
        final LlmSkillExecutor forkExecutor = new LlmSkillExecutor(mockLlmClient, renderer,
                new DefaultToolExecutionManager(), recordingFork);

        final Skill skill = createForkSkill("Forked body", "researcher");

        final SkillExecutionContext context = SkillExecutionContext.builder().skill(skill)
                .defaultModel(LlmModel.builder().build()).toolRegistry(new DefaultToolRegistry()).executionId(runId())
                .build();
        final SkillExecutionResult result = forkExecutor.execute(context, SkillExecutionRequest.builder().build());

        assertThat(result.isFailure()).isTrue();
        assertThat(result.getResponse()).contains("Skill fork failed").contains("unknown subagent 'researcher'");
        assertThat(result.getError()).isPresent();
    }

    @Test
    @DisplayName("Default 3-arg constructor uses NoOpSkillForkExecutor — fork skills fail with clear message")
    void defaultConstructorWiresNoOpForkExecutor() {
        final Skill skill = createForkSkill("Forked body", "researcher");

        final SkillExecutionContext context = SkillExecutionContext.builder().skill(skill)
                .defaultModel(LlmModel.builder().build()).toolRegistry(new DefaultToolRegistry()).executionId(runId())
                .build();
        final SkillExecutionResult result = executor.execute(context, SkillExecutionRequest.builder().build());

        assertThat(result.isFailure()).isTrue();
        assertThat(result.getResponse()).contains("Skill fork failed").contains("fork execution is not configured");
    }

    @Test
    @DisplayName("NoOpSkillForkExecutor is exposed as a usable default — sanity check on constructor wiring")
    void noOpForkExecutorMatchesDefault() {
        final SkillForkExecutor noOp = new NoOpSkillForkExecutor();
        final SkillForkOutcome outcome = noOp.fork(createForkSkill("body", "researcher"), "goal", ToolContext.empty());
        assertThat(outcome.isSuccess()).isFalse();
        assertThat(outcome.getErrorMessage()).get().asString().contains("fork execution is not configured");
    }

    @Test
    @DisplayName("ToolContext-supplied SkillForkExecutor overrides the constructor-injected one for fork-mode skills")
    void toolContextForkExecutorOverridesConstructorInjected() {
        final RecordingSkillForkExecutor constructorFork = new RecordingSkillForkExecutor(
                SkillForkOutcome.success("from-constructor"));
        final RecordingSkillForkExecutor perContextFork = new RecordingSkillForkExecutor(
                SkillForkOutcome.success("from-tool-context"));
        final LlmSkillExecutor forkExecutor = new LlmSkillExecutor(mockLlmClient, renderer,
                new DefaultToolExecutionManager(), constructorFork);

        final Skill skill = createForkSkill("Forked body", "researcher");

        final ToolContext toolContext = ToolContext.builder()
                .put(ToolContextKeys.SKILL_FORK_EXECUTOR_KEY, perContextFork).build();
        final SkillExecutionContext context = SkillExecutionContext.builder().skill(skill)
                .defaultModel(LlmModel.builder().build()).toolRegistry(new DefaultToolRegistry()).executionId(runId())
                .toolContext(toolContext).build();

        final SkillExecutionResult result = forkExecutor.execute(context, SkillExecutionRequest.builder().build());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResponse()).isEqualTo("from-tool-context");
        assertThat(perContextFork.lastSkill).isSameAs(skill);
        assertThat(constructorFork.lastSkill).isNull();
    }

    @Test
    @DisplayName("ToolContext-supplied SkillToolDispatcher runs the batch instead of the execution manager")
    void toolContextDispatcherRunsTheBatch() {
        final ToolUse counterUse = ToolUse.of("id", "Counter", Map.of("n", 1));
        mockLlmClient.setResponses(List.of(LlmResponse.of("Counting", List.of(counterUse)), LlmResponse.text("Done")));

        final CountingTool counter = new CountingTool();
        final RecordingSkillToolDispatcher dispatcher = new RecordingSkillToolDispatcher();
        final ToolContext toolContext = ToolContext.builder().put(ToolContextKeys.SKILL_TOOL_DISPATCHER_KEY, dispatcher)
                .build();

        final SkillExecutionResult result = executeSkill(counterSkill(), counter, toolContext);

        assertThat(result.isSuccess()).isTrue();
        assertThat(dispatcher.callCount).isOne();
        assertThat(dispatcher.lastToolUses).containsExactly(counterUse);
        assertThat(dispatcher.lastToolContext).isSameAs(toolContext);
        assertThat(dispatcher.lastAllowedTools).hasSize(1);
        assertThat(dispatcher.lastIterationCount).isZero();
        // The point of the indirection: the tool did not run behind the invoker's back.
        assertThat(counter.executions).isZero();
    }

    @Test
    @DisplayName("No bound dispatcher — the batch falls back to the execution manager and the tool really runs")
    void absentDispatcherFallsBackToExecutionManager() {
        final ToolUse counterUse = ToolUse.of("id", "Counter", Map.of("n", 1));
        mockLlmClient.setResponses(List.of(LlmResponse.of("Counting", List.of(counterUse)), LlmResponse.text("Done")));

        final CountingTool counter = new CountingTool();

        final SkillExecutionResult result = executeSkill(counterSkill(), counter, ToolContext.empty());

        assertThat(result.isSuccess()).isTrue();
        assertThat(counter.executions).isOne();
    }

    private static Skill counterSkill() {
        return Skill.builder().name("test")
                .metadata(SkillMetadata.builder().name("test").description("Test").allowedTools("Counter").build())
                .content(SkillContent.of("Task")).build();
    }

    private SkillExecutionResult executeSkill(Skill skill, Tool extraTool, ToolContext toolContext) {
        final ToolRegistry toolRegistry = new DefaultToolRegistry();
        for (Tool tool : allTools) {
            toolRegistry.register(tool);
        }
        toolRegistry.register(extraTool);

        final SkillExecutionContext context = SkillExecutionContext.builder().skill(skill)
                .defaultModel(LlmModel.builder().build()).toolRegistry(toolRegistry).executionId(runId())
                .toolContext(toolContext).build();

        return executor.execute(context, SkillExecutionRequest.builder().build());
    }

    private Skill createForkSkill(String body, String agentName) {
        return Skill
                .builder().name("test").metadata(SkillMetadata.builder().name("test").description("Test")
                        .executionMode(ExecutionMode.FORK).forkAgentName(agentName).build())
                .content(SkillContent.of(body)).build();
    }

    @Test
    @DisplayName("Should fail when max iterations exceeded")
    void shouldFailWhenMaxIterationsExceeded() {
        ToolUse toolUse = ToolUse.of("id", "Read", Map.of());
        // Configure responses: tool-use loop forever
        mockLlmClient.setResponses(
                List.of(LlmResponse.of("First", List.of(toolUse)), LlmResponse.of("Second", List.of(toolUse)),
                        LlmResponse.of("Third", List.of(toolUse)), LlmResponse.text("Final")));

        Skill skill = Skill.builder().name("test")
                .metadata(SkillMetadata.builder().name("test").description("Test").maxIterations(2).build())
                .content(SkillContent.of("Task")).build();

        SkillExecutionResult result = executeSkill(skill, "");

        assertThat(result.isFailure()).isTrue();
        assertThat(result.getResponse()).contains("Max tools execution iterations (2)");
    }

    private Skill createSimpleSkill(String body) {
        return Skill.builder().name("test").metadata(SkillMetadata.builder().name("test").description("Test").build())
                .content(SkillContent.of(body)).build();
    }

    private static class MockLlmClient implements LlmClient {
        private final java.util.Queue<LlmResponse> responses = new java.util.LinkedList<>();
        private Exception error;
        private List<ToolDefinition> lastCalledTools;
        private List<Message> lastCalledMessages;

        void setResponse(LlmResponse response) {
            responses.clear();
            responses.add(response);
            if (response.hasToolUses()) {
                responses.add(LlmResponse.text("Task completed"));
            }
            error = null;
        }

        void setResponses(List<LlmResponse> queued) {
            responses.clear();
            responses.addAll(queued);
            error = null;
        }

        void setError(Exception error) {
            this.error = error;
            responses.clear();
        }

        List<ToolDefinition> getLastCalledTools() {
            return lastCalledTools;
        }

        List<Message> getLastCalledMessages() {
            return lastCalledMessages;
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            this.lastCalledTools = tools;
            this.lastCalledMessages = messages;
            if (error != null) {
                throw new RuntimeException(error);
            }
            if (responses.isEmpty()) {
                return LlmResponse.text("No more responses configured");
            }
            return responses.poll();
        }

        @Override
        public String getProviderName() {
            return "Mock LLM";
        }

    }

    private static final class RecordingSkillForkExecutor implements SkillForkExecutor {
        private final SkillForkOutcome outcome;
        Skill lastSkill;
        String lastGoal;
        ToolContext lastToolContext;

        RecordingSkillForkExecutor(SkillForkOutcome outcome) {
            this.outcome = outcome;
        }

        @Override
        public SkillForkOutcome fork(Skill skill, String goal, ToolContext toolContext) {
            this.lastSkill = skill;
            this.lastGoal = goal;
            this.lastToolContext = toolContext;
            return outcome;
        }
    }

    /**
     * Records the batch it was handed and answers success without touching any tool, so a test can tell "the
     * dispatcher ran the call" apart from "the execution manager ran the call".
     */
    private static final class RecordingSkillToolDispatcher implements SkillToolDispatcher {
        int callCount;
        ToolContext lastToolContext;
        List<ToolUse> lastToolUses;
        List<AllowedTool> lastAllowedTools;
        int lastIterationCount = -1;

        @Override
        public List<ToolUseResult> dispatch(ToolRegistry toolRegistry, ToolContext toolContext, List<ToolUse> toolUses,
                List<AllowedTool> allowedTools, int iterationCount) {
            this.callCount++;
            this.lastToolContext = toolContext;
            this.lastToolUses = toolUses;
            this.lastAllowedTools = allowedTools;
            this.lastIterationCount = iterationCount;
            return toolUses.stream().map(use -> ToolUseResult.success(use.getId(), "dispatched")).toList();
        }
    }

    /** Counts its own executions so a test can assert whether the tool was actually reached. */
    private static final class CountingTool implements Tool {
        int executions;

        @Override
        public ToolDefinition getDefinition() {
            return ToolDefinition.of("Counter", "Counts executions", Map.of("type", "object"));
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext context) {
            this.executions++;
            return ToolResult.success("counted");
        }
    }

    /** Names the same subject the real {@code BashTool} does, so {@code Bash(...)} specs are judged the same way. */
    private static class MockBashTool implements Tool, ToolPermissionSubjectAware {

        @Override
        public ToolDefinition getDefinition() {
            return ToolDefinition.of("Bash", "Execute bash command", Map.of("type", "object"));
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext context) {
            return ToolResult.success("Mock bash execution");
        }

        @Override
        public Optional<PermissionSubject> permissionSubject(ToolInput input, ToolContext context) {
            return input.get("command") instanceof String command && !command.isBlank()
                    ? Optional.of(PermissionSubject.command(command))
                    : Optional.empty();
        }
    }
}
