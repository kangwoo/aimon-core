package at.aimon.core.llms.openai;

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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

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
 * The cancellation lever over the real transport, on both endpoints: it cuts <em>this</em> call's transport from any
 * thread, at once, without touching the stream or its reader — and nothing the SDK does afterwards sends the request
 * again or turns a whole answer into a cancelled one.
 *
 * <p>
 * Every row of {@code docs/design/llm/cancellation-abort-close.md} §5 is here, over a local HTTP/1.1 server that
 * writes chunked SSE by hand and holds whatever the row needs held. The deterministic signals are an exception
 * recorded on the lever, a request count read on the server, and the chunks a sink received; the time bounds are hang
 * guards except the one on the lever, which is the non-blocking contract measured.
 *
 * <p>
 * On the base commit the lever was {@code StreamResponse.close()}, which closes the response body under the reader's
 * in-flight read and then takes the reader's lock. These tests fail there by whichever arm of that race runs: the
 * lever throws {@code IllegalStateException("Unbalanced enter/exit")} and the consumer hangs until the read timeout,
 * or the lever blocks in OkHttp's drain. The header-wait row fails there because no lever existed before the stream
 * opened.
 */
@DisplayName("OpenAI - cancellation over the real transport: abort() is not close()")
class OpenAICancellationTransportTest {

    private static final Duration HANG_GUARD = Duration.ofSeconds(20);
    /** A cancelled call must return within this; it is a guard, not a measurement. */
    private static final Duration RESULT_GUARD = Duration.ofSeconds(1);
    /**
     * The non-blocking bound on the lever itself. {@code Call.cancel()} takes microseconds; a lever that blocked would
     * block for the read timeout ({@link #CLIENT_TIMEOUT}), so 1 s separates the two by orders of magnitude either way
     * while leaving a loaded CI machine's scheduling jitter out of the result — the same bound the Anthropic suite
     * uses.
     */
    private static final Duration LEVER_BOUND = Duration.ofSeconds(1);
    /**
     * Comfortably above the SDK's first retry backoff (0.5 s x jitter in [0.75, 1.0], so 375-500 ms), so a hook that
     * stopped refusing an aborted attempt would have sent its second request before the count is read.
     */
    private static final Duration RETRY_GRACE = Duration.ofMillis(1500);
    /**
     * Short enough that a regression which hangs the consumer until the read timeout fails the suite in seconds, long
     * enough that no row here comes near it.
     */
    private static final Duration CLIENT_TIMEOUT = Duration.ofSeconds(15);
    private static final int READ_BLOCKED_ROUNDS = 30;
    private static final int TERMINAL_HELD_ROUNDS = 20;
    private static final int TERMINAL_RACE_ROUNDS = 50;

    private static final String FIRST = "The answer is ";
    private static final String SECOND = "forty-two.";
    private static final String WHOLE = FIRST + SECOND;

    /** The two endpoints, each with a model that routes to it and the wire shape of its stream. */
    enum Endpoint {
        CHAT_COMPLETIONS("gpt-4o") {
            @Override
            String opening() {
                return chunk("{\"role\":\"assistant\",\"content\":\"\"}", "null");
            }

            @Override
            String delta(String text) {
                return chunk("{\"content\":\"" + text + "\"}", "null");
            }

            @Override
            String terminal() {
                return chunk("{}", "\"stop\"") + "data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\","
                        + "\"created\":1,\"model\":\"m\",\"choices\":[],\"usage\":{\"prompt_tokens\":3,"
                        + "\"completion_tokens\":2,\"total_tokens\":5}}\n\n";
            }

            @Override
            String tail() {
                // The SDK stops reading at the sentinel, so the sentinel is what a held tail holds back.
                return "data: [DONE]\n\n";
            }

            private static String chunk(String delta, String finishReason) {
                return "data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"m\","
                        + "\"choices\":[{\"index\":0,\"delta\":" + delta + ",\"finish_reason\":" + finishReason
                        + "}]}\n\n";
            }
        },
        RESPONSES("gpt-5-mini") {
            @Override
            String opening() {
                return "event: response.created\ndata: {\"type\":\"response.created\",\"sequence_number\":0,"
                        + "\"response\":{\"id\":\"r\",\"created_at\":1,\"model\":\"m\",\"object\":\"response\","
                        + "\"parallel_tool_calls\":true,\"tool_choice\":\"auto\",\"tools\":[],"
                        + "\"status\":\"in_progress\",\"output\":[]}}\n\n";
            }

            @Override
            String delta(String text) {
                return "event: response.output_text.delta\ndata: {\"type\":\"response.output_text.delta\","
                        + "\"content_index\":0,\"item_id\":\"msg_1\",\"output_index\":0,\"logprobs\":[],"
                        + "\"delta\":\"" + text + "\",\"sequence_number\":1}\n\n";
            }

            @Override
            String terminal() {
                return "event: response.completed\ndata: {\"type\":\"response.completed\",\"sequence_number\":2,"
                        + "\"response\":{\"id\":\"r\",\"created_at\":1,\"model\":\"m\",\"object\":\"response\","
                        + "\"parallel_tool_calls\":true,\"tool_choice\":\"auto\",\"tools\":[],"
                        + "\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"id\":\"msg_1\","
                        + "\"role\":\"assistant\",\"status\":\"completed\",\"content\":[{\"type\":\"output_text\","
                        + "\"text\":\"" + WHOLE + "\",\"annotations\":[]}]}],\"usage\":{\"input_tokens\":3,"
                        + "\"output_tokens\":2,\"total_tokens\":5,\"input_tokens_details\":{\"cached_tokens\":0},"
                        + "\"output_tokens_details\":{\"reasoning_tokens\":0}}}}\n\n";
            }

            @Override
            String tail() {
                // No sentinel on this endpoint: the stream ends at EOF, so a held tail is the chunk terminator alone.
                return "";
            }
        };

        private final String model;

        Endpoint(String model) {
            this.model = model;
        }

        /** A model that routes to this endpoint. */
        String model() {
            return model;
        }

        abstract String opening();

        abstract String delta(String text);

        /** The terminal event (and, on Chat, the usage chunk that follows it). */
        abstract String terminal();

        /** What follows the terminal event before the chunk terminator. */
        abstract String tail();

        String afterSomeText() {
            return opening() + delta(FIRST);
        }

        String throughTheTerminalEvent() {
            return opening() + delta(FIRST) + delta(SECOND) + terminal();
        }

        String whole() {
            return throughTheTerminalEvent() + tail();
        }

        /**
         * As {@link #throughTheTerminalEvent()}, but with the last text delta moved to <em>after</em> the terminal
         * event: a probe, not a wire shape a provider sends. Events are parsed and dispatched one at a time on the
         * consumer thread, so once the sink has the second delta the terminal event has been recorded — a
         * deterministic gate where a sleep would only make it likely. The text is unchanged, and so is the usage,
         * which the terminal event carries.
         */
        String terminalEventThenProbe() {
            return opening() + delta(FIRST) + terminal() + delta(SECOND);
        }
    }

    private final ExecutorService worker = Executors.newCachedThreadPool();
    private final ExecutorService tripper = Executors.newSingleThreadExecutor();

    @AfterEach
    void tearDown() {
        worker.shutdownNow();
        tripper.shutdownNow();
    }

    // --- row 1: cancel while the consumer is blocked reading the body -------------------------------------------

    @ParameterizedTest
    @EnumSource(Endpoint.class)
    @DisplayName("row 1: a cancel while the reader is blocked returns at once, throws nothing, and ends the call")
    void abortWhileReadBlocked(Endpoint endpoint) throws Exception {
        for (int round = 0; round < READ_BLOCKED_ROUNDS; round++) {
            try (StallingSseServer server = StallingSseServer.sending(endpoint.afterSomeText(), "")) {
                final RecordingCancellation cancellation = new RecordingCancellation();
                final RecordingSink sink = new RecordingSink(1);

                final Future<LlmResponse> running = stream(client(server, endpoint, null), sink, cancellation);
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

    @ParameterizedTest
    @EnumSource(Endpoint.class)
    @DisplayName("row 2: a cancel during the header wait ends the call and the SDK does not send the request again")
    void abortDuringHeaderWait(Endpoint endpoint) throws Exception {
        try (StallingSseServer server = StallingSseServer.stallingBeforeHeaders()) {
            final RecordingCancellation cancellation = new RecordingCancellation();
            final RecordingSink sink = new RecordingSink(0);

            final Future<LlmResponse> running = stream(client(server, endpoint, 2), sink, cancellation);
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

    // --- row 3: cancel around the terminal event ---------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(Endpoint.class)
    @DisplayName("row 3: a cancel after the terminal event, with the transport held open, returns the whole answer")
    void cancelAfterTerminalEvent(Endpoint endpoint) throws Exception {
        for (int round = 0; round < TERMINAL_HELD_ROUNDS; round++) {
            try (StallingSseServer server = StallingSseServer.sending(endpoint.terminalEventThenProbe(),
                    endpoint.tail())) {
                final RecordingCancellation cancellation = new RecordingCancellation();
                final RecordingSink sink = new RecordingSink(2);

                final Future<LlmResponse> running = stream(client(server, endpoint, null), sink, cancellation);
                assertThat(server.awaitAnswered(1, HANG_GUARD)).isTrue();
                // The probe delta follows the terminal event: once the sink has it, the consumer has recorded the
                // terminal event and is reading toward the held tail.
                assertThat(sink.awaitAllText(HANG_GUARD)).as("round %d", round).isTrue();
                tripper.execute(cancellation::trip);

                final LlmResponse response = running.get(RESULT_GUARD.toMillis(), TimeUnit.MILLISECONDS);
                assertThat(response.getTextContent()).as("round %d", round).isEqualTo(WHOLE);
                assertThat(response.getTokenUsage().getTotalTokens()).as("round %d: the terminal event's usage", round)
                        .isEqualTo(5);
                assertThat(sink.count(LlmStreamChunk.Kind.STREAM_END)).as("round %d", round).isEqualTo(1);
                assertThat(cancellation.awaitTripped(RESULT_GUARD)).isTrue();
                assertThat(cancellation.leverRuns().get(0).failure).as("round %d", round).isNull();
                server.release();
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Endpoint.class)
    @DisplayName("row 3: a cancel racing the terminal event yields STREAM_END with a response or neither, never else")
    void cancelRacesTerminalEvent(Endpoint endpoint) throws Exception {
        for (int round = 0; round < TERMINAL_RACE_ROUNDS; round++) {
            try (StallingSseServer server = StallingSseServer.complete(endpoint.whole())) {
                final RecordingCancellation cancellation = new RecordingCancellation();
                final RecordingSink sink = new RecordingSink(2);
                // Tripped from inside the sink on the last delta: the consumer is between events, the terminal event
                // is in flight or already buffered, and which side wins is the race.
                sink.onLastText(cancellation::trip);

                final Future<LlmResponse> running = stream(client(server, endpoint, null), sink, cancellation);

                assertInvariant(round, running, sink, cancellation);
            }
        }
    }

    /**
     * The §4.1 invariant: the sink received {@code STREAM_END} if and only if the call returned a response; a call that
     * threw is a cancellation and nothing else.
     */
    private static void assertInvariant(int round, Future<LlmResponse> running, RecordingSink sink,
            RecordingCancellation cancellation) throws Exception {
        LlmResponse response = null;
        Throwable failure = null;
        try {
            response = running.get(HANG_GUARD.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            failure = e.getCause();
        }
        if (response != null) {
            assertThat(response.getTextContent()).as("round %d: a returned answer is whole", round).isEqualTo(WHOLE);
            assertThat(sink.count(LlmStreamChunk.Kind.STREAM_END)).as("round %d: STREAM_END with a response", round)
                    .isEqualTo(1);
        } else {
            assertThat(failure).as("round %d: a call that threw is a cancellation", round)
                    .isInstanceOf(LlmCallCancelledException.class);
            assertThat(sink.count(LlmStreamChunk.Kind.STREAM_END))
                    .as("round %d: no STREAM_END without a response", round).isZero();
        }
        assertThat(cancellation.leverRuns()).allSatisfy(run -> assertThat(run.failure).isNull());
    }

    // --- row 4: a stale lever from the previous call ---------------------------------------------------------

    @ParameterizedTest
    @EnumSource(Endpoint.class)
    @DisplayName("row 4: the previous call's lever, run while the next call streams, does not touch the next call")
    void staleAbortDoesNotReachNextCall(Endpoint endpoint) throws Exception {
        final RecordingCancellation cancellation = new RecordingCancellation();
        final Runnable previousLever;
        try (StallingSseServer first = StallingSseServer.complete(endpoint.whole())) {
            final LlmResponse previous = stream(client(first, endpoint, null), new RecordingSink(2), cancellation)
                    .get(HANG_GUARD.toMillis(), TimeUnit.MILLISECONDS);
            assertThat(previous.getTextContent()).isEqualTo(WHOLE);
            assertThat(cancellation.levers()).hasSize(1);
            previousLever = cancellation.levers().get(0);
        }

        try (StallingSseServer next = StallingSseServer.sending(endpoint.afterSomeText(),
                endpoint.delta(SECOND) + endpoint.terminal() + endpoint.tail())) {
            final RecordingSink sink = new RecordingSink(1);
            final Future<LlmResponse> running = stream(client(next, endpoint, null), sink, cancellation);
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

    @ParameterizedTest
    @EnumSource(Endpoint.class)
    @DisplayName("row 6: after a cancel, the server sees the connection closed")
    void connectionIsReleasedAfterAbort(Endpoint endpoint) throws Exception {
        try (StallingSseServer server = StallingSseServer.sending(endpoint.afterSomeText(), "")) {
            final RecordingCancellation cancellation = new RecordingCancellation();
            final RecordingSink sink = new RecordingSink(1);

            final Future<LlmResponse> running = stream(client(server, endpoint, null), sink, cancellation);
            assertThat(sink.awaitAllText(HANG_GUARD)).isTrue();
            tripper.execute(cancellation::trip);
            assertThatThrownBy(() -> running.get(RESULT_GUARD.toMillis(), TimeUnit.MILLISECONDS))
                    .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(LlmCallCancelledException.class);

            assertThat(server.awaitClientDisconnected(1, Duration.ofSeconds(5))).as("the socket was closed").isTrue();
        }
    }

    // --- the correlation header ------------------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(Endpoint.class)
    @DisplayName("the abort key the client puts on the SDK params never reaches the wire")
    void abortKeyNeverReachesTheWire(Endpoint endpoint) throws Exception {
        try (StallingSseServer server = StallingSseServer.complete(endpoint.whole())) {
            final OpenAILlmClient client = client(server, endpoint, null);
            final LlmResponse streamed = stream(client, new RecordingSink(2), new RecordingCancellation())
                    .get(HANG_GUARD.toMillis(), TimeUnit.MILLISECONDS);
            assertThat(streamed.getTextContent()).isEqualTo(WHOLE);
            assertThat(server.requestHeaders()).hasSize(1);
            assertThat(server.requestHeaders().get(0)).doesNotContainKey("x-aimon-abort-key");
        }
        try (StallingSseServer server = StallingSseServer.complete(endpoint.whole())) {
            // The cancellable non-streaming call is rerouted through the streaming path and carries the key too.
            final LlmResponse rerouted = client(server, endpoint, null).sendMessage(SystemPromptParts.empty(),
                    List.of(Message.user("hi")), List.of(), LlmModel.builder().build(), LlmCallMetadata.empty(),
                    new RecordingCancellation());
            assertThat(rerouted.getTextContent()).isEqualTo(WHOLE);
            assertThat(server.requestHeaders()).hasSize(1);
            assertThat(server.requestHeaders().get(0)).doesNotContainKey("x-aimon-abort-key");
        }
    }

    // --- §4.1: a transport failure after the terminal event, token live ---------------------------------------

    @ParameterizedTest
    @EnumSource(Endpoint.class)
    @DisplayName("a reset after the terminal event returns the whole answer and is not retried")
    void trailingResetAfterTerminalEventReturnsTheAnswer(Endpoint endpoint) throws Exception {
        try (StallingSseServer server = StallingSseServer.sending(endpoint.terminalEventThenProbe(), endpoint.tail())) {
            final RecordingCancellation live = new RecordingCancellation();
            final RecordingSink sink = new RecordingSink(2);

            final Future<LlmResponse> running = stream(client(server, endpoint, 3), sink, live);
            // The probe delta follows the terminal event: the reset lands after it was recorded, never before.
            assertThat(sink.awaitAllText(HANG_GUARD)).isTrue();
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

    private static OpenAILlmClient client(StallingSseServer server, Endpoint endpoint, Integer maxRetries) {
        return new OpenAILlmClient(OpenAIConfig.builder().apiKey("test-key").model(endpoint.model)
                .baseUrl(server.url() + "/v1").maxRetries(maxRetries).timeout(CLIENT_TIMEOUT).build());
    }

    private Future<LlmResponse> stream(OpenAILlmClient client, LlmStreamSink sink, LlmCancellation cancellation) {
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

    /** Records what the client hands its sink, and says when the expected text deltas have all arrived. */
    static final class RecordingSink implements LlmStreamSink {
        private final List<LlmStreamChunk> chunks = new CopyOnWriteArrayList<>();
        private final CountDownLatch allText;
        private final int expectedTextDeltas;
        private volatile Runnable onLastText = () -> {
        };

        RecordingSink(int expectedTextDeltas) {
            this.expectedTextDeltas = expectedTextDeltas;
            this.allText = new CountDownLatch(expectedTextDeltas);
        }

        /** Runs on the consumer thread, inside the sink, when the last expected text delta arrives. */
        void onLastText(Runnable action) {
            this.onLastText = action;
        }

        @Override
        public void accept(LlmStreamChunk chunk) {
            chunks.add(chunk);
            if (chunk.getKind() == LlmStreamChunk.Kind.TEXT_DELTA) {
                allText.countDown();
                if (count(LlmStreamChunk.Kind.TEXT_DELTA) == expectedTextDeltas) {
                    onLastText.run();
                }
            }
        }

        boolean awaitAllText(Duration timeout) throws InterruptedException {
            return allText.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        List<LlmStreamChunk.Kind> kinds() {
            return chunks.stream().map(LlmStreamChunk::getKind).toList();
        }

        int count(LlmStreamChunk.Kind kind) {
            return (int) chunks.stream().filter(chunk -> chunk.getKind() == kind).count();
        }
    }
}
