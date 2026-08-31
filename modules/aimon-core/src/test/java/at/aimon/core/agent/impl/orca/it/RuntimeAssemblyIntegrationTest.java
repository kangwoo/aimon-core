package at.aimon.core.agent.impl.orca.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.impl.orca.OrcaAgentExecutionResult;
import at.aimon.core.agent.impl.orca.OrcaAgentRuntime;
import at.aimon.core.agent.impl.orca.OrcaAgentRuntimeFactory;
import at.aimon.core.agent.impl.orca.tool.OrcaSchedulingToolProvider;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.knowledge.KnowledgeStore;
import at.aimon.core.tools.scheduling.CancelScheduledTaskTool;
import at.aimon.core.tools.scheduling.ListScheduledTasksTool;
import at.aimon.core.tools.scheduling.ScheduleTaskTool;

/**
 * L0 — assembly. Verifies the shape of a runtime built by the real {@code OrcaAgentRuntimeFactory} with the real
 * default providers.
 *
 * <p>
 * Everything here is about wiring, not behaviour: which tools exist, which registries are non-null, what reaches the
 * model, and what {@code close()} does. These are the assertions that break when a provider silently stops registering
 * or a collaborator stops being threaded through — the class of regression that the executor unit suite cannot see
 * because it hand-assembles its runtimes.
 */
@DisplayName("RT-IT-L0: runtime assembly via the real factory + default providers")
class RuntimeAssemblyIntegrationTest {

    /**
     * The tools {@code defaultToolProviders()} must register when no {@code ScheduledTaskManager} is wired.
     *
     * <p>
     * Pinned as an exact set on purpose. A loosely-worded {@code contains(...)} would let a tool silently disappear;
     * an exact set forces anyone adding or removing a default tool to state the change here, which is the point of an
     * assembly test. Scheduling tools are absent because the harness passes a null manager (see below).
     */
    private static final List<String> EXPECTED_DEFAULT_TOOLS = List.of("AgentOutput", "Bash", "BashOutput", "Edit",
            "Grep", "Read", "Skill", "Task", "TaskList", "TaskStop", "TodoWrite", "Write");

    @TempDir
    Path tempDir;

    private OrcaRuntimeItSupport support;
    private ScriptedLlmClient llm;

    @BeforeEach
    void setUp() {
        support = new OrcaRuntimeItSupport(tempDir);
        llm = new ScriptedLlmClient();
    }

    @AfterEach
    void tearDown() {
        support.close();
    }

    @Test
    @DisplayName("the default providers register exactly the expected tool set")
    void defaultProvidersRegisterExpectedTools() {
        final OrcaRuntimeItSupport.Node node = support.newNode(llm);

        assertThat(node.toolNames()).containsExactlyInAnyOrderElementsOf(EXPECTED_DEFAULT_TOOLS);
    }

    @Test
    @DisplayName("scheduling tools are absent when no ScheduledTaskManager is wired (null-safe assembly)")
    void schedulingToolsAbsentWithoutManager() {
        final OrcaRuntimeItSupport.Node node = support.newNode(llm);

        // The tool names are read off the production constants rather than spelled out. Spelling them out is how this
        // assertion was vacuous before: the tools are registered as "schedule_task"/"list_scheduled_tasks"/
        // "cancel_scheduled_task", so a hand-written camel-case list excluded three names that never existed and would
        // have passed with the scheduling provider fully wired.
        assertThat(node.toolNames()).doesNotContain(ScheduleTaskTool.TOOL_NAME, ListScheduledTasksTool.TOOL_NAME,
                CancelScheduledTaskTool.TOOL_NAME);

        // And the provider really was consulted — it is in the default list and declined because the manager was null.
        // Without this half, the absence above would also be satisfied by a provider that was never registered at all,
        // which is a different (and unasserted) state of the world.
        assertThat(OrcaAgentRuntimeFactory.defaultToolProviders())
                .anyMatch(provider -> provider instanceof OrcaSchedulingToolProvider);
    }

    @Test
    @DisplayName("every registry and the compaction collaborators are wired")
    void registriesAndCollaboratorsAreWired() {
        final OrcaAgentRuntime runtime = support.newNode(llm).runtime();

        assertThat(runtime.getToolRegistry()).isNotNull();
        assertThat(runtime.getHookRegistry()).isNotNull();
        assertThat(runtime.getCommandRegistry()).isNotNull();
        assertThat(runtime.getSubagentRegistry()).isNotNull();
        assertThat(runtime.getSkillRegistry()).isNotNull();
        assertThat(runtime.getFileSystem()).isNotNull();
        assertThat(runtime.getEnvironment()).isNotNull();

        // Compaction is assembled unconditionally by doCreate() — an absent engine or guard would silently disable
        // transcript compaction for every session on this agent.
        assertThat(runtime.getCompactionEngine()).isPresent();
        assertThat(runtime.getCompactionGuard()).isPresent();

        // The default prompt-too-long recovery strategy must be wired, otherwise the executor falls back to NoOp
        // and a PromptTooLong error aborts the turn instead of being recovered.
        assertThat(runtime.getPromptSizeRecoveryStrategy()).isPresent();
    }

    /**
     * Each of these three is asserted absent <b>and</b> present, from the same harness, because on their own the
     * absences are unfalsifiable: a getter hard-wired to return {@code Optional.empty()} satisfies all three, and so
     * does an assembly path that quietly stopped threading the collaborator through. Only the paired opt-in
     * distinguishes "the bootstrap said nothing" from "the wiring is broken".
     */
    @Test
    @DisplayName("optional collaborators are absent by default and present once the bootstrap supplies them")
    void optionalCollaboratorsFollowTheBootstrap() {
        final OrcaAgentRuntime bare = support.newNode(llm).runtime();

        // No MCP factory was passed to create(), so no McpClientManager was built — and therefore none to close.
        assertThat(bare.getMcpClientManager()).isEmpty();
        assertThat(bare.getKnowledgeStore()).isEmpty();
        // workflowRunnerEnabled defaults to false: no hosting pool is created unless a bootstrap opts in.
        assertThat(bare.getWorkflowRunner()).isEmpty();

        final KnowledgeStore knowledgeStore = mock(KnowledgeStore.class);
        final OrcaAgentRuntime opted = support.newNode("opted-in", llm, OrcaRuntimeItSupport.options()
                .knowledgeStore(knowledgeStore).workflowRunnerEnabled(true).mcp(config -> null, List::of)).runtime();

        assertThat(opted.getMcpClientManager()).isPresent();
        assertThat(opted.getKnowledgeStore()).contains(knowledgeStore);
        assertThat(opted.getWorkflowRunner()).isPresent();
    }

    @Test
    @DisplayName("the runtime id is deterministic from the agent, not freshly generated")
    void runtimeIdIsDeterministic() {
        final OrcaRuntimeItSupport.Node first = support.newNode("stable", llm);
        final OrcaRuntimeItSupport.Node second = support.newNode("stable", llm);

        // Scope model: AgentRuntimeId is `agent:<name>[:<discriminator>]` and must be derivable from the agent, so a
        // cron re-fire can resolve ScheduledTask.boundRuntimeId long after the original session ended. Two runtimes
        // built for the same agent name must therefore share an id.
        assertThat(first.runtime().getId()).isEqualTo(second.runtime().getId());
        assertThat(first.runtime().getId().value()).isEqualTo("agent:stable");
    }

    @Test
    @DisplayName("the assembled tool set is what actually reaches the model")
    void assembledToolsReachTheModel() {
        final OrcaRuntimeItSupport.Node node = support.newNode(llm);
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        llm.script(sessionId.value(), ScriptedLlmClient.text("ok"));

        node.run(sessionId, "hello");

        // The registry holding a tool is only half the wiring — the executor must also offer it to the LLM. Asserting
        // on the ToolDefinitions the client received closes the gap between "registered" and "usable".
        assertThat(llm.firstCallFor(sessionId.value()).toolNames())
                .containsExactlyInAnyOrderElementsOf(EXPECTED_DEFAULT_TOOLS);
    }

    @Test
    @DisplayName("the agent's system prompt reaches the model")
    void systemPromptReachesTheModel() {
        final OrcaRuntimeItSupport.Node node = support.newNode("prompt-probe", llm);
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        llm.script(sessionId.value(), ScriptedLlmClient.text("ok"));

        node.run(sessionId, "hello");

        assertThat(llm.firstCallFor(sessionId.value()).systemPrompt())
                .contains("You are prompt-probe, an integration-test agent.");
    }

    @Test
    @DisplayName("a turn runs end-to-end through the assembled runtime")
    void assembledRuntimeRunsATurn() {
        final OrcaRuntimeItSupport.Node node = support.newNode(llm);
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        llm.script(sessionId.value(), ScriptedLlmClient.text("the answer"));

        final OrcaAgentExecutionResult result = node.run(sessionId, "what is the answer?");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getFinalAnswer()).isEqualTo("the answer");
        assertThat(result.getIterationCount()).isEqualTo(1);
        // Routing sanity: if the executor ever stopped stamping traceId=sessionId, this call would land on UNROUTED
        // and every per-session assertion in the suite would quietly test nothing.
        assertThat(llm.callCount(ScriptedLlmClient.UNROUTED)).isZero();
    }

    @Test
    @DisplayName("close() is idempotent and does not close borrowed collaborators")
    void closeIsIdempotentAndClosesOnlyOwnedResources() {
        final OrcaRuntimeItSupport.Node node = support.newNode(llm);

        // Idempotence has to be asserted on the second call itself. Closing twice and then reading a recorded field
        // (as this test used to) asserted nothing: the field is null whether close() is idempotent or was never called.
        node.runtime().close();
        assertThatCode(() -> node.runtime().close()).doesNotThrowAnyException();

        // Scope model: closing an agent-scoped runtime must not take down the application-scoped executor plumbing.
        // Each node builds its own executor, so the survivor proves the *shared* plumbing beneath them — the tool and
        // hook managers, the subagent manager's pools, the LLM client — is still usable, not that one executor object
        // outlived a close it never received.
        final OrcaRuntimeItSupport.Node survivor = support.newNode("survivor", llm);
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        llm.script(sessionId.value(), ScriptedLlmClient.text("still alive"));

        assertThat(survivor.run(sessionId, "ping").getFinalAnswer()).isEqualTo("still alive");
    }
}
