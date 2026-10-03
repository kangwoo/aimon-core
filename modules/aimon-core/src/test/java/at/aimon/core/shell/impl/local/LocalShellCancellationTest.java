package at.aimon.core.shell.impl.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

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

    private static boolean alive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }
}
