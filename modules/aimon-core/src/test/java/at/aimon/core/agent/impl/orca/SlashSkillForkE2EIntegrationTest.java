package at.aimon.core.agent.impl.orca;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.Environment;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.store.InMemorySessionRecordStore;
import at.aimon.core.agent.session.transcript.DefaultTranscriptManager;
import at.aimon.core.agent.tool.DefaultToolExecutionManager;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.command.DefaultCommandExecutionManager;
import at.aimon.core.command.DefaultCommandRegistry;
import at.aimon.core.filesystem.impl.local.LocalFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystemConfig;
import at.aimon.core.hook.DefaultHookExecutionManager;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.Role;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.skill.ExecutionMode;
import at.aimon.core.skill.InvokePolicy;
import at.aimon.core.skill.Skill;
import at.aimon.core.skill.SkillContent;
import at.aimon.core.skill.SkillMetadata;
import at.aimon.core.skill.SkillRegistry;
import at.aimon.core.subagent.DefaultSubagentExecutionManager;
import at.aimon.core.subagent.Subagent;
import at.aimon.core.subagent.SubagentContent;
import at.aimon.core.subagent.SubagentMetadata;
import at.aimon.core.subagent.SubagentRegistry;

/**
 * End-to-end regression test for the user-slash invocation path.
 *
 * <p>
 * Mirrors {@code SkillForkE2EIntegrationTest} (SK-IT-2, LLM tool-call path) but exercises the full slash chain:
 * {@link OrcaAgentExecutor#execute} → {@code DefaultCommandExecutionManager} → {@code SkillBackedCommandExecutor} →
 * {@code LlmSkillExecutor} → {@code SubagentBackedSkillForkExecutor} → real {@code DefaultSubagentExecutionManager}.
 *
 * <p>
 * Confirms that {@link OrcaAgentExecutor} resolves the per-execution {@code SkillForkExecutor} from the live
 * {@code OrcaAgentRuntime} and threads it through {@code ToolContext} so {@code LlmSkillExecutor.executeFork}
 * picks the production {@code SubagentBackedSkillForkExecutor} instead of the {@code NoOpSkillForkExecutor} that the
 * {@code DefaultCommandExecutionManager(LlmClient)} convenience constructor injects.
 *
 * <p>
 * The unit-level wiring is covered by {@code OrcaSkillForkExecutorResolverTest} and {@code LlmSkillExecutorTest}; this
 * test catches regressions where a future refactor of {@code OrcaAgentExecutor.executeCommand} silently drops the
 * {@code SKILL_FORK_EXECUTOR_KEY} or stops resolving the executor.
 */
@DisplayName("Slash → fork subagent e2e round-trip (OrcaAgentExecutor)")
class SlashSkillForkE2EIntegrationTest {

    @TempDir
    Path tempDir;

    private RecordingLlmClient llmClient;
    private DefaultSubagentExecutionManager subagentManager;
    private InMemorySkillRegistry skillRegistry;
    private InMemorySubagentRegistry subagentRegistry;
    private DefaultToolRegistry toolRegistry;
    private OrcaAgentExecutor executor;

    @BeforeEach
    void setUp() {
        llmClient = new RecordingLlmClient("Looks good — ship it.");

        final DefaultToolExecutionManager toolManager = new DefaultToolExecutionManager();
        final DefaultHookExecutionManager hookManager = new DefaultHookExecutionManager();
        final DefaultCommandExecutionManager commandManager = new DefaultCommandExecutionManager(llmClient);
        subagentManager = new DefaultSubagentExecutionManager(llmClient, toolManager, hookManager);

        executor = new OrcaAgentExecutor(llmClient, new DefaultTranscriptManager(new InMemorySessionRecordStore()),
                toolManager, hookManager, commandManager, subagentManager);

        toolRegistry = new DefaultToolRegistry();
        skillRegistry = new InMemorySkillRegistry();
        subagentRegistry = new InMemorySubagentRegistry();
    }

    @AfterEach
    void tearDown() {
        subagentManager.close();
    }

    @Test
    @DisplayName("happy path — /<skill> args renders body with $ARGUMENTS, subagent runs, final answer surfaces")
    void slashForkSkill_RoundTrip_RendersBodyAsGoalAndSurfacesAnswer() {
        skillRegistry.add(forkSkill("review", "code-reviewer", "Review the following: $ARGUMENTS"));
        subagentRegistry.add(simpleSubagent("code-reviewer"));

        final OrcaAgentExecutionResult result = executor.execute(createContext(),
                createRequest("/review src/main/java/Foo.java"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getFinalAnswer()).isEqualTo("Looks good — ship it.");

        // The rendered skill body became the subagent goal — substitution happened before fork, and the live
        // SubagentBackedSkillForkExecutor (not NoOp) was resolved from OrcaAgentRuntime + threaded through
        // ToolContext.
        assertThat(llmClient.lastUserMessage()).contains("Review the following: src/main/java/Foo.java");
        assertThat(llmClient.callCount()).isOne();
    }

    @Test
    @DisplayName("unknown subagent — slash invocation surfaces clear failure (fail-fast in fork executor)")
    void slashForkSkill_UnknownSubagent_FailsFastFromCommandFlow() {
        skillRegistry.add(forkSkill("review", "missing-agent", "body"));
        // Intentionally do not register `missing-agent` in subagentRegistry.

        final OrcaAgentExecutionResult result = executor.execute(createContext(), createRequest("/review"));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("Skill fork failed for 'review'")
                .contains("references unknown subagent 'missing-agent'");
        assertThat(llmClient.callCount()).isZero();
    }

    @Test
    @DisplayName("non-user-invocable skill — slash lookup misses, command not found")
    void slashForkSkill_NonUserInvocable_FailsWithCommandNotFound() {
        // model=true, user=false (the framework default) — slash registry filters this out.
        final Skill modelOnly = Skill.builder().name("review")
                .metadata(SkillMetadata.builder().name("review").description("model-only fixture")
                        .invokePolicy(InvokePolicy.defaults()).executionMode(ExecutionMode.FORK)
                        .forkAgentName("code-reviewer").build())
                .content(SkillContent.of("Review: $ARGUMENTS")).build();
        skillRegistry.add(modelOnly);
        subagentRegistry.add(simpleSubagent("code-reviewer"));

        final OrcaAgentExecutionResult result = executor.execute(createContext(), createRequest("/review foo"));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("review");
        assertThat(llmClient.callCount()).isZero();
    }

    private OrcaAgentRuntime createContext() {
        final LocalFileSystem fileSystem = new LocalFileSystem(new LocalFileSystemConfig(tempDir.toString()));
        fileSystem.initialize();
        final DefaultCommandRegistry commandRegistry = new DefaultCommandRegistry(List.of(), skillRegistry, fileSystem,
                ".aimon/commands");
        commandRegistry.initialize();

        return OrcaAgentRuntime.builder()
                .agent(DefaultAgent.builder().name("TestAgent").maxIterations(2).systemPrompt("You are a test agent")
                        .model(LlmModel.builder().name("gpt-4").build()).build())
                .toolRegistry(toolRegistry).hookRegistry(new DefaultHookRegistry()).commandRegistry(commandRegistry)
                .subagentRegistry(subagentRegistry).skillRegistry(skillRegistry).fileSystem(fileSystem)
                .environment(Environment.createDefault()).build();
    }

    private static OrcaAgentExecutionRequest createRequest(String userInput) {
        return OrcaAgentExecutionRequest.builder().userInput(userInput).sessionId(SessionId.generate()).build();
    }

    private static Skill forkSkill(String name, String agentName, String body) {
        return Skill.builder().name(name)
                .metadata(SkillMetadata.builder().name(name).description("e2e fixture — " + name)
                        .invokePolicy(InvokePolicy.of(true, true)).executionMode(ExecutionMode.FORK)
                        .forkAgentName(agentName).build())
                .content(SkillContent.of(body)).build();
    }

    private static Subagent simpleSubagent(String name) {
        return Subagent.of(name, SubagentMetadata.builder().description("e2e " + name).maxIterations(2).build(),
                SubagentContent.of("you are " + name));
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
