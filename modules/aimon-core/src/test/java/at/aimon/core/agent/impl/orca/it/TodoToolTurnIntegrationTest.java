package at.aimon.core.agent.impl.orca.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.impl.orca.OrcaAgentExecutionResult;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.tools.todo.TodoWriteTool;

/**
 * L1 — {@code TodoWrite} through an assembled runtime.
 *
 * <p>
 * <b>Todos are write-only.</b> There is no read tool, no {@code /todos} command, and no prompt re-injection; the
 * {@code InMemoryTodoRepository} is constructed privately inside {@code OrcaTodoToolProvider} and {@code TodoWriteTool}
 * exposes no accessor for it. So "the list was stored" is not directly observable from a turn, and a test that claimed
 * to check it would really be checking the tool's return string.
 *
 * <p>
 * What <em>is</em> observable — and is the property that actually matters — is <b>which bucket the write goes to</b>.
 * The bucket is chosen by {@link TodoWriteTool#CONTEXT_ID_KEY}, which {@code OrcaAgentExecutor} stamps with the
 * session id. {@link ContextProbeTool} reports that key from inside a real tool call, so these tests assert the
 * routing rather than the storage. If the executor ever stops stamping the key, every session silently shares the
 * {@code "default"} bucket — that is the regression this file exists to catch, and its negative counterpart lives in
 * {@code CrossSessionIsolationIntegrationTest}.
 */
@DisplayName("RT-IT-L1: TodoWrite through an assembled runtime")
class TodoToolTurnIntegrationTest {

    @TempDir
    Path tempDir;

    private OrcaRuntimeItSupport support;
    private ScriptedLlmClient llm;
    private OrcaRuntimeItSupport.Node node;

    @BeforeEach
    void setUp() {
        support = new OrcaRuntimeItSupport(tempDir);
        llm = new ScriptedLlmClient();
        node = support.newNode("agent-a", llm,
                OrcaRuntimeItSupport.options().extraToolProvider(ContextProbeTool.provider()));
    }

    @AfterEach
    void tearDown() {
        support.close();
    }

    private static Map<String, Object> todo(String content, String status, String activeForm) {
        return Map.of("content", content, "status", status, "activeForm", activeForm);
    }

    @Test
    @DisplayName("a todo list is accepted and its summary comes back as an observation")
    void todoListIsWrittenAndSummarised() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        llm.script(sessionId.value(),
                ScriptedLlmClient.callTool("TodoWrite",
                        Map.of("todos",
                                List.of(todo("Seed the fixture", "completed", "Seeding the fixture"),
                                        todo("Run the suite", "in_progress", "Running the suite"),
                                        todo("Report results", "pending", "Reporting results")))),
                ScriptedLlmClient.text("tracked"));

        final OrcaAgentExecutionResult result = node.run(sessionId, "plan the work");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getFinalAnswer()).isEqualTo("tracked");
        final List<String> observations = llm.lastCallFor(sessionId.value()).observations();
        assertThat(observations).anyMatch(observation -> observation.contains("3 total tasks"));
        assertThat(observations).anyMatch(observation -> observation.contains("Running the suite"));
    }

    @Test
    @DisplayName("the write is bucketed by session id, and both turns of one session share that bucket")
    void bucketKeyIsTheSessionIdAndIsStableAcrossTurns() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        llm.script(sessionId.value(), ScriptedLlmClient.callTool("ContextProbe", Map.of()),
                ScriptedLlmClient.text("first"), ScriptedLlmClient.callTool("ContextProbe", Map.of()),
                ScriptedLlmClient.text("second"));

        node.run(sessionId, "first turn");
        final String firstBucket = bucketKeyFrom(sessionId);

        node.run(sessionId, "second turn");
        final String secondBucket = bucketKeyFrom(sessionId);

        // The key must be the session id — not absent (which collapses every session into the shared "default"
        // bucket) and not per-turn (which would lose the list between turns of one session).
        assertThat(firstBucket).isEqualTo(sessionId.value());
        assertThat(secondBucket).isEqualTo(sessionId.value());
    }

    @Test
    @DisplayName("a validation failure is an observation, not a turn failure")
    void twoInProgressTasksAreRejectedAsObservation() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        llm.script(sessionId.value(),
                ScriptedLlmClient.callTool("TodoWrite",
                        Map.of("todos",
                                List.of(todo("First", "in_progress", "Doing first"),
                                        todo("Second", "in_progress", "Doing second")))),
                ScriptedLlmClient.text("fixed it"));

        final OrcaAgentExecutionResult result = node.run(sessionId, "plan badly");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getFinalAnswer()).isEqualTo("fixed it");
        assertThat(llm.lastCallFor(sessionId.value()).observations())
                .anyMatch(observation -> observation.contains("At most ONE task can be 'in_progress'"));
    }

    @Test
    @DisplayName("an unknown todo field is rejected — the schema's additionalProperties:false is really enforced")
    void unknownTodoFieldIsRejected() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        llm.script(sessionId.value(),
                ScriptedLlmClient.callTool("TodoWrite",
                        Map.of("todos",
                                List.of(Map.of("content", "Do the thing", "status", "in_progress", "activeForm",
                                        "Doing the thing", "priority", "high")))),
                ScriptedLlmClient.text("dropped the extra field"));

        final OrcaAgentExecutionResult result = node.run(sessionId, "plan with an invented field");

        // Nothing validates the JSON schema before dispatch; enforcement is Jackson's, which fails on the unknown
        // property while binding Todo. Worth pinning: the schema says additionalProperties:false, and this is the only
        // thing that makes that claim true at runtime.
        assertThat(result.isSuccess()).isTrue();
        assertThat(llm.lastCallFor(sessionId.value()).observations())
                .anyMatch(observation -> observation.contains("Failed to parse todos"));
    }

    @Test
    @DisplayName("an empty todo list is rejected as an observation")
    void emptyTodoListIsRejected() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        llm.script(sessionId.value(), ScriptedLlmClient.callTool("TodoWrite", Map.of("todos", List.of())),
                ScriptedLlmClient.text("recovered"));

        final OrcaAgentExecutionResult result = node.run(sessionId, "plan nothing");

        assertThat(result.isSuccess()).isTrue();
        assertThat(llm.lastCallFor(sessionId.value()).observations())
                .anyMatch(observation -> observation.contains("Todo list cannot be empty"));
    }

    /** Reads the todo bucket key out of the most recent probe observation for {@code sessionId}. */
    private String bucketKeyFrom(SessionId sessionId) {
        final String observation = llm.lastCallFor(sessionId.value()).lastObservation();
        return ContextProbeTool.valueOf(observation, TodoWriteTool.CONTEXT_ID_KEY.name());
    }
}
