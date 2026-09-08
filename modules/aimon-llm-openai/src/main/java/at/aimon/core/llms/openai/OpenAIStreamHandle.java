package at.aimon.core.llms.openai;

import java.util.function.Consumer;
import java.util.stream.Stream;

import com.openai.core.http.StreamResponse;

import at.aimon.core.llm.streaming.LlmStreamSink;

/**
 * A live SDK stream with its element type erased, so {@link OpenAILlmClient}'s streaming plumbing is written once.
 *
 * <p>
 * {@code StreamResponse<ChatCompletionChunk>} and {@code StreamResponse<ResponseStreamEvent>} have no useful common
 * supertype. Erasing the generic behind these two methods is what lets the client keep <em>one</em>
 * try-with-resources, <em>one</em> {@code cancellation.onCancel(...)} registration and <em>one</em> catch cascade —
 * which is how the four preserved behaviours survive a second endpoint rather than being re-implemented next to it.
 *
 * <p>
 * There is deliberately no {@code toLlmResponse()} here: the aggregator is owned by the client, which still ends with
 * its own {@code return aggregator.toLlmResponse()} outside the try.
 */
interface OpenAIStreamHandle extends AutoCloseable {

    /**
     * Drains the stream through the endpoint's mapper, feeding the caller's {@link LlmStreamSink} and the client's
     * aggregator. Throws nothing of its own: a provider failure propagates so the client's cascade classifies it.
     */
    void consume();

    /**
     * Aborts and releases the stream.
     *
     * <p>
     * Delegates to {@link StreamResponse#close()}, which cancels the underlying OkHttp call and is thread-safe and
     * idempotent — the property the cancellation lever is built on.
     */
    @Override
    void close();

    /**
     * Wraps one SDK stream and the consumer that drains it.
     *
     * @param <T>
     *            the SDK's stream element type, which does not escape this method
     * @param streamResponse
     *            the open stream (must not be null)
     * @param consumer
     *            the endpoint's mapper entry point (must not be null)
     * @return a handle over that stream
     */
    static <T> OpenAIStreamHandle of(StreamResponse<T> streamResponse, Consumer<Stream<T>> consumer) {
        return new OpenAIStreamHandle() {
            @Override
            public void consume() {
                consumer.accept(streamResponse.stream());
            }

            @Override
            public void close() {
                streamResponse.close();
            }
        };
    }
}
