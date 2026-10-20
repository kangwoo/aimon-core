package at.aimon.core.llms.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.openai.core.ObjectMappers;
import com.openai.errors.OpenAIIoException;
import com.openai.models.chat.completions.ChatCompletionChunk;

import at.aimon.core.llm.LlmCancellation;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.exception.LlmCallCancelledException;
import at.aimon.core.llm.streaming.ChunkAggregator;
import at.aimon.core.llm.streaming.LlmStreamChunk;
import at.aimon.core.llm.streaming.LlmStreamSink;

/**
 * How the Chat Completions mapper classifies a stream that does not reach {@code [DONE]}: by the token before the
 * {@code finish_reason} chunk, by nothing after it.
 */
@DisplayName("OpenAIStreamingMapper - a stream cut short")
class OpenAIStreamingMapperTest {

    private final List<LlmStreamChunk> emitted = new ArrayList<>();
    private final LlmStreamSink sink = emitted::add;
    private final ChunkAggregator aggregator = new ChunkAggregator();
    private final OpenAIStreamingMapper mapper = new OpenAIStreamingMapper(sink, aggregator);

    private static ChatCompletionChunk chunk(String json) {
        try {
            return ObjectMappers.jsonMapper().readValue(json, ChatCompletionChunk.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Malformed fixture: " + json, e);
        }
    }

    private static ChatCompletionChunk delta(String text) {
        return chunk("{\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"m\","
                + "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"" + text + "\"},\"finish_reason\":null}]}");
    }

    private static ChatCompletionChunk finish() {
        return chunk("{\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"m\","
                + "\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}");
    }

    private static ChatCompletionChunk usage() {
        return chunk("{\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"m\","
                + "\"choices\":[],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2,\"total_tokens\":5}}");
    }

    /** The given chunks, then the SDK's transport failure — what a cut socket looks like out of the SDK's stream. */
    private static Stream<ChatCompletionChunk> thenTransportFails(ChatCompletionChunk... chunks) {
        return Stream.concat(Stream.of(chunks), Stream.generate(() -> {
            throw new OpenAIIoException("Stream failed", new IOException("Canceled"));
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
    @DisplayName("a transport failure after finish_reason and usage is swallowed: the answer is whole")
    void failureAfterTerminalEventReturnsTheAnswer() {
        mapper.consume(thenTransportFails(delta("Hel"), delta("lo"), finish(), usage()), tripped());

        final LlmResponse response = aggregator.toLlmResponse();
        assertThat(response.getTextContent()).isEqualTo("Hello");
        assertThat(response.getTokenUsage().getTotalTokens()).isEqualTo(5);
        assertThat(streamEnds()).isEqualTo(1);
    }

    @Test
    @DisplayName("a transport failure between finish_reason and the usage chunk returns the text with empty usage")
    void failureBeforeTheUsageChunkReturnsTheTextWithEmptyUsage() {
        mapper.consume(thenTransportFails(delta("Hello"), finish()), LlmCancellation.none());

        final LlmResponse response = aggregator.toLlmResponse();
        assertThat(response.getTextContent()).isEqualTo("Hello");
        assertThat(response.getTokenUsage().getTotalTokens()).isZero();
        assertThat(streamEnds()).isEqualTo(1);
    }

    @Test
    @DisplayName("a transport failure before any finish_reason propagates and emits no STREAM_END, token live or not")
    void failureBeforeTerminalEventPropagates() {
        assertThatThrownBy(() -> mapper.consume(thenTransportFails(delta("Hel")), LlmCancellation.none()))
                .isInstanceOf(OpenAIIoException.class);
        assertThat(streamEnds()).isZero();

        final OpenAIStreamingMapper underATrippedToken = new OpenAIStreamingMapper(sink, new ChunkAggregator());
        assertThatThrownBy(() -> underATrippedToken.consume(thenTransportFails(delta("Hel")), tripped()))
                .as("the client's cascade classifies it by the token; the mapper does not")
                .isInstanceOf(OpenAIIoException.class);
        assertThat(streamEnds()).isZero();
    }

    @Test
    @DisplayName("a stream that ends silently before finish_reason under a tripped token is a cancellation")
    void silentEndUnderATrippedTokenIsACancellation() {
        assertThatThrownBy(() -> mapper.consume(Stream.of(delta("Hel")), tripped()))
                .isInstanceOf(LlmCallCancelledException.class);
        assertThat(streamEnds()).isZero();
    }

    @Test
    @DisplayName("a stream that ends silently before finish_reason under a live token ends the answer as before")
    void silentEndUnderALiveTokenEndsTheAnswer() {
        final AtomicBoolean asked = new AtomicBoolean();
        mapper.consume(Stream.of(delta("Hel")), new LlmCancellation() {
            @Override
            public boolean isCancelled() {
                asked.set(true);
                return false;
            }

            @Override
            public void onCancel(Runnable abort) {
            }
        });

        assertThat(asked).isTrue();
        assertThat(aggregator.toLlmResponse().getTextContent()).isEqualTo("Hel");
        assertThat(streamEnds()).isEqualTo(1);
    }
}
