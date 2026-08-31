package at.aimon.core.llm.streaming;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Sink decorator that buffers chunks until an explicit {@link #flush()} or discards them on {@link #abort()}.
 *
 * <p>
 * Used by {@link at.aimon.core.llm.invoke.LlmCallGateway} when
 * {@link LlmStreamingOptions#isBufferUntilFirstSuccess()} is {@code true}: chunks produced by a given attempt are
 * staged here and only forwarded to the outer sink if the attempt succeeds. On retry, the buffered chunks are dropped
 * and a fresh buffer is allocated.
 *
 * <p>
 * Instances are single-use: once {@link #flush()} or {@link #abort()} has been called, further {@link #accept} calls
 * throw {@link IllegalStateException}.
 */
public final class BufferingStreamSink implements LlmStreamSink {

    private final LlmStreamSink downstream;
    private final Object lock = new Object();
    private final List<LlmStreamChunk> buffer = new ArrayList<>();
    private boolean terminated;

    public BufferingStreamSink(LlmStreamSink downstream) {
        this.downstream = Objects.requireNonNull(downstream, "downstream");
    }

    @Override
    public void accept(LlmStreamChunk chunk) {
        Objects.requireNonNull(chunk, "chunk");
        synchronized (lock) {
            if (terminated) {
                throw new IllegalStateException("BufferingStreamSink is already terminated");
            }
            buffer.add(chunk);
        }
    }

    /**
     * Forwards all buffered chunks to the downstream sink in arrival order, then marks this sink as terminated.
     */
    public void flush() {
        final List<LlmStreamChunk> toFlush;
        synchronized (lock) {
            if (terminated) {
                throw new IllegalStateException("BufferingStreamSink is already terminated");
            }
            terminated = true;
            toFlush = new ArrayList<>(buffer);
            buffer.clear();
        }
        for (LlmStreamChunk chunk : toFlush) {
            downstream.accept(chunk);
        }
    }

    /**
     * Discards any buffered chunks and marks this sink as terminated.
     */
    public void abort() {
        synchronized (lock) {
            terminated = true;
            buffer.clear();
        }
    }

    /**
     * @return the number of chunks currently buffered (for tests / diagnostics).
     */
    public int bufferedCount() {
        synchronized (lock) {
            return buffer.size();
        }
    }

    /**
     * @return whether {@link #flush()} or {@link #abort()} has been invoked.
     */
    public boolean isTerminated() {
        synchronized (lock) {
            return terminated;
        }
    }
}
