package at.aimon.core.agent.impl.orca;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

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
import at.aimon.core.agent.tool.Tool;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.command.DefaultCommandExecutionManager;
import at.aimon.core.command.DefaultCommandRegistry;
import at.aimon.core.filesystem.impl.local.LocalFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystemConfig;
import at.aimon.core.hook.DefaultHookExecutionManager;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.HookEventType;
import at.aimon.core.hook.event.PostToolHook;
import at.aimon.core.hook.event.PreToolHook;
import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.skill.InvokePolicy;
import at.aimon.core.skill.Skill;
import at.aimon.core.skill.SkillContent;
import at.aimon.core.skill.SkillMetadata;
import at.aimon.core.skill.SkillRegistry;
import at.aimon.core.subagent.DefaultSubagentExecutionManager;
import at.aimon.core.subagent.Subagent;
import at.aimon.core.subagent.SubagentRegistry;

/**
 * E2E regression test for the tool calls an <em>inline</em> slash skill makes.
 *
 * <p>
 * A skill invoked as {@code /audit} runs its own ReAct loop, and that loop used to reach the agent's real
 * {@code ToolRegistry} through {@code ToolExecutionManager.executeAll} directly — no {@code PermissionRequest} hooks,
 * no
 * side-effect approval gate, no {@code PreTool} / {@code PostTool}. A tool the agent could not have run without asking
 * ran without asking as soon as the user typed a slash.
 *
 * <p>
 * {@code OrcaAgentExecutor.executeCommand} now binds a {@code SkillToolDispatcher} into the command {@code ToolContext}
 * that routes each call through the same {@code SingleToolInvoker} the ReAct loop uses. These tests pin that from the
 * outside: they drive {@link OrcaAgentExecutor#execute} with a slash invocation and assert on what the hooks saw, so a
 * refactor that drops the {@code SKILL_TOOL_DISPATCHER_KEY} binding fails here rather than silently reopening the hole.
 *
 * <p>
 * Companion unit coverage lives in {@code LlmSkillExecutorTest} (prefers a bound dispatcher, falls back without one).
 */
@DisplayName("Slash skill tool dispatch e2e (OrcaAgentExecutor)")
class SlashSkillToolDispatchE2EIntegrationTest {

    @TempDir
    Path tempDir;

    private ScriptedLlmClient llmClient;
    private DefaultSubagentExecutionManager subagentManager;
    private InMemorySkillRegistry skillRegistry;
    private DefaultToolRegistry toolRegistry;
    private DefaultHookRegistry hookRegistry;
    private CountingTool countingTool;
    private OrcaAgentExecutor executor;

    @BeforeEach
    void setUp() {
        llmClient = new ScriptedLlmClient();

        final DefaultToolExecutionManager toolManager = new DefaultToolExecutionManager();
        final DefaultHookExecutionManager hookManager = new DefaultHookExecutionManager();
        final DefaultCommandExecutionManager commandManager = new DefaultCommandExecutionManager(llmClient,
                toolManager);
        subagentManager = new DefaultSubagentExecutionManager(llmClient, toolManager, hookManager);

        executor = new OrcaAgentExecutor(llmClient, new DefaultTranscriptManager(new InMemorySessionRecordStore()),
                toolManager, hookManager, commandManager, subagentManager);

        countingTool = new CountingTool();
        toolRegistry = new DefaultToolRegistry();
        toolRegistry.register(countingTool);
        hookRegistry = new DefaultHookRegistry();
        skillRegistry = new InMemorySkillRegistry();
    }

    @AfterEach
    void tearDown() {
        subagentManager.close();
    }

    @Test
    @DisplayName("a skill's tool call fires the agent's PreTool and PostTool hooks")
    void slashSkillToolCall_FiresPreAndPostToolHooks() {
        final List<String> preToolSaw = new ArrayList<>();
        final AtomicInteger postToolCalls = new AtomicInteger();
        hookRegistry.register(HookEventType.PRE_TOOL, (PreToolHook) context -> {
            preToolSaw.add(context.getCurrentToolUse().getName());
            return HookResult.success();
        });
        hookRegistry.register(HookEventType.POST_TOOL, (PostToolHook) context -> {
            postToolCalls.incrementAndGet();
            return HookResult.success();
        });

        skillRegistry.add(inlineSkill("audit", "Audit this: $ARGUMENTS"));
        llmClient.script(LlmResponse.of("Counting", List.of(ToolUse.of("call-1", CountingTool.NAME, Map.of()))),
                LlmResponse.text("audit complete"));

        final OrcaAgentExecutionResult result = executor.execute(createContext(), createRequest("/audit src"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getFinalAnswer()).isEqualTo("audit complete");
        assertThat(preToolSaw).containsExactly(CountingTool.NAME);
        assertThat(postToolCalls).hasValue(1);
        assertThat(countingTool.executions).isOne();
    }

    @Test
    @DisplayName("a PreTool hook can block a skill's tool call — the tool never runs, the skill still finishes")
    void slashSkillToolCall_BlockedByPreToolHook() {
        hookRegistry.register(HookEventType.PRE_TOOL, (PreToolHook) context -> HookResult.block("blocked by policy"));

        skillRegistry.add(inlineSkill("audit", "Audit this: $ARGUMENTS"));
        llmClient.script(LlmResponse.of("Counting", List.of(ToolUse.of("call-1", CountingTool.NAME, Map.of()))),
                LlmResponse.text("audit complete"));

        final OrcaAgentExecutionResult result = executor.execute(createContext(), createRequest("/audit src"));

        assertThat(result.isSuccess()).isTrue();
        // The hook decided, not the tool: the block reason went back to the skill's LLM as a tool error.
        assertThat(countingTool.executions).isZero();
        assertThat(llmClient.toolResultText()).contains("blocked by policy");
    }

    @Test
    @DisplayName("a tool outside the skill's allowed-tools list is refused per call, and the skill continues")
    void slashSkillToolCall_OutsideAllowedToolsIsRefusedWithoutAbortingTheSkill() {
        skillRegistry.add(inlineSkill("audit", "Audit this: $ARGUMENTS", "SomethingElse"));
        llmClient.script(LlmResponse.of("Counting", List.of(ToolUse.of("call-1", CountingTool.NAME, Map.of()))),
                LlmResponse.text("audit complete"));

        final OrcaAgentExecutionResult result = executor.execute(createContext(), createRequest("/audit src"));

        // Through the invoker a permission violation is one tool's error result, not the end of the skill —
        // the loop gets an observation and the LLM gets another turn to answer.
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getFinalAnswer()).isEqualTo("audit complete");
        assertThat(countingTool.executions).isZero();
        assertThat(llmClient.toolResultText()).contains("not allowed").contains("SomethingElse");
    }

    private OrcaAgentRuntime createContext() {
        final LocalFileSystem fileSystem = new LocalFileSystem(new LocalFileSystemConfig(tempDir.toString()));
        fileSystem.initialize();
        final DefaultCommandRegistry commandRegistry = new DefaultCommandRegistry(List.of(), skillRegistry, fileSystem,
                ".aimon/commands");
        commandRegistry.initialize();

        return OrcaAgentRuntime.builder()
                .agent(DefaultAgent.builder().name("TestAgent").maxIterations(3).systemPrompt("You are a test agent")
                        .model(LlmModel.builder().name("gpt-4").build()).build())
                .toolRegistry(toolRegistry).hookRegistry(hookRegistry).commandRegistry(commandRegistry)
                .subagentRegistry(new EmptySubagentRegistry()).skillRegistry(skillRegistry).fileSystem(fileSystem)
                .environment(Environment.createDefault()).build();
    }

    private static OrcaAgentExecutionRequest createRequest(String userInput) {
        return OrcaAgentExecutionRequest.builder().userInput(userInput).sessionId(SessionId.generate()).build();
    }

    private static Skill inlineSkill(String name, String body) {
        return inlineSkill(name, body, CountingTool.NAME);
    }

    /** An INLINE (default execution mode) skill — it runs its own ReAct loop rather than forking a subagent. */
    private static Skill inlineSkill(String name, String body, String allowedTool) {
        return Skill.builder().name(name)
                .metadata(SkillMetadata.builder().name(name).description("e2e fixture — " + name)
                        .invokePolicy(InvokePolicy.of(true, true)).maxIterations(3).allowedTools(allowedTool).build())
                .content(SkillContent.of(body)).build();
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

    /** No subagents — these skills run inline, the registry only has to exist. */
    private static final class EmptySubagentRegistry implements SubagentRegistry {
        @Override
        public Optional<Subagent> getSubagent(String subagentName) {
            return Optional.empty();
        }

        @Override
        public List<Subagent> getAllSubagents() {
            return List.of();
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

    /** Counts its own executions so a test can assert whether the tool was actually reached. */
    private static final class CountingTool implements Tool {
        static final String NAME = "Counter";

        private int executions;

        @Override
        public ToolDefinition getDefinition() {
            return ToolDefinition.of(NAME, "Counts executions", Map.of("type", "object"));
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext context) {
            this.executions++;
            return ToolResult.success("counted");
        }
    }

    /**
     * Returns scripted responses in order (the last one repeats), and remembers the tool-result messages it was handed
     * so a test can read back what the skill's loop observed.
     */
    private static final class ScriptedLlmClient implements LlmClient {
        private final List<LlmResponse> responses = new ArrayList<>();
        private final List<String> toolResults = new ArrayList<>();
        private int callCount;

        void script(LlmResponse... scripted) {
            responses.clear();
            responses.addAll(List.of(scripted));
        }

        String toolResultText() {
            return String.join("\n", toolResults);
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            return sendMessage(systemPrompt, messages, tools, modelConfig, LlmCallMetadata.empty());
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig, LlmCallMetadata metadata) {
            for (Message message : messages) {
                if (message.hasToolResults()) {
                    message.getToolUseResults().forEach(r -> toolResults.add(r.getContent()));
                }
            }
            if (responses.isEmpty()) {
                return LlmResponse.text("done");
            }
            final int index = Math.min(callCount++, responses.size() - 1);
            return responses.get(index);
        }

        @Override
        public String getProviderName() {
            return "Scripted";
        }
    }
}
