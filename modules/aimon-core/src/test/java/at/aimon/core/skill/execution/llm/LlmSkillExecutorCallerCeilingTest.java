package at.aimon.core.skill.execution.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.ExecutionId;
import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.DefaultToolExecutionManager;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.agent.tool.Tool;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.agent.tool.permission.AllowedTool;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.skill.Skill;
import at.aimon.core.skill.SkillContent;
import at.aimon.core.skill.SkillMetadata;
import at.aimon.core.skill.execution.SkillExecutionContext;
import at.aimon.core.skill.execution.SkillExecutionRequest;
import at.aimon.core.skill.execution.SkillExecutionResult;
import at.aimon.core.skill.render.DefaultSkillContentRenderer;
import at.aimon.core.tools.ToolContextKeys;

/**
 * Locks the agent's allow-list onto the skill path, the hole the agent-level list would otherwise leave open.
 *
 * <p>
 * A skill's {@code allowed-tools} used to be the only bound on the tools it runs, so a skill naming {@code Bash}
 * reached {@code Bash} inside an agent narrowed to {@code Read} — by a model call to {@code Skill}, or by a user
 * typing {@code /my-skill}. The restriction described what the agent did with its own hands and not what it could
 * cause, which is the same defect the subagent ceiling fixes on the delegation side.
 *
 * <p>
 * The caller's list reaches this executor through the tool context: published by {@code SingleToolInvoker} on the
 * tool-call path, and by {@code OrcaAgentExecutor}'s command tool context on the user-slash path, which is hand-built
 * because no tool call sits above it.
 */
@DisplayName("LlmSkillExecutor — caller allow-list ceiling")
class LlmSkillExecutorCallerCeilingTest {

    /** Records what was offered on each call and answers with a scripted response, then plain text. */
    private static final class DefinitionCapturingLlmClient implements LlmClient {

        private final List<List<String>> offeredToolNames = new CopyOnWriteArrayList<>();
        private final List<LlmResponse> responses = new CopyOnWriteArrayList<>();
        private int index;

        private synchronized LlmResponse capture(List<ToolDefinition> tools) {
            offeredToolNames.add(tools.stream().map(ToolDefinition::getName).toList());
            return index < responses.size() ? responses.get(index++) : LlmResponse.text("done");
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            return capture(tools);
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig, LlmCallMetadata metadata) {
            return capture(tools);
        }

        @Override
        public String getProviderName() {
            return "DefinitionCapturing";
        }

        List<String> firstOffer() {
            return offeredToolNames.get(0);
        }
    }

    private DefinitionCapturingLlmClient llmClient;
    private LlmSkillExecutor executor;

    @BeforeEach
    void setUp() {
        llmClient = new DefinitionCapturingLlmClient();
        executor = new LlmSkillExecutor(llmClient, new DefaultSkillContentRenderer(),
                new DefaultToolExecutionManager());
    }

    private static Tool tool(String name) {
        return new AbstractTool(name, name + " description", Map.of("type", "object")) {
            @Override
            public ToolResult execute(ToolInput input, ToolContext context) {
                return ToolResult.success("ran " + name);
            }
        };
    }

    /** {@code allowedTools} is the space-delimited spelling the skill frontmatter uses; blank means no list. */
    private static Skill skillAllowing(String allowedTools) {
        final SkillMetadata.Builder metadata = SkillMetadata.builder().name("test").description("Test");
        if (!allowedTools.isBlank()) {
            metadata.allowedTools(allowedTools);
        }
        return Skill.builder().name("test").metadata(metadata.build()).content(SkillContent.of("Task")).build();
    }

    private SkillExecutionResult run(Skill skill, List<String> callerAllowed) {
        final ToolRegistry registry = new DefaultToolRegistry();
        registry.register(tool("Read"));
        registry.register(tool("Bash"));
        registry.register(tool("Write"));

        final ToolContext.Builder toolContext = ToolContext.builder();
        if (callerAllowed != null) {
            toolContext.put(ToolContextKeys.CALLER_ALLOWED_TOOLS,
                    callerAllowed.stream().map(AllowedTool::parse).toList());
        }

        final SkillExecutionContext context = SkillExecutionContext.builder().skill(skill)
                .defaultModel(LlmModel.builder().build()).toolRegistry(registry)
                .executionId(ExecutionId.generate("skill:test")).toolContext(toolContext.build()).build();

        return executor.execute(context, SkillExecutionRequest.builder().rawArguments("").arguments(List.of()).build());
    }

    @Test
    @DisplayName("a skill is not offered a tool its caller may not use, even when the skill names it")
    void theCallersListNarrowsTheOffer() {
        final SkillExecutionResult result = run(skillAllowing("Read Bash"), List.of("Read"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(llmClient.firstOffer()).containsExactly("Read");
    }

    @Test
    @DisplayName("a skill calling a tool its caller may not use is refused at dispatch")
    void theCallersListRefusesTheCall() {
        // The half the offer filter cannot do: nothing stops the model naming a tool it was not shown.
        llmClient.responses.add(LlmResponse.of("", List.of(ToolUse.of("t1", "Bash", Map.of()))));

        final SkillExecutionResult result = run(skillAllowing("Read Bash"), List.of("Read"));

        // The permission violation surfaces as a failed skill execution rather than a ran tool.
        assertThat(result.isSuccess()).isFalse();
        assertThat(llmClient.firstOffer()).doesNotContain("Bash");
    }

    @Test
    @DisplayName("no caller list imposes no ceiling — the skill's own list governs, as it did before")
    void noCallerListLeavesTheSkillAlone() {
        assertThat(run(skillAllowing("Read Bash"), null).isSuccess()).isTrue();
        assertThat(llmClient.firstOffer()).containsExactlyInAnyOrder("Read", "Bash");
    }

    @Test
    @DisplayName("a skill with no list of its own inherits the caller's rather than staying unrestricted")
    void anUnrestrictedSkillInheritsTheCeiling() {
        assertThat(run(skillAllowing(""), List.of("Read")).isSuccess()).isTrue();
        assertThat(llmClient.firstOffer()).containsExactly("Read");
    }

    @Test
    @DisplayName("two lists with nothing in common refuse the skill instead of running it unrestricted")
    void disjointListsRefuseTheSkill() {
        final SkillExecutionResult result = run(skillAllowing("Bash"), List.of("Read"));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError().orElseThrow().getMessage()).contains("nothing in common").contains("Read")
                .contains("Bash");
        assertThat(llmClient.offeredToolNames).as("the LLM is never called at all").isEmpty();
    }
}
