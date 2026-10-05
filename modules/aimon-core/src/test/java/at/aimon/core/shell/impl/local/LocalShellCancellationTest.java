package at.aimon.core.shell.impl.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.shell.ExecutionOptions;
import at.aimon.core.shell.ShellCancellationSource;
import at.aimon.core.shell.ShellCommandResult;
import at.aimon.core.shell.ShellFeature;
import at.aimon.core.shell.exception.ShellCancelledException;
import at.aimon.core.shell.exception.ShellTimeoutException;

@DisplayName("LocalShell — stopping a running command through its cancellation signal")
@DisabledOnOs(OS.WINDOWS)
class LocalShellCancellationTest {

    private LocalShell shell;

    @BeforeEach
    void setUp() {
        shell = new LocalShell();
    }

    @AfterEach
    void tearDown() {
        shell.close();
    }

    @Test
    @DisplayName("declares CANCELLATION")
    void declaresTheFeature() {
        assertThat(shell.supports(ShellFeature.CANCELLATION)).isTrue();
    }

    @Test
    @DisplayName("a cancelled command throws ShellCancelledException, and the shell and the child it spawned are dead")
    void cancelKillsTheProcessTree(@TempDir Path dir) throws Exception {
        final ShellCancellationSource source = ShellCancellationSource.create();
        final Path parentPid = dir.resolve("parent.pid");
        final Path childPid = dir.resolve("child.pid");
        final String command = "echo before-cancel; echo $$ > " + parentPid + "; sleep 120 & echo $! > " + childPid
                + "; wait";

        final CompletableFuture<Throwable> outcome = CompletableFuture
                .supplyAsync(() -> catchThrowable(() -> shell.execute(() -> command, options(source))));
        final long parent = awaitPid(parentPid);
        final long child = awaitPid(childPid);
        assertThat(alive(parent)).isTrue();
        assertThat(alive(child)).isTrue();

        assertThat(source.cancel()).isTrue();

        final Throwable thrown = outcome.get(10, TimeUnit.SECONDS);
        assertThat(thrown).isInstanceOf(ShellCancelledException.class);
        // What the command printed before it was stopped travels on the exception, as it does for a timeout.
        assertThat(((ShellCancelledException) thrown).stdout()).contains("before-cancel");
        awaitDead(parent);
        awaitDead(child);
    }

    @Test
    @DisplayName("a child that ignores SIGTERM is killed even when its parent honours SIGTERM and exits first")
    void cancelKillsAChildThatIgnoresTerm(@TempDir Path dir) throws Exception {
        final ShellCancellationSource source = ShellCancellationSource.create();
        final Path childPid = dir.resolve("child.pid");
        final Path grandchildPid = dir.resolve("grandchild.pid");
        // The inner shell ignores TERM, and so does its sleep (an ignored signal stays ignored across exec). The
        // outer shell honours TERM, so it exits inside the grace period and leaves the TERM-proof pair behind unless
        // they are killed on their own account. The child pid is written last, so both are running when it appears.
        final String command = "bash -c 'trap \"\" TERM; sleep 999 & echo $! > " + grandchildPid + "; echo $$ > "
                + childPid + "; wait' & wait";

        final CompletableFuture<Throwable> outcome = CompletableFuture
                .supplyAsync(() -> catchThrowable(() -> shell.execute(() -> command, options(source))));
        final long child = awaitPid(childPid);
        final long grandchild = awaitPid(grandchildPid);
        assertThat(alive(child)).isTrue();
        assertThat(alive(grandchild)).isTrue();

        assertThat(source.cancel()).isTrue();

        assertThat(outcome.get(10, TimeUnit.SECONDS)).isInstanceOf(ShellCancelledException.class);
        awaitDead(child);
        awaitDead(grandchild);
    }

    @Test
    @DisplayName("a process the command forks in answer to the stop request is killed with it (EE-55)")
    void cancelKillsAProcessBornAfterTheStopRequest(@TempDir Path dir) throws Exception {
        final ShellCancellationSource source = ShellCancellationSource.create();
        final Path parentPid = dir.resolve("parent.pid");
        final Path latePid = dir.resolve("late.pid");
        // The shell answers TERM by forking: the new sleep did not exist when the tree was first enumerated, so it
        // gets neither the polite request nor a place in that snapshot. The shell then stays in `wait`, alive when
        // the grace period runs out, which is what lets the second enumeration find its late child. The loop keeps a
        // foreground child around so the trap runs as soon as that child is terminated.
        final String command = "trap 'sleep 300 & echo $! > " + latePid + "; wait' TERM; echo $$ > " + parentPid
                + "; while :; do sleep 1; done";

        final CompletableFuture<Throwable> outcome = CompletableFuture
                .supplyAsync(() -> catchThrowable(() -> shell.execute(() -> command, options(source))));
        final long parent = awaitPid(parentPid);
        try {
            assertThat(source.cancel()).isTrue();

            assertThat(outcome.get(10, TimeUnit.SECONDS)).isInstanceOf(ShellCancelledException.class);
            awaitDead(parent);
            awaitDead(lateProcess(latePid));
        } finally {
            killLeftover(latePid);
        }
    }

    @Test
    @DisplayName("the late process is found through a surviving child even when the command's own shell has exited (EE-55)")
    void cancelKillsAProcessBornUnderASurvivingChild(@TempDir Path dir) throws Exception {
        final ShellCancellationSource source = ShellCancellationSource.create();
        final Path childPid = dir.resolve("child.pid");
        final Path latePid = dir.resolve("late.pid");
        // The outer shell honours TERM and is gone inside the grace period, so nothing can be enumerated from it any
        // more. The inner shell forks on TERM and stays: the late sleep is reachable only from that inner handle.
        final String command = "bash -c 'trap \"sleep 300 & echo \\$! > " + latePid + "; wait\" TERM; echo $$ > "
                + childPid + "; while :; do sleep 1; done' & wait";

        final CompletableFuture<Throwable> outcome = CompletableFuture
                .supplyAsync(() -> catchThrowable(() -> shell.execute(() -> command, options(source))));
        final long child = awaitPid(childPid);
        try {
            assertThat(source.cancel()).isTrue();

            assertThat(outcome.get(10, TimeUnit.SECONDS)).isInstanceOf(ShellCancelledException.class);
            awaitDead(child);
            awaitDead(lateProcess(latePid));
        } finally {
            killLeftover(latePid);
        }
    }

    @Test
    @DisplayName("a timeout kills the late process the same way — it is the same code path (EE-55)")
    void timeoutKillsAProcessBornAfterTheStopRequest(@TempDir Path dir) throws Exception {
        final Path parentPid = dir.resolve("parent.pid");
        final Path latePid = dir.resolve("late.pid");
        final String command = "trap 'sleep 300 & echo $! > " + latePid + "; wait' TERM; echo $$ > " + parentPid
                + "; while :; do sleep 1; done";

        try {
            assertThatThrownBy(() -> shell.execute(() -> command,
                    ExecutionOptions.builder().timeout(Duration.ofSeconds(2)).build()))
                    .isInstanceOf(ShellTimeoutException.class);
            awaitDead(awaitPid(parentPid));
            awaitDead(lateProcess(latePid));
        } finally {
            killLeftover(latePid);
        }
    }

    @Test
    @DisplayName("a signal tripped before the call means the command never starts")
    void cancelledBeforeStartDoesNotRunTheCommand(@TempDir Path dir) {
        final ShellCancellationSource source = ShellCancellationSource.create();
        source.cancel();
        final Path marker = dir.resolve("ran");

        assertThatThrownBy(() -> shell.execute(() -> "touch " + marker, options(source)))
                .isInstanceOf(ShellCancelledException.class);

        // "Start it and kill it" would leave the file behind: a command with side effects must not run at all.
        assertThat(marker).doesNotExist();
    }

    @Test
    @DisplayName("a signal tripped after the command ended changes nothing")
    void cancelAfterCompletionIsHarmless() throws Exception {
        final ShellCancellationSource source = ShellCancellationSource.create();

        final ShellCommandResult result = shell.execute(() -> "echo done", options(source));
        source.cancel();

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.stdout()).contains("done");
    }

    @Test
    @DisplayName("a command without a signal runs as before")
    void noSignalNoCancellation() throws Exception {
        final ShellCommandResult result = shell.execute(() -> "echo plain",
                ExecutionOptions.builder().timeout(Duration.ofSeconds(30)).build());

        assertThat(result.stdout()).contains("plain");
    }

    @Test
    @DisplayName("a cancelled command leaves no capture file behind")
    void cancelledCommandCleansUpItsCaptureFiles(@TempDir Path dir) throws Exception {
        // Found by content, not by counting: other tests share the temp directory, and only this command prints this.
        final String marker = "marker-" + UUID.randomUUID();
        final ShellCancellationSource source = ShellCancellationSource.create();
        final Path pidFile = dir.resolve("pid");

        final CompletableFuture<Throwable> outcome = CompletableFuture.supplyAsync(() -> catchThrowable(() -> shell
                .execute(() -> "echo " + marker + "; echo $$ > " + pidFile + "; exec sleep 120", options(source))));
        awaitPid(pidFile);
        source.cancel();

        assertThat(outcome.get(10, TimeUnit.SECONDS)).isInstanceOf(ShellCancelledException.class);
        assertThat(captureFilesContaining(marker)).isEmpty();
    }

    private static ExecutionOptions options(ShellCancellationSource source) {
        return ExecutionOptions.builder().timeout(Duration.ofMinutes(5)).cancellation(source.token()).build();
    }

    private static List<Path> captureFilesContaining(String marker) throws IOException {
        final Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
        try (Stream<Path> files = Files.list(tmp)) {
            return files.filter(file -> file.getFileName().toString().startsWith("aimon-shell-"))
                    .filter(file -> contains(file, marker)).toList();
        }
    }

    private static boolean contains(Path file, String marker) {
        try {
            return Files.readString(file).contains(marker);
        } catch (IOException | RuntimeException e) {
            // Deleted between the listing and the read, or another test's binary capture: not ours either way.
            return false;
        }
    }

    private static long awaitPid(Path pidFile) throws Exception {
        final long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.exists(pidFile)) {
                final String text = Files.readString(pidFile).trim();
                if (!text.isEmpty()) {
                    return Long.parseLong(text);
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("the command never wrote " + pidFile);
    }

    private static void awaitDead(long pid) throws Exception {
        final long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (alive(pid)) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("process " + pid + " is still alive");
            }
            Thread.sleep(20);
        }
    }

    /**
     * The pid of the process the TERM trap forked. Read only after the shell that writes the file is dead, so the file
     * is final. On a machine so loaded that the shell never got to run its trap inside the grace period there is no
     * late process and nothing to assert: the run is reported as skipped, not as green.
     */
    private static long lateProcess(Path pidFile) throws IOException {
        final String text = Files.exists(pidFile) ? Files.readString(pidFile).trim() : "";
        assumeTrue(!text.isEmpty(), "the shell was killed before its TERM trap forked anything");
        return Long.parseLong(text);
    }

    /** Keeps a failing run from leaving a five-minute sleep behind: the assertion already recorded the failure. */
    private static void killLeftover(Path pidFile) {
        try {
            if (Files.exists(pidFile)) {
                ProcessHandle.of(Long.parseLong(Files.readString(pidFile).trim()))
                        .ifPresent(ProcessHandle::destroyForcibly);
            }
        } catch (IOException | RuntimeException ignored) {
            // Nothing was written, or it is already gone
        }
    }

    private static boolean alive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }
}
