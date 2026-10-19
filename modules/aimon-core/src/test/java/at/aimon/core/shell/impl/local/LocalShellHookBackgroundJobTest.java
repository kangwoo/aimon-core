package at.aimon.core.shell.impl.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import at.aimon.core.shell.ExecutionOptions;
import at.aimon.core.shell.ShellCommandResult;
import at.aimon.core.shell.exception.ShellTimeoutException;

/**
 * What a hook's command leaves running on {@code LocalShell}, as {@link ExecutionOptions#isHook()} states it (#283 F4):
 * a background job outlives a command that exits by itself, and a timeout ends it.
 */
@DisabledOnOs(OS.WINDOWS)
@DisplayName("LocalShell: what a hook leaves running (#283 F4)")
class LocalShellHookBackgroundJobTest {

    private final LocalShell shell = new LocalShell();

    @Test
    @DisplayName("a background job of a hook command that exits by itself keeps running")
    void backgroundJobOutlivesTheCommand() throws Exception {
        final ShellCommandResult result = shell.execute(() -> "sleep 30 & echo $!",
                ExecutionOptions.builder().hook(true).timeout(Duration.ofSeconds(10)).build());
        final long pid = Long.parseLong(result.stdout().strip());
        try {
            assertThat(result.exitCode()).isZero();
            assertThat(isAlive(pid)).isTrue();
        } finally {
            ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
        }
    }

    @Test
    @DisplayName("a timeout ends the background job still reachable as a descendant")
    void timeoutEndsTheBackgroundJob() throws Exception {
        final ShellTimeoutException timeout = catchThrowableOfType(
                () -> shell.execute(() -> "sleep 30 & echo $!; wait",
                        ExecutionOptions.builder().hook(true).timeout(Duration.ofMillis(500)).build()),
                ShellTimeoutException.class);
        assertThat(timeout).isNotNull();
        final long pid = Long.parseLong(timeout.stdout().strip());
        try {
            final Instant deadline = Instant.now().plusSeconds(5);
            while (isAlive(pid) && Instant.now().isBefore(deadline)) {
                Thread.onSpinWait();
            }
            assertThat(isAlive(pid)).isFalse();
        } finally {
            ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
        }
    }

    private static boolean isAlive(long pid) {
        final Optional<ProcessHandle> handle = ProcessHandle.of(pid);
        return handle.isPresent() && handle.get().isAlive();
    }
}
