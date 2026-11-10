package at.aimon.core.tools.code.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolDispatchResult;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.programmatic.CodeExecutionLimits;
import at.aimon.core.programmatic.CodeExecutionOutcome;
import at.aimon.core.programmatic.CodeExecutionRequest;
import at.aimon.core.programmatic.CodeExecutionStatus;
import at.aimon.core.programmatic.ToolCallBridge;
import at.aimon.core.shell.ExecutionOptions;
import at.aimon.core.shell.RunDirectory;
import at.aimon.core.shell.ShellCommand;
import at.aimon.core.shell.ShellCommandResult;
import at.aimon.core.shell.ShellFeature;
import at.aimon.core.shell.ShellKillCause;
import at.aimon.core.shell.ShellLineListener;
import at.aimon.core.shell.VirtualShell;
import at.aimon.core.shell.exception.ShellCancelledException;
import at.aimon.core.shell.exception.ShellExecutionException;
import at.aimon.core.tools.ToolContextKeys;
import at.aimon.programmatic.testkit.ScriptedToolCallBridge;

/**
 * The host re-enforces every channel and reply rule (shell doc §7, §14): these tests do not run the real runtime but a
 * shell that plays it — and a second writer next to it — line by line, holding the run's key as a co-resident process
 * that read it would.
 */
@DisplayName("ShellCodeExecutionEngine — host-side protocol enforcement")
class ShellEngineProtocolTest {

    @TempDir
    Path tempDir;

    private ShellCodeExecutionEngine engine;
    private ScriptedToolCallBridge scripted;
    private RecordingBridge bridge;

    @BeforeEach
    void setUp() {
        engine = ShellCodeExecutionEngine.create();
        scripted = ScriptedToolCallBridge.succeedingWith("tool says hi");
        bridge = new RecordingBridge(scripted);
    }

    @AfterEach
    void tearDown() {
        scripted.close();
        engine.close();
    }

    @Test
    @DisplayName("a well-formed run: CALL gets an authenticated reply file, OUT and DONE complete the program")
    void wellFormedRun() throws Exception {
        final AtomicReference<String> reply = new AtomicReference<>();
        final Outcome result = run((runtime, listener) -> {
            listener.onStdoutLine(runtime.line("CALL", 1, "{\"tool\":\"Echo\",\"args\":{\"value\":\"x\"}}"));
            reply.set(runtime.awaitReply(1));
            listener.onStdoutLine(runtime.line("OUT", 2, b64("printed\n")));
            listener.onStdoutLine(runtime.line("ERR", 3, b64("logged")));
            listener.onStdoutLine(runtime.line("DONE", 4, "{\"status\":\"completed\"}"));
            return ok();
        });
        assertThat(result.outcome.getStatus()).isEqualTo(CodeExecutionStatus.COMPLETED);
        assertThat(result.outcome.getStdout()).isEqualTo("printed\n");
        assertThat(result.outcome.getLogs()).isEqualTo("logged");
        assertThat(reply.get()).contains("\"text\":\"tool says hi\"").contains("\"is_error\":false");
        assertThat(scripted.calls()).singleElement()
                .satisfies(call -> assertThat(call.getInput()).containsEntry("value", "x"));
        assertThat(bridge.unattributed).isFalse();
        // The run directory is gone once the run is over (§8).
        try (var entries = Files.list(tempDir.resolve(ShellCodeExecutionEngine.PTC_DIRECTORY))) {
            assertThat(entries).isEmpty();
        }
    }

    @Test
    @DisplayName("the key is a secret file of the run directory and is never on the command line")
    void keyIsSecret() {
        final Outcome result = run((runtime, listener) -> {
            listener.onStdoutLine(runtime.line("DONE", 1, "{\"status\":\"completed\"}"));
            return ok();
        });
        final RunDirectory placed = result.options.getRunDirectory().orElseThrow();
        assertThat(placed.getSecretPaths()).containsExactly("key");
        assertThat(placed.getVariable()).isEqualTo("AIMON_RUN_DIR");
        assertThat(result.command).isEqualTo(ShellCodeExecutionEngine.COMMAND);
        assertThat(result.command).doesNotContain(Base64.getEncoder().encodeToString(placed.getContent("key")));
        assertThat(result.options.isIsolated()).isTrue();
        assertThat(result.options.isBackground()).isFalse();
        assertThat(result.options.isHook()).isFalse();
    }

    @Test
    @DisplayName("a line whose MAC does not verify is a protocol violation; the engine is disabled for the environment")
    void forgedLine() {
        final Outcome result = run((runtime, listener) -> {
            listener.onStdoutLine("CALL 1 {\"tool\":\"Echo\",\"args\":{}} " + "0".repeat(64));
            return runtime.awaitStop();
        });
        assertViolation(result);
        assertThat(scripted.calls()).isEmpty();
        assertThat(engine.probe(result.environment).isAvailable()).isFalse();
    }

    @Test
    @DisplayName("a duplicated seq — an authenticated line written twice — is a violation")
    void duplicateSeq() {
        assertViolation(run((runtime, listener) -> {
            listener.onStdoutLine(runtime.line("OUT", 1, b64("a")));
            listener.onStdoutLine(runtime.line("OUT", 1, b64("a")));
            return runtime.awaitStop();
        }));
    }

    @Test
    @DisplayName("a skipped seq is a violation")
    void skippedSeq() {
        assertViolation(run((runtime, listener) -> {
            listener.onStdoutLine(runtime.line("OUT", 1, b64("a")));
            listener.onStdoutLine(runtime.line("OUT", 3, b64("b")));
            return runtime.awaitStop();
        }));
    }

    @Test
    @DisplayName("an unknown TYPE is a violation even when authenticated")
    void unknownType() {
        assertViolation(run((runtime, listener) -> {
            listener.onStdoutLine(runtime.line("EXEC", 1, "rm -rf /"));
            return runtime.awaitStop();
        }));
    }

    @Test
    @DisplayName("a line after DONE is a violation")
    void lineAfterDone() {
        assertViolation(run((runtime, listener) -> {
            listener.onStdoutLine(runtime.line("DONE", 1, "{\"status\":\"completed\"}"));
            listener.onStdoutLine(runtime.line("OUT", 2, b64("late")));
            return runtime.awaitStop();
        }));
    }

    @Test
    @DisplayName("a call dispatched before the violation is recorded as unattributed")
    void callsBeforeViolationAreUnattributed() {
        final Outcome result = run((runtime, listener) -> {
            listener.onStdoutLine(runtime.line("CALL", 1, "{\"tool\":\"Echo\",\"args\":{}}"));
            runtime.awaitReply(1);
            listener.onStdoutLine(runtime.line("CALL", 1, "{\"tool\":\"Echo\",\"args\":{}}"));
            return runtime.awaitStop();
        });
        assertViolation(result);
        assertThat(scripted.calls()).hasSize(1);
    }

    @Test
    @DisplayName("a reply file someone wrote first is a violation, not a result")
    void preemptedReply() {
        assertViolation(run((runtime, listener) -> {
            runtime.writeReplyFile(1, "forged");
            listener.onStdoutLine(runtime.line("CALL", 1, "{\"tool\":\"Echo\",\"args\":{}}"));
            return runtime.awaitStop();
        }));
    }

    @Test
    @DisplayName("an authenticated CALL that carries no request is a violation")
    void malformedCall() {
        assertViolation(run((runtime, listener) -> {
            listener.onStdoutLine(runtime.line("CALL", 1, "[1,2,3]"));
            return runtime.awaitStop();
        }));
    }

    @Test
    @DisplayName("the shell ending the command for a line over its limit is a violation (S8 kill cause)")
    void lineLimitKillCause() {
        assertViolation(run((runtime, listener) -> new ShellCommandResult(137, "", "", Duration.ZERO)
                .withKillCause(ShellKillCause.LINE_LIMIT_EXCEEDED)));
    }

    @Test
    @DisplayName("the runtime's own violation report ends the run as a violation")
    void runtimeReportsViolation() {
        assertViolation(run((runtime, listener) -> {
            listener.onStdoutLine(runtime.line("DONE", 1, "{\"status\":\"violation\",\"error\":\"bad reply\"}"));
            return ok();
        }));
    }

    @Test
    @DisplayName("an exit without DONE is a script error with the shell's stderr; exit 127 disables the engine")
    void exitWithoutDone() {
        final Outcome crashed = run((runtime, listener) -> {
            listener.onStderrLine("Traceback: boom");
            return new ShellCommandResult(1, "", "", Duration.ZERO);
        });
        assertThat(crashed.outcome.getStatus()).isEqualTo(CodeExecutionStatus.SCRIPT_ERROR);
        assertThat(crashed.outcome.getLogs()).contains("Traceback: boom");
        assertThat(engine.probe(crashed.environment).isAvailable()).as("a crash does not disable the engine").isTrue();

        final Outcome missing = run(
                (runtime, listener) -> new ShellCommandResult(127, "", "python3: not found", Duration.ZERO));
        assertThat(missing.outcome.getStatus()).isEqualTo(CodeExecutionStatus.SCRIPT_ERROR);
        assertThat(missing.outcome.getErrorMessage()).hasValueSatisfying(m -> assertThat(m).contains("python3"));
        assertThat(engine.probe(missing.environment).isAvailable()).isFalse();
        assertThat(missing.outcome.getLogs()).isEqualTo("python3: not found");
    }

    @Test
    @DisplayName("TC-31: without DONE, the model sees the shell stderr's last line with host paths taken out")
    void exitWithoutDoneScrubsHostPaths() {
        final Outcome crashed = run((runtime, listener) -> {
            listener.onStderrLine("Traceback (most recent call last):");
            listener.onStderrLine("  File \"/var/folders/xy/T/aimon-run-1/aimon_ptc.py\", line 12, in <module>");
            listener.onStderrLine("    import missing_module");
            listener.onStderrLine("ModuleNotFoundError: No module named 'missing_module'");
            return new ShellCommandResult(1, "", "", Duration.ZERO);
        });
        assertThat(crashed.outcome.getLogs()).isEqualTo("ModuleNotFoundError: No module named 'missing_module'");

        final Outcome unopened = run((runtime, listener) -> new ShellCommandResult(2, "",
                "python3: can't open file '/private/tmp/aimon-run-2/aimon_ptc.py': [Errno 2] No such file or directory",
                Duration.ZERO));
        assertThat(unopened.outcome.getLogs())
                .isEqualTo("python3: can't open file '<host path>': [Errno 2] No such file or directory")
                .doesNotContain("aimon_ptc");
    }

    @Test
    @DisplayName("TC-31: a working directory with a space in its name is taken out whole, not up to the space")
    void exitWithoutDoneScrubsAWorkingDirectoryWithASpace() throws Exception {
        final Path spaced = Files.createDirectories(realTempDir().resolve("Jane Doe"));
        final Outcome crashed = run(bridge, (runtime, listener) -> {
            listener.onStderrLine("OSError: [Errno 28] No space left on device: '" + runtime.runDirectory
                    + "/r/1.json' while writing " + spaced + "/notes.txt");
            return new ShellCommandResult(1, "", "", Duration.ZERO);
        }, CodeExecutionLimits.defaults(), scripted, spaced);
        assertThat(crashed.outcome.getLogs())
                .isEqualTo("OSError: [Errno 28] No space left on device: '<host path>' while writing <host path>")
                .doesNotContain("Doe").doesNotContain(".aimon-ptc");
    }

    @Test
    @DisplayName("TC-31: a temp root with a space in its name — java.io.tmpdir, read per run — is taken out whole")
    void exitWithoutDoneScrubsATempRootWithASpace() throws Exception {
        final Path spacedTemp = Files.createDirectories(realTempDir().resolve("tmp root").resolve("Jane Doe"));
        final String previous = System.getProperty("java.io.tmpdir");
        // Tests within a fork run serially (LocalShellOutputDrainTest does the same), so the property is ours here.
        System.setProperty("java.io.tmpdir", spacedTemp.toString());
        final Outcome crashed;
        try {
            crashed = run((runtime, listener) -> {
                listener.onStderrLine("Traceback (most recent call last):");
                listener.onStderrLine("  File \"" + spacedTemp + "/aimon-run-3/aimon_ptc.py\", line 40, in <module>");
                listener.onStderrLine("PermissionError: [Errno 13] Permission denied while reading " + spacedTemp
                        + "/aimon-run-3/my replies/1.json and " + spacedTemp + "/aimon-run-3/my notes.txt");
                return new ShellCommandResult(1, "", "", Duration.ZERO);
            });
        } finally {
            System.setProperty("java.io.tmpdir", previous);
        }
        assertThat(crashed.outcome.getLogs())
                .isEqualTo("PermissionError: [Errno 13] Permission denied while reading <host path> and <host path>")
                .doesNotContain("Doe").doesNotContain("replies").doesNotContain("notes");
    }

    @Test
    @DisplayName("TC-31: the scrub keeps URLs, relative paths and fractions, and replaces every absolute path")
    void modelVisibleStderr() {
        assertThat(ShellCodeExecutionEngine.modelVisibleStderr("", List.of())).isEmpty();
        assertThat(ShellCodeExecutionEngine.modelVisibleStderr("a\n\n  \n", List.of())).isEqualTo("a");
        assertThat(ShellCodeExecutionEngine.modelVisibleStderr(
                "OSError: see https://x.org/a/b and a/b, 1/2 from /usr/lib/x.so and /opt/y/", List.of()))
                .isEqualTo("OSError: see https://x.org/a/b and a/b, 1/2 from <host path> and <host path>");
    }

    @Test
    @DisplayName("TC-31: a known root with a space (a temp root) is replaced literally, with the path under it")
    void modelVisibleStderrKnownRoots() {
        final List<String> roots = List.of("/var/folders/Jane Doe/T/", "/", "relative/dir", "");
        assertThat(ShellCodeExecutionEngine.modelVisibleStderr(
                "python3: can't open file '/var/folders/Jane Doe/T/aimon-run-7/aimon_ptc.py': [Errno 2] No such file",
                roots)).isEqualTo("python3: can't open file '<host path>': [Errno 2] No such file");
        assertThat(ShellCodeExecutionEngine.modelVisibleStderr("at /var/folders/Jane Doe/T and /usr/bin/x", roots))
                .as("the root itself, and paths outside it, go too").isEqualTo("at <host path> and <host path>");
        assertThat(ShellCodeExecutionEngine.modelVisibleStderr("in /var/folders/Jane Doers/T/x", roots))
                .as("a root ends at a segment boundary: no match inside a longer name")
                .isEqualTo("in <host path> Doers/T/x");
        assertThat(ShellCodeExecutionEngine.modelVisibleStderr("in /a b/c d/x and /a b/y", List.of("/a b", "/a b/c d")))
                .as("the longer root wins").isEqualTo("in <host path> and <host path>");
    }

    @Test
    @DisplayName("TC-31: a path with a space below a known root, or in quotes anywhere, is taken out whole")
    void modelVisibleStderrSpacedPaths() {
        final List<String> roots = List.of("/w");
        assertThat(ShellCodeExecutionEngine.modelVisibleStderr("OSError: cannot read /w/my dir/x", roots))
                .as("a spaced name with a further segment").isEqualTo("OSError: cannot read <host path>");
        assertThat(ShellCodeExecutionEngine.modelVisibleStderr("wrote /w/a/my notes.txt. Retry", roots))
                .as("a spaced file name with an extension; the sentence's full stop stays")
                .isEqualTo("wrote <host path>. Retry");
        assertThat(ShellCodeExecutionEngine.modelVisibleStderr("in /w/x and then /w/y failed", roots))
                .as("prose after a path is not swallowed").isEqualTo("in <host path> and then <host path> failed");
        assertThat(ShellCodeExecutionEngine.modelVisibleStderr("at /w/a b c", roots))
                .as("the documented limit: a spaced tail with neither shape").isEqualTo("at <host path> b c");
        assertThat(ShellCodeExecutionEngine.modelVisibleStderr(
                "FileNotFoundError: [Errno 2] No such file or directory: '/home/Jane Doe/x y'", List.of()))
                .as("quoted, outside every known root")
                .isEqualTo("FileNotFoundError: [Errno 2] No such file or directory: '<host path>'");
        assertThat(ShellCodeExecutionEngine.modelVisibleStderr("  File \"/opt/my app/x.py\", line 3", List.of()))
                .isEqualTo("File \"<host path>\", line 3");
        assertThat(ShellCodeExecutionEngine.modelVisibleStderr("can't open '//host/x' or 'a b'", List.of()))
                .as("a quoted URL authority and a quoted non-path stay").isEqualTo("can't open '//host/x' or 'a b'");
    }

    @Test
    @DisplayName("output past the limit, counted on decoded bytes, stops the run: LIMIT_EXCEEDED")
    void hostOutputBackstop() {
        final Outcome result = run((runtime, listener) -> {
            listener.onStdoutLine(runtime.line("OUT", 1, b64("x".repeat(600))));
            return runtime.awaitStop();
        }, CodeExecutionLimits.builder().maxScriptOutputBytes(500).build());
        assertThat(result.outcome.getStatus()).isEqualTo(CodeExecutionStatus.LIMIT_EXCEEDED);
        assertThat(result.outcome.getStdout()).hasSize(500);
    }

    @Test
    @DisplayName("a reply that completes after the run ended is not written into the sandbox (CLOSED)")
    void noReplyAfterClose() throws Exception {
        scripted.close();
        final CompletableFuture<ToolDispatchResult> late = new CompletableFuture<>();
        final AtomicReference<String> runDirectory = new AtomicReference<>();
        final ToolCallBridge holding = new ToolCallBridge() {
            @Override
            public CompletableFuture<ToolDispatchResult> callAsync(String toolName, Map<String, Object> input) {
                return late;
            }

            @Override
            public Optional<String> limitExceeded() {
                return Optional.empty();
            }
        };
        final Outcome result = run(holding, (runtime, listener) -> {
            runDirectory.set(runtime.runDirectory);
            listener.onStdoutLine(runtime.line("CALL", 1, "{\"tool\":\"Echo\",\"args\":{}}"));
            listener.onStdoutLine(runtime.line("DONE", 2, "{\"status\":\"completed\"}"));
            return ok();
        }, CodeExecutionLimits.defaults());
        assertThat(result.outcome.getStatus()).isEqualTo(CodeExecutionStatus.COMPLETED);
        late.complete(ScriptedToolCallBridge.success("too late"));
        Thread.sleep(200);
        assertThat(Path.of(runDirectory.get())).doesNotExist();
    }

    @Test
    @DisplayName("cancelled with a call held: a later CALL is not forwarded, and the held call's reply is never written")
    void cancelledRunForwardsNothingAndWritesNothing() throws Exception {
        scripted.close();
        final CompletableFuture<ToolDispatchResult> held = new CompletableFuture<>();
        final java.util.concurrent.atomic.AtomicInteger forwarded = new java.util.concurrent.atomic.AtomicInteger();
        final AtomicReference<String> runDirectory = new AtomicReference<>();
        final ScriptedToolCallBridge signal = ScriptedToolCallBridge.succeedingWith("unused");
        final ToolCallBridge holding = new ToolCallBridge() {
            @Override
            public CompletableFuture<ToolDispatchResult> callAsync(String toolName, Map<String, Object> input) {
                forwarded.incrementAndGet();
                return held;
            }

            @Override
            public Optional<String> limitExceeded() {
                return Optional.empty();
            }
        };
        try {
            final Outcome result = run(holding, (runtime, listener) -> {
                runDirectory.set(runtime.runDirectory);
                listener.onStdoutLine(runtime.line("CALL", 1, "{\"tool\":\"Echo\",\"args\":{}}"));
                signal.cancel();
                // A line still in flight when the cancellation lands (Q-C4's second symptom).
                listener.onStdoutLine(runtime.line("CALL", 2, "{\"tool\":\"Echo\",\"args\":{}}"));
                return runtime.awaitStop();
            }, CodeExecutionLimits.defaults(), signal);
            assertThat(result.outcome.getStatus()).isEqualTo(CodeExecutionStatus.CANCELLED);
            assertThat(forwarded.get()).isEqualTo(1);
            assertThat(Path.of(runDirectory.get())).doesNotExist();
            held.complete(ScriptedToolCallBridge.success("too late"));
            Thread.sleep(200);
            assertThat(Path.of(runDirectory.get())).as("no reply recreated the run directory").doesNotExist();
        } finally {
            signal.close();
        }
    }

    private void assertViolation(Outcome result) {
        assertThat(result.outcome.getStatus()).isEqualTo(CodeExecutionStatus.PROTOCOL_VIOLATION);
        assertThat(result.outcome.getStatus().isRetryable()).isFalse();
        assertThat(result.outcome.getStdout()).isEmpty();
        assertThat(bridge.unattributed).isTrue();
    }

    private static ShellCommandResult ok() {
        return new ShellCommandResult(0, "", "", Duration.ZERO);
    }

    private static String b64(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private Outcome run(Scenario scenario) {
        return run(bridge, scenario, CodeExecutionLimits.defaults());
    }

    private Outcome run(Scenario scenario, CodeExecutionLimits limits) {
        return run(bridge, scenario, limits);
    }

    private Outcome run(ToolCallBridge callBridge, Scenario scenario, CodeExecutionLimits limits) {
        return run(callBridge, scenario, limits, scripted);
    }

    private Outcome run(ToolCallBridge callBridge, Scenario scenario, CodeExecutionLimits limits,
            ScriptedToolCallBridge signalOwner) {
        return run(callBridge, scenario, limits, signalOwner, realTempDir());
    }

    private Outcome run(ToolCallBridge callBridge, Scenario scenario, CodeExecutionLimits limits,
            ScriptedToolCallBridge signalOwner, Path workingDirectory) {
        final ScriptedShell shell = new ScriptedShell(scenario);
        final SandboxedLocalEnvironment environment = new SandboxedLocalEnvironment(workingDirectory, shell);
        final CodeExecutionRequest request = CodeExecutionRequest.builder().toolUseId("toolu_1").code("pass")
                .tools(List.of(ToolDefinition.of("Echo", "Echoes.", Map.of("type", "object")))).limits(limits)
                .wallClock(Duration.ofSeconds(20)).cancellation(signalOwner.signal()).build();
        final CodeExecutionOutcome outcome = engine.execute(request, callBridge,
                ToolContext.builder().put(ToolContextKeys.EXECUTION_ENVIRONMENT, environment).build());
        return new Outcome(outcome, environment, shell.command, shell.options);
    }

    private Path realTempDir() {
        try {
            return tempDir.toRealPath();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class Outcome {
        final CodeExecutionOutcome outcome;
        final SandboxedLocalEnvironment environment;
        final String command;
        final ExecutionOptions options;

        Outcome(CodeExecutionOutcome outcome, SandboxedLocalEnvironment environment, String command,
                ExecutionOptions options) {
            this.outcome = outcome;
            this.environment = environment;
            this.command = command;
            this.options = options;
        }
    }

    @FunctionalInterface
    private interface Scenario {
        ShellCommandResult play(Runtime runtime, ShellLineListener listener) throws ShellExecutionException;
    }

    /** What a process that holds the run's key can do: write lines, read replies, pre-empt them. */
    private static final class Runtime {
        private final PtcProtocol protocol;
        private final String runDirectory;
        private final ExecutionOptions options;
        private final at.aimon.core.environment.RuntimeFileSystem files;

        Runtime(RunDirectory placed, ExecutionOptions options, at.aimon.core.environment.RuntimeFileSystem files) {
            this.protocol = new PtcProtocol(placed.getContent("key"));
            this.runDirectory = new String(placed.getContent("reply_dir"), StandardCharsets.UTF_8);
            this.options = options;
            this.files = files;
        }

        String line(String type, long seq, String payload) {
            return protocol.line(type, seq, payload);
        }

        String awaitReply(long seq) {
            final Path reply = Path.of(runDirectory, "r", String.valueOf(seq));
            final long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            try {
                while (!Files.exists(reply)) {
                    if (System.nanoTime() > deadline) {
                        throw new AssertionError("no reply " + seq);
                    }
                    Thread.sleep(5);
                }
                final byte[] file = Files.readAllBytes(reply);
                final int newline = indexOf(file, (byte) '\n');
                final byte[] payload = java.util.Arrays.copyOfRange(file, newline + 1, file.length);
                assertThat(file).startsWith(protocol.reply(seq, payload));
                Files.delete(reply);
                return new String(payload, StandardCharsets.UTF_8);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        void writeReplyFile(long seq, String text) {
            try {
                files.writeNew(runDirectory + "/r/" + seq, text.getBytes(StandardCharsets.UTF_8));
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        }

        /** Waits, as a real shell does, until the engine stops the command, then reports the cancellation. */
        ShellCommandResult awaitStop() throws ShellCancelledException {
            final long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (!options.getCancellation().isCancelled()) {
                if (System.nanoTime() > deadline) {
                    throw new AssertionError("the engine never stopped the command");
                }
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            throw new ShellCancelledException("cancelled");
        }

        private static int indexOf(byte[] bytes, byte b) {
            for (int i = 0; i < bytes.length; i++) {
                if (bytes[i] == b) {
                    return i;
                }
            }
            return -1;
        }
    }

    /** A streaming, isolated, run-directory shell that plays a scenario instead of running the command. */
    private final class ScriptedShell implements VirtualShell {
        private final Scenario scenario;
        private volatile String command;
        private volatile ExecutionOptions options;

        ScriptedShell(Scenario scenario) {
            this.scenario = scenario;
        }

        @Override
        public ShellCommandResult execute(ShellCommand cmd) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ShellCommandResult execute(ShellCommand cmd, ExecutionOptions opts) {
            // The probe: answer "available" without running anything.
            return ok();
        }

        @Override
        public ShellCommandResult execute(ShellCommand cmd, ExecutionOptions opts, ShellLineListener listener)
                throws ShellExecutionException {
            this.command = cmd.asString();
            this.options = opts;
            final RunDirectory placed = opts.getRunDirectory().orElseThrow();
            return scenario.play(
                    new Runtime(placed, opts, new at.aimon.core.environment.impl.LocalRuntimeFileSystem(realTempDir())),
                    listener);
        }

        @Override
        public String getWorkingDirectory() {
            return realTempDir().toString();
        }

        @Override
        public boolean supports(ShellFeature feature) {
            return feature != ShellFeature.INTERACTIVE;
        }

        @Override
        public void close() {
        }
    }

    /** Delegates to the scripted bridge and records whether the engine marked its calls unattributed. */
    private static final class RecordingBridge implements ToolCallBridge {
        private final ToolCallBridge delegate;
        private final List<String> names = new CopyOnWriteArrayList<>();
        private volatile boolean unattributed;
        private final AtomicBoolean marked = new AtomicBoolean();

        RecordingBridge(ToolCallBridge delegate) {
            this.delegate = delegate;
        }

        @Override
        public CompletableFuture<ToolDispatchResult> callAsync(String toolName, Map<String, Object> input) {
            names.add(toolName);
            return delegate.callAsync(toolName, input);
        }

        @Override
        public Optional<String> limitExceeded() {
            return delegate.limitExceeded();
        }

        @Override
        public void markUnattributed() {
            marked.set(true);
            unattributed = true;
        }
    }
}
