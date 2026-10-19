package at.aimon.core.shell.impl.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.shell.ExecutionOptions;
import at.aimon.core.shell.RunDirectory;
import at.aimon.core.shell.ShellCancellationSource;
import at.aimon.core.shell.ShellCommandResult;
import at.aimon.core.shell.ShellFeature;
import at.aimon.core.shell.exception.RunDirectoryIntegrityException;
import at.aimon.core.shell.exception.ShellCancelledException;
import at.aimon.core.shell.exception.ShellExecutionException;
import at.aimon.core.shell.exception.ShellTimeoutException;

/**
 * {@link LocalShell}'s {@link ShellFeature#RUN_DIRECTORY}: a fresh private directory per command, holding exactly the
 * bytes it was given, named in the variable, and gone when the command ends (EE-109).
 */
@DisabledOnOs(OS.WINDOWS)
@DisplayName("LocalShell run directory (EE-109)")
class LocalShellRunDirectoryTest {

    @TempDir
    Path workingDirectory;

    private final LocalShell shell = new LocalShell(Runtime.getRuntime().availableProcessors(), null, "bash");

    private static RunDirectory run() {
        return RunDirectory.builder().variable("AIMON_HOOK_DIR")
                .file("guard.sh", "echo guarded\n".getBytes(StandardCharsets.UTF_8))
                .file("lib/common.sh", "COMMON=1\n".getBytes(StandardCharsets.UTF_8)).build();
    }

    private ExecutionOptions.Builder options() {
        return ExecutionOptions.builder().timeout(Duration.ofSeconds(10)).hook(true).isolated(true)
                .workingDirectory(workingDirectory.toString()).runDirectory(run());
    }

    @Test
    @DisplayName("declares the feature")
    void declaresTheFeature() {
        assertThat(shell.supports(ShellFeature.RUN_DIRECTORY)).isTrue();
    }

    @Test
    @DisplayName("the variable names a private directory outside the working directory that holds the bytes")
    void placesTheFiles() throws Exception {
        final AtomicReference<String> modes = new AtomicReference<>();
        shell.onRunDirectoryWritten(dir -> {
            try {
                modes.set(PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)) + " "
                        + PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve("lib"))) + " "
                        + PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve("guard.sh"))));
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });

        final ShellCommandResult result = shell
                .execute(() -> "printf '%s\\n' \"$AIMON_HOOK_DIR\"; bash \"$AIMON_HOOK_DIR/guard.sh\";"
                        + " . \"$AIMON_HOOK_DIR/lib/common.sh\"; echo \"common=$COMMON\"", options().build());

        final String[] lines = result.stdout().split("\n");
        final Path dir = Path.of(lines[0]);
        assertThat(dir.getFileName().toString()).startsWith("aimon-run-");
        assertThat(dir.startsWith(workingDirectory) || dir.startsWith(workingDirectory.toRealPath())).isFalse();
        assertThat(lines).contains("guarded", "common=1");
        assertThat(modes.get()).isEqualTo("rwx------ rwx------ rw-------");
        assertThat(dir).as("removed when the command ended").doesNotExist();
    }

    @Test
    @DisplayName("the variable wins over the same name among the caller's variables")
    void variableWinsOverTheEnvironment() throws Exception {
        final ShellCommandResult result = shell.execute(() -> "printf '%s' \"$AIMON_HOOK_DIR\"",
                options().environment(Map.of("AIMON_HOOK_DIR", "/planted")).build());

        assertThat(result.stdout()).contains("aimon-run-").doesNotContain("/planted");
    }

    @Test
    @DisplayName("each command gets a directory of its own")
    void freshEveryTime() throws Exception {
        final String first = shell.execute(() -> "printf '%s' \"$AIMON_HOOK_DIR\"", options().build()).stdout();
        final String second = shell.execute(() -> "printf '%s' \"$AIMON_HOOK_DIR\"", options().build()).stdout();

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    @DisplayName("a command that writes beside its files loses them at the next run")
    void writesDoNotPersist() throws Exception {
        shell.execute(() -> "echo left > \"$AIMON_HOOK_DIR/state\"", options().build());

        final ShellCommandResult result = shell.execute(() -> "cat \"$AIMON_HOOK_DIR/state\" 2>/dev/null || echo none",
                options().build());

        assertThat(result.stdout().strip()).isEqualTo("none");
    }

    @Test
    @DisplayName("the directory is removed after a non-zero exit")
    void removedAfterFailure() throws Exception {
        final ShellCommandResult result = shell.execute(() -> "printf '%s' \"$AIMON_HOOK_DIR\"; exit 3",
                options().build());

        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(Path.of(result.stdout())).doesNotExist();
    }

    @Test
    @DisplayName("the directory is removed after a timeout")
    void removedAfterTimeout() throws Exception {
        final AtomicReference<Path> placed = new AtomicReference<>();
        shell.onRunDirectoryWritten(placed::set);

        assertThatThrownBy(() -> shell.execute(() -> "sleep 30", options().timeout(Duration.ofMillis(300)).build()))
                .isInstanceOf(ShellTimeoutException.class);

        assertThat(placed.get()).isNotNull().doesNotExist();
    }

    @Test
    @DisplayName("the directory is removed after a cancel")
    void removedAfterCancel() throws Exception {
        final AtomicReference<Path> placed = new AtomicReference<>();
        shell.onRunDirectoryWritten(placed::set);
        final ShellCancellationSource source = ShellCancellationSource.create();
        CompletableFuture.delayedExecutor(300, TimeUnit.MILLISECONDS).execute(source::cancel);

        assertThatThrownBy(() -> shell.execute(() -> "sleep 30", options().cancellation(source.token()).build()))
                .isInstanceOf(ShellCancelledException.class);

        assertThat(placed.get()).isNotNull().doesNotExist();
    }

    @Test
    @DisplayName("files the filesystem would keep as one are refused, and the command does not run")
    void caseMergedNamesAreRefused() throws Exception {
        final Path probe = Files.createTempFile("aimon-case-", ".probe");
        try {
            final Path upper = probe.resolveSibling(probe.getFileName().toString().toUpperCase());
            assumeTrue(Files.exists(upper), "the temp directory is case sensitive");
        } finally {
            Files.deleteIfExists(probe);
        }
        final Path marker = workingDirectory.resolve("ran");
        final RunDirectory merged = RunDirectory.builder().variable("D").file("Run.sh", new byte[]{1})
                .file("run.sh", new byte[]{2}).build();

        assertThatThrownBy(() -> shell.execute(() -> "touch " + marker,
                ExecutionOptions.builder().hook(true).runDirectory(merged).build()))
                .isInstanceOf(ShellExecutionException.class).hasMessageContaining("letter case");
        assertThat(marker).doesNotExist();
    }

    @Test
    @DisplayName("files that differ from their bytes when read back are refused as tampered, and the command does not run")
    void integrityFailureIsReported() throws Exception {
        final Path marker = workingDirectory.resolve("ran");
        final AtomicReference<Path> placed = new AtomicReference<>();
        shell.onRunDirectoryWritten(dir -> {
            placed.set(dir);
            try {
                Files.writeString(dir.resolve("guard.sh"), "exit 0\n");
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });

        assertThatThrownBy(() -> shell.execute(() -> "touch " + marker, options().build()))
                .isInstanceOf(RunDirectoryIntegrityException.class).hasMessageContaining("guard.sh")
                .hasMessageNotContaining("exit 0");
        assertThat(marker).doesNotExist();
        assertThat(placed.get()).doesNotExist();
    }

    @Test
    @DisplayName("a command without a run directory gets no variable")
    void noRunDirectoryNoVariable() throws Exception {
        final ShellCommandResult result = shell.execute(() -> "printf '%s' \"${AIMON_HOOK_DIR-unset}\"",
                ExecutionOptions.builder().hook(true).build());

        assertThat(result.stdout()).isEqualTo("unset");
    }
}
