package at.aimon.core.tools.code.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.tool.ToolDispatchResult;
import at.aimon.core.environment.RuntimeFileSystem;
import at.aimon.core.programmatic.CodeExecutionLimits;
import at.aimon.core.programmatic.ToolCallBridge;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * The host side of one run, without a shell: a stopping run forwards no call (Q-C4), {@code close()} drains the reply
 * writes already started before the run directory is deleted (§8), and the byte caps never split a UTF-8 character
 * (§7).
 */
@DisplayName("ShellProgramRun — stopping, the reply drain, UTF-8-safe output and logs")
class ShellProgramRunTest {

    private static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
    private static final String CALL = "{\"tool\":\"Echo\",\"args\":{}}";

    private final PtcProtocol protocol = new PtcProtocol(KEY);
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final RecordingBridge bridge = new RecordingBridge();
    private final RecordingFiles files = new RecordingFiles();
    private final AtomicInteger stops = new AtomicInteger();

    @AfterEach
    void tearDown() {
        files.release.countDown();
        executor.shutdownNow();
    }

    private ShellProgramRun run(CodeExecutionLimits limits, long deadline, Duration drain) {
        return new ShellProgramRun(KEY, bridge, files, "/w/.aimon-ptc/x/r", limits, executor,
                ShellProgramRun.Control.of(stops::incrementAndGet, deadline).withReplyDrain(drain));
    }

    private ShellProgramRun run() {
        return run(CodeExecutionLimits.defaults(), System.nanoTime() + Duration.ofMinutes(5).toNanos(),
                Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("after markStopping a valid CALL is verified but not forwarded; OUT and DONE still count")
    void stoppingForwardsNoCall() {
        final ShellProgramRun run = run();
        run.markStopping();
        run.onStdoutLine(protocol.line("CALL", 1, CALL));
        run.onStdoutLine(protocol.line("OUT", 2, b64("partial")));
        run.onStdoutLine(protocol.line("DONE", 3, "{\"status\":\"completed\"}"));
        assertThat(bridge.calls).isEmpty();
        assertThat(files.writes).isEmpty();
        assertThat(run.violation()).isEmpty();
        assertThat(run.stdout()).isEqualTo("partial");
        assertThat(run.done()).isPresent();
    }

    @Test
    @DisplayName("after markStopping a forged CALL is still a violation")
    void stoppingStillVerifies() {
        final ShellProgramRun run = run();
        run.markStopping();
        run.onStdoutLine("CALL 1 " + CALL + " " + "0".repeat(64));
        assertThat(run.violation()).isPresent();
        assertThat(bridge.calls).isEmpty();
    }

    @Test
    @DisplayName("a CALL handled after the wall clock ran out is not forwarded")
    void pastTheDeadlineForwardsNoCall() {
        final ShellProgramRun run = run(CodeExecutionLimits.defaults(), System.nanoTime() - 1, Duration.ofSeconds(5));
        run.onStdoutLine(protocol.line("CALL", 1, CALL));
        assertThat(bridge.calls).isEmpty();
        assertThat(run.violation()).isEmpty();
    }

    @Test
    @DisplayName("a stop the run makes itself (output limit) also stops forwarding")
    void ownStopForwardsNoCall() {
        final ShellProgramRun run = run(CodeExecutionLimits.builder().maxScriptOutputBytes(4).build(),
                System.nanoTime() + Duration.ofMinutes(5).toNanos(), Duration.ofSeconds(5));
        run.onStdoutLine(protocol.line("OUT", 1, b64("too long")));
        run.onStdoutLine(protocol.line("CALL", 2, CALL));
        assertThat(stops.get()).isEqualTo(1);
        assertThat(bridge.calls).isEmpty();
    }

    @Test
    @DisplayName("close() waits for a reply write in flight, and returns once it finishes")
    void closeDrainsWriteInFlight() throws Exception {
        final ShellProgramRun run = run();
        run.onStdoutLine(protocol.line("CALL", 1, CALL));
        assertThat(files.entered.await(10, TimeUnit.SECONDS)).isTrue();
        final Future<?> closing = executor.submit(run::close);
        Thread.sleep(200);
        assertThat(closing.isDone()).as("close() returned while a write was in flight").isFalse();
        files.release.countDown();
        closing.get(10, TimeUnit.SECONDS);
        assertThat(files.writes).containsExactly("/w/.aimon-ptc/x/r/1");
    }

    @Test
    @DisplayName("a reply that completes after close() never reaches the file system")
    void noWriteStartsAfterClose() throws Exception {
        final CompletableFuture<ToolDispatchResult> late = new CompletableFuture<>();
        bridge.answer = late;
        final ShellProgramRun run = run();
        run.onStdoutLine(protocol.line("CALL", 1, CALL));
        run.close();
        late.complete(ToolDispatchResult.builder().toolUseId("t").text("late").build());
        Thread.sleep(200);
        assertThat(files.attempts.get()).isZero();
    }

    @Test
    @DisplayName("a write that never finishes: close() gives up after the drain bound and logs a WARN")
    void drainIsBounded() throws Exception {
        final ShellProgramRun run = run(CodeExecutionLimits.defaults(),
                System.nanoTime() + Duration.ofMinutes(5).toNanos(), Duration.ofMillis(300));
        run.onStdoutLine(protocol.line("CALL", 1, CALL));
        assertThat(files.entered.await(10, TimeUnit.SECONDS)).isTrue();
        final Logger logger = (Logger) LoggerFactory.getLogger(ShellProgramRun.class);
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            final long start = System.nanoTime();
            executor.submit(run::close).get(10, TimeUnit.SECONDS);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isBetween(Duration.ofMillis(250),
                    Duration.ofSeconds(5));
        } catch (TimeoutException e) {
            throw new AssertionError("close() did not honour its bound", e);
        } finally {
            logger.detachAppender(appender);
        }
        assertThat(appender.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("still in flight");
        });
    }

    @Test
    @DisplayName("a character split across two ERR lines decodes whole; the tail keeps maxLogChars characters")
    void logsDecodeAcrossLines() {
        final ShellProgramRun run = run(CodeExecutionLimits.builder().maxLogChars(3).build(),
                System.nanoTime() + Duration.ofMinutes(5).toNanos(), Duration.ofSeconds(5));
        final byte[] text = "가나다라".getBytes(StandardCharsets.UTF_8);
        run.onStdoutLine(protocol.line("ERR", 1, Base64.getEncoder().encodeToString(Arrays.copyOfRange(text, 0, 4))));
        run.onStdoutLine(protocol.line("ERR", 2, Base64.getEncoder().encodeToString(Arrays.copyOfRange(text, 4, 12))));
        assertThat(run.logs()).isEqualTo("나다라");
    }

    @Test
    @DisplayName("stdout cut at the byte cap ends on a whole character")
    void stdoutCutKeepsWholeCharacters() {
        final ShellProgramRun run = run(CodeExecutionLimits.builder().maxScriptOutputBytes(7).build(),
                System.nanoTime() + Duration.ofMinutes(5).toNanos(), Duration.ofSeconds(5));
        run.onStdoutLine(protocol.line("OUT", 1, b64("한한한")));
        assertThat(run.outputLimitExceeded()).isTrue();
        assertThat(run.stdout()).isEqualTo("한한");
    }

    private static String b64(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private static final class RecordingBridge implements ToolCallBridge {
        final List<String> calls = new CopyOnWriteArrayList<>();
        volatile CompletableFuture<ToolDispatchResult> answer;

        @Override
        public CompletableFuture<ToolDispatchResult> callAsync(String toolName, Map<String, Object> input) {
            calls.add(toolName);
            final CompletableFuture<ToolDispatchResult> held = answer;
            return held != null
                    ? held
                    : CompletableFuture.completedFuture(ToolDispatchResult.builder().toolUseId("t").text("ok").build());
        }

        @Override
        public Optional<String> limitExceeded() {
            return Optional.empty();
        }
    }

    /** A runtime file system whose writeNew blocks until released. */
    private static final class RecordingFiles implements RuntimeFileSystem {
        final List<String> writes = new CopyOnWriteArrayList<>();
        final AtomicInteger attempts = new AtomicInteger();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public void writeNew(String path, byte[] bytes) throws IOException {
            attempts.incrementAndGet();
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted");
            }
            writes.add(path);
        }

        @Override
        public byte[] read(String path, int maxBytes) {
            return new byte[0];
        }

        @Override
        public void delete(String path) {
        }

        @Override
        public void createDirectories(String path) {
        }
    }
}
