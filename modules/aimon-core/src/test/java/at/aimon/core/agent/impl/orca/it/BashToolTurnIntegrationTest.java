package at.aimon.core.agent.impl.orca.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.impl.orca.OrcaAgentExecutionResult;
import at.aimon.core.agent.session.SessionId;

/**
 * L1 — {@code Bash} and {@code BashOutput} through an assembled runtime.
 *
 * <p>
 * <b>Bash runs in the execution environment's shell.</b> The tool takes its {@code VirtualShell} from the
 * {@code ExecutionEnvironment} of the execution (execution-environment design §4.2, §6), the same environment its file
 * tools read and write, so the shell's default working directory is the workspace root — here the node's
 * {@code VirtualFileSystem} root — not the JVM's current directory. {@link #bashRunsInTheNodeRoot} pins that.
 *
 * <p>
 * The shell is still not confined by the {@code VirtualFileSystem}: a command given an absolute path writes wherever
 * the process may write, past the file tools' path rules. {@link #bashWritesBypassTheVirtualFileSystem} pins that half,
 * and it is why the multi-runtime isolation suite claims nothing about Bash.
 */
@DisplayName("RT-IT-L1: Bash through an assembled runtime")
class BashToolTurnIntegrationTest {

    /** Matches the id in {@code "Background task started with ID: bash_1a2b3c4d"} (BashTool#executeInBackground). */
    private static final Pattern TASK_ID = Pattern.compile("ID: (bash_[0-9a-f]+)");

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
    @DisplayName("a command really runs and its stdout comes back as an observation")
    void commandOutputReachesTheModel() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        llm.script(sessionId.value(), ScriptedLlmClient.callTool("Bash", Map.of("command", "echo SENTINEL-BASH-4d19")),
                ScriptedLlmClient.text("ran it"));

        final OrcaAgentExecutionResult result = node.run(sessionId, "echo a sentinel");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getFinalAnswer()).isEqualTo("ran it");
        assertThat(llm.lastCallFor(sessionId.value()).observations())
                .anyMatch(observation -> observation.contains("SENTINEL-BASH-4d19"));
    }

    @Test
    @DisplayName("a non-zero exit is an observation, not a turn failure")
    void nonZeroExitIsObservedNotFatal() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        llm.script(sessionId.value(),
                ScriptedLlmClient.callTool("Bash", Map.of("command", "echo BEFORE-EXIT-7c31 && exit 3")),
                ScriptedLlmClient.text("recovered"));

        final OrcaAgentExecutionResult result = node.run(sessionId, "run a failing command");

        // The shell returns a non-zero exit as a normal ShellCommandResult — it does not throw — and BashTool is what
        // turns that into ToolResult.error. The loop must hand it to the model and continue: exiting 3 is
        // information, not a crash.
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getFinalAnswer()).isEqualTo("recovered");
        final List<String> observations = llm.lastCallFor(sessionId.value()).observations();
        assertThat(observations).anyMatch(observation -> observation.contains("[exit code: 3]"));
        // The output produced before the failure survives into the error message — losing it would leave the model
        // guessing about what the command managed to do.
        assertThat(observations).anyMatch(observation -> observation.contains("BEFORE-EXIT-7c31"));
    }

    @Test
    @DisplayName("Bash runs in the execution environment's working directory — the node's file system root")
    void bashRunsInTheNodeRoot() throws Exception {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        llm.script(sessionId.value(), ScriptedLlmClient.callTool("Bash", Map.of("command", "pwd")),
                ScriptedLlmClient.text("done"));

        node.run(sessionId, "where am I");

        final String reported = llm.lastCallFor(sessionId.value()).observations().stream()
                .filter(observation -> observation.startsWith("/")).findFirst()
                .orElseThrow(() -> new AssertionError("pwd produced no path-looking observation"));

        // Behaviour change (execution-environment design §1.6/§4.2): the shell and the file tools come from one
        // environment and see the same files, so the default cwd is the workspace root, not the JVM's directory.
        // Compared as real paths — macOS reports /private/var for a /var temp dir.
        assertThat(Path.of(reported.trim()).toRealPath()).isEqualTo(node.root().toRealPath());
        assertThat(reported.trim()).isNotEqualTo(System.getProperty("user.dir"));
    }

    @Test
    @DisplayName("a file created by Bash does not land in the node's file system")
    void bashWritesBypassTheVirtualFileSystem() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        // Write via an absolute path into the temp dir so the test never litters the JVM working directory.
        final Path target = tempDir.resolve("bash-made-this.txt");
        llm.script(sessionId.value(),
                ScriptedLlmClient.callTool("Bash", Map.of("command", "echo hi > '" + target + "'")),
                ScriptedLlmClient.text("done"));

        node.run(sessionId, "write via bash");

        // It exists on disk, but outside the node's root — so node.fileExists cannot see it. This is the concrete
        // shape of "Bash is not VFS-confined", and the reason the isolation suite makes no Bash claims.
        assertThat(target).exists();
        assertThat(node.fileExists("bash-made-this.txt")).isFalse();
    }

    @Test
    @DisplayName("a background command's output is retrievable through BashOutput in a later iteration")
    void backgroundCommandIsReadableViaBashOutput() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        // The second response is dynamic: BashOutput needs the taskId that the first call's observation just minted.
        llm.scriptDynamic(sessionId.value(),
                call -> ScriptedLlmClient.callTool("Bash",
                        Map.of("command", "echo SENTINEL-BG-6b02", "run_in_background", true)),
                call -> ScriptedLlmClient.callTool("BashOutput",
                        Map.of("taskId", taskIdFrom(call.lastObservation()), "block", true, "wait_up_to", 10)),
                call -> ScriptedLlmClient.text("collected"));

        final OrcaAgentExecutionResult result = node.run(sessionId, "run in background then collect");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getFinalAnswer()).isEqualTo("collected");
        // Both tools were registered by one provider and share a BackgroundBashManager; if that sharing broke, the
        // second call would report "Shell not found" instead of the output.
        assertThat(llm.lastCallFor(sessionId.value()).observations())
                .anyMatch(observation -> observation.contains("SENTINEL-BG-6b02"));
    }

    @Test
    @DisplayName("BashOutput for an unknown task is an observation, not a turn failure")
    void unknownBackgroundTaskIsObserved() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        llm.script(sessionId.value(), ScriptedLlmClient.callTool("BashOutput", Map.of("taskId", "bash_deadbeef")),
                ScriptedLlmClient.text("moved on"));

        final OrcaAgentExecutionResult result = node.run(sessionId, "collect a task that never existed");

        assertThat(result.isSuccess()).isTrue();
        assertThat(llm.lastCallFor(sessionId.value()).observations())
                .anyMatch(observation -> observation.contains("Shell not found"));
    }

    private static String taskIdFrom(String observation) {
        final Matcher matcher = TASK_ID.matcher(observation);
        if (!matcher.find()) {
            throw new AssertionError("no background task id in observation: " + observation);
        }
        return matcher.group(1);
    }
}
