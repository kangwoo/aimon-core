package at.aimon.core.llms.anthropic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.prompt.SystemPromptParts;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmCancellation;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.exception.LlmCallCancelledException;
import at.aimon.core.llm.streaming.LlmStreamChunk;
import at.aimon.core.llm.streaming.LlmStreamSink;
import at.aimon.core.llm.streaming.LlmStreamingOptions;
import at.aimon.llm.testkit.StallingSseServer;

/**
 * The cancellation lever over the real transport: it cuts <em>this</em> call's transport from any thread, at once,
 * without touching the stream or its reader — and nothing the SDK does afterwards sends the request again or turns a
 * whole answer into a cancelled one.
 *
 * <p>
 * Rows 1, 2 and 4 of {@code docs/design/llm/cancellation-abort-close.md} §5 for this provider, plus the terminal-event
 * row, which is deterministic here: this mapper emits {@code STREAM_END} at {@code message_stop} inside the loop, and
 * the SDK keeps reading to EOF after it, so a cancel landing in that window is a cancel of a transport whose answer
 * has already been announced whole.
 *
 * <p>
 * On the base commit this SDK's adapter already closed the body race-safely, so row 1 is a regression guard rather
 * than a reproduction; the lever's bound is the looser one because an abort that lands between two reads drains the
 * body for up to OkHttp's 100 ms discard on the aborting thread. Row 2 and the trailing-reset case fail on the base
 * commit: no lever existed before the stream opened, and a transport failure after {@code message_stop} was a
 * provider failure.
 */
@DisplayName("Anthropic - cancellation over the real transport: abort() is not close()")
class AnthropicCancellationTransportTest {

    private static final Duration HANG_GUARD = Duration.ofSeconds(20);
    /** A cancelled call must return within this; it is a guard, not a measurement. */
    private static final Duration RESULT_GUARD = Duration.ofSeconds(1);
    /**
     * The non-blocking bound on the lever. Looser than OpenAI's 100 ms: with no read in flight the SDK's race-safe
     * close drains the body on the aborting thread for up to OkHttp's 100 ms discard, so 100 ms itself would be racy.
     */
    private static final Duration LEVER_BOUND = Duration.ofSeconds(1);
    /**
     * Comfortably above the SDK's first retry backoff (0.5 s x jitter in [0.75, 1.0], so 375-500 ms), so a hook that
     * stopped refusing an aborted attempt would have sent its second request before the count is read.
     */
    private static final Duration RETRY_GRACE = Duration.ofMillis(1500);
    private static final Duration CLIENT_TIMEOUT = Duration.ofSeconds(15);
    private static final int READ_BLOCKED_ROUNDS = 30;
    private static final int TERMINAL_HELD_ROUNDS = 20;

    private static final String FIRST = "The answer is ";
    private static final String SECOND = "forty-two.";
    private static final String WHOLE = FIRST + SECOND;

    private static final String OPENING = "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{"
            + "\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-x\",\"content\":[],"
            + "\"stop_reason\":null,\"stop_sequence\":null,\"usage\":{\"input_tokens\":10,\"output_tokens\":1}}}\n\n"
            + "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,"
            + "\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n";
    /** The block stop, the final usage and {@code message_stop}: the stream's terminal event and what leads to it. */
    private static final String TERMINAL = "event: content_block_stop\ndata: {\"type\":\"content_block_stop\","
            + "\"index\":0}\n\n" + "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{"
            + "\"stop_reason\":\"end_turn\",\"stop_sequence\":null},\"usage\":{\"output_tokens\":12}}\n\n"
            + "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n";

    private static String delta(String text) {
        return "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"text_delta\",\"text\":\"" + text + "\"}}\n\n";
    }

    private static final String AFTER_SOME_TEXT = OPENING + delta(FIRST);
    private static final String THROUGH_MESSAGE_STOP = OPENING + delta(FIRST) + delta(SECOND) + TERMINAL;

    private final ExecutorService worker = Executors.newCachedThreadPool();
    private final ExecutorService tripper = Executors.newSingleThreadExecutor();

    @AfterEach
    void tearDown() {
        worker.shutdownNow();
        tripper.shutdownNow();
    }

    // --- row 1: cancel while the consumer is blocked reading the body -------------------------------------------

    @Test
    @DisplayName("row 1: a cancel while the reader is blocked returns at once, throws nothing, and ends the call")
    void abortWhileReadBlocked() throws Exception {
        for (int round = 0; round < READ_BLOCKED_ROUNDS; round++) {
            try (StallingSseServer server = StallingSseServer.sending(AFTER_SOME_TEXT, "");
                    AnthropicLlmClient client = client(server, null)) {
                final RecordingCancellation cancellation = new RecordingCancellation();
                final RecordingSink sink = new RecordingSink(1);

                final Future<LlmResponse> running = stream(client, sink, cancellation);
                assertThat(sink.awaitAllText(HANG_GUARD)).as("round %d: the delta reached the sink", round).isTrue();
                tripper.execute(cancellation::trip);

                assertThatThrownBy(() -> running.get(RESULT_GUARD.toMillis(), TimeUnit.MILLISECONDS))
                        .as("round %d: the call ends as a cancellation within the guard", round)
                        .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(LlmCallCancelledException.class);
                assertThat(cancellation.awaitTripped(RESULT_GUARD)).as("round %d: the lever returned", round).isTrue();
                assertThat(cancellation.leverRuns()).as("round %d: one lever, registered before the request", round)
                        .hasSize(1);
                final LeverRun lever = cancellation.leverRuns().get(0);
                assertThat(lever.failure).as("round %d: the lever threw nothing", round).isNull();
                assertThat(lever.elapsed).as("round %d: the lever is non-blocking", round)
                        .isLessThanOrEqualTo(LEVER_BOUND);
                assertThat(sink.kinds()).as("round %d: no STREAM_END for a cut stream", round)
                        .containsExactly(LlmStreamChunk.Kind.TEXT_DELTA);
            }
        }
    }

    // --- row 2: cancel during the header wait ------------------------------------------------------------------

    @Test
    @DisplayName("row 2: a cancel during the header wait ends the call and the SDK does not send the request again")
    void abortDuringHeaderWait() throws Exception {
        try (StallingSseServer server = StallingSseServer.stallingBeforeHeaders();
                AnthropicLlmClient client = client(server, 2)) {
            final RecordingCancellation cancellation = new RecordingCancellation();
            final RecordingSink sink = new RecordingSink(0);

            final Future<LlmResponse> running = stream(client, sink, cancellation);
            assertThat(server.awaitAnswered(1, HANG_GUARD)).as("the request reached the server").isTrue();
            tripper.execute(cancellation::trip);

            assertThatThrownBy(() -> running.get(RESULT_GUARD.toMillis(), TimeUnit.MILLISECONDS))
                    .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(LlmCallCancelledException.class);
            assertThat(cancellation.awaitTripped(RESULT_GUARD)).isTrue();
            assertThat(cancellation.leverRuns()).hasSize(1);
            assertThat(cancellation.leverRuns().get(0).failure).isNull();
            assertThat(server.awaitClientDisconnected(1, HANG_GUARD)).as("the client hung up").isTrue();
            // Past the first backoff: a retry of the aborted attempt would be on the wire by now.
            Thread.sleep(RETRY_GRACE.toMillis());
            assertThat(server.requestCount()).as("exactly one request, with retries left").isEqualTo(1);
            assertThat(sink.kinds()).isEmpty();
        }
    }

    // --- row 3: cancel after message_stop, before EOF ----------------------------------------------------------

    @Test
    @DisplayName("row 3: a cancel after message_stop, with the transport held open, returns the whole answer")
    void cancelAfterTerminalEvent() throws Exception {
        for (int round = 0; round < TERMINAL_HELD_ROUNDS; round++) {
            try (StallingSseServer server = StallingSseServer.sending(THROUGH_MESSAGE_STOP, "");
                    AnthropicLlmClient client = client(server, null)) {
                final RecordingCancellation cancellation = new RecordingCancellation();
                final RecordingSink sink = new RecordingSink(2);

                final Future<LlmResponse> running = stream(client, sink, cancellation);
                // STREAM_END is emitted at message_stop, inside the loop: once the sink has it the consumer is back
                // in the read that waits for the held chunk terminator.
                assertThat(sink.awaitStreamEnd(HANG_GUARD)).as("round %d", round).isTrue();
                tripper.execute(cancellation::trip);

                final LlmResponse response = running.get(RESULT_GUARD.toMillis(), TimeUnit.MILLISECONDS);
                assertThat(response.getTextContent()).as("round %d", round).isEqualTo(WHOLE);
                assertThat(response.getTokenUsage().getCompletionTokens()).as("round %d: message_delta's usage", round)
                        .isEqualTo(12);
                assertThat(sink.count(LlmStreamChunk.Kind.STREAM_END)).as("round %d", round).isEqualTo(1);
                assertThat(cancellation.awaitTripped(RESULT_GUARD)).isTrue();
                assertThat(cancellation.leverRuns().get(0).failure).as("round %d", round).isNull();
                server.release();
            }
        }
    }

    // --- row 4: a stale lever from the previous call ---------------------------------------------------------

    @Test
    @DisplayName("row 4: the previous call's lever, run while the next call streams, does not touch the next call")
    void staleAbortDoesNotReachNextCall() throws Exception {
        final RecordingCancellation cancellation = new RecordingCancellation();
        final Runnable previousLever;
        try (StallingSseServer first = StallingSseServer.complete(THROUGH_MESSAGE_STOP);
                AnthropicLlmClient client = client(first, null)) {
            final LlmResponse previous = stream(client, new RecordingSink(2), cancellation).get(HANG_GUARD.toMillis(),
                    TimeUnit.MILLISECONDS);
            assertThat(previous.getTextContent()).isEqualTo(WHOLE);
            assertThat(cancellation.levers()).hasSize(1);
            previousLever = cancellation.levers().get(0);
        }

        try (StallingSseServer next = StallingSseServer.sending(AFTER_SOME_TEXT, delta(SECOND) + TERMINAL);
                AnthropicLlmClient client = client(next, null)) {
            final RecordingSink sink = new RecordingSink(1);
            final Future<LlmResponse> running = stream(client, sink, cancellation);
            assertThat(sink.awaitAllText(HANG_GUARD)).isTrue();

            // The race the executor's single-listener bridge leaves open: the cancel thread read the previous call's
            // lever just before clearAbort(). It must hold no lever of this call.
            tripper.submit(previousLever).get(HANG_GUARD.toMillis(), TimeUnit.MILLISECONDS);

            assertThat(next.awaitClientDisconnected(1, Duration.ofMillis(500)))
                    .as("the next call's connection is still up").isFalse();
            next.release();
            final LlmResponse response = running.get(HANG_GUARD.toMillis(), TimeUnit.MILLISECONDS);
            assertThat(response.getTextContent()).isEqualTo(WHOLE);
        }
    }

    // --- row 6: resources after a cancel -----------------------------------------------------------------------

    @Test
    @DisplayName("row 6: after a cancel, the server sees the connection closed")
    void connectionIsReleasedAfterAbort() throws Exception {
        try (StallingSseServer server = StallingSseServer.sending(AFTER_SOME_TEXT, "");
                AnthropicLlmClient client = client(server, null)) {
            final RecordingCancellation cancellation = new RecordingCancellation();
            final RecordingSink sink = new RecordingSink(1);

            final Future<LlmResponse> running = stream(client, sink, cancellation);
            assertThat(sink.awaitAllText(HANG_GUARD)).isTrue();
            tripper.execute(cancellation::trip);
            assertThatThrownBy(() -> running.get(RESULT_GUARD.toMillis(), TimeUnit.MILLISECONDS))
                    .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(LlmCallCancelledException.class);

            assertThat(server.awaitClientDisconnected(1, Duration.ofSeconds(5))).as("the socket was closed").isTrue();
        }
    }

    // --- the correlation header ------------------------------------------------------------------------------

    @Test
    @DisplayName("the abort key the client puts on the SDK params never reaches the wire")
    void abortKeyNeverReachesTheWire() throws Exception {
        try (StallingSseServer server = StallingSseServer.complete(THROUGH_MESSAGE_STOP);
                AnthropicLlmClient client = client(server, null)) {
            final LlmResponse streamed = stream(client, new RecordingSink(2), new RecordingCancellation())
                    .get(HANG_GUARD.toMillis(), TimeUnit.MILLISECONDS);
            assertThat(streamed.getTextContent()).isEqualTo(WHOLE);
            assertThat(server.requestHeaders()).hasSize(1);
            assertThat(server.requestHeaders().get(0)).doesNotContainKey("x-aimon-abort-key");
        }
        try (StallingSseServer server = StallingSseServer.complete(THROUGH_MESSAGE_STOP);
                AnthropicLlmClient client = client(server, null)) {
            // The cancellable non-streaming call is rerouted through the streaming path and carries the key too.
            final LlmResponse rerouted = client.sendMessage(SystemPromptParts.empty(), List.of(Message.user("hi")),
                    List.of(), LlmModel.builder().build(), LlmCallMetadata.empty(), new RecordingCancellation());
            assertThat(rerouted.getTextContent()).isEqualTo(WHOLE);
            assertThat(server.requestHeaders()).hasSize(1);
            assertThat(server.requestHeaders().get(0)).doesNotContainKey("x-aimon-abort-key");
        }
    }

    // --- §4.1: a transport failure after the terminal event, token live ---------------------------------------

    @Test
    @DisplayName("a reset after message_stop returns the whole answer and is not retried")
    void trailingResetAfterTerminalEventReturnsTheAnswer() throws Exception {
        try (StallingSseServer server = StallingSseServer.sending(THROUGH_MESSAGE_STOP, "");
                AnthropicLlmClient client = client(server, 3)) {
            final RecordingCancellation live = new RecordingCancellation();
            final RecordingSink sink = new RecordingSink(2);

            final Future<LlmResponse> running = stream(client, sink, live);
            assertThat(sink.awaitStreamEnd(HANG_GUARD)).isTrue();
            server.reset();

            final LlmResponse response = running.get(HANG_GUARD.toMillis(), TimeUnit.MILLISECONDS);
            assertThat(response.getTextContent()).isEqualTo(WHOLE);
            assertThat(sink.count(LlmStreamChunk.Kind.STREAM_END)).isEqualTo(1);
            Thread.sleep(RETRY_GRACE.toMillis());
            assertThat(server.requestCount()).as("a whole answer is not retried").isEqualTo(1);
            assertThat(live.isCancelled()).isFalse();
        }
    }

    // --- helpers ------------------------------------------------------------------------------------------------

    private static AnthropicLlmClient client(StallingSseServer server, Integer maxRetries) {
        return new AnthropicLlmClient(AnthropicConfig.builder().apiKey("test-key").model("claude-x")
                .baseUrl(server.url()).maxRetries(maxRetries).timeout(CLIENT_TIMEOUT).build());
    }

    private Future<LlmResponse> stream(AnthropicLlmClient client, LlmStreamSink sink, LlmCancellation cancellation) {
        return worker.submit(() -> client.sendMessageStreaming(SystemPromptParts.empty(), List.of(Message.user("hi")),
                List.of(), LlmModel.builder().build(), LlmCallMetadata.empty(), LlmStreamingOptions.defaults(), sink,
                cancellation));
    }

    /** One run of one lever: how long it took and what, if anything, it threw. */
    static final class LeverRun {
        final Duration elapsed;
        final Throwable failure;

        LeverRun(Duration elapsed, Throwable failure) {
            this.elapsed = elapsed;
            this.failure = failure;
        }
    }

    /**
     * A token tripped from another thread, the way a {@code TaskStop} would be: the levers registered so far run on the
     * tripping thread, one registered afterwards runs at once, and every run is timed and its exception kept — the
     * deterministic signal for the non-blocking, no-throw contract, independent of the production signal's
     * swallow-and-warn.
     */
    static final class RecordingCancellation implements LlmCancellation {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final List<Runnable> levers = new CopyOnWriteArrayList<>();
        private final List<LeverRun> runs = new CopyOnWriteArrayList<>();
        private final CountDownLatch tripped = new CountDownLatch(1);

        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }

        @Override
        public void onCancel(Runnable abort) {
            levers.add(abort);
            if (cancelled.get()) {
                run(abort);
            }
        }

        void trip() {
            if (cancelled.compareAndSet(false, true)) {
                levers.forEach(this::run);
                tripped.countDown();
            }
        }

        boolean awaitTripped(Duration timeout) throws InterruptedException {
            return tripped.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        List<Runnable> levers() {
            return List.copyOf(levers);
        }

        List<LeverRun> leverRuns() {
            return List.copyOf(runs);
        }

        private void run(Runnable lever) {
            final long start = System.nanoTime();
            Throwable failure = null;
            try {
                lever.run();
            } catch (Throwable t) {
                failure = t;
            }
            runs.add(new LeverRun(Duration.ofNanos(System.nanoTime() - start), failure));
        }
    }

    /** Records what the client hands its sink, and says when the expected text deltas and the end have arrived. */
    static final class RecordingSink implements LlmStreamSink {
        private final List<LlmStreamChunk> chunks = new CopyOnWriteArrayList<>();
        private final CountDownLatch allText;
        private final CountDownLatch streamEnd = new CountDownLatch(1);

        RecordingSink(int expectedTextDeltas) {
            this.allText = new CountDownLatch(expectedTextDeltas);
        }

        @Override
        public void accept(LlmStreamChunk chunk) {
            chunks.add(chunk);
            if (chunk.getKind() == LlmStreamChunk.Kind.TEXT_DELTA) {
                allText.countDown();
            } else if (chunk.getKind() == LlmStreamChunk.Kind.STREAM_END) {
                streamEnd.countDown();
            }
        }

        boolean awaitAllText(Duration timeout) throws InterruptedException {
            return allText.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        boolean awaitStreamEnd(Duration timeout) throws InterruptedException {
            return streamEnd.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        List<LlmStreamChunk.Kind> kinds() {
            return chunks.stream().map(LlmStreamChunk::getKind).toList();
        }

        int count(LlmStreamChunk.Kind kind) {
            return (int) chunks.stream().filter(chunk -> chunk.getKind() == kind).count();
        }
    }
}
