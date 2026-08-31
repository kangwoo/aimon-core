package at.aimon.core.agent.impl.orca.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.subagent.InMemorySubagentRegistry;
import at.aimon.core.subagent.Subagent;
import at.aimon.core.tools.ToolContextKeys;
import at.aimon.core.tools.todo.TodoWriteTool;

/**
 * L4 — what identity a subagent fork carries, and what it must <b>not</b> carry.
 *
 * <p>
 * A fork is not a session's turn, so it has no {@link ToolContextKeys#SESSION_ID}. It used to mint one, and that minted
 * id reached tools looking exactly like the user's own while meaning nothing — a fork wearing a session's name. The
 * replacement splits the two axes apart:
 *
 * <ul>
 * <li>{@link ToolContextKeys#EXECUTION_ID} — <em>lifetime</em>: what this run is. Minted per fork, node-local, never
 * persisted.
 * <li>{@link ToolContextKeys#INVOKING_SESSION_ID} — <em>reach</em>: whose decisions apply to it. The user's session,
 * relayed down.
 * </ul>
 *
 * <p>
 * {@link #nestedForkInheritsTheUsersSessionIdNotTheIntermediateFork} is the test that keeps them split. An intermediate
 * fork must hand down the id it <em>inherited</em>, not its own — relaying its own would give a reach that works
 * exactly one level deep and then silently stops, and the failure would be invisible until someone's session-scoped
 * approval mysteriously did not apply two levels down.
 *
 * <p>
 * Every assertion here reads {@link ContextProbeTool}'s observation from inside the fork, because that is the only
 * honest vantage point: the context a fork's tools actually receive, not what the executor was asked to build.
 */
@DisplayName("RT-IT-L4: subagent fork identity")
class SubagentIdentityIsolationIntegrationTest {

    private static final String OUTER = "it-outer";
    private static final String INNER = "it-inner";

    @TempDir
    Path tempDir;

    private OrcaRuntimeItSupport support;
    private ScriptedLlmClient llm;
    private OrcaRuntimeItSupport.Node node;

    @BeforeEach
    void setUp() {
        support = new OrcaRuntimeItSupport(tempDir);
        llm = new ScriptedLlmClient();

        final InMemorySubagentRegistry codeSubagents = new InMemorySubagentRegistry();
        codeSubagents.register(Subagent.builder().name(OUTER).description("Outer integration-test worker")
                .systemPrompt("You are the outer worker.").maxIterations(6).build());
        codeSubagents.register(Subagent.builder().name(INNER).description("Inner integration-test worker")
                .systemPrompt("You are the inner worker.").maxIterations(6).build());

        node = support.newNode("agent-a", llm, OrcaRuntimeItSupport.options().codeSubagents(codeSubagents)
                .extraToolProvider(ContextProbeTool.provider()));
    }

    @AfterEach
    void tearDown() {
        support.close();
    }

    private static Map<String, Object> task(String subagentName) {
        return Map.of("subagent_name", subagentName, "prompt", "probe your context", "description", "probe");
    }

    @Test
    @DisplayName("the main agent's turn has a session id and no execution id")
    void aTurnCarriesTheSessionAxisOnly() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        llm.script(sessionId.value(), ScriptedLlmClient.callTool("ContextProbe", Map.of()),
                ScriptedLlmClient.text("done"));

        node.run(sessionId, "probe the parent context");

        // The positive control for every absence asserted below: on a real turn these keys are populated, so a probe
        // reporting "absent" inside a fork means the fork genuinely lacks them rather than the probe being broken.
        final String observation = llm.lastCallFor(sessionId.value()).lastObservation();
        assertThat(ContextProbeTool.valueOf(observation, ToolContextKeys.SESSION_ID.name()))
                .isEqualTo(sessionId.value());
        assertThat(ContextProbeTool.valueOf(observation, ToolContextKeys.EXECUTION_ID.name()))
                .isEqualTo(ContextProbeTool.ABSENT);
        // The main agent is the invoker, not an invokee — it must not repeat its own id here.
        assertThat(ContextProbeTool.valueOf(observation, ToolContextKeys.INVOKING_SESSION_ID.name()))
                .isEqualTo(ContextProbeTool.ABSENT);
    }

    @Test
    @DisplayName("a fork has an execution id and the invoking session's id, but no session id of its own")
    void forkCarriesExecutionAndInvokerButNoSession() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        final String forkRoute = ScriptedLlmClient.forkRoute(sessionId.value(), OUTER);
        llm.script(sessionId.value(), ScriptedLlmClient.callTool("Task", task(OUTER)),
                ScriptedLlmClient.text("delegated"));
        llm.script(forkRoute, ScriptedLlmClient.callTool("ContextProbe", Map.of()), ScriptedLlmClient.text("probed"));

        node.run(sessionId, "delegate a probe");

        final String observation = forkObservation(forkRoute, 1);
        // The whole point: a fork must not be able to pass for a session. A tool keying on SESSION_ID must find
        // nothing here rather than an id that looks like the user's.
        assertThat(ContextProbeTool.valueOf(observation, ToolContextKeys.SESSION_ID.name()))
                .isEqualTo(ContextProbeTool.ABSENT);
        final String executionId = ContextProbeTool.presentValueOf(observation, ToolContextKeys.EXECUTION_ID.name());
        assertThat(executionId).isNotEqualTo(sessionId.value());
        // Reach, not lifetime: the fork acts for the session that spawned it.
        assertThat(ContextProbeTool.valueOf(observation, ToolContextKeys.INVOKING_SESSION_ID.name()))
                .isEqualTo(sessionId.value());
        // And its todo writes bucket on the run identity, so a fork cannot scribble into the user's list.
        assertThat(ContextProbeTool.valueOf(observation, TodoWriteTool.CONTEXT_ID_KEY.name())).isEqualTo(executionId);
    }

    @Test
    @DisplayName("a nested fork inherits the user's session id, not the intermediate fork's identity")
    void nestedForkInheritsTheUsersSessionIdNotTheIntermediateFork() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        final String outerRoute = ScriptedLlmClient.forkRoute(sessionId.value(), OUTER);
        final String innerRoute = ScriptedLlmClient.forkRoute(sessionId.value(), INNER);
        llm.script(sessionId.value(), ScriptedLlmClient.callTool("Task", task(OUTER)),
                ScriptedLlmClient.text("delegated"));
        llm.script(outerRoute, ScriptedLlmClient.callTool("ContextProbe", Map.of()),
                ScriptedLlmClient.callTool("Task", task(INNER)), ScriptedLlmClient.text("outer done"));
        llm.script(innerRoute, ScriptedLlmClient.callTool("ContextProbe", Map.of()),
                ScriptedLlmClient.text("inner done"));

        node.run(sessionId, "delegate two levels deep");

        final String outerObservation = forkObservation(outerRoute, 1);
        final String innerObservation = forkObservation(innerRoute, 1);
        final String outerExecutionId = ContextProbeTool.presentValueOf(outerObservation,
                ToolContextKeys.EXECUTION_ID.name());
        final String innerExecutionId = ContextProbeTool.presentValueOf(innerObservation,
                ToolContextKeys.EXECUTION_ID.name());

        // Lifetime: each run is its own, two levels down as well as one.
        assertThat(innerExecutionId).isNotEqualTo(outerExecutionId);
        assertThat(ContextProbeTool.valueOf(innerObservation, ToolContextKeys.SESSION_ID.name()))
                .isEqualTo(ContextProbeTool.ABSENT);
        // Reach: the user's session, relayed straight through. Relaying the intermediate fork's own identity instead
        // would make this the outer execution id — a reach that quietly dies one level down.
        assertThat(ContextProbeTool.valueOf(innerObservation, ToolContextKeys.INVOKING_SESSION_ID.name()))
                .isEqualTo(sessionId.value());
        assertThat(ContextProbeTool.valueOf(innerObservation, ToolContextKeys.INVOKING_SESSION_ID.name()))
                .isNotEqualTo(outerExecutionId);
    }

    @Test
    @DisplayName("two forks spawned by one turn get distinct execution ids and distinct todo buckets")
    void repeatedForksGetDistinctExecutionIds() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        final String forkRoute = ScriptedLlmClient.forkRoute(sessionId.value(), OUTER);
        llm.script(sessionId.value(), ScriptedLlmClient.callTool("tu-1", "Task", task(OUTER)),
                ScriptedLlmClient.callTool("tu-2", "Task", task(OUTER)), ScriptedLlmClient.text("both delegated"));
        // Both forks route to the same key and consume this one script in order: probe, finish, probe, finish.
        llm.script(forkRoute, ScriptedLlmClient.callTool("ContextProbe", Map.of()), ScriptedLlmClient.text("first"),
                ScriptedLlmClient.callTool("ContextProbe", Map.of()), ScriptedLlmClient.text("second"));

        node.run(sessionId, "delegate twice");

        assertThat(llm.callCount(forkRoute)).isEqualTo(4);
        final String firstObservation = forkObservation(forkRoute, 1);
        final String secondObservation = forkObservation(forkRoute, 3);
        final String firstExecutionId = ContextProbeTool.presentValueOf(firstObservation,
                ToolContextKeys.EXECUTION_ID.name());
        final String secondExecutionId = ContextProbeTool.presentValueOf(secondObservation,
                ToolContextKeys.EXECUTION_ID.name());

        // An execution id shared across runs would silently merge two concurrent forks' todo lists — the same
        // collapse the "default" bucket causes for unkeyed runs.
        assertThat(firstExecutionId).isNotEqualTo(secondExecutionId);
        assertThat(ContextProbeTool.valueOf(firstObservation, TodoWriteTool.CONTEXT_ID_KEY.name()))
                .isNotEqualTo(ContextProbeTool.valueOf(secondObservation, TodoWriteTool.CONTEXT_ID_KEY.name()));
        // Both still act for the same user session — distinct lifetimes, identical reach.
        assertThat(ContextProbeTool.valueOf(secondObservation, ToolContextKeys.INVOKING_SESSION_ID.name()))
                .isEqualTo(sessionId.value());
    }

    /** The observation carried on call {@code index} of {@code route} — the probe result from that fork iteration. */
    private String forkObservation(String route, int index) {
        assertThat(llm.callsFor(route)).as("fork route %s must have at least %d calls", route, index + 1)
                .hasSizeGreaterThan(index);
        return llm.callsFor(route).get(index).lastObservation();
    }
}
