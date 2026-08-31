package at.aimon.core.shell.impl.local;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.shell.ExecutionOptions;
import at.aimon.core.shell.ShellCommand;
import at.aimon.core.shell.ShellCommandResult;

@DisplayName("LocalShell Unix Shell Selection Tests")
class LocalShellUnixShellTest {

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
    @DisplayName("Should use default bash shell")
    void shouldUseDefaultBashShell() throws Exception {
        ShellCommand command = () -> "echo 'test'";
        ShellCommandResult result = shell.execute(command);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.stdout()).contains("test");
    }

    @Test
    @DisplayName("Should use sh shell from ExecutionOptions")
    void shouldUseShShellFromOptions() throws Exception {
        ExecutionOptions options = ExecutionOptions.builder().charset(StandardCharsets.UTF_8).unixShell("sh").build();

        ShellCommand command = () -> "echo 'test with sh'";
        ShellCommandResult result = shell.execute(command, options);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.stdout()).contains("test with sh");
    }

    @Test
    @DisplayName("Should use bash shell from ExecutionOptions")
    void shouldUseBashShellFromOptions() throws Exception {
        ExecutionOptions options = ExecutionOptions.builder().charset(StandardCharsets.UTF_8).unixShell("bash").build();

        ShellCommand command = () -> "echo 'test with bash'";
        ShellCommandResult result = shell.execute(command, options);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.stdout()).contains("test with bash");
    }

    @Test
    @DisplayName("Should use default sh shell from constructor")
    void shouldUseDefaultShShellFromConstructor() throws Exception {
        LocalShell shShell = new LocalShell(Runtime.getRuntime().availableProcessors(), // ioThreads
                null, // defaultWorkingDirectory
                "sh" // defaultUnixShell
        );

        try {
            ShellCommand command = () -> "echo 'test with default sh'";
            ShellCommandResult result = shShell.execute(command);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.stdout()).contains("test with default sh");
        } finally {
            shShell.close();
        }
    }

    @Test
    @DisplayName("ExecutionOptions shell should override default shell")
    void executionOptionsShellShouldOverrideDefaultShell() throws Exception {
        // Create shell with sh as default
        LocalShell shShell = new LocalShell(Runtime.getRuntime().availableProcessors(), // ioThreads
                null, // defaultWorkingDirectory
                "sh" // defaultUnixShell
        );

        try {
            // But use bash in options
            ExecutionOptions options = ExecutionOptions.builder().charset(StandardCharsets.UTF_8).unixShell("bash")
                    .build();

            ShellCommand command = () -> "echo 'override test'";
            ShellCommandResult result = shShell.execute(command, options);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.stdout()).contains("override test");
        } finally {
            shShell.close();
        }
    }

    @Test
    @DisplayName("Should use default shell when ExecutionOptions shell is null")
    void shouldUseDefaultShellWhenOptionsShellIsNull() throws Exception {
        LocalShell shShell = new LocalShell(Runtime.getRuntime().availableProcessors(), // ioThreads
                null, // defaultWorkingDirectory
                "sh" // defaultUnixShell
        );

        try {
            ExecutionOptions options = ExecutionOptions.builder().charset(StandardCharsets.UTF_8).build();

            ShellCommand command = () -> "echo 'default shell test'";
            ShellCommandResult result = shShell.execute(command, options);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.stdout()).contains("default shell test");
        } finally {
            shShell.close();
        }
    }
}
