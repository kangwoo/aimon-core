package at.aimon.core.agent.impl.orca.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.impl.orca.OrcaAgentExecutionResult;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.tools.ToolContextKeys;
import at.aimon.core.tools.todo.TodoWriteTool;

/**
 * L4 — two sessions of the <b>same</b> agent runtime must not see each other's state.
 *
 * <p>
 * The runtime is agent-scoped and deliberately shared across sessions, so nothing here is separated by construction the
 * way two runtimes' file systems are. Session separation is achieved instead by <b>keying</b>: the executor stamps the
 * session id into the tool context and the transcript store, and everything downstream buckets on it. That makes the
 * failure mode quiet — if a key stops being stamped, nothing throws; two sessions simply start sharing a bucket.
 *
 * <p>
 * {@code TodoWriteTool} is the sharpest example: an absent {@link TodoWriteTool#CONTEXT_ID_KEY} does not fail the
 * write, it silently redirects it to the {@code "default"} bucket that every unkeyed run shares. So
 * {@link #todoBucketsAreKeyedByTheSessionId} asserts the key is present <em>and</em> is the session's own id — the
 * absence check is the point, not a formality.
 *
 * <p>
 * The file system is intentionally <b>not</b> asserted to be session-separated: it is agent-scoped, so two sessions of
 * one agent share it by design. Cross-agent file isolation lives in {@code MultiRuntimeIsolationIntegrationTest}.
 */
@DisplayName("RT-IT-L4: isolation between sessions of one runtime")
class CrossSessionIsolationIntegrationTest {

    @TempDir
    Path tempDir;

    private OrcaRuntimeItSupport support;
    private ScriptedLlmClient llm;
    private OrcaRuntimeItSupport.Node node;

    @BeforeEach
    void setUp() {
        support = new OrcaRuntimeItSupport(tempDir);
        llm = new ScriptedLlmClient();
        // Two parties: the two sessions that meet in concurrentSessionsRouteIndependently. The other tests never call
        // the tool, so the barrier simply goes unused.
        node = support.newNode("agent-a", llm,
                OrcaRuntimeItSupport.options().extraToolProvider(ContextProbeTool.provider())
                        .extraToolProvider(RendezvousTool.provider(new CyclicBarrier(2))));
    }

    @AfterEach
    void tearDown() {
        support.close();
    }

    @Test
    @DisplayName("each session's todo writes are bucketed by its own session id, never the shared default")
    void todoBucketsAreKeyedByTheSessionId() {
        final SessionId first = OrcaRuntimeItSupport.newSession();
        final SessionId second = OrcaRuntimeItSupport.newSession();
        llm.script(first.value(), ScriptedLlmClient.callTool("ContextProbe", Map.of()), ScriptedLlmClient.text("a"));
        llm.script(second.value(), ScriptedLlmClient.callTool("ContextProbe", Map.of()), ScriptedLlmClient.text("b"));

        node.run(first, "probe from the first session");
        node.run(second, "probe from the second session");

        final String firstBucket = bucketKeyOf(first);
        final String secondBucket = bucketKeyOf(second);

        assertThat(firstBucket).isEqualTo(first.value());
        assertThat(secondBucket).isEqualTo(second.value());
        assertThat(firstBucket).isNotEqualTo(secondBucket);
        // "default" is TodoWriteTool's private DEFAULT_CONTEXT_ID — the bucket every context-less run falls into.
        // Landing there is exactly the silent collapse this test exists to catch.
        assertThat(firstBucket).isNotEqualTo("default");
    }

    @Test
    @DisplayName("one session's conversation does not appear in another session's prompt")
    void transcriptsDoNotLeakBetweenSessions() {
        final SessionId first = OrcaRuntimeItSupport.newSession();
        final SessionId second = OrcaRuntimeItSupport.newSession();
        llm.script(first.value(), ScriptedLlmClient.text("noted"), ScriptedLlmClient.text("still noted"));
        llm.script(second.value(), ScriptedLlmClient.text("unrelated"));

        node.run(first, "remember SENTINEL-SESSION-A-6d18");
        node.run(second, "say something unrelated");
        node.run(first, "what did I ask you to remember");

        // Positive half: the first session's second turn really does carry its own earlier turn, so the transcript is
        // doing its job and the negative assertion below is not passing vacuously.
        assertThat(llm.lastCallFor(first.value()).allText()).anyMatch(text -> text.contains("SENTINEL-SESSION-A-6d18"));
        assertThat(llm.callsFor(second.value())).allSatisfy(
                call -> assertThat(call.allText()).noneMatch(text -> text.contains("SENTINEL-SESSION-A-6d18")));
    }

    @Test
    @DisplayName("a tool observation from one session never reaches the other session's model")
    void observationsDoNotLeakBetweenSessions() {
        final SessionId first = OrcaRuntimeItSupport.newSession();
        final SessionId second = OrcaRuntimeItSupport.newSession();
        llm.script(first.value(),
                ScriptedLlmClient.callTool("Write", Map.of("file_path", "note.txt", "content", "OBS-A-4b73")),
                ScriptedLlmClient.callTool("Read", Map.of("file_path", "note.txt")), ScriptedLlmClient.text("written"));
        // The second session must run a tool of its own. Scripting it as a bare text reply — as this test used to —
        // leaves it with no observations at all, and "no observation from A leaked" then holds because there are no
        // observations to inspect. It would keep holding if every session shared one buffer.
        llm.script(second.value(),
                ScriptedLlmClient.callTool("Write", Map.of("file_path", "other.txt", "content", "OBS-B-91ce")),
                ScriptedLlmClient.callTool("Read", Map.of("file_path", "other.txt")),
                ScriptedLlmClient.text("also written"));

        node.run(first, "write a note");
        node.run(second, "write a different note");

        assertThat(observationsOf(first)).anyMatch(text -> text.contains("OBS-A-4b73"))
                .noneMatch(text -> text.contains("OBS-B-91ce"));
        assertThat(observationsOf(second)).anyMatch(text -> text.contains("OBS-B-91ce"))
                .noneMatch(text -> text.contains("OBS-A-4b73"));
        // The two sessions share this agent's file system by design, so either file is readable from either session —
        // what must not cross is the conversation, and it did not.
        assertThat(node.fileExists("note.txt")).isTrue();
        assertThat(node.fileExists("other.txt")).isTrue();
    }

    /**
     * Both turns are held at a shared barrier mid-flight, so the isolation below is asserted against genuine overlap.
     *
     * <p>
     * Releasing a start latch and then reading the results does not do that: the latch gates submission, and the first
     * turn can finish before the second thread is even scheduled. The test then proves only that two sequential turns
     * route independently — which the rest of this class already covers — and would keep passing if the runtime
     * serialised every turn behind one lock.
     */
    @Test
    @DisplayName("concurrent sessions of one runtime each consume only their own script")
    void concurrentSessionsRouteIndependently() throws Exception {
        final SessionId first = OrcaRuntimeItSupport.newSession();
        final SessionId second = OrcaRuntimeItSupport.newSession();
        llm.script(first.value(),
                ScriptedLlmClient.callTool("Write", Map.of("file_path", "first.txt", "content", "CONC-A-1e62")),
                ScriptedLlmClient.callTool("Rendezvous", Map.of()),
                ScriptedLlmClient.callTool("Read", Map.of("file_path", "first.txt")),
                ScriptedLlmClient.text("first done"));
        llm.script(second.value(),
                ScriptedLlmClient.callTool("Write", Map.of("file_path", "second.txt", "content", "CONC-B-7a04")),
                ScriptedLlmClient.callTool("Rendezvous", Map.of()),
                ScriptedLlmClient.callTool("Read", Map.of("file_path", "second.txt")),
                ScriptedLlmClient.text("second done"));

        final ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            final Future<OrcaAgentExecutionResult> futureFirst = pool.submit(() -> node.run(first, "run the first"));
            final Future<OrcaAgentExecutionResult> futureSecond = pool.submit(() -> node.run(second, "run the second"));

            // A routing collapse would show here first: one session would consume the other's next response and end
            // with the wrong final answer, or run off the end of its script (which now throws rather than silently
            // answering "done").
            assertThat(futureFirst.get(60, TimeUnit.SECONDS).getFinalAnswer()).isEqualTo("first done");
            assertThat(futureSecond.get(60, TimeUnit.SECONDS).getFinalAnswer()).isEqualTo("second done");
        } finally {
            pool.shutdownNow();
        }

        // Proof the overlap happened: neither turn could have passed the barrier alone. A timeout would surface here as
        // an error observation instead, and the isolation assertions below would be back to testing sequential runs.
        assertThat(observationsOf(first)).anyMatch(text -> text.startsWith(RendezvousTool.ARRIVED));
        assertThat(observationsOf(second)).anyMatch(text -> text.startsWith(RendezvousTool.ARRIVED));

        assertThat(llm.callCount(first.value())).isEqualTo(4);
        assertThat(llm.callCount(second.value())).isEqualTo(4);
        assertThat(observationsOf(first)).anyMatch(text -> text.contains("CONC-A-1e62"))
                .noneMatch(text -> text.contains("CONC-B-7a04"));
        assertThat(observationsOf(second)).anyMatch(text -> text.contains("CONC-B-7a04"))
                .noneMatch(text -> text.contains("CONC-A-1e62"));
    }

    private List<String> observationsOf(SessionId sessionId) {
        return llm.callsFor(sessionId.value()).stream().flatMap(call -> call.observations().stream()).toList();
    }

    private String bucketKeyOf(SessionId sessionId) {
        final String observation = llm.lastCallFor(sessionId.value()).lastObservation();
        // presentValueOf, not valueOf: an absent key is the regression, and it must not read as "the bucket is
        // literally <absent>".
        assertThat(ContextProbeTool.presentValueOf(observation, ToolContextKeys.SESSION_ID.name()))
                .isEqualTo(sessionId.value());
        return ContextProbeTool.presentValueOf(observation, TodoWriteTool.CONTEXT_ID_KEY.name());
    }
}
