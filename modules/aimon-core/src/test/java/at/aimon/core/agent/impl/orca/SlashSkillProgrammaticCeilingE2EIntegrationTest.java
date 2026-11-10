package at.aimon.core.agent.impl.orca;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
import at.aimon.core.agent.tool.ProgrammaticEngineChoice;
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
import at.aimon.core.environment.RuntimeFileSystem;
import at.aimon.core.environment.StagedResource;
import at.aimon.core.environment.TestExecutionEnvironments;
import at.aimon.core.environment.impl.LocalRuntimeFileSystem;
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
import at.aimon.core.llm.ToolUseResult;
import at.aimon.core.programmatic.CodeExecutionLimits;
import at.aimon.core.shell.VirtualShell;
import at.aimon.core.shell.impl.local.LocalShell;
import at.aimon.core.skill.InvokePolicy;
import at.aimon.core.skill.Skill;
import at.aimon.core.skill.SkillContent;
import at.aimon.core.skill.SkillMetadata;
import at.aimon.core.skill.SkillRegistry;
import at.aimon.core.subagent.DefaultSubagentExecutionManager;
import at.aimon.core.subagent.InMemorySubagentRegistry;
import at.aimon.core.subagent.Subagent;
import at.aimon.core.tools.code.CodeSpanAttributes;
import at.aimon.core.tools.code.ExecuteCodeTool;
import at.aimon.core.tools.code.ScriptedEngine;
import at.aimon.core.tools.code.shell.ShellCodeExecutionEngine;
import at.aimon.core.tools.skill.SkillTool;
import at.aimon.core.tools.task.TaskTool;
import at.aimon.core.tracing.SpanExporter;
import at.aimon.core.tracing.SpanType;
import at.aimon.core.tracing.TraceSpan;
import at.aimon.core.tracing.impl.DefaultTracer;
import at.aimon.core.tracing.impl.InMemoryTraceSpanStore;
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
    private final ShellCodeExecutionEngine shellEngine = ShellCodeExecutionEngine.create();

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
        shellEngine.close();
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
    @DisplayName("2: model-called inline Skill(probe) without programmatic keys narrows the rest of the turn — the"
            + " fork the main loop then spawns is ceilinged by the skill and not offered ExecuteCode (TC-26); the"
            + " main loop's iteration span after the skill says why (TC-30)")
    void modelCalledInlineSkill_NarrowsTheTurn_ForkCeilingedByTheSkill() {
        final InMemoryTraceSpanStore store = tracing();
        // SkillTool's INLINE path returns the rendered instructions to the main loop and narrows the turn's
        // inline-skill ceiling; from the next iteration the main loop dispatches through its dispatcher narrowed to
        // the skill, which publishes the skill's programmatic settings (none) as the ceiling of the fork Task spawns.
        // Before TC-26 this fork was ceilinged by the agent and offered ExecuteCode.
        skillRegistry.add(probe(ProgrammaticToolSettings.none()));
        toolRegistry.register(new SkillTool(skillRegistry));
        llmClient.mainScript = List.of(List.of(ToolUse.of("m-1", SkillTool.TOOL_NAME, Map.of("skill", "probe"))),
                List.of(taskCall("m-2")));

        final SessionId sessionId = SessionId.generate();
        assertThat(executor
                .execute(runtime(READ_GREP),
                        OrcaAgentExecutionRequest.builder().userInput("please probe").sessionId(sessionId).build())
                .isSuccess()).isTrue();

        assertThat(llmClient.skillCalls).isZero();
        assertThat(llmClient.forkTools).hasSize(1);
        assertThat(names(llmClient.forkTools.get(0))).contains("Read", "Grep")
                .doesNotContain(ExecuteCodeTool.TOOL_NAME);
        // The main loop itself: offered ExecuteCode before the skill, not after it.
        assertThat(names(llmClient.mainTools.get(0))).contains(ExecuteCodeTool.TOOL_NAME);
        assertThat(names(llmClient.mainTools.get(1))).doesNotContain(ExecuteCodeTool.TOOL_NAME);
        assertThat(ceilingWarnings()).hasSize(1);
        // Before, the main loop's own iterations said nothing: none() reads as an agent that never opted in.
        final List<TraceSpan> mainIterations = iterations(store, sessionId, null);
        assertThat(mainIterations).hasSizeGreaterThanOrEqualTo(2);
        assertThat(mainIterations.get(0).getAttributes()).doesNotContainKey(CodeSpanAttributes.UNAVAILABLE_REASON);
        assertThat(mainIterations.get(1).getAttributes().get(CodeSpanAttributes.UNAVAILABLE_REASON))
                .isEqualTo(NO_KEYS_REASON.formatted("probe"));
    }

    @Test
    @DisplayName("6: inline Skill(read-only) allowing [Read] — the next iteration is offered only Read, and a Grep call"
            + " there is refused (TC-26)")
    void inlineSkillNarrowsTheNextIteration() {
        skillRegistry.add(readOnlySkill());
        toolRegistry.register(new SkillTool(skillRegistry));
        llmClient.mainScript = List.of(List.of(ToolUse.of("m-1", SkillTool.TOOL_NAME, Map.of("skill", "read-only"))),
                List.of(ToolUse.of("m-2", "Grep", Map.of("path", "x"))));

        final OrcaAgentExecutionResult result = executor.execute(runtime(READ_GREP), request("narrow me"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(names(llmClient.mainTools.get(0))).contains("Read", "Grep", SkillTool.TOOL_NAME);
        assertThat(names(llmClient.mainTools.get(1))).containsExactly("Read");
        assertThat(resultOf(result, "m-1").isError()).isFalse();
        assertThat(resultOf(result, "m-1").getContent()).contains("For the rest of this turn only these tools");
        assertThat(resultOf(result, "m-2").isError()).isTrue();
    }

    @Test
    @DisplayName("7: a Grep in the same response as the inline Skill call runs — the narrowing takes effect from the"
            + " next iteration (TC-26 timing rule)")
    void callInTheSameResponseRunsUnderTheOfferedCeiling() {
        skillRegistry.add(readOnlySkill());
        toolRegistry.register(new SkillTool(skillRegistry));
        llmClient.mainScript = List.of(List.of(ToolUse.of("m-1", SkillTool.TOOL_NAME, Map.of("skill", "read-only")),
                ToolUse.of("m-2", "Grep", Map.of("path", "x"))));

        final OrcaAgentExecutionResult result = executor.execute(runtime(READ_GREP), request("narrow me"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(resultOf(result, "m-2").isError()).isFalse();
        assertThat(resultOf(result, "m-2").getContent()).isEqualTo("Grep ok");
        assertThat(names(llmClient.mainTools.get(1))).containsExactly("Read");
    }

    @Test
    @DisplayName("8: a fork whose model calls an inline skill is narrowed from its next iteration (TC-26)")
    void forkLoopInlineSkillNarrowsTheFork() {
        skillRegistry.add(readOnlySkill());
        toolRegistry.register(new SkillTool(skillRegistry));
        final String skilled = readerName + "-skilled";
        subagentRegistry.register(Subagent.builder().name(skilled).systemPrompt(FORK_PROMPT).maxIterations(3)
                .tools(List.of(ExecuteCodeTool.TOOL_NAME, "Read", "Grep", SkillTool.TOOL_NAME)).programmatic(READ_GREP)
                .build());
        llmClient.mainScript = List.of(List.of(ToolUse.of("m-1", "Task",
                Map.of("subagent_name", skilled, "prompt", "read the notes", "description", "read notes"))));
        llmClient.forkScript = List.of(List.of(ToolUse.of("f-1", SkillTool.TOOL_NAME, Map.of("skill", "read-only"))));

        assertThat(executor.execute(runtime(READ_GREP), request("delegate")).isSuccess()).isTrue();

        assertThat(llmClient.forkTools).hasSize(2);
        assertThat(names(llmClient.forkTools.get(0))).contains(ExecuteCodeTool.TOOL_NAME, "Read", "Grep",
                SkillTool.TOOL_NAME);
        assertThat(names(llmClient.forkTools.get(1))).containsExactly("Read");
    }

    @Test
    @DisplayName("13: a fork whose model calls an inline skill without programmatic keys loses ExecuteCode from its"
            + " next iteration, and that iteration's span says why (TC-30)")
    void forkLoopInlineSkillWithoutPtcReportsTheReason() {
        final InMemoryTraceSpanStore store = tracing();
        skillRegistry.add(probe(ProgrammaticToolSettings.none()));
        toolRegistry.register(new SkillTool(skillRegistry));
        final String skilled = readerName + "-probing";
        subagentRegistry.register(Subagent.builder().name(skilled).systemPrompt(FORK_PROMPT).maxIterations(3)
                .tools(List.of(ExecuteCodeTool.TOOL_NAME, "Read", "Grep", SkillTool.TOOL_NAME)).programmatic(READ_GREP)
                .build());
        llmClient.mainScript = List.of(List.of(ToolUse.of("m-1", "Task",
                Map.of("subagent_name", skilled, "prompt", "read the notes", "description", "read notes"))));
        llmClient.forkScript = List.of(List.of(ToolUse.of("f-1", SkillTool.TOOL_NAME, Map.of("skill", "probe"))));
        final SessionId sessionId = SessionId.generate();

        assertThat(executor
                .execute(runtime(READ_GREP),
                        OrcaAgentExecutionRequest.builder().userInput("delegate").sessionId(sessionId).build())
                .isSuccess()).isTrue();

        assertThat(llmClient.forkTools).hasSize(2);
        assertThat(names(llmClient.forkTools.get(0))).contains(ExecuteCodeTool.TOOL_NAME);
        assertThat(names(llmClient.forkTools.get(1))).contains("Read", "Grep")
                .doesNotContain(ExecuteCodeTool.TOOL_NAME);
        final List<TraceSpan> forkIterations = iterations(store, sessionId, skilled);
        assertThat(forkIterations).hasSize(2);
        assertThat(forkIterations.get(0).getAttributes()).doesNotContainKey(CodeSpanAttributes.UNAVAILABLE_REASON);
        assertThat(forkIterations.get(1).getAttributes().get(CodeSpanAttributes.UNAVAILABLE_REASON))
                .isEqualTo(NO_KEYS_REASON.formatted("probe"));
    }

    @Test
    @DisplayName("12: inside a user-typed slash skill, an inline skill without programmatic keys empties the skill"
            + " loop's PTC — the iteration span that follows says why (TC-30)")
    void inlineSkillInSlashLoopReportsTheReason() {
        final InMemoryTraceSpanStore store = tracing();
        skillRegistry.add(Skill.builder().name("ptc-probe")
                .metadata(SkillMetadata.builder().name("ptc-probe").description("e2e fixture — ptc probe")
                        .invokePolicy(InvokePolicy.of(true, true)).maxIterations(3)
                        .allowedTools("Read Grep ExecuteCode Skill").programmatic(READ_GREP).build())
                .content(SkillContent.of("PTC probe: $ARGUMENTS")).build());
        skillRegistry.add(probe(ProgrammaticToolSettings.none()));
        toolRegistry.register(new SkillTool(skillRegistry));
        llmClient.skillScript = List.of(ToolUse.of("s-1", SkillTool.TOOL_NAME, Map.of("skill", "probe")));
        final SessionId sessionId = SessionId.generate();

        assertThat(executor
                .execute(runtime(READ_GREP),
                        OrcaAgentExecutionRequest.builder().userInput("/ptc-probe look").sessionId(sessionId).build())
                .isSuccess()).isTrue();

        assertThat(llmClient.skillTools).hasSize(2);
        assertThat(names(llmClient.skillTools.get(0))).contains(ExecuteCodeTool.TOOL_NAME);
        assertThat(names(llmClient.skillTools.get(1))).contains("Read", "Grep")
                .doesNotContain(ExecuteCodeTool.TOOL_NAME);
        final List<TraceSpan> reported = store.bySession(sessionId.value()).stream()
                .filter(span -> span.getType() == SpanType.ITERATION)
                .filter(span -> span.getAttributes().containsKey(CodeSpanAttributes.UNAVAILABLE_REASON)).toList();
        assertThat(reported).isNotEmpty()
                .allSatisfy(span -> assertThat(span.getAttributes().get(CodeSpanAttributes.UNAVAILABLE_REASON))
                        .isEqualTo(NO_KEYS_REASON.formatted("probe")));
    }

    /** What an iteration span says once an inline skill without programmatic keys emptied the execution's PTC. */
    private static final String NO_KEYS_REASON = "inline skill '%s' sets no programmatic-tools, and an activated "
            + "skill's programmatic settings narrow the execution's, so no tool is open to code";

    /** Traces the main loop and its forks into a fresh store. */
    private InMemoryTraceSpanStore tracing() {
        final InMemoryTraceSpanStore store = new InMemoryTraceSpanStore();
        final DefaultTracer tracer = new DefaultTracer(store, SpanExporter.noop());
        executor.tracer = tracer;
        subagentManager.withTracer(tracer);
        return store;
    }

    /** The iteration spans of the main loop ({@code subagent == null}) or of the named fork, in order. */
    private static List<TraceSpan> iterations(InMemoryTraceSpanStore store, SessionId sessionId, String subagent) {
        return store.bySession(sessionId.value()).stream().filter(span -> span.getType() == SpanType.ITERATION)
                .filter(span -> Objects.equals(subagent, span.getAttributes().get("subagent"))).toList();
    }

    @Test
    @DisplayName("9: an inline skill that narrows Bash to a pattern turns the shell engine off for the rest of the turn"
            + " — ExecuteCode is no longer offered, the iteration span says why, and Bash stays offered (TC-26)")
    void patternedBashInInlineSkillTurnsShellEngineOff() {
        final DefaultToolRegistry shellTools = shellToolRegistry();
        llmClient.mainScript = List.of(List.of(ToolUse.of("m-1", SkillTool.TOOL_NAME, Map.of("skill", "git-helper"))));
        final InMemoryTraceSpanStore store = new InMemoryTraceSpanStore();
        executor.tracer = new DefaultTracer(store, SpanExporter.noop());
        final SessionId sessionId = SessionId.generate();

        final OrcaAgentExecutionResult result = executor.execute(shellRuntime(shellTools),
                OrcaAgentExecutionRequest.builder().userInput("check git").sessionId(sessionId).build());

        assertThat(result.isSuccess()).isTrue();
        assertThat(llmClient.mainTools).hasSize(2);
        assertThat(executeCodeDescription(llmClient.mainTools.get(0))).contains("Python");
        assertThat(names(llmClient.mainTools.get(1))).contains("Bash", "Read")
                .doesNotContain(ExecuteCodeTool.TOOL_NAME);
        final List<TraceSpan> iterations = store.bySession(sessionId.value()).stream()
                .filter(span -> span.getType() == SpanType.ITERATION).toList();
        assertThat(iterations).hasSize(2);
        assertThat(iterations.get(0).getAttributes()).doesNotContainKey("ptc.unavailable_reason");
        assertThat(iterations.get(1).getAttributes().get("ptc.unavailable_reason")).contains("Bash");
    }

    @Test
    @DisplayName("10: the same patterned-Bash narrowing through the fork loop turns the fork's shell engine off")
    void patternedBashInInlineSkillTurnsForkShellEngineOff() {
        final DefaultToolRegistry shellTools = shellToolRegistry();
        final String shellFork = readerName + "-shell";
        subagentRegistry.register(Subagent.builder().name(shellFork).systemPrompt(FORK_PROMPT).maxIterations(3)
                .tools(List.of("Bash", "Read", ExecuteCodeTool.TOOL_NAME, SkillTool.TOOL_NAME))
                .programmatic(READ_ONLY_PYTHON).build());
        llmClient.mainScript = List.of(List.of(ToolUse.of("m-1", "Task",
                Map.of("subagent_name", shellFork, "prompt", "check git", "description", "check git"))));
        llmClient.forkScript = List.of(List.of(ToolUse.of("f-1", SkillTool.TOOL_NAME, Map.of("skill", "git-helper"))));

        assertThat(executor.execute(shellRuntime(shellTools), request("delegate")).isSuccess()).isTrue();

        assertThat(llmClient.forkTools).hasSize(2);
        assertThat(executeCodeDescription(llmClient.forkTools.get(0))).contains("Python");
        assertThat(names(llmClient.forkTools.get(1))).contains("Bash", "Read")
                .doesNotContain(ExecuteCodeTool.TOOL_NAME);
    }

    @Test
    @DisplayName("11: inside a user-typed slash skill, an inline skill narrowing Bash rebuilds the skill loop's offer"
            + " from the narrowed allow-list — the shell engine goes off there too (TC-26)")
    void patternedBashInInlineSkillTurnsSlashLoopShellEngineOff() {
        final DefaultToolRegistry shellTools = shellToolRegistry();
        skillRegistry.add(Skill.builder().name("shell-probe")
                .metadata(SkillMetadata.builder().name("shell-probe").description("e2e fixture — shell probe")
                        .invokePolicy(InvokePolicy.of(true, true)).maxIterations(3)
                        .allowedTools("Bash Read ExecuteCode Skill").programmatic(READ_ONLY_PYTHON).build())
                .content(SkillContent.of("Shell probe: $ARGUMENTS")).build());
        llmClient.skillScript = List.of(ToolUse.of("s-1", SkillTool.TOOL_NAME, Map.of("skill", "git-helper")));

        assertThat(executor.execute(shellRuntime(shellTools), request("/shell-probe look")).isSuccess()).isTrue();

        assertThat(llmClient.skillTools).hasSize(2);
        assertThat(executeCodeDescription(llmClient.skillTools.get(0))).contains("Python");
        assertThat(names(llmClient.skillTools.get(1))).contains("Bash", "Read")
                .doesNotContain(ExecuteCodeTool.TOOL_NAME, SkillTool.TOOL_NAME);
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

    private static final ProgrammaticToolSettings READ_ONLY_PYTHON = ProgrammaticToolSettings.builder()
            .tools(List.of("Read")).engine(ProgrammaticEngineChoice.PYTHON).build();

    private static ToolUseResult resultOf(OrcaAgentExecutionResult result, String toolUseId) {
        return result.getConversationHistory().stream().flatMap(m -> m.getToolUseResults().stream())
                .filter(r -> r.getToolUseId().equals(toolUseId)).findFirst()
                .orElseThrow(() -> new AssertionError("no result for " + toolUseId));
    }

    /** Inline skill {@code read-only}: allowed-tools [Read], no programmatic keys. */
    private static Skill readOnlySkill() {
        return Skill.builder().name("read-only")
                .metadata(SkillMetadata.builder().name("read-only").description("e2e fixture — read only")
                        .invokePolicy(InvokePolicy.of(true, true)).allowedTools("Read").build())
                .content(SkillContent.of("Only read.")).build();
    }

    /**
     * Tools for the shell-engine cases: Bash, Read, Skill (with {@code git-helper}: allowed-tools
     * [Bash(git:*), Read, ExecuteCode], programmatic-tools [Read]), Task, and ExecuteCode over the real shell
     * engine. Nothing runs a program: only the offer is judged, from the environment's declared facts.
     */
    private DefaultToolRegistry shellToolRegistry() {
        skillRegistry.add(Skill.builder().name("git-helper")
                .metadata(SkillMetadata.builder().name("git-helper").description("e2e fixture — git helper")
                        .invokePolicy(InvokePolicy.of(true, true)).allowedTools("Bash(git:*) Read ExecuteCode")
                        .programmatic(READ_ONLY).build())
                .content(SkillContent.of("Use git status only.")).build());
        final DefaultToolRegistry shellTools = new DefaultToolRegistry();
        shellTools.register(new ReaderTool("Bash", "BASH-TOOL-DESCRIPTION"));
        shellTools.register(new ReaderTool("Read", READ_DESC));
        shellTools.register(new SkillTool(skillRegistry));
        shellTools.register(new ExecuteCodeTool(List.of(shellEngine), CodeExecutionLimits.defaults()));
        shellTools.register(new TaskTool(LlmModel.builder().name("gpt-4").build(), subagentRegistry, shellTools,
                hookRegistry, subagentManager));
        return shellTools;
    }

    /**
     * The runtime of the shell-engine cases: allowed-tools [Bash, Read, ExecuteCode, Skill, Task], programmatic-tools
     * [Read] on the Python engine, in a sandbox that declares python3 and offers a runtime file system.
     */
    private OrcaAgentRuntime shellRuntime(DefaultToolRegistry shellTools) {
        final LocalFileSystem fileSystem = new LocalFileSystem(new LocalFileSystemConfig(tempDir.toString()));
        fileSystem.initialize();
        final DefaultCommandRegistry commandRegistry = new DefaultCommandRegistry(List.of(), skillRegistry, fileSystem,
                ".aimon/commands");
        commandRegistry.initialize();
        final ExecutionEnvironment sandbox = new SandboxEnvironment(TestExecutionEnvironments.of(fileSystem), tempDir);
        return OrcaAgentRuntime.builder()
                .agent(DefaultAgent.builder().name("TestAgent").maxIterations(4).systemPrompt(MAIN_PROMPT)
                        .model(LlmModel.builder().name("gpt-4").build())
                        .tools(List.of("Bash", "Read", ExecuteCodeTool.TOOL_NAME, SkillTool.TOOL_NAME, "Task"))
                        .programmatic(READ_ONLY_PYTHON).build())
                .toolRegistry(shellTools).hookRegistry(hookRegistry).commandRegistry(commandRegistry)
                .subagentRegistry(subagentRegistry).skillRegistry(skillRegistry).controlFileSystem(fileSystem)
                .executionEnvironmentProvider(request -> request.parent().orElse(sandbox)).build();
    }

    /**
     * A sandbox that declares python3 and offers a runtime file system over a local directory, so the shell engine's
     * static facts hold; the shell is a local one.
     */
    private static final class SandboxEnvironment implements ExecutionEnvironment {
        private final ExecutionEnvironment delegate;
        private final VirtualShell shell;
        private final RuntimeFileSystem files;

        SandboxEnvironment(ExecutionEnvironment delegate, Path root) {
            this.delegate = delegate;
            this.shell = new LocalShell(root);
            this.files = new LocalRuntimeFileSystem(root);
        }

        @Override
        public VirtualFileSystem fileSystem() {
            return delegate.fileSystem();
        }

        @Override
        public VirtualShell shell() {
            return shell;
        }

        @Override
        public EnvironmentDescriptor descriptor() {
            return EnvironmentDescriptor.builder().workingDirectory(delegate.descriptor().workingDirectory())
                    .isolation(EnvironmentIsolation.SANDBOXED).runtime(EnvironmentDescriptor.RUNTIME_PYTHON3).build();
        }

        @Override
        public Optional<RuntimeFileSystem> runtimeFileSystem() {
            return Optional.of(files);
        }

        @Override
        public String stage(StagedResource resource) {
            return delegate.stage(resource);
        }
    }

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
        private final List<List<ToolDefinition>> mainTools = new CopyOnWriteArrayList<>();
        private volatile String readerName;
        /** The main loop's responses, one batch of tool calls per iteration, then text. */
        private volatile List<List<ToolUse>> mainScript = List.of();
        /** A fork's responses, one batch per iteration, then text; empty answers at once. */
        private volatile List<List<ToolUse>> forkScript = List.of();
        /** The skill loop's first response, when not the default Task call. */
        private volatile List<ToolUse> skillScript;
        private volatile int skillCalls;

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            return sendMessage(systemPrompt, messages, tools, modelConfig, LlmCallMetadata.empty());
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig, LlmCallMetadata metadata) {
            final long results = messages.stream().filter(Message::hasToolResults).count();
            if (systemPrompt.contains(FORK_PROMPT)) {
                forkTools.add(List.copyOf(tools));
                if (results < forkScript.size()) {
                    return LlmResponse.of("Forking", forkScript.get((int) results));
                }
                return LlmResponse.text("fork done");
            }
            if (systemPrompt.contains(SKILL_PROMPT)) {
                skillCalls++;
                skillTools.add(List.copyOf(tools));
                if (sawToolResult(messages)) {
                    return LlmResponse.text("skill done");
                }
                if (skillScript != null) {
                    return LlmResponse.of("Skilling", skillScript);
                }
                return LlmResponse.of("Spawning", List.of(ToolUse.of("s-1", "Task",
                        Map.of("subagent_name", readerName, "prompt", "read the notes", "description", "read notes"))));
            }
            mainTools.add(List.copyOf(tools));
            if (results < mainScript.size()) {
                return LlmResponse.of("Working", mainScript.get((int) results));
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
