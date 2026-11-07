package at.aimon.core.tools.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.ExecutionId;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.transcript.SessionSnapshot;
import at.aimon.core.agent.tool.DefaultToolExecutionManager;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.permission.AllowedTool;
import at.aimon.core.command.execution.ExecutionMetadata;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.tool.SimpleTool;
import at.aimon.core.skill.Skill;
import at.aimon.core.skill.SkillContent;
import at.aimon.core.skill.SkillMetadata;
import at.aimon.core.skill.execution.SkillExecutionContext;
import at.aimon.core.skill.execution.SkillExecutionRequest;
import at.aimon.core.skill.execution.SkillExecutionResult;
import at.aimon.core.skill.execution.llm.LlmSkillExecutor;
import at.aimon.core.skill.render.DefaultSkillContentRenderer;
import at.aimon.core.subagent.InMemorySubagentRegistry;
import at.aimon.core.subagent.Subagent;
import at.aimon.core.subagent.SubagentExecutionManager;
import at.aimon.core.subagent.SubagentLaunchContext;
import at.aimon.core.subagent.execution.SubagentExecutionResult;
import at.aimon.core.toolinvocation.ToolCallDispatchers;
import at.aimon.core.tools.ToolContextKeys;
import at.aimon.core.workflow.WorkflowRunner;
import at.aimon.core.workflow.WorkflowRunners;

/**
 * TC-25 end to end: a slash skill whose {@code allowed-tools} are [Read, Workflow] launches a background
 * {@code Workflow}; the run's subagents are launched under the skill's allow-list, not the agent's.
 *
 * <p>
 * Driven from the skill loop down: the real {@link LlmSkillExecutor} dispatches {@code Workflow} through the caller's
 * dispatcher narrowed to the skill (which publishes the skill's allow-list as {@code CALLER_ALLOWED_TOOLS}), the real
 * {@link WorkflowTool} submits to a real agent-scoped background runner whose base launch context carries the agent's
 * wider allow-list, and the subagent manager records the launch context each subagent gets. Before TC-25 the run's
 * subagents were launched under the agent's list ([Read, Grep, Bash, Workflow]).
 */
@DisplayName("Background Workflow ceiling e2e (slash skill → Workflow background → subagent launch context)")
class BackgroundWorkflowCeilingE2EIntegrationTest {

    private static final List<AllowedTool> AGENT_ALLOWED = tools("Read", "Grep", "Bash", "Workflow");

    private final List<SubagentLaunchContext> launches = new CopyOnWriteArrayList<>();
    private WorkflowRunner backgroundRunner;

    @AfterEach
    void tearDown() {
        if (backgroundRunner != null) {
            backgroundRunner.close();
        }
    }

    @Test
    @DisplayName("/probe [Read, Workflow] launches a background Workflow whose subagents get callerAllowedTools ⊆"
            + " [Read, Workflow]")
    void backgroundRunInheritsTheSkillCeiling() throws Exception {
        final SubagentExecutionManager manager = mock(SubagentExecutionManager.class);
        when(manager.execute(any(SubagentLaunchContext.class), any(Subagent.class), anyString()))
                .thenAnswer(invocation -> {
                    launches.add(invocation.getArgument(0, SubagentLaunchContext.class));
                    return SubagentExecutionResult.success("analysis", SessionSnapshot.of(SessionId.generate()),
                            ExecutionMetadata.builder().iterationCount(1).tokenUsage(TokenUsage.empty())
                                    .timestamps(Instant.now(), Instant.now()).build());
                });
        final LlmModel model = LlmModel.builder().name("gpt-4").build();
        final DefaultToolRegistry registry = new DefaultToolRegistry();
        backgroundRunner = WorkflowRunners.create(manager,
                SubagentLaunchContext.builder().agentRuntimeId(AgentRuntimeId.of("agent:test"))
                        .subagentRegistry(new InMemorySubagentRegistry()).toolRegistry(registry)
                        .hookRegistry(new DefaultHookRegistry()).defaultModel(model).callerAllowedTools(AGENT_ALLOWED)
                        .build());
        registry.register(new WorkflowTool(model, new InMemorySubagentRegistry(), registry, new DefaultHookRegistry(),
                manager, List.of(), backgroundRunner));
        registry.register(SimpleTool.of(ToolDefinition.of("Read", "Read a file", Map.of("type", "object"))));
        registry.register(SimpleTool.of(ToolDefinition.of("Grep", "Search", Map.of("type", "object"))));
        registry.register(SimpleTool.of(ToolDefinition.of("Bash", "Run", Map.of("type", "object"))));

        final Skill probe = Skill
                .builder().name("probe").metadata(SkillMetadata.builder().name("probe").description("probe")
                        .allowedTools("Read Workflow").maxIterations(3).build())
                .content(SkillContent.of("Probe.")).build();
        final ToolContext callerContext = ToolContext.builder()
                .put(ToolContextKeys.TOOL_CALL_DISPATCHER,
                        ToolCallDispatchers.ungated(new DefaultToolExecutionManager(), registry))
                .put(ToolContextKeys.CALLER_ALLOWED_TOOLS, AGENT_ALLOWED).build();
        final LlmClient llm = new BackgroundWorkflowLlm();

        final SkillExecutionResult result = new LlmSkillExecutor(llm, new DefaultSkillContentRenderer(),
                new DefaultToolExecutionManager())
                .execute(
                        SkillExecutionContext.builder().skill(probe).defaultModel(model).toolRegistry(registry)
                                .executionId(ExecutionId.generate("skill:probe")).toolContext(callerContext).build(),
                        SkillExecutionRequest.builder().rawArguments("").arguments(List.of()).build());

        assertThat(result.isSuccess()).as(result.getResponse()).isTrue();
        final Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (launches.size() < 2 && Instant.now().isBefore(deadline)) {
            Thread.sleep(10);
        }
        assertThat(launches).hasSize(2).allSatisfy(launch -> assertThat(launch.getCallerAllowedTools()).isNotEmpty()
                .isSubsetOf(tools("Read", "Workflow")));
    }

    private static List<AllowedTool> tools(String... names) {
        return java.util.Arrays.stream(names).map(AllowedTool::parse).toList();
    }

    /** The skill loop's model: one background Workflow call over two perspectives, then an answer. */
    private static final class BackgroundWorkflowLlm implements LlmClient {
        private final List<Integer> calls = new ArrayList<>();

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            return sendMessage(systemPrompt, messages, tools, modelConfig, LlmCallMetadata.empty());
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig, LlmCallMetadata metadata) {
            calls.add(calls.size());
            if (messages.stream().anyMatch(Message::hasToolResults)) {
                return LlmResponse.text("started");
            }
            return LlmResponse.of("", List.of(ToolUse.of("w-1", "Workflow", Map.of("prompt", "ship it?", "perspectives",
                    "technical,risk", "synthesize", false, "mode", "background"))));
        }

        @Override
        public String getProviderName() {
            return "Scripted";
        }
    }
}
