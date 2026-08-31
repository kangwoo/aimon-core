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
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.ToolUse;

/**
 * L1 — single-turn file tools. Drives the production {@code Read}/{@code Write}/{@code Edit}/{@code Grep} instances
 * that the real providers registered, over a real {@code LocalFileSystem}, through a real ReAct turn.
 *
 * <p>
 * Each test asserts the <b>full round trip</b>, which is what distinguishes this from the per-tool unit tests: the
 * model asks for a tool → the executor finds it in the assembled registry → the real tool runs against real storage →
 * the observation comes back into the next prompt → the loop terminates with an answer. A break anywhere along that
 * chain fails here; a per-tool unit test would still pass.
 */
@DisplayName("RT-IT-L1: file tools through an assembled runtime")
class FileToolTurnIntegrationTest {

    @TempDir
    Path tempDir;

    private OrcaRuntimeItSupport support;
    private ScriptedLlmClient llm;
    private OrcaRuntimeItSupport.Node node;

    @BeforeEach
    void setUp() {
        support = new OrcaRuntimeItSupport(tempDir);
        llm = new ScriptedLlmClient();
        node = support.newNode(llm);
    }

    @AfterEach
    void tearDown() {
        support.close();
    }

    @Test
    @DisplayName("Write — the tool call actually creates the file on the node's file system")
    void writeToolCreatesFile() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        llm.script(sessionId.value(),
                ScriptedLlmClient.callTool("Write", Map.of("file_path", "notes.txt", "content", "hello from IT")),
                ScriptedLlmClient.text("written"));

        final OrcaAgentExecutionResult result = node.run(sessionId, "write a note");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getFinalAnswer()).isEqualTo("written");
        assertThat(node.fileExists("notes.txt")).isTrue();
        assertThat(node.readFile("notes.txt")).isEqualTo("hello from IT");
        // Two calls: the one that asked for the tool, and the one that saw its observation.
        assertThat(llm.callCount(sessionId.value())).isEqualTo(2);
    }

    @Test
    @DisplayName("Read — file content comes back into the next prompt as an observation")
    void readToolObservationReachesNextPrompt() {
        node.writeFile("greeting.txt", "SENTINEL-READ-8f2a");
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        llm.script(sessionId.value(), ScriptedLlmClient.callTool("Read", Map.of("file_path", "greeting.txt")),
                ScriptedLlmClient.text("read it"));

        final OrcaAgentExecutionResult result = node.run(sessionId, "read the greeting");

        assertThat(result.isSuccess()).isTrue();
        // The observation must be fed back — otherwise the model would be reasoning blind on the next iteration.
        assertThat(llm.lastCallFor(sessionId.value()).observations()).anyMatch(c -> c.contains("SENTINEL-READ-8f2a"));
    }

    @Test
    @DisplayName("Edit — the replacement lands in the file, not just in the observation")
    void editToolMutatesFile() {
        node.writeFile("config.txt", "mode=draft\nlevel=1\n");
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        llm.script(sessionId.value(), ScriptedLlmClient.callTool("Read", Map.of("file_path", "config.txt")),
                ScriptedLlmClient.callTool("Edit",
                        Map.of("file_path", "config.txt", "old_string", "mode=draft", "new_string", "mode=final")),
                ScriptedLlmClient.text("edited"));

        final OrcaAgentExecutionResult result = node.run(sessionId, "promote the config");

        assertThat(result.isSuccess()).isTrue();
        // EditTool rebuilds the file line-by-line and drops the trailing newline on purpose (EditTool:267, "to match
        // exact file content"), so the round-tripped content is normalized rather than byte-identical to the seed.
        // Pinned here because it is observable behaviour of the assembled runtime, not an artefact of this test.
        assertThat(node.readFile("config.txt")).isEqualTo("mode=final\nlevel=1");
        assertThat(result.getIterationCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("Grep — matches from real files reach the model")
    void grepToolFindsSeededMatches() {
        node.writeFile("a.txt", "alpha NEEDLE-3c7e beta\n");
        node.writeFile("b.txt", "nothing here\n");
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        llm.script(sessionId.value(),
                ScriptedLlmClient.callTool("Grep", Map.of("pattern", "NEEDLE-3c7e", "output_mode", "content")),
                ScriptedLlmClient.text("found"));

        final OrcaAgentExecutionResult result = node.run(sessionId, "find the needle");

        assertThat(result.isSuccess()).isTrue();
        final List<String> observations = llm.lastCallFor(sessionId.value()).observations();
        assertThat(observations).anyMatch(c -> c.contains("a.txt"));
        assertThat(observations).noneMatch(c -> c.contains("b.txt"));
    }

    @Test
    @DisplayName("a failing tool call is reported to the model as an observation, not as a turn failure")
    void toolFailureIsObservedNotFatal() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        llm.script(sessionId.value(), ScriptedLlmClient.callTool("Read", Map.of("file_path", "does-not-exist.txt")),
                ScriptedLlmClient.text("recovered"));

        final OrcaAgentExecutionResult result = node.run(sessionId, "read a missing file");

        // Tool contract: execute() never throws, so a missing file is an error *result*. The loop must survive it and
        // hand the model a chance to recover — a failed tool must not abort the turn.
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getFinalAnswer()).isEqualTo("recovered");
        assertThat(llm.lastCallFor(sessionId.value()).observations())
                .anyMatch(c -> c.toLowerCase().contains("not found") || c.toLowerCase().contains("error"));
    }

    @Test
    @DisplayName("a hallucinated tool name does not abort the turn, and the model is told why")
    void unknownToolIsReportedAndSurvived() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        llm.script(sessionId.value(), ScriptedLlmClient.callTool("NoSuchTool", Map.of("x", "y")),
                ScriptedLlmClient.text("moved on"));

        final OrcaAgentExecutionResult result = node.run(sessionId, "call a tool that does not exist");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getFinalAnswer()).isEqualTo("moved on");
        // Surviving is only half of it. If the executor swallowed the unknown call and sent nothing back, the model
        // would keep re-issuing it with no idea why nothing happened — so the observation naming the tool is the part
        // that makes recovery possible, and asserting only on the final answer never checked it. Pinned verbatim: the
        // hallucinated name has to appear in the text the model reads, which a loose "contains an error" would not say.
        assertThat(llm.lastCallFor(sessionId.value()).observations()).containsExactly("Unknown tool: NoSuchTool");
    }

    /**
     * The executor's no-progress guard: three consecutive tool-only iterations in which every call failed abort the
     * turn.
     *
     * <p>
     * Without it a model that keeps re-issuing the same failing call burns the whole iteration budget and every token
     * with it. The guard is invisible in ordinary tests — {@link #toolFailureIsObservedNotFatal} deliberately fails
     * once and recovers — so this is the only place the threshold is stated, and it is the reason tests that script
     * several failures in a row have to interleave something that succeeds.
     */
    @Test
    @DisplayName("three consecutive all-failing tool iterations abort the turn")
    void repeatedTotalToolFailureAbortsTheTurn() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        final LlmResponse failingRead = ScriptedLlmClient.callTool("Read", Map.of("file_path", "does-not-exist.txt"));
        // Four failures are scripted but only three can be consumed: the guard fires at the third, before the fourth
        // is requested. A fifth entry would therefore be dead script.
        llm.script(sessionId.value(), failingRead, failingRead, failingRead, failingRead);

        final OrcaAgentExecutionResult result = node.run(sessionId, "keep reading a file that is not there");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("no progress");
        assertThat(result.getIterationCount()).isEqualTo(3);
        assertThat(llm.callCount(sessionId.value())).isEqualTo(3);
    }

    /**
     * One assistant response carrying three {@code tool_use} blocks. Every other test here sends one call per
     * iteration, so nothing else covers the batch path — and the model reconciles a batch by <b>position and
     * {@code tool_use} id</b>, which means a dropped or reordered result does not surface as an error but as the model
     * attributing one call's outcome to another.
     *
     * <p>
     * The failing call sits in the middle on purpose: it is what makes the ordering observable without depending on
     * either {@code Write} observation's exact wording, and it also pins that one failure inside a batch does not
     * discard the siblings that succeeded.
     */
    @Test
    @DisplayName("several tool_use blocks in one response all run, and their observations return in order")
    void batchedToolUsesAllRunAndKeepTheirOrder() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        llm.script(sessionId.value(),
                ScriptedLlmClient.callTools(
                        ToolUse.of("tu-1", "Write", Map.of("file_path", "batch-1.txt", "content", "BATCH-ONE")),
                        ToolUse.of("tu-2", "Read", Map.of("file_path", "does-not-exist.txt")),
                        ToolUse.of("tu-3", "Write", Map.of("file_path", "batch-3.txt", "content", "BATCH-THREE"))),
                ScriptedLlmClient.text("batch done"));

        final OrcaAgentExecutionResult result = node.run(sessionId, "do three things at once");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getFinalAnswer()).isEqualTo("batch done");
        // Two iterations, not four: a batch is one iteration however many calls it carries.
        assertThat(result.getIterationCount()).isEqualTo(2);
        assertThat(node.readFile("batch-1.txt")).isEqualTo("BATCH-ONE");
        assertThat(node.readFile("batch-3.txt")).isEqualTo("BATCH-THREE");

        final List<String> observations = llm.lastCallFor(sessionId.value()).observations();
        assertThat(observations).hasSize(3);
        assertThat(observations.get(0)).contains("batch-1.txt").doesNotContain("File not found");
        assertThat(observations.get(1)).contains("File not found");
        assertThat(observations.get(2)).contains("batch-3.txt").doesNotContain("File not found");
    }

    @Test
    @DisplayName("writes stay inside the node's own file system root")
    void writesAreConfinedToTheNodeRoot() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        llm.script(sessionId.value(),
                ScriptedLlmClient.callTool("Write", Map.of("file_path", "confined.txt", "content", "x")),
                ScriptedLlmClient.text("done"));

        node.run(sessionId, "write");

        // The file must exist under this node's root and nowhere else under the shared temp dir — the property the
        // multi-runtime isolation suite depends on.
        assertThat(node.root().resolve("confined.txt")).exists();
        assertThat(tempDir.resolve("confined.txt")).doesNotExist();
    }
}
