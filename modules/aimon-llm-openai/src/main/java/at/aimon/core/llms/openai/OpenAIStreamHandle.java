package at.aimon.core.llms.openai;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Stream;

import com.openai.core.http.StreamResponse;

import at.aimon.core.llm.streaming.LlmStreamSink;

/**
 * A live SDK stream with its element type erased, so {@link OpenAILlmClient}'s streaming plumbing is written once.
 *
 * <p>
 * {@code StreamResponse<ChatCompletionChunk>} and {@code StreamResponse<ResponseStreamEvent>} have no useful common
 * supertype. Erasing the generic behind these methods is what lets the client keep <em>one</em> consume-and-close
 * sequence, <em>one</em> {@code cancellation.onCancel(...)} registration and <em>one</em> catch cascade — which is how
 * the preserved behaviours survive a second endpoint rather than being re-implemented next to it.
 *
 * <p>
 * The handle has two ways of ending a stream, and they are for two different threads. {@link #abort()} is the
 * cancellation lever: any thread, at any time, and it cuts only the call's transport. {@link #close()} is the
 * consumer thread's cleanup, run exactly once after {@link #consume()} returned or threw — also after an abort,
 * because cancelling a call closes its socket but releases none of what the SDK still holds (its connection, its call
 * timeout, its reader and response). The two never run the same code: a close from the cancelling thread would close
 * the response body under the consumer's in-flight read, which is the race this split exists to remove.
 *
 * <p>
 * There is deliberately no {@code toLlmResponse()} here: the aggregator is owned by the client, which still ends with
 * its own {@code return aggregator.toLlmResponse()} after the call.
 */
interface OpenAIStreamHandle extends AutoCloseable {

    /**
     * Drains the stream through the endpoint's mapper, feeding the caller's {@link LlmStreamSink} and the client's
     * aggregator, and marks the call complete when the drain returns normally — from then on an abort has nothing to
     * do. Throws nothing of its own: a provider failure propagates so the client's cascade classifies it.
     */
    void consume();

    /**
     * Cuts the call's transport: the current attempt's {@code okhttp3.Call} is cancelled and nothing else is touched.
     * Any thread; thread-safe, idempotent, non-blocking, never throws. A no-op once {@link #consume()} has returned
     * normally, and always for a call whose token can never fire (it has no abort object).
     */
    void abort();

    /**
     * Releases the stream: {@code StreamResponse.close()} exactly once, on the consumer thread only, after the
     * consumer's own read has unwound. Never the cancellation lever — see the class Javadoc.
     */
    @Override
    void close();

    /**
     * Wraps one SDK stream, the consumer that drains it, and the abort object of the call it belongs to.
     *
     * @param <T>
     *            the SDK's stream element type, which does not escape this method
     * @param abort
     *            the call's abort object, opened before the request was sent, or {@code null} for a call whose token
     *            can never fire: the handle's {@code abort()} then has nothing to cut
     * @param streamResponse
     *            the open stream (must not be null)
     * @param consumer
     *            the endpoint's mapper entry point (must not be null)
     * @return a handle over that stream
     */
    static <T> OpenAIStreamHandle of(OpenAICallAbort abort, StreamResponse<T> streamResponse,
            Consumer<Stream<T>> consumer) {
        Objects.requireNonNull(streamResponse, "streamResponse");
        Objects.requireNonNull(consumer, "consumer");
        return new OpenAIStreamHandle() {
            private final AtomicBoolean closed = new AtomicBoolean();

            @Override
            public void consume() {
                consumer.accept(streamResponse.stream());
                if (abort != null) {
                    abort.complete();
                }
            }

            @Override
            public void abort() {
                if (abort != null) {
                    abort.abort();
                }
            }

            @Override
            public void close() {
                if (closed.compareAndSet(false, true)) {
                    streamResponse.close();
                }
            }
        };
    }
}
