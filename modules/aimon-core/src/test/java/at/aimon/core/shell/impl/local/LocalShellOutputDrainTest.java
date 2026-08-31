package at.aimon.core.shell.impl.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.shell.ExecutionOptions;
import at.aimon.core.shell.ShellCommand;
import at.aimon.core.shell.ShellCommandResult;
import at.aimon.core.shell.exception.ShellExecutionException;
import at.aimon.core.shell.exception.ShellTimeoutException;

/**
 * Tests for the file-backed output capture of {@link LocalShell} (issue #13, superseding the drain-based workaround
 * from
 * issue #10).
 *
 * <p>
 * The child's stdout/stderr are redirected to temp files, so a command that leaves a backgrounded child holding the
 * write-end (e.g. {@code server & echo done}) no longer delays EOF or leaks a reader thread — the output produced
 * before
 * the parent exits is captured in full. Truncation now means only that a stream exceeded the in-memory capture limit.
 *
 * <p>
 * Also covers the termination paths that decide what happens to that capture — a timeout or an interrupt kills the
 * process tree and still surfaces whatever the process had flushed before it died.
 *
 * <p>
 * Relies on Unix job-control / shell semantics, so it is disabled on Windows.
 */
@DisabledOnOs(OS.WINDOWS)
@DisplayName("LocalShell output capture and termination tests")
class LocalShellOutputDrainTest {

    private static final String TEMP_FILE_PREFIX = "aimon-shell-";
    private static final long POLL_INTERVAL_MILLIS = 25L;

    private LocalShell shell;

    @BeforeEach
    void setUp() {
        shell = new LocalShell();
    }

    @AfterEach
    void tearDown() {
        if (shell != null) {
            shell.close();
        }
    }

    @Test
    @DisplayName("a backgrounded child holding the pipe no longer truncates output; it is captured in full")
    void backgroundChildOutputIsFullyCaptured() throws Exception {
        // Pre-#13 this drained to a timeout and (pre-#10) returned ""; with file capture the parent's "done" is read in
        // full and nothing is flagged truncated, and no reader thread is left blocked on the surviving sleep.
        final ShellCommandResult result = shell.execute(() -> "sleep 2 & echo done");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.stdout()).contains("done");
        assertThat(result.outputTruncated()).isFalse();
    }

    @Test
    @DisplayName("a backgrounded child does not truncate stderr either")
    void backgroundChildStderrIsFullyCaptured() throws Exception {
        final ShellCommandResult result = shell.execute(() -> "sleep 2 & echo oops 1>&2");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.stderr()).contains("oops");
        assertThat(result.outputTruncated()).isFalse();
    }

    @Test
    @DisplayName("a normal command is captured and not marked truncated")
    void normalCommandIsNotTruncated() throws Exception {
        final ShellCommandResult result = shell.execute(() -> "echo hello");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.stdout()).contains("hello");
        assertThat(result.outputTruncated()).isFalse();
    }

    @Test
    @DisplayName("stdout and stderr are captured on separate channels by default")
    void stdoutAndStderrCapturedSeparately() throws Exception {
        final ShellCommandResult result = shell.execute(() -> "echo out; echo err 1>&2");

        assertThat(result.stdout()).contains("out").doesNotContain("err");
        assertThat(result.stderr()).contains("err").doesNotContain("out");
        assertThat(result.outputTruncated()).isFalse();
    }

    @Test
    @DisplayName("redirectErrorStream merges stderr into stdout, leaving stderr empty")
    void redirectErrorStreamMergesStderrIntoStdout() throws Exception {
        final ExecutionOptions options = ExecutionOptions.builder().redirectErrorStream(true).build();

        final ShellCommandResult result = shell.execute(() -> "echo out; echo err 1>&2", options);

        assertThat(result.stdout()).contains("out").contains("err");
        assertThat(result.stderr()).isEmpty();
    }

    @Test
    @DisplayName("output exceeding maxCaptureBytes is capped and flagged truncated")
    void outputExceedingCaptureLimitIsTruncated() throws Exception {
        final ExecutionOptions options = ExecutionOptions.builder().maxCaptureBytes(5).build();

        final ShellCommandResult result = shell.execute(() -> "printf '0123456789'", options);

        assertThat(result.stdout()).isEqualTo("01234");
        assertThat(result.outputTruncated()).isTrue();
    }

    @Test
    @DisplayName("output within maxCaptureBytes is captured fully and not flagged truncated")
    void outputWithinCaptureLimitIsNotTruncated() throws Exception {
        final ExecutionOptions options = ExecutionOptions.builder().maxCaptureBytes(100).build();

        final ShellCommandResult result = shell.execute(() -> "printf '0123456789'", options);

        assertThat(result.stdout()).isEqualTo("0123456789");
        assertThat(result.outputTruncated()).isFalse();
    }

    @Test
    @DisplayName("a command that reads stdin terminates when no stdin payload was supplied")
    void commandReadingStdinSeesEofWhenNoStdinSupplied() throws Exception {
        // Without an explicit stdin redirect the child inherits a pipe nothing ever writes to or closes, so `cat`
        // would block until the timeout below fires. An empty redirect gives it immediate EOF instead.
        final ExecutionOptions options = ExecutionOptions.builder().timeout(Duration.ofSeconds(5)).build();

        final ShellCommandResult result = shell.execute(() -> "cat", options);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.stdout()).isEmpty();
    }

    @Test
    @DisplayName("an explicit stdin payload is delivered to the command and followed by EOF")
    void explicitStdinPayloadIsDelivered() throws Exception {
        final ExecutionOptions options = ExecutionOptions.builder().stdin("hello stdin\n")
                .timeout(Duration.ofSeconds(5)).build();

        final ShellCommandResult result = shell.execute(() -> "cat", options);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.stdout()).contains("hello stdin");
    }

    @Test
    @DisplayName("a timed-out command surfaces the partial output flushed before the kill")
    void timeoutSurfacesPartialOutputOnException() {
        final ShellCommand command = () -> "echo out; sleep 2";
        final ExecutionOptions options = ExecutionOptions.builder().timeout(Duration.ofMillis(300)).build();

        assertThatThrownBy(() -> shell.execute(command, options)).isInstanceOfSatisfying(ShellTimeoutException.class,
                ex -> {
                    assertThat(ex.stdout()).contains("out");
                    assertThat(ex.outputTruncated()).isFalse();
                });
    }

    @Test
    @DisplayName("a timed-out command kills its descendants, not just the shell that spawned them")
    void timeoutKillsDescendantProcesses(@TempDir Path tempDir) throws Exception {
        // `sleep 30 &` is re-parented to init the instant its shell dies, so a parent-only kill leaves it running
        // long past the timeout that was supposed to stop it. `wait` keeps the shell alive until the timeout fires.
        final Path pidFile = tempDir.resolve("child.pid");
        final ShellCommand command = () -> "sleep 30 & echo $! > '" + pidFile + "'; wait";
        final ExecutionOptions options = ExecutionOptions.builder().timeout(Duration.ofMillis(500)).build();

        assertThatThrownBy(() -> shell.execute(command, options)).isInstanceOf(ShellTimeoutException.class);

        final long childPid = Long.parseLong(Files.readString(pidFile).trim());
        // The kill is asynchronous, so poll rather than asserting on the instant the exception surfaces.
        assertThat(await(() -> !isAlive(childPid), Duration.ofSeconds(10)))
                .as("backgrounded child (pid %d) should have been killed along with its parent", childPid).isTrue();
    }

    @Test
    @DisplayName("an interrupted command surfaces the partial output flushed before the kill")
    void interruptSurfacesPartialOutputOnException(@TempDir Path tempDir) throws Exception {
        // The marker makes the flush deterministic: once it exists, the shell has finished `echo out` and the
        // capture file already holds that line, so the interrupt below cannot land too early.
        final Path marker = tempDir.resolve("flushed");
        final ShellCommand command = () -> "echo out; touch '" + marker + "'; sleep 30";
        final ExecutionOptions options = ExecutionOptions.builder().timeout(Duration.ofSeconds(60)).build();

        final AtomicReference<Throwable> thrown = new AtomicReference<>();
        final AtomicBoolean interruptFlagPreserved = new AtomicBoolean();
        final Thread worker = new Thread(() -> {
            try {
                shell.execute(command, options);
            } catch (Throwable t) {
                thrown.set(t);
                interruptFlagPreserved.set(Thread.currentThread().isInterrupted());
            }
        }, "interrupt-partial-output-worker");

        worker.start();
        assertThat(await(() -> Files.exists(marker), Duration.ofSeconds(10))).as("command reached the marker").isTrue();
        worker.interrupt();
        worker.join(Duration.ofSeconds(10).toMillis());

        assertThat(thrown.get()).isInstanceOfSatisfying(ShellExecutionException.class, ex -> {
            assertThat(ex.stdout()).contains("out");
            assertThat(ex.outputTruncated()).isFalse();
        });
        assertThat(interruptFlagPreserved).as("the interrupt flag is restored before the exception propagates")
                .isTrue();
    }

    @Test
    @DisplayName("temp capture files are cleaned up on success, failure, and timeout paths")
    void tempCaptureFilesAreCleanedUp(@TempDir Path isolatedTmp) throws Exception {
        // Point java.io.tmpdir (which Files.createTempFile reads per call) at a per-test dir so this assertion is not
        // polluted by other test forks that share the OS temp dir. Tests within a fork run serially, so mutating the
        // property here is safe.
        final String previousTmpDir = System.getProperty("java.io.tmpdir");
        System.setProperty("java.io.tmpdir", isolatedTmp.toString());
        try (LocalShell isolatedShell = new LocalShell()) {
            isolatedShell.execute(() -> "echo hi");
            final ShellCommandResult failure = isolatedShell.execute(() -> "exit 3");
            assertThat(failure.exitCode()).isEqualTo(3);
            assertThatThrownBy(() -> isolatedShell.execute(() -> "sleep 2",
                    ExecutionOptions.builder().timeout(Duration.ofMillis(200)).build()))
                    .isInstanceOf(ShellTimeoutException.class);
        } finally {
            System.setProperty("java.io.tmpdir", previousTmpDir);
        }

        assertThat(listCaptureFiles(isolatedTmp)).as("no leftover aimon-shell-* temp files after execution").isEmpty();
    }

    private static boolean isAlive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    private static boolean await(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        final long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(POLL_INTERVAL_MILLIS);
        }
        return condition.getAsBoolean();
    }

    private static Set<String> listCaptureFiles(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString()).filter(name -> name.startsWith(TEMP_FILE_PREFIX))
                    .collect(Collectors.toSet());
        }
    }
}
