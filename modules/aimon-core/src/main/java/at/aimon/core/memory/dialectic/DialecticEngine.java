package at.aimon.core.memory.dialectic;

import java.util.Objects;
import java.util.Optional;

import at.aimon.core.llm.streaming.LlmStreamChunk;
import at.aimon.core.llm.streaming.LlmStreamSink;

/**
 * Answers natural-language questions about a peer using stored observations.
 *
 * <p>
 * The engine is the read-side counterpart to {@link
 * at.aimon.core.memory.deriver.Deriver}: where the deriver writes observations
 * from conversation history, the dialectic engine reads them to answer
 * questions like "what does Alice prefer for tea?". Implementations differ in
 * how they retrieve and reason over observations — the stage 3 single-shot
 * implementation prefetches by semantic search and concatenates the results
 * into the system prompt, while later ReAct implementations will iterate
 * through retrieval tools (design doc §6.2).
 *
 * <p>
 * The interface is small on purpose: callers either get the full response in
 * one call ({@link #query}) or stream the answer chunk-by-chunk
 * ({@link #queryStream}). The default {@code queryStream} implementation falls
 * back to the synchronous path and emits a single {@code TEXT_DELTA} chunk
 * followed by {@code STREAM_END} — provider implementations are free to
 * override with a true streaming call.
 */
public interface DialecticEngine {

    /**
     * Answers {@code query} synchronously.
     *
     * @return the response with the answer and the observations the engine
     *         considered
     */
    DialecticResponse query(DialecticQuery query);

    /**
     * Streams the answer for {@code query} into {@code sink}.
     *
     * <p>
     * The default implementation calls {@link #query} and emits the resulting
     * answer as a single {@link LlmStreamChunk.Kind#TEXT_DELTA} chunk followed
     * by exactly one {@link LlmStreamChunk.Kind#STREAM_END} chunk. Engines that
     * support true streaming should override this method.
     */
    default void queryStream(DialecticQuery query, LlmStreamSink sink) {
        Objects.requireNonNull(query, "query cannot be null");
        Objects.requireNonNull(sink, "sink cannot be null");
        DialecticResponse response = query(query);
        int index = 0;
        if (!response.getAnswer().isEmpty()) {
            sink.accept(LlmStreamChunk.textDelta(index++, response.getAnswer()));
        }
        sink.accept(LlmStreamChunk.streamEnd(index, response.getTokenUsage(), Optional.empty()));
    }
}
