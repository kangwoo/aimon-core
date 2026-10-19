package at.aimon.core.shell.impl.local;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import at.aimon.core.shell.ExecutionOptions;
import at.aimon.core.shell.ShellCommandResult;

/**
 * A hook's or an isolated command's stdin, stdout and stderr are not reachable by name while it runs (EE-109): the
 * capture files are unlinked once the child holds them, and stdin goes through a pipe.
 */
@DisabledOnOs(OS.WINDOWS)
@DisplayName("LocalShell hook I/O (EE-109)")
class LocalShellHookIoTest {

    private static final String TEMP_FILE_PREFIX = "aimon-shell-";
    private static final Path TMP = Path.of(System.getProperty("java.io.tmpdir"));

    private final LocalShell shell = new LocalShell();

    /**
     * Lists, from inside the command, the files under the temp directory that are its own stdin, stdout or stderr:
     * the ones whose inode is that of the command's descriptor 0, 1 or 2. Matching by inode keeps out the capture
     * files of whatever else runs at the same time.
     */
    private Set<String> ownFilesSeenFromInside(ExecutionOptions.Builder options) throws Exception {
        final ShellCommandResult result = shell.execute(() -> "sleep 0.3; exec 4<&0 5>&1 6>&2; for fd in 4 5 6; do"
                + " ino=$(stat -L -c %i /dev/fd/$fd 2>/dev/null || stat -L -f %i /dev/fd/$fd 2>/dev/null) || continue;"
                + " find \"$PROBE_DIR\" -maxdepth 1 -name '" + TEMP_FILE_PREFIX + "*' -inum \"$ino\" >&5; done",
                options.timeout(Duration.ofSeconds(10)).environment(Map.of("PROBE_DIR", TMP.toString())).build());
        return Stream.of(result.stdout().split("\n")).map(String::strip).filter(name -> !name.isEmpty())
                .collect(Collectors.toSet());
    }

    @Test
    @DisplayName("a model-facing command can find its own capture files by name — the probe works")
    void modelFacingCommandSeesItsFiles() throws Exception {
        assertThat(ownFilesSeenFromInside(ExecutionOptions.builder().stdin("x"))).hasSize(3)
                .allMatch(path -> path.contains(TEMP_FILE_PREFIX));
    }

    @Test
    @DisplayName("an isolated command finds none of its own .in, .out or .err while it runs")
    void isolatedCommandHasNoNamedFiles() throws Exception {
        assertThat(ownFilesSeenFromInside(ExecutionOptions.builder().hook(true).isolated(true).stdin("{}"))).isEmpty();
    }

    @Test
    @DisplayName("a skill hook (hook mode only) finds none either")
    void hookCommandHasNoNamedFiles() throws Exception {
        assertThat(ownFilesSeenFromInside(ExecutionOptions.builder().hook(true).stdin("{}"))).isEmpty();
    }

    @Test
    @DisplayName("stdin larger than a pipe buffer reaches the command intact")
    void largeStdinArrivesIntact() throws Exception {
        final String payload = "x".repeat(2 * 1024 * 1024 + 7);

        final ShellCommandResult result = shell.execute(() -> "wc -c | tr -d ' '",
                ExecutionOptions.builder().hook(true).stdin(payload).timeout(Duration.ofSeconds(20)).build());

        assertThat(result.stdout().strip()).isEqualTo(String.valueOf(payload.length()));
    }

    @Test
    @DisplayName("stdout and stderr are captured as before, merged or not")
    void outputIsCaptured() throws Exception {
        final ShellCommandResult separate = shell.execute(() -> "echo out; echo err >&2",
                ExecutionOptions.builder().hook(true).build());
        final ShellCommandResult merged = shell.execute(() -> "echo out; echo err >&2",
                ExecutionOptions.builder().hook(true).redirectErrorStream(true).build());

        assertThat(separate.stdout().strip()).isEqualTo("out");
        assertThat(separate.stderr().strip()).isEqualTo("err");
        assertThat(merged.stdout()).contains("out").contains("err");
    }

    @Test
    @DisplayName("a hook that never reads a large stdin finishes at its exit")
    void unreadStdinDoesNotBlock() throws Exception {
        final long start = System.nanoTime();

        final ShellCommandResult result = shell.execute(() -> "exit 0", ExecutionOptions.builder().hook(true)
                .stdin("y".repeat(4 * 1024 * 1024)).timeout(Duration.ofSeconds(20)).build());

        assertThat(result.exitCode()).isZero();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("a background process that keeps the unread stdin open does not hold the call")
    void backgroundHolderOfStdinDoesNotBlock() throws Exception {
        final long start = System.nanoTime();

        // bash gives `cmd &` /dev/null as stdin; an explicit <&3 is what it takes to keep the pipe.
        final ShellCommandResult result = shell.execute(() -> "exec 3<&0; (sleep 3 <&3 >/dev/null 2>&1 &); exit 0",
                ExecutionOptions.builder().hook(true).stdin("z".repeat(4 * 1024 * 1024)).timeout(Duration.ofSeconds(20))
                        .build());

        assertThat(result.exitCode()).isZero();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("output of `cmd &` left running does not hold the call")
    void backgroundOutputDoesNotBlock() throws Exception {
        final long start = System.nanoTime();

        final ShellCommandResult result = shell.execute(() -> "(sleep 3; echo late) & echo done",
                ExecutionOptions.builder().hook(true).isolated(true).timeout(Duration.ofSeconds(20)).build());

        assertThat(result.stdout()).contains("done").doesNotContain("late");
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
    }
}
