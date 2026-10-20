package at.aimon.core.llms.anthropic;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import com.anthropic.core.http.HttpResponse;

/**
 * One logical LLM call's abort lever: created before the SDK call goes out, released after it returns.
 *
 * <p>
 * The lever is whichever of two things the SDK's OkHttp adapter sanctions another thread using against the attempt in
 * flight. During the header wait it is the attempt's {@link CompletableFuture}, whose {@code cancel(true)} makes the
 * adapter cancel its {@code okhttp3.Call}. Once the headers are in it is the adapter's {@link HttpResponse}, whose
 * {@code close()} is documented race-safe: it cancels the call if a read is in flight, and closes the body itself
 * otherwise. That adapter response is the only thing this object ever closes — never the SDK's {@code StreamResponse}
 * or its reader: closing those is the consumer thread's job, after its read has unwound.
 *
 * <p>
 * Two threads meet here. The transport decorator ({@link AnthropicAbortableHttpClient}) attaches once per attempt —
 * the SDK retries inside one logical call, so the attempt changes while the abort object does not — and the
 * cancelling thread calls {@link #abort()} at most usefully once. An attach stores the lever and then reads the
 * state; {@code abort} moves the state and then reads the levers. Whichever order the two interleave in, one of them
 * sees the other's write, so an abort that lands while an attempt is being created still cancels that attempt. Both
 * levers are idempotent, which is what lets both sides act without a lock.
 *
 * <p>
 * An abort object only ever holds levers of <em>its own</em> call. That invariant is what makes a stale
 * {@link #abort()} — the executor's single-listener bridge reading the previous call's lever just before
 * {@code clearAbort()} — a no-op rather than a cut through the next call: after {@link #complete()} or
 * {@link #release()} the state is terminal and the levers are dropped.
 *
 * <p>
 * Thread-safe; every method throws nothing, and {@link #abort()} is bounded rather than instant. When no read is in
 * flight, the lever closes the adapter's race-safe body on the calling thread, and on HTTP/1.1 OkHttp's chunked-source
 * discard can take up to about 100 ms there; otherwise it returns at once.
 */
final class AnthropicCallAbort {

    /**
     * The request header that correlates a call with its abort object across the SDK's layers. Put on the SDK params
     * by the streaming path, read and stripped by the transport decorator before the SDK's adapter sees the request.
     */
    static final String HEADER = "X-Aimon-Abort-Key";

    /** The call's lifecycle as the lever sees it. */
    enum State {
        /** The call is running, or about to: an abort cuts its current attempt. */
        OPEN,
        /** The call was aborted; any lever attached from now on is pulled at once. */
        ABORTED,
        /** The call returned normally; an abort has nothing left to do. */
        COMPLETED,
        /** The client is done with the call; the levers are dropped. */
        RELEASED
    }

    private final String key;
    private final AtomicReference<State> state = new AtomicReference<>(State.OPEN);
    private final AtomicReference<CompletableFuture<HttpResponse>> attempt = new AtomicReference<>();
    private final AtomicReference<HttpResponse> response = new AtomicReference<>();

    AnthropicCallAbort(String key) {
        this.key = Objects.requireNonNull(key, "key");
    }

    /** The opaque, per-call key carried by {@link #HEADER}. */
    String key() {
        return key;
    }

    State state() {
        return state.get();
    }

    boolean isAborted() {
        return state.get() == State.ABORTED;
    }

    /**
     * Cuts the current attempt's transport. Any thread; idempotent; never throws; bounded (up to about 100 ms on
     * HTTP/1.1, when it closes a body with no read in flight).
     *
     * <p>
     * A no-op after {@link #complete()} or {@link #release()}: a completed answer cannot be un-completed, and a
     * released object holds nothing to cut.
     */
    void abort() {
        if (!state.compareAndSet(State.OPEN, State.ABORTED)) {
            return;
        }
        pull(attempt.get(), response.get());
    }

    /**
     * Makes {@code attempt} the lever for the header phase. Called by the transport decorator for every attempt of
     * this call, including the SDK's retries. If the call has already been aborted, the attempt is cancelled right
     * here — the decorator then fails it without waiting.
     */
    void attachAttempt(CompletableFuture<HttpResponse> attempt) {
        Objects.requireNonNull(attempt, "attempt");
        this.attempt.set(attempt);
        if (state.get() == State.ABORTED) {
            attempt.cancel(true);
        }
    }

    /**
     * Makes {@code response} the lever for the body phase. Called by the transport decorator once an attempt's
     * headers are in. If the call has already been aborted, the response is closed right here — the decorator then
     * fails the attempt rather than returning it.
     */
    void attachResponse(HttpResponse response) {
        Objects.requireNonNull(response, "response");
        this.response.set(response);
        if (state.get() == State.ABORTED) {
            closeQuietly(response);
        }
    }

    /** Consumer thread, after the stream was drained normally: a later abort has nothing to do. */
    void complete() {
        state.compareAndSet(State.OPEN, State.COMPLETED);
    }

    /** The client's {@code finally}: the call is over, whichever way it ended; drop the levers. */
    void release() {
        state.set(State.RELEASED);
        attempt.set(null);
        response.set(null);
    }

    private static void pull(CompletableFuture<HttpResponse> attempt, HttpResponse response) {
        if (attempt != null) {
            // A completed future ignores this; the response lever covers that phase.
            attempt.cancel(true);
        }
        if (response != null) {
            closeQuietly(response);
        }
    }

    /** The adapter's close is race-safe and idempotent; a throw here would break the lever's no-throw contract. */
    private static void closeQuietly(HttpResponse response) {
        try {
            response.close();
        } catch (RuntimeException e) {
            // The read on the consumer thread fails on its own once the call is cancelled or the body is closed.
        }
    }

    @Override
    public String toString() {
        return "AnthropicCallAbort{key=" + key + ", state=" + state.get() + "}";
    }
}
