package at.aimon.core.environment.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.shell.ExecutionOptions;
import at.aimon.core.shell.RunDirectory;
import at.aimon.core.shell.ShellCommand;
import at.aimon.core.shell.ShellCommandResult;
import at.aimon.core.shell.ShellFeature;
import at.aimon.core.shell.ShellLineListener;
import at.aimon.core.shell.VirtualShell;
import at.aimon.core.shell.exception.ShellExecutionException;

class WorkingDirectoryShellTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Applying the branch working directory keeps the hook and background flags")
    void execute_appliesWorkingDirectoryAndKeepsFlags() throws Exception {
        RecordingShell delegate = new RecordingShell();
        Path branch = tempDir.resolve("branch");
        WorkingDirectoryShell shell = new WorkingDirectoryShell(delegate, branch);

        shell.execute(() -> "true", ExecutionOptions.builder().hook(true).background(true).build());

        assertThat(delegate.lastOptions.getWorkingDirectory()).isEqualTo(branch.toString());
        assertThat(delegate.lastOptions.isHook()).isTrue();
        assertThat(delegate.lastOptions.isBackground()).isTrue();
        assertThat(branch).isDirectory();
    }

    @Test
    @DisplayName("Applying the branch working directory keeps the run directory (EE-109)")
    void execute_appliesWorkingDirectoryAndKeepsTheRunDirectory() throws Exception {
        RecordingShell delegate = new RecordingShell();
        WorkingDirectoryShell shell = new WorkingDirectoryShell(delegate, tempDir.resolve("branch"));
        RunDirectory run = RunDirectory.builder().variable("AIMON_SKILL_DIR").file("a", new byte[0]).build();

        shell.execute(() -> "true", ExecutionOptions.builder().hook(true).runDirectory(run).build());

        assertThat(delegate.lastOptions.getRunDirectory()).containsSame(run);
    }

    @Test
    @DisplayName("A command that sets its own working directory is passed through unchanged")
    void execute_ownWorkingDirectory_passedThrough() throws Exception {
        RecordingShell delegate = new RecordingShell();
        WorkingDirectoryShell shell = new WorkingDirectoryShell(delegate, tempDir.resolve("branch"));
        ExecutionOptions options = ExecutionOptions.builder().workingDirectory("/elsewhere").hook(true).build();

        shell.execute(() -> "true", options);

        assertThat(delegate.lastOptions).isSameAs(options);
    }

    @Test
    @DisplayName("The streaming execute is forwarded with the branch working directory, listener and all")
    void streamingExecute_forwardedWithWorkingDirectory() throws Exception {
        RecordingShell delegate = new RecordingShell();
        Path branch = tempDir.resolve("branch");
        WorkingDirectoryShell shell = new WorkingDirectoryShell(delegate, branch);
        ShellLineListener listener = line -> {
        };

        shell.execute(() -> "true", ExecutionOptions.defaults(), listener);

        assertThat(delegate.lastOptions.getWorkingDirectory()).isEqualTo(branch.toString());
        assertThat(delegate.lastListener).isSameAs(listener);
    }

    @Test
    @DisplayName("The default streaming execute refuses — a shell that does not stream says so")
    void defaultStreamingExecute_refuses() {
        assertThatThrownBy(() -> new RecordingShell().execute(() -> "true", ExecutionOptions.defaults(), line -> {
        }, true)).isInstanceOf(UnsupportedOperationException.class);
    }

    private static final class RecordingShell implements VirtualShell {

        private ExecutionOptions lastOptions;
        private ShellLineListener lastListener;

        @Override
        public ShellCommandResult execute(ShellCommand command, ExecutionOptions options, ShellLineListener listener) {
            lastOptions = options;
            lastListener = listener;
            return new ShellCommandResult(0, "", "", Duration.ZERO);
        }

        /** Reaches the interface's default, which this class overrides above. */
        ShellCommandResult execute(ShellCommand command, ExecutionOptions options, ShellLineListener listener,
                boolean useDefault) throws ShellExecutionException {
            return VirtualShell.super.execute(command, options, listener);
        }

        @Override
        public ShellCommandResult execute(ShellCommand command) {
            return execute(command, ExecutionOptions.defaults());
        }

        @Override
        public ShellCommandResult execute(ShellCommand command, ExecutionOptions options) {
            lastOptions = options;
            return new ShellCommandResult(0, "", "", Duration.ZERO);
        }

        @Override
        public String getWorkingDirectory() {
            return "/";
        }

        @Override
        public boolean supports(ShellFeature feature) {
            return false;
        }

        @Override
        public void close() {
        }
    }
}
