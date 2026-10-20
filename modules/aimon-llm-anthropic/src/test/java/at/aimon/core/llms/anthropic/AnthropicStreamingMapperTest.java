package at.aimon.core.llms.anthropic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.anthropic.errors.AnthropicIoException;
import com.anthropic.models.messages.RawMessageStreamEvent;

import at.aimon.core.llm.LlmCancellation;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.exception.LlmCallCancelledException;
import at.aimon.core.llm.streaming.ChunkAggregator;
import at.aimon.core.llm.streaming.LlmStreamChunk;
import at.aimon.core.llm.streaming.LlmStreamSink;

/**
 * How the mapper classifies a stream that does not reach EOF: by the token before {@code message_stop}, by nothing
 * after it — {@code STREAM_END} went out at {@code message_stop}, inside the loop, and the SDK's trailing read to EOF
 * cannot take it back.
 */
@DisplayName("AnthropicStreamingMapper - a stream cut short")
class AnthropicStreamingMapperTest {

    private static final AnthropicDivergenceReporter SILENT = (signature, message, args) -> {
    };

    private final List<LlmStreamChunk> emitted = new ArrayList<>();
    private final LlmStreamSink sink = emitted::add;
    private final ChunkAggregator aggregator = new ChunkAggregator();
    private final AnthropicStreamingMapper mapper = new AnthropicStreamingMapper(sink, aggregator, "Anthropic", SILENT,
            false);

    private static RawMessageStreamEvent messageStart() {
        return AnthropicFixtures.event("{\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"type\":\"message\","
                + "\"role\":\"assistant\",\"model\":\"claude-x\",\"content\":[],\"stop_reason\":null,"
                + "\"stop_sequence\":null,\"usage\":{\"input_tokens\":10,\"output_tokens\":1}}}");
    }

    private static RawMessageStreamEvent textStart() {
        return AnthropicFixtures.event("{\"type\":\"content_block_start\",\"index\":0,"
                + "\"content_block\":{\"type\":\"text\",\"text\":\"\"}}");
    }

    private static RawMessageStreamEvent textDelta(String text) {
        return AnthropicFixtures.event("{\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"text_delta\",\"text\":\"" + text + "\"}}");
    }

    private static RawMessageStreamEvent blockStop() {
        return AnthropicFixtures.event("{\"type\":\"content_block_stop\",\"index\":0}");
    }

    private static RawMessageStreamEvent messageDelta() {
        return AnthropicFixtures.event("{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\","
                + "\"stop_sequence\":null},\"usage\":{\"output_tokens\":12}}");
    }

    private static RawMessageStreamEvent messageStop() {
        return AnthropicFixtures.event("{\"type\":\"message_stop\"}");
    }

    /** The given events, then the SDK's transport failure — what a cut socket looks like out of the SDK's stream. */
    private static Stream<RawMessageStreamEvent> thenTransportFails(RawMessageStreamEvent... events) {
        return Stream.concat(Stream.of(events), Stream.generate(() -> {
            throw new AnthropicIoException("Stream failed", new IOException("Canceled"));
        }));
    }

    private static LlmCancellation tripped() {
        return new LlmCancellation() {
            @Override
            public boolean isCancelled() {
                return true;
            }

            @Override
            public void onCancel(Runnable abort) {
                abort.run();
            }
        };
    }

    private long streamEnds() {
        return emitted.stream().filter(chunk -> chunk.getKind() == LlmStreamChunk.Kind.STREAM_END).count();
    }

    @Test
    @DisplayName("a transport failure after message_stop is swallowed: the answer was already announced whole")
    void failureAfterMessageStopReturnsTheAnswer() {
        mapper.consume(thenTransportFails(messageStart(), textStart(), textDelta("Hel"), textDelta("lo"), blockStop(),
                messageDelta(), messageStop()), tripped());

        final LlmResponse response = aggregator.toLlmResponse();
        assertThat(response.getTextContent()).isEqualTo("Hello");
        assertThat(response.getTokenUsage().getCompletionTokens()).isEqualTo(12);
        assertThat(streamEnds()).isEqualTo(1);
    }

    @Test
    @DisplayName("a transport failure before message_stop propagates and emits no STREAM_END, token live or not")
    void failureBeforeMessageStopPropagates() {
        assertThatThrownBy(() -> mapper.consume(thenTransportFails(messageStart(), textStart(), textDelta("Hel")),
                LlmCancellation.none())).isInstanceOf(AnthropicIoException.class);
        assertThat(streamEnds()).isZero();

        final AnthropicStreamingMapper underATrippedToken = new AnthropicStreamingMapper(sink, new ChunkAggregator(),
                "Anthropic", SILENT, false);
        assertThatThrownBy(() -> underATrippedToken
                .consume(thenTransportFails(messageStart(), textStart(), textDelta("Hel")), tripped()))
                .as("the client's cascade classifies it by the token; the mapper does not")
                .isInstanceOf(AnthropicIoException.class);
        assertThat(streamEnds()).isZero();
    }

    @Test
    @DisplayName("a stream that ends silently before message_stop under a tripped token is a cancellation")
    void silentEndUnderATrippedTokenIsACancellation() {
        assertThatThrownBy(() -> mapper.consume(Stream.of(messageStart(), textStart(), textDelta("Hel")), tripped()))
                .isInstanceOf(LlmCallCancelledException.class);
        assertThat(streamEnds()).isZero();
    }

    @Test
    @DisplayName("a stream that ends silently before message_stop under a live token gets the synthetic end as before")
    void silentEndUnderALiveTokenGetsTheSyntheticEnd() {
        mapper.consume(Stream.of(messageStart(), textStart(), textDelta("Hel")), LlmCancellation.none());

        assertThat(aggregator.toLlmResponse().getTextContent()).isEqualTo("Hel");
        assertThat(streamEnds()).isEqualTo(1);
    }
}
