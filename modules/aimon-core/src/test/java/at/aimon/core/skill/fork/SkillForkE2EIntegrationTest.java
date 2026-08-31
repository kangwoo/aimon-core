package at.aimon.core.skill.fork;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.Environment;
import at.aimon.core.agent.tool.DefaultToolExecutionManager;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.hook.DefaultHookExecutionManager;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.Role;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.skill.ExecutionMode;
import at.aimon.core.skill.Skill;
import at.aimon.core.skill.SkillContent;
import at.aimon.core.skill.SkillMetadata;
import at.aimon.core.skill.SkillRegistry;
import at.aimon.core.skill.render.DefaultSkillContentRenderer;
import at.aimon.core.subagent.DefaultSubagentExecutionManager;
import at.aimon.core.subagent.Subagent;
import at.aimon.core.subagent.SubagentContent;
import at.aimon.core.subagent.SubagentMetadata;
import at.aimon.core.subagent.SubagentRegistry;
import at.aimon.core.tools.ToolContextKeys;
import at.aimon.core.tools.skill.SkillTool;

/**
 * End-to-end regression test.
 *
 * <p>
 * Wires {@link SkillTool} with {@link SubagentBackedSkillForkExecutor} backed by a real
 * {@link DefaultSubagentExecutionManager} and an in-memory {@link LlmClient} double, then drives a fork-mode skill
 * end-to-end. Confirms the rendered skill body (with {@code $ARGUMENTS} substituted) becomes the subagent goal, and the
 * subagent's final answer surfaces through {@link ToolResult}.
 *
 * <p>
 * Distinct from {@link SubagentBackedSkillForkExecutorTest} (which mocks the manager) — this test exercises the real
 * subagent loop with a deterministic LLM double so we catch wiring regressions across SkillTool, fork executor, and
 * subagent execution.
 */
@DisplayName("SK-IT-2: SkillTool → fork subagent e2e round-trip")
class SkillForkE2EIntegrationTest {

    private RecordingLlmClient llmClient;
    private DefaultSubagentExecutionManager subagentManager;
    private InMemorySkillRegistry skillRegistry;
    private InMemorySubagentRegistry subagentRegistry;
    private SkillTool skillTool;

    @BeforeEach
    void setUp() {
        llmClient = new RecordingLlmClient("Looks good — ship it.");
        subagentManager = new DefaultSubagentExecutionManager(llmClient, new DefaultToolExecutionManager(),
                new DefaultHookExecutionManager());

        skillRegistry = new InMemorySkillRegistry();
        subagentRegistry = new InMemorySubagentRegistry();

        final ToolRegistry toolRegistry = new DefaultToolRegistry();
        final HookRegistry hookRegistry = new DefaultHookRegistry();
        final Environment environment = Environment.createDefault();
        final LlmModel defaultModel = LlmModel.builder().name("gpt-4").build();

        final SkillForkExecutor forkExecutor = new SubagentBackedSkillForkExecutor(defaultModel, subagentRegistry,
                toolRegistry, hookRegistry, environment, subagentManager);

        skillTool = new SkillTool(skillRegistry, new DefaultSkillContentRenderer(), forkExecutor);
    }

    @AfterEach
    void tearDown() {
        subagentManager.close();
    }

    @Test
    @DisplayName("happy path — skill body rendered with $ARGUMENTS, subagent runs, final answer surfaces")
    void forkSkill_RoundTrip_RendersBodyAsGoalAndSurfacesAnswer() {
        skillRegistry.add(forkSkill("review", "code-reviewer", "Review the following: $ARGUMENTS"));
        subagentRegistry.add(simpleSubagent("code-reviewer"));

        final ToolResult result = skillTool.execute(
                ToolInput.of(Map.of("skill", "review", "args", "src/main/java/Foo.java")), contextWithExecutionId());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("=== Skill Forked ===").contains("Skill: review")
                .contains("Agent: code-reviewer").contains("Looks good — ship it.");

        // The rendered skill body became the subagent goal — substitution must have happened before fork.
        assertThat(llmClient.lastUserMessage()).contains("Review the following: src/main/java/Foo.java");
    }

    @Test
    @DisplayName("unknown subagent — SkillTool surfaces clear failure (fail-fast in fork executor)")
    void forkSkill_UnknownSubagent_FailsFastFromSkillTool() {
        skillRegistry.add(forkSkill("review", "missing-agent", "body"));
        // Intentionally do not register `missing-agent` in subagentRegistry.

        final ToolResult result = skillTool.execute(ToolInput.of(Map.of("skill", "review")), contextWithExecutionId());

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("Skill fork failed for 'review'")
                .contains("references unknown subagent 'missing-agent'");
        assertThat(llmClient.callCount()).isZero();
    }

    @Test
    @DisplayName("missing agent runtime id — SkillTool surfaces clear failure")
    void forkSkill_MissingAgentRuntimeId_FailsWithClearMessage() {
        skillRegistry.add(forkSkill("review", "code-reviewer", "body"));
        subagentRegistry.add(simpleSubagent("code-reviewer"));

        final ToolResult result = skillTool.execute(ToolInput.of(Map.of("skill", "review")), ToolContext.empty());

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("Skill fork failed for 'review'")
                .contains("agent runtime ID not available");
        assertThat(llmClient.callCount()).isZero();
    }

    private static Skill forkSkill(String name, String agentName, String body) {
        return Skill.builder().name(name)
                .metadata(SkillMetadata.builder().name(name).description("e2e fixture — " + name)
                        .executionMode(ExecutionMode.FORK).forkAgentName(agentName).build())
                .content(SkillContent.of(body)).build();
    }

    private static Subagent simpleSubagent(String name) {
        return Subagent.of(name, SubagentMetadata.builder().description("e2e " + name).maxIterations(2).build(),
                SubagentContent.of("you are " + name));
    }

    private static ToolContext contextWithExecutionId() {
        return ToolContext.builder().put(ToolContextKeys.AGENT_RUNTIME_ID, AgentRuntimeId.of("agent:test-1")).build();
    }

    /** In-memory {@link SkillRegistry} so the test does not depend on classpath fixtures. */
    private static final class InMemorySkillRegistry implements SkillRegistry {
        private final Map<String, Skill> skills = new HashMap<>();

        void add(Skill skill) {
            skills.put(skill.getName(), skill);
        }

        @Override
        public Optional<Skill> getSkill(String skillName) {
            return Optional.ofNullable(skills.get(skillName));
        }

        @Override
        public List<Skill> getAllSkills() {
            return new ArrayList<>(skills.values());
        }

        @Override
        public void reloadSkill(String skillName) {
            // no-op
        }

        @Override
        public void reloadAll() {
            // no-op
        }
    }

    /** In-memory {@link SubagentRegistry} so the test does not depend on classpath fixtures. */
    private static final class InMemorySubagentRegistry implements SubagentRegistry {
        private final Map<String, Subagent> subagents = new HashMap<>();

        void add(Subagent subagent) {
            subagents.put(subagent.getName(), subagent);
        }

        @Override
        public Optional<Subagent> getSubagent(String subagentName) {
            return Optional.ofNullable(subagents.get(subagentName));
        }

        @Override
        public List<Subagent> getAllSubagents() {
            return new ArrayList<>(subagents.values());
        }

        @Override
        public void reloadSubagent(String subagentName) {
            // no-op
        }

        @Override
        public void reloadAll() {
            // no-op
        }
    }

    /**
     * In-memory {@link LlmClient} that returns a fixed text response for every call so the subagent's ReAct loop
     * terminates on the first iteration with a final answer. Records the user-message content of the most recent call
     * so the test can verify the rendered skill body reached the subagent as its goal.
     */
    private static final class RecordingLlmClient implements LlmClient {
        private final String finalAnswer;
        private final List<String> userMessages = new ArrayList<>();

        RecordingLlmClient(String finalAnswer) {
            this.finalAnswer = finalAnswer;
        }

        int callCount() {
            return userMessages.size();
        }

        String lastUserMessage() {
            if (userMessages.isEmpty()) {
                return "";
            }
            return userMessages.get(userMessages.size() - 1);
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            return sendMessage(systemPrompt, messages, tools, modelConfig, LlmCallMetadata.empty());
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig, LlmCallMetadata metadata) {
            for (Message m : messages) {
                if (m.getRole() == Role.USER) {
                    userMessages.add(m.getContent());
                }
            }
            return LlmResponse.text(finalAnswer);
        }

        @Override
        public String getProviderName() {
            return "Recording";
        }

    }
}
