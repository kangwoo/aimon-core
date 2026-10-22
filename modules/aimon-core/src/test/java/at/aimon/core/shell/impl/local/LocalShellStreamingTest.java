package at.aimon.core.shell.impl.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import at.aimon.core.shell.ExecutionOptions;
import at.aimon.core.shell.ShellCancellationSource;
import at.aimon.core.shell.ShellCommandResult;
import at.aimon.core.shell.ShellFeature;
import at.aimon.core.shell.exception.ShellCancelledException;
import at.aimon.core.shell.exception.ShellExecutionException;
import at.aimon.core.shell.exception.ShellTimeoutException;

@DisplayName("LocalShell — streaming standard output line by line")
@DisabledOnOs(OS.WINDOWS)
class LocalShellStreamingTest {

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
    @DisplayName("declares STREAMING")
    void declaresTheFeature() {
        assertThat(shell.supports(ShellFeature.STREAMING)).isTrue();
    }

    @Test
    @DisplayName("hands over each stdout line without its terminator; the result keeps stderr and the exit code only")
    void handsOverLines() throws Exception {
        final List<String> lines = new CopyOnWriteArrayList<>();

        final ShellCommandResult result = shell.execute(
                () -> "printf 'one\\ntwo\\r\\n'; echo oops >&2; printf 'three'; exit 3",
                ExecutionOptions.builder().timeout(Duration.ofSeconds(10)).build(), lines::add);

        assertThat(lines).containsExactly("one", "two", "three");
        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(result.stdout()).isEmpty();
        assertThat(result.stderr()).contains("oops");
    }

    @Test
    @DisplayName("a line arrives while the command is still running")
    void lineArrivesBeforeTheCommandEnds() throws Exception {
        final CountDownLatch firstLine = new CountDownLatch(1);
        final ShellCancellationSource stop = ShellCancellationSource.create();
        final CompletableFuture<Void> run = CompletableFuture.runAsync(() -> {
            try {
                shell.execute(() -> "echo ready; sleep 30",
                        ExecutionOptions.builder().timeout(Duration.ofSeconds(60)).cancellation(stop.token()).build(),
                        line -> firstLine.countDown());
            } catch (ShellCancelledException expected) {
                // stopped below
            } catch (ShellExecutionException e) {
                throw new IllegalStateException(e);
            }
        });

        assertThat(firstLine.await(5, TimeUnit.SECONDS)).as("line delivered before exit").isTrue();
        assertThat(run).isNotDone();
        stop.cancel();
        run.get(10, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("an endless command is stopped by its cancellation signal, and the lines so far were delivered")
    void cancellationStopsAnEndlessCommand() throws Exception {
        final List<String> lines = new CopyOnWriteArrayList<>();
        final ShellCancellationSource stop = ShellCancellationSource.create();
        final CompletableFuture<Throwable> run = CompletableFuture.supplyAsync(() -> {
            try {
                shell.execute(() -> "while true; do echo tick; sleep 0.05; done",
                        ExecutionOptions.builder().timeout(Duration.ofSeconds(60)).cancellation(stop.token()).build(),
                        lines::add);
                return null;
            } catch (ShellExecutionException | RuntimeException e) {
                return e;
            }
        });

        while (lines.size() < 3) {
            Thread.sleep(20);
        }
        stop.cancel();

        assertThat(run.get(10, TimeUnit.SECONDS)).isInstanceOf(ShellCancelledException.class);
        assertThat(lines).allMatch("tick"::equals);
    }

    @Test
    @DisplayName("the timeout ends a streaming command as it ends any other")
    void timeoutEndsTheCommand() {
        final List<String> lines = new CopyOnWriteArrayList<>();

        assertThatThrownBy(() -> shell.execute(() -> "echo started; sleep 30",
                ExecutionOptions.builder().timeout(Duration.ofMillis(500)).build(), lines::add))
                .isInstanceOf(ShellTimeoutException.class);
        assertThat(lines).containsExactly("started");
    }

    @Test
    @DisplayName("a listener that throws loses its line, not the command")
    void throwingListenerDoesNotStopTheCommand() throws Exception {
        final List<String> lines = new CopyOnWriteArrayList<>();

        final ShellCommandResult result = shell.execute(() -> "echo bad; echo good",
                ExecutionOptions.builder().timeout(Duration.ofSeconds(10)).build(), line -> {
                    if (line.equals("bad")) {
                        throw new IllegalStateException("boom");
                    }
                    lines.add(line);
                });

        assertThat(result.exitCode()).isZero();
        assertThat(lines).containsExactly("good");
    }

    @Test
    @DisplayName("a hook's private capture streams too: the tail channel is opened before the file is unlinked")
    void privateIoStreams() throws Exception {
        final List<String> lines = new CopyOnWriteArrayList<>();

        shell.execute(() -> "echo hidden",
                ExecutionOptions.builder().timeout(Duration.ofSeconds(10)).isolated(true).build(), lines::add);

        assertThat(lines).containsExactly("hidden");
    }
}
