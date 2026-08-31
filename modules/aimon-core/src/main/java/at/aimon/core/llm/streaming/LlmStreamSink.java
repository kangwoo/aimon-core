package at.aimon.core.llm.streaming;

/**
 * Callback consumed by LLM provider clients during streaming calls.
 *
 * <p>
 * Provider clients invoke {@link #accept(LlmStreamChunk)} once per chunk parsed from the underlying HTTP / SSE /
 * WebSocket stream. The sink is a lightweight functional interface so callers can compose decorators (e.g.,
 * {@link BufferingStreamSink}, attempt-aware wrappers inside {@link at.aimon.core.llm.invoke.LlmCallGateway}) without
 * introducing reactive dependencies.
 *
 * <p>
 * <b>Lifecycle expectations</b>:
 * <ul>
 * <li>Zero or more {@link LlmStreamChunk.Kind#TEXT_DELTA} chunks (chronologically increasing {@code index}).</li>
 * <li>Exactly one terminal {@link LlmStreamChunk.Kind#STREAM_END} chunk per successful stream.</li>
 * <li>On provider error, the streaming call itself throws — the sink is not expected to receive a stream-end.</li>
 * </ul>
 *
 * <p>
 * Implementations <b>must</b> be safe to call from any thread, though providers typically invoke the sink from a single
 * thread per call. Backpressure is out of scope for the current design; see
 * {@code docs/design/llm/streaming.md} §9 for the future {@code Flow.Publisher}
 * migration path.
 */
@FunctionalInterface
public interface LlmStreamSink {

    /**
     * Consumes a single chunk.
     *
     * @param chunk
     *            non-null chunk
     */
    void accept(LlmStreamChunk chunk);

    /**
     * Returns a sink that discards every chunk. Used when a caller re-routes a request through the streaming path
     * purely to obtain the streaming path's abort lever (see the providers' non-streaming cancellation overload) and
     * only wants the aggregated final response, not incremental delivery.
     *
     * @return a shared no-op sink (never {@code null})
     */
    static LlmStreamSink discarding() {
        return chunk -> {
        };
    }
}
