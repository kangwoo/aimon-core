package at.aimon.core.llms.openai;

import java.util.List;
import java.util.stream.Stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.openai.core.ObjectMappers;
import com.openai.core.http.StreamResponse;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseStreamEvent;

/**
 * Builds Responses API fixtures from JSON, the way the SDK itself would receive them.
 *
 * <p>
 * Deserialising rather than using the SDK builders is deliberate and is the same property
 * {@link OpenAiReasoningTraces} relies on in production: every SDK model has a {@code @JsonCreator} constructor whose
 * {@code JsonField}s default to missing, so Jackson bypasses {@code checkRequired} entirely. That is what lets a test
 * express the shapes that matter — a usage document with a counter missing, a response with no {@code status} — which
 * no builder can construct.
 *
 * <p>
 * It also keeps the fixtures readable as the wire format the server actually sends, so a reviewer checks them against
 * the API rather than against a chain of setters.
 */
final class ResponsesFixtures {

    private ResponsesFixtures() {
    }

    /** Parses one {@code Response} document. */
    static Response response(String json) {
        return read(json, Response.class);
    }

    /** Parses one streaming event document. */
    static ResponseStreamEvent event(String json) {
        return read(json, ResponseStreamEvent.class);
    }

    /** The request body as it would be serialised onto the wire — the only assertion surface that cannot lie. */
    static String bodyOf(ResponseCreateParams params) {
        try {
            return ObjectMappers.jsonMapper().writeValueAsString(params._body());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialise ResponseCreateParams body", e);
        }
    }

    /** The request body as a tree, for assertions about item order and nesting. */
    static JsonNode bodyTreeOf(ResponseCreateParams params) {
        try {
            return ObjectMappers.jsonMapper().readTree(bodyOf(params));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to re-read ResponseCreateParams body", e);
        }
    }

    /** A closeable stream over the given events, standing in for the SDK's SSE stream. */
    static StreamResponse<ResponseStreamEvent> streamOf(ResponseStreamEvent... events) {
        return new RecordingStreamResponse(List.of(events));
    }

    /** {@link #streamOf} plus a record of whether the client closed it. */
    static RecordingStreamResponse recordingStreamOf(ResponseStreamEvent... events) {
        return new RecordingStreamResponse(List.of(events));
    }

    private static <T> T read(String json, Class<T> type) {
        try {
            return ObjectMappers.jsonMapper().readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Malformed fixture: " + json, e);
        }
    }

    /**
     * A {@link StreamResponse} over a fixed event list that remembers whether — and how often — it was closed.
     *
     * <p>
     * The close count is what lets a cancellation test assert that the abort lever is thread-safe and idempotent
     * without a live connection.
     */
    static final class RecordingStreamResponse implements StreamResponse<ResponseStreamEvent> {

        private final List<ResponseStreamEvent> events;
        private volatile int closeCount;
        private volatile boolean drained;

        private RecordingStreamResponse(List<ResponseStreamEvent> events) {
            this.events = events;
        }

        @Override
        public Stream<ResponseStreamEvent> stream() {
            return events.stream().peek(event -> {
                if (closeCount > 0) {
                    // A closed SDK stream stops delivering and the read unwinds. Reproducing that is what makes a
                    // cancellation test mean anything: without it, close() would be a no-op the stream ignored.
                    throw new IllegalStateException("stream closed");
                }
            }).onClose(() -> drained = true);
        }

        @Override
        public void close() {
            closeCount++;
        }

        int closeCount() {
            return closeCount;
        }

        boolean wasDrained() {
            return drained;
        }
    }
}
