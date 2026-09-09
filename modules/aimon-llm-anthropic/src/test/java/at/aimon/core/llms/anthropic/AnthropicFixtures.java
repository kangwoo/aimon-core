package at.aimon.core.llms.anthropic;

import java.util.List;
import java.util.stream.Stream;

import com.anthropic.core.ObjectMappers;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Builds Messages API fixtures from JSON, the way the SDK itself would receive them.
 *
 * <p>
 * Deserialising rather than using the SDK builders is deliberate and is the same property
 * {@link AnthropicReasoningTraces} relies on in production: every SDK model has a {@code @JsonCreator} constructor
 * whose {@code JsonField}s default to missing, so Jackson bypasses {@code checkRequired} entirely. That is what lets a
 * test express the shapes that matter — a usage document with a counter missing, a block carrying a field this SDK
 * version does not model — which no builder can construct.
 *
 * <p>
 * It matters more here than it does on the OpenAI side for a second reason: a Mockito-mocked {@code ThinkingBlock}
 * serialises to nothing, so a byte-exactness assertion over a mock would be vacuously true. These fixtures are the
 * real thing.
 */
final class AnthropicFixtures {

    private AnthropicFixtures() {
    }

    /** Parses one response content block. */
    static ContentBlock contentBlock(String json) {
        return read(json, ContentBlock.class);
    }

    /** Parses one {@code Message} document. */
    static com.anthropic.models.messages.Message message(String json) {
        return read(json, com.anthropic.models.messages.Message.class);
    }

    /** Parses one streaming event document. */
    static RawMessageStreamEvent event(String json) {
        return read(json, RawMessageStreamEvent.class);
    }

    /** The request body as it would be serialised onto the wire — the only assertion surface that cannot lie. */
    static String bodyOf(MessageCreateParams params) {
        try {
            return ObjectMappers.jsonMapper().writeValueAsString(params._body());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialise MessageCreateParams body", e);
        }
    }

    /** The request body as a tree, for assertions about block order and nesting. */
    static JsonNode bodyTreeOf(MessageCreateParams params) {
        try {
            return ObjectMappers.jsonMapper().readTree(bodyOf(params));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to re-read MessageCreateParams body", e);
        }
    }

    /** Re-reads an arbitrary JSON string as a tree, so a payload can be compared whole rather than by substring. */
    static JsonNode treeOf(String json) {
        try {
            return ObjectMappers.jsonMapper().readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Malformed JSON: " + json, e);
        }
    }

    /** A closeable stream over the given events, standing in for the SDK's SSE stream. */
    static StreamResponse<RawMessageStreamEvent> streamOf(RawMessageStreamEvent... events) {
        return new RecordingStreamResponse(List.of(events));
    }

    private static <T> T read(String json, Class<T> type) {
        try {
            return ObjectMappers.jsonMapper().readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Malformed fixture: " + json, e);
        }
    }

    /** A {@link StreamResponse} over a fixed event list that remembers whether it was closed. */
    static final class RecordingStreamResponse implements StreamResponse<RawMessageStreamEvent> {

        private final List<RawMessageStreamEvent> events;
        private volatile int closeCount;

        private RecordingStreamResponse(List<RawMessageStreamEvent> events) {
            this.events = events;
        }

        @Override
        public Stream<RawMessageStreamEvent> stream() {
            return events.stream();
        }

        @Override
        public void close() {
            closeCount++;
        }

        int closeCount() {
            return closeCount;
        }
    }
}
