package at.aimon.core.tools.code.shell;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.interrupt.DefaultInterruptCoordinator;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolDispatchResult;
import at.aimon.core.environment.RuntimeFileSystem;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.programmatic.CodeExecutionLimits;
import at.aimon.core.programmatic.CodeExecutionRequest;
import at.aimon.core.programmatic.ToolCallBridge;
import at.aimon.core.tools.ToolContextKeys;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Measures the reply drain ({@link ShellProgramRun#REPLY_DRAIN}, backlog TC-20; ptc-leftovers-core §1.12): how long
 * {@code ShellProgramRun.close()} waits for reply writes in flight when a program ends while its replies are being
 * written, and how long one {@code writeNew} takes — locally, and with latency added to model a remote sandbox.
 *
 * <p>
 * Gated: it runs only with {@code AIMON_MEASURE=1} in the environment, never in {@code test}, {@code checkAll} or CI.
 * It prints p50, p99 and max of both distributions per series, and asserts the structural claim the bound rests on:
 * {@code close()} waits for one attempt of each write already in flight, which run in parallel — so no wait exceeds
 * the slowest single write of its series by more than scheduling slack.
 *
 * <p>
 * The wait is read from the DEBUG line {@code close()} logs when it waited (millisecond resolution); a run without
 * that line did not wait.
 */
@EnabledIfEnvironmentVariable(named = "AIMON_MEASURE", matches = "1")
@DisplayName("ShellProgramRun — measuring the reply drain (AIMON_MEASURE=1)")
class ShellReplyDrainMeasurementTest {

    private static final int PROGRAMS = 200;
    private static final int CALLS = 8;
    private static final long SLACK_MILLIS = 50;
    private static final Pattern WAITED = Pattern.compile("closing the run waited (\\d+) ms");
    /** Fires CALLS tool calls without awaiting them and ends at once, so replies are written while the run closes. */
    private static final String PROGRAM = "async def main():\n" + "    for i in range(" + CALLS + "):\n"
            + "        asyncio.ensure_future(Echo({\"i\": i}))\n" + "    await asyncio.sleep(0)\n" + "await main()\n";

    @TempDir
    Path tempDir;

    private final ExecutorService answers = Executors.newCachedThreadPool();
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Logger logger;
    private Level previousLevel;

    @BeforeEach
    void setUp() {
        assumeTrue(Python3.available(), "python3 3.8+ is not installed");
        logger = (Logger) LoggerFactory.getLogger(ShellProgramRun.class);
        previousLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        logger.setLevel(previousLevel);
        answers.shutdownNow();
    }

    @Test
    @DisplayName("local, fixed 50 ms, fixed 250 ms and heavy-tailed write latency: close() waits at most one write")
    void measure() throws Exception {
        final List<String> report = new ArrayList<>();
        report.add(series("local", () -> 0L));
        report.add(series("fixed 50 ms", () -> 50L));
        report.add(series("fixed 250 ms", () -> 250L));
        // Heavy-tailed: mostly 20-60 ms, 1 in 50 writes 0.8-1.2 s (p99 about 1 s).
        report.add(series("heavy-tailed",
                () -> ThreadLocalRandom.current().nextInt(50) == 0
                        ? 800L + ThreadLocalRandom.current().nextLong(400)
                        : 20L + ThreadLocalRandom.current().nextLong(40)));
        System.out.println("REPLY-DRAIN-MEASUREMENT\n" + String.join("\n", report));
    }

    private String series(String name, LongSupplier addedLatencyMillis) throws IOException {
        final List<Long> writes = new CopyOnWriteArrayList<>();
        final ShellCodeExecutionEngine engine = ShellCodeExecutionEngine.create();
        final Path root = tempDir.resolve(name.replace(' ', '-')).toAbsolutePath();
        java.nio.file.Files.createDirectories(root.resolve(ShellCodeExecutionEngine.PTC_DIRECTORY));
        final SandboxedLocalEnvironment environment = new SandboxedLocalEnvironment(root.toRealPath(),
                files -> new SlowWrites(files, addedLatencyMillis, writes));
        appender.list.clear();
        int waitedRuns = 0;
        for (int i = 0; i < PROGRAMS; i++) {
            final DelayedBridge bridge = new DelayedBridge(answers);
            final CodeExecutionLimits limits = CodeExecutionLimits.defaults();
            final CodeExecutionRequest request = CodeExecutionRequest.builder().toolUseId("toolu_drain_" + i)
                    .code(PROGRAM).tools(List.of(ToolDefinition.of("Echo", "Echoes.", Map.of("type", "object"))))
                    .limits(limits).wallClock(Duration.ofSeconds(30)).cancellation(bridge.signal.getSignal()).build();
            engine.execute(request, bridge,
                    ToolContext.builder().put(ToolContextKeys.EXECUTION_ENVIRONMENT, environment).build());
        }
        final List<Long> waits = new ArrayList<>();
        for (ILoggingEvent event : appender.list) {
            final Matcher matcher = WAITED.matcher(event.getFormattedMessage());
            if (matcher.find()) {
                waits.add(Long.parseLong(matcher.group(1)));
                waitedRuns++;
            }
        }
        while (waits.size() < PROGRAMS) {
            waits.add(0L);
        }
        final long slowestWrite = writes.stream().mapToLong(Long::longValue).max().orElse(0);
        assertThat(waits).as("%s: every close() wait is at most the slowest single write + %d ms", name, SLACK_MILLIS)
                .allSatisfy(wait -> assertThat(wait).isLessThanOrEqualTo(slowestWrite + SLACK_MILLIS));
        return String.format(Locale.ROOT, "%-13s writes n=%d %s | close() waits n=%d (waited %d) %s", name,
                writes.size(), stats(writes), waits.size(), waitedRuns, stats(waits));
    }

    private static String stats(List<Long> values) {
        if (values.isEmpty()) {
            return "p50=- p99=- max=-";
        }
        final List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        return String.format(Locale.ROOT, "p50=%dms p99=%dms max=%dms", percentile(sorted, 0.50),
                percentile(sorted, 0.99), sorted.get(sorted.size() - 1));
    }

    private static long percentile(List<Long> sorted, double p) {
        final int index = (int) Math.ceil(p * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    /** Answers each call on a thread of its own after a random 0-20 ms, as tools that take a moment do. */
    private static final class DelayedBridge implements ToolCallBridge {
        private final ExecutorService executor;
        private final DefaultInterruptCoordinator signal = new DefaultInterruptCoordinator();

        DelayedBridge(ExecutorService executor) {
            this.executor = executor;
        }

        @Override
        public CompletableFuture<ToolDispatchResult> callAsync(String toolName, Map<String, Object> input) {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    TimeUnit.MILLISECONDS.sleep(ThreadLocalRandom.current().nextLong(21));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return ToolDispatchResult.builder().toolUseId("t").text("ok").build();
            }, executor);
        }

        @Override
        public Optional<String> limitExceeded() {
            return Optional.empty();
        }
    }

    /** Adds latency to every {@code writeNew} and records each one's total time. */
    private static final class SlowWrites implements RuntimeFileSystem {
        private final RuntimeFileSystem delegate;
        private final LongSupplier addedLatencyMillis;
        private final List<Long> writes;

        SlowWrites(RuntimeFileSystem delegate, LongSupplier addedLatencyMillis, List<Long> writes) {
            this.delegate = delegate;
            this.addedLatencyMillis = addedLatencyMillis;
            this.writes = writes;
        }

        @Override
        public void writeNew(String path, byte[] bytes) throws IOException {
            final long start = System.nanoTime();
            try {
                final long latency = addedLatencyMillis.getAsLong();
                if (latency > 0) {
                    TimeUnit.MILLISECONDS.sleep(latency);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted");
            }
            try {
                delegate.writeNew(path, bytes);
            } finally {
                writes.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
            }
        }

        @Override
        public byte[] read(String path, int maxBytes) throws IOException {
            return delegate.read(path, maxBytes);
        }

        @Override
        public void delete(String path) throws IOException {
            delegate.delete(path);
        }

        @Override
        public void createDirectories(String path) throws IOException {
            delegate.createDirectories(path);
        }
    }
}
