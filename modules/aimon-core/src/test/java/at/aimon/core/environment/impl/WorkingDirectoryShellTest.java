package at.aimon.core.environment.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.shell.ExecutionOptions;
import at.aimon.core.shell.ShellCommand;
import at.aimon.core.shell.ShellCommandResult;
import at.aimon.core.shell.ShellFeature;
import at.aimon.core.shell.VirtualShell;

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
    @DisplayName("A command that sets its own working directory is passed through unchanged")
    void execute_ownWorkingDirectory_passedThrough() throws Exception {
        RecordingShell delegate = new RecordingShell();
        WorkingDirectoryShell shell = new WorkingDirectoryShell(delegate, tempDir.resolve("branch"));
        ExecutionOptions options = ExecutionOptions.builder().workingDirectory("/elsewhere").hook(true).build();

        shell.execute(() -> "true", options);

        assertThat(delegate.lastOptions).isSameAs(options);
    }

    private static final class RecordingShell implements VirtualShell {

        private ExecutionOptions lastOptions;

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
