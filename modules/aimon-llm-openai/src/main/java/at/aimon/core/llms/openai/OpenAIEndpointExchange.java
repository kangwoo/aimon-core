package at.aimon.core.llms.openai;

import com.openai.core.RequestOptions;

import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.streaming.ChunkAggregator;
import at.aimon.core.llm.streaming.LlmStreamSink;

/**
 * Everything that differs between {@code /v1/chat/completions} and {@code /v1/responses}, for one request.
 *
 * <p>
 * Exactly four things vary by endpoint: how the params are built, how the blocking call is made, how the stream is
 * opened and consumed, and how the result becomes an {@link LlmResponse}. Params are built when the implementation is
 * constructed — at the same point in {@link OpenAILlmClient} that {@code buildRequest} used to run, i.e.
 * <em>before</em>
 * the streaming path's try-with-resources — so a failure while building them escapes exactly as it does today rather
 * than being accidentally improved or worsened.
 *
 * <p>
 * <strong>Neither implementation catches anything.</strong> Every failure lands in the client's existing three-way
 * cascade, which is the only place {@code OpenAIExceptionMapper.map} is called on this path. That is what makes the
 * mid-stream classification behaviour survive a second endpoint structurally rather than by promise.
 */
interface OpenAIEndpointExchange {

    /**
     * Makes the blocking call and converts its result.
     *
     * @param options
     *            per-request options, or {@code null} to keep the single-argument SDK overload so the client-wide
     *            default timeout applies unchanged
     * @return the converted response (never null)
     */
    LlmResponse callBlocking(RequestOptions options);

    /**
     * Opens the streaming call.
     *
     * <p>
     * The sink and aggregator are arguments rather than constructor state because the blocking path has neither, and a
     * field that is only meaningful on one of two paths is a null waiting to be dereferenced.
     *
     * @param options
     *            per-request options, or {@code null} for the single-argument overload
     * @param sink
     *            the caller's stream sink (must not be null)
     * @param aggregator
     *            the client's aggregator, which builds the final response (must not be null)
     * @return an open stream handle (never null)
     */
    OpenAIStreamHandle openStream(RequestOptions options, LlmStreamSink sink, ChunkAggregator aggregator);
}
