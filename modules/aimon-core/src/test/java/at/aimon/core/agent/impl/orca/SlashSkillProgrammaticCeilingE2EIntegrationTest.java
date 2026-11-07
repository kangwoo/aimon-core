package at.aimon.core.agent.impl.orca;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.store.InMemorySessionRecordStore;
import at.aimon.core.agent.session.transcript.DefaultTranscriptManager;
import at.aimon.core.agent.tool.ConcurrencyBehavior;
import at.aimon.core.agent.tool.DataReach;
import at.aimon.core.agent.tool.DefaultToolExecutionManager;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.agent.tool.DestructiveBehavior;
import at.aimon.core.agent.tool.ProgrammaticToolSettings;
import at.aimon.core.agent.tool.SideEffectLevel;
import at.aimon.core.agent.tool.Tool;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.command.DefaultCommandExecutionManager;
import at.aimon.core.command.DefaultCommandRegistry;
import at.aimon.core.environment.EnvironmentDescriptor;
import at.aimon.core.environment.EnvironmentIsolation;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.environment.StagedResource;
import at.aimon.core.environment.TestExecutionEnvironments;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystemConfig;
import at.aimon.core.hook.DefaultHookExecutionManager;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.programmatic.CodeExecutionLimits;
import at.aimon.core.shell.VirtualShell;
import at.aimon.core.skill.InvokePolicy;
import at.aimon.core.skill.Skill;
import at.aimon.core.skill.SkillContent;
import at.aimon.core.skill.SkillMetadata;
import at.aimon.core.skill.SkillRegistry;
import at.aimon.core.subagent.DefaultSubagentExecutionManager;
import at.aimon.core.subagent.InMemorySubagentRegistry;
import at.aimon.core.subagent.Subagent;
import at.aimon.core.tools.code.ExecuteCodeTool;
import at.aimon.core.tools.code.ScriptedEngine;
import at.aimon.core.tools.skill.SkillTool;
import at.aimon.core.tools.task.TaskTool;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * E2E regression test for the programmatic ceiling a slash skill puts on the forks it spawns (PTC design B.6,
 * review-3 Blocking).
 *
 * <p>
 * An inline slash skill runs its own loop through the agent's dispatcher narrowed to the skill. That narrowed
 * dispatcher publishes the skill's <em>effective</em> programmatic settings — its own opt-in narrowed by the agent's
 * — as {@code CALLER_PROGRAMMATIC} to every call it runs, so a fork the skill spawns through {@code Task} is ceilinged
 * by the skill and not by the agent. A skill without programmatic keys is a ceiling of "nothing": the fork is not
 * offered {@code ExecuteCode} although both the agent and the fork's own definition opt in.
 *
 * <p>
 * Driven from the outside: the real {@link OrcaAgentExecutor}, {@link DefaultSubagentExecutionManager} and
 * {@link TaskTool}, with an {@link ExecuteCodeTool} over a scripted JavaScript engine on a HOST environment, so that
 * {@code ExecuteCode} is genuinely offerable — the control case proves the fixture can offer it.
 */
@DisplayName("Slash skill programmatic ceiling e2e (OrcaAgentExecutor → skill → Task → fork)")
class SlashSkillProgrammaticCeilingE2EIntegrationTest {

    private static final String MAIN_PROMPT = "MAIN-AGENT-PROMPT";
    private static final String FORK_PROMPT = "READER-FORK-PROMPT";
    /** The skill loop's system prompt always starts with this (SkillSystemPromptBuilder). */
    private static final String SKILL_PROMPT = "executing a user-defined command";
    private static final String READ_DESC = "READ-TOOL-DESCRIPTION";
    private static final String GREP_DESC = "GREP-TOOL-DESCRIPTION";
    private static final ProgrammaticToolSettings READ_GREP = ProgrammaticToolSettings.builder()
            .tools(List.of("Read", "Grep")).build();
    private static final ProgrammaticToolSettings READ_ONLY = ProgrammaticToolSettings.builder().tools(List.of("Read"))
            .build();

    @TempDir
    Path tempDir;

    private RoutingLlmClient llmClient;
    private DefaultSubagentExecutionManager subagentManager;
    private OrcaAgentExecutor executor;
    private InMemorySkillRegistry skillRegistry;
    private InMemorySubagentRegistry subagentRegistry;
    private DefaultToolRegistry toolRegistry;
    private DefaultHookRegistry hookRegistry;
    /** Unique per test: the caller-ceiling WARN is once per subagent name for the JVM. */
    private String readerName;
    private ListAppender<ILoggingEvent> warnings;
    private Logger managerLogger;

    @BeforeEach
    void setUp() {
        llmClient = new RoutingLlmClient();
        final DefaultToolExecutionManager toolManager = new DefaultToolExecutionManager();
        final DefaultHookExecutionManager hookManager = new DefaultHookExecutionManager();
        final DefaultCommandExecutionManager commandManager = new DefaultCommandExecutionManager(llmClient,
                toolManager);
        subagentManager = new DefaultSubagentExecutionManager(llmClient, toolManager, hookManager);
        executor = new OrcaAgentExecutor(llmClient, new DefaultTranscriptManager(new InMemorySessionRecordStore()),
                toolManager, hookManager, commandManager, subagentManager);

        readerName = "reader-" + UUID.randomUUID().toString().substring(0, 8);
        llmClient.readerName = readerName;
        subagentRegistry = new InMemorySubagentRegistry();
        subagentRegistry.register(Subagent.builder().name(readerName).systemPrompt(FORK_PROMPT).maxIterations(3)
                .tools(List.of(ExecuteCodeTool.TOOL_NAME, "Read", "Grep")).programmatic(READ_GREP).build());
        skillRegistry = new InMemorySkillRegistry();
        hookRegistry = new DefaultHookRegistry();

        toolRegistry = new DefaultToolRegistry();
        toolRegistry.register(new ReaderTool("Read", READ_DESC));
        toolRegistry.register(new ReaderTool("Grep", GREP_DESC));
        toolRegistry
                .register(new ExecuteCodeTool(List.of(ScriptedEngine.javascript()), CodeExecutionLimits.defaults()));
        toolRegistry.register(new TaskTool(LlmModel.builder().name("gpt-4").build(), subagentRegistry, toolRegistry,
                hookRegistry, subagentManager));

        warnings = new ListAppender<>();
        warnings.start();
        managerLogger = (Logger) LoggerFactory.getLogger(DefaultSubagentExecutionManager.class);
        managerLogger.addAppender(warnings);
    }

    @AfterEach
    void tearDown() {
        managerLogger.detachAppender(warnings);
        subagentManager.close();
    }

    @Test
    @DisplayName("1: user-typed /probe without programmatic keys — the fork it spawns is not offered ExecuteCode; the"
            + " caller-ceiling WARN is logged once")
    void userSlashSkillWithoutPtc_ForkNotOfferedExecuteCode() {
        skillRegistry.add(probe(ProgrammaticToolSettings.none()));

        // Twice, so "once" is a claim about the second spawn too.
        assertThat(executor.execute(runtime(READ_GREP), request("/probe look around")).isSuccess()).isTrue();
        assertThat(executor.execute(runtime(READ_GREP), request("/probe look again")).isSuccess()).isTrue();

        assertThat(llmClient.forkTools).hasSize(2);
        for (List<ToolDefinition> offered : llmClient.forkTools) {
            assertThat(names(offered)).contains("Read", "Grep").doesNotContain(ExecuteCodeTool.TOOL_NAME);
        }
        // The skill itself, allowed ExecuteCode by name but without programmatic keys, is not offered it either.
        assertThat(llmClient.skillTools).isNotEmpty().allSatisfy(offered -> assertThat(names(offered))
                .contains("Task", "Read", "Grep").doesNotContain(ExecuteCodeTool.TOOL_NAME));
        assertThat(ceilingWarnings()).hasSize(1);
    }

    @Test
    @DisplayName("2 (departure): model-called Skill(probe) is injected into the main turn, not run as a loop — the"
            + " main loop spawns the fork under the agent's ceiling, so the fork IS offered ExecuteCode")
    void modelCalledInlineSkill_IsInjection_ForkCeilingedByTheAgent() {
        // SkillTool's INLINE path returns the rendered instructions to the main loop (SkillTool#execute, "INLINE keeps
        // the historical inject-into-context behaviour"); no skill loop and no narrowed dispatcher exist on this path,
        // so the agent itself runs Task and its own settings are the ceiling. Pinned here so a change to that path is
        // a visible decision.
        skillRegistry.add(probe(ProgrammaticToolSettings.none()));
        toolRegistry.register(new SkillTool(skillRegistry));
        llmClient.mainScript = List.of(ToolUse.of("m-1", SkillTool.TOOL_NAME, Map.of("skill", "probe")),
                taskCall("m-2"));

        assertThat(executor.execute(runtime(READ_GREP), request("please probe")).isSuccess()).isTrue();

        assertThat(llmClient.skillCalls).isZero();
        assertThat(llmClient.forkTools).hasSize(1);
        assertThat(names(llmClient.forkTools.get(0))).contains(ExecuteCodeTool.TOOL_NAME, "Read", "Grep");
        assertThat(ceilingWarnings()).isEmpty();
    }

    @Test
    @DisplayName("3 (control): /probe declaring programmatic-tools [Read, Grep] — the fork IS offered ExecuteCode,"
            + " with Read and Grep callable")
    void userSlashSkillWithPtc_ForkOfferedExecuteCode() {
        skillRegistry.add(probe(READ_GREP));

        assertThat(executor.execute(runtime(READ_GREP), request("/probe look around")).isSuccess()).isTrue();

        assertThat(llmClient.forkTools).hasSize(1);
        final String description = executeCodeDescription(llmClient.forkTools.get(0));
        assertThat(description).contains(READ_DESC).contains(GREP_DESC);
        // The skill opts in under an agent that allows it, so the skill is offered ExecuteCode as well.
        assertThat(names(llmClient.skillTools.get(0))).contains(ExecuteCodeTool.TOOL_NAME);
        assertThat(ceilingWarnings()).isEmpty();
    }

    @Test
    @DisplayName("4: /probe declaring programmatic-tools [Read] — the fork is offered ExecuteCode with only Read"
            + " callable")
    void userSlashSkillWithNarrowerPtc_ForkCallableSetNarrowed() {
        skillRegistry.add(probe(READ_ONLY));

        assertThat(executor.execute(runtime(READ_GREP), request("/probe look around")).isSuccess()).isTrue();

        assertThat(llmClient.forkTools).hasSize(1);
        final String description = executeCodeDescription(llmClient.forkTools.get(0));
        assertThat(description).contains(READ_DESC).doesNotContain(GREP_DESC);
        assertThat(ceilingWarnings()).isEmpty();
    }

    @Test
    @DisplayName("5: agent without programmatic settings, /probe with [Read, Grep] — the fork is not offered"
            + " ExecuteCode: the agent's ceiling reaches the skill first")
    void agentWithoutPtc_SkillWithPtc_ForkNotOfferedExecuteCode() {
        skillRegistry.add(probe(READ_GREP));

        assertThat(
                executor.execute(runtime(ProgrammaticToolSettings.none()), request("/probe look around")).isSuccess())
                .isTrue();

        assertThat(llmClient.forkTools).hasSize(1);
        assertThat(names(llmClient.forkTools.get(0))).contains("Read", "Grep")
                .doesNotContain(ExecuteCodeTool.TOOL_NAME);
        assertThat(ceilingWarnings()).hasSize(1);
    }

    // ---------------------------------------------------------------- fixture

    private List<String> ceilingWarnings() {
        return warnings.list.stream().filter(e -> e.getLevel() == Level.WARN).map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.contains("'" + readerName + "'")).filter(m -> m.contains("caller allows none")).toList();
    }

    private static List<String> names(List<ToolDefinition> tools) {
        return tools.stream().map(ToolDefinition::getName).toList();
    }

    private static String executeCodeDescription(List<ToolDefinition> tools) {
        return tools.stream().filter(t -> t.getName().equals(ExecuteCodeTool.TOOL_NAME)).findFirst()
                .map(ToolDefinition::getDescription)
                .orElseThrow(() -> new AssertionError("ExecuteCode was not offered to the fork: " + names(tools)));
    }

    private ToolUse taskCall(String id) {
        return ToolUse.of(id, "Task",
                Map.of("subagent_name", readerName, "prompt", "read the notes", "description", "read notes"));
    }

    /**
     * Inline skill {@code probe}: allowed-tools [Task, Read, Grep, ExecuteCode], and the given programmatic settings.
     *
     * <p>
     * {@code ExecuteCode} is in the allow-list on purpose (a departure from the design's [Task, Read, Grep]): the
     * skill's allow-list is also the fork's allow-list ceiling, so without it the fork would lose {@code ExecuteCode}
     * to the allow-list intersection and the negative cases would pass for the wrong reason. With it, the programmatic
     * ceiling is the only thing that can take {@code ExecuteCode} away.
     */
    private static Skill probe(ProgrammaticToolSettings programmatic) {
        return Skill.builder().name("probe")
                .metadata(SkillMetadata.builder().name("probe").description("e2e fixture — probe")
                        .invokePolicy(InvokePolicy.of(true, true)).maxIterations(3)
                        .allowedTools("Task Read Grep ExecuteCode").programmatic(programmatic).build())
                .content(SkillContent.of("Probe: $ARGUMENTS")).build();
    }

    private OrcaAgentRuntime runtime(ProgrammaticToolSettings agentProgrammatic) {
        final LocalFileSystem fileSystem = new LocalFileSystem(new LocalFileSystemConfig(tempDir.toString()));
        fileSystem.initialize();
        final DefaultCommandRegistry commandRegistry = new DefaultCommandRegistry(List.of(), skillRegistry, fileSystem,
                ".aimon/commands");
        commandRegistry.initialize();
        final ExecutionEnvironment host = new HostEnvironment(TestExecutionEnvironments.of(fileSystem));
        return OrcaAgentRuntime.builder()
                .agent(DefaultAgent.builder().name("TestAgent").maxIterations(4).systemPrompt(MAIN_PROMPT)
                        .model(LlmModel.builder().name("gpt-4").build()).programmatic(agentProgrammatic).build())
                .toolRegistry(toolRegistry).hookRegistry(hookRegistry).commandRegistry(commandRegistry)
                .subagentRegistry(subagentRegistry).skillRegistry(skillRegistry).controlFileSystem(fileSystem)
                .executionEnvironmentProvider(request -> request.parent().orElse(host)).build();
    }

    private static OrcaAgentExecutionRequest request(String userInput) {
        return OrcaAgentExecutionRequest.builder().userInput(userInput).sessionId(SessionId.generate()).build();
    }

    /**
     * The test environment, declaring HOST isolation so {@code programmatic-engine: auto} resolves to JavaScript —
     * without it {@code ExecuteCode} is never offerable and the negative cases would be vacuous.
     */
    private static final class HostEnvironment implements ExecutionEnvironment {
        private final ExecutionEnvironment delegate;

        HostEnvironment(ExecutionEnvironment delegate) {
            this.delegate = delegate;
        }

        @Override
        public VirtualFileSystem fileSystem() {
            return delegate.fileSystem();
        }

        @Override
        public VirtualShell shell() {
            return delegate.shell();
        }

        @Override
        public EnvironmentDescriptor descriptor() {
            return EnvironmentDescriptor.builder().workingDirectory(delegate.descriptor().workingDirectory())
                    .isolation(EnvironmentIsolation.HOST).build();
        }

        @Override
        public String stage(StagedResource resource) {
            return delegate.stage(resource);
        }
    }

    /** A READ_ONLY, code-callable tool. */
    private static final class ReaderTool implements Tool {
        private final String name;
        private final String description;

        ReaderTool(String name, String description) {
            this.name = name;
            this.description = description;
        }

        @Override
        public ToolDefinition getDefinition() {
            return ToolDefinition.of(name, description, Map.of("type", "object", "properties",
                    Map.of("path", Map.of("type", "string", "description", "a path"))));
        }

        @Override
        public SideEffectLevel getSideEffectLevel() {
            return SideEffectLevel.READ_ONLY;
        }

        @Override
        public DestructiveBehavior getDestructiveBehavior() {
            return DestructiveBehavior.NON_DESTRUCTIVE;
        }

        @Override
        public DataReach dataReach() {
            return DataReach.ENVIRONMENT;
        }

        @Override
        public ConcurrencyBehavior getConcurrencyBehavior() {
            return ConcurrencyBehavior.CONCURRENT_SAFE;
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext context) {
            return ToolResult.success(name + " ok");
        }
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

    /**
     * Answers by which loop is calling, told apart by system prompt, and records the tools offered to the fork. The
     * skill loop's first call is {@code Task(reader)}; the fork answers at once; every loop answers text once it has
     * seen a tool result. The main loop follows {@link #mainScript}, one tool call per iteration, then text.
     */
    private static final class RoutingLlmClient implements LlmClient {
        private final List<List<ToolDefinition>> forkTools = new CopyOnWriteArrayList<>();
        private final List<List<ToolDefinition>> skillTools = new CopyOnWriteArrayList<>();
        private volatile String readerName;
        private volatile List<ToolUse> mainScript = List.of();
        private volatile int skillCalls;

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            return sendMessage(systemPrompt, messages, tools, modelConfig, LlmCallMetadata.empty());
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig, LlmCallMetadata metadata) {
            if (systemPrompt.contains(FORK_PROMPT)) {
                forkTools.add(List.copyOf(tools));
                return LlmResponse.text("fork done");
            }
            if (systemPrompt.contains(SKILL_PROMPT)) {
                skillCalls++;
                skillTools.add(List.copyOf(tools));
                if (sawToolResult(messages)) {
                    return LlmResponse.text("skill done");
                }
                return LlmResponse.of("Spawning", List.of(ToolUse.of("s-1", "Task",
                        Map.of("subagent_name", readerName, "prompt", "read the notes", "description", "read notes"))));
            }
            final long results = messages.stream().filter(Message::hasToolResults).count();
            if (results < mainScript.size()) {
                return LlmResponse.of("Working", List.of(mainScript.get((int) results)));
            }
            return LlmResponse.text("main done");
        }

        private static boolean sawToolResult(List<Message> messages) {
            return messages.stream().anyMatch(Message::hasToolResults);
        }

        @Override
        public String getProviderName() {
            return "Routing";
        }
    }
}
