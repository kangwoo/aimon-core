package at.aimon.core.llms.openai;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.Call;

/**
 * One logical LLM call's abort lever: created before the SDK call goes out, released after it returns.
 *
 * <p>
 * The lever is {@link Call#cancel()} on the attempt currently in flight — the only thing OkHttp sanctions one thread
 * doing to a call another thread is reading. It closes the socket; the blocked read on the consumer thread fails with
 * {@code IOException("Canceled")} and unwinds on its own. Nothing here ever touches the SDK's stream or its reader:
 * closing those is the consumer thread's job ({@link OpenAIStreamHandle#close()}), after the read has unwound.
 *
 * <p>
 * Two threads meet here. The transport hook ({@link OpenAIAbortableHttpClient}) calls {@link #attach(Call)} once per
 * attempt — the SDK retries inside one logical call, so the attempt changes while the abort object does not — and the
 * cancelling thread calls {@link #abort()} at most usefully once. {@code attach} stores the lever and then reads the
 * state; {@code abort} moves the state and then reads the lever. Whichever order the two interleave in, one of them
 * sees the other's write, so an abort that lands while an attempt is being created still cancels that attempt. A
 * second cancel of the same call is harmless, which is what lets both sides act without a lock.
 *
 * <p>
 * An abort object only ever holds levers of <em>its own</em> call. That invariant is what makes a stale
 * {@link #abort()} — the executor's single-listener bridge reading the previous call's lever just before
 * {@code clearAbort()} — a no-op rather than a cut through the next call: after {@link #complete()} or
 * {@link #release()} the state is terminal and the lever is dropped.
 *
 * <p>
 * Thread-safe; every method is non-blocking and throws nothing.
 */
final class OpenAICallAbort {

    /**
     * The request header that correlates a call with its abort object across the SDK's layers. Put on the SDK params
     * by the streaming path, read and stripped by the transport before anything reaches the wire.
     */
    static final String HEADER = "X-Aimon-Abort-Key";

    /** The call's lifecycle as the lever sees it. */
    enum State {
        /** The call is running, or about to: an abort cuts its current attempt. */
        OPEN,
        /** The call was aborted; any attempt attached from now on is cancelled at once. */
        ABORTED,
        /** The call returned normally; an abort has nothing left to do. */
        COMPLETED,
        /** The client is done with the call; the lever is dropped. */
        RELEASED
    }

    private final String key;
    private final AtomicReference<State> state = new AtomicReference<>(State.OPEN);
    private final AtomicReference<Call> call = new AtomicReference<>();

    OpenAICallAbort(String key) {
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
     * Cuts the current attempt's transport. Any thread; idempotent; returns in microseconds; never throws.
     *
     * <p>
     * A no-op after {@link #complete()} or {@link #release()}: a completed answer cannot be un-completed, and a
     * released object holds nothing to cut.
     */
    void abort() {
        if (!state.compareAndSet(State.OPEN, State.ABORTED)) {
            return;
        }
        final Call current = call.get();
        if (current != null) {
            current.cancel();
        }
    }

    /**
     * Makes {@code attempt} the lever. Called by the transport hook for every attempt of this call, including the
     * SDK's retries. If the call has already been aborted, the attempt is cancelled right here — the hook then fails
     * it without a byte on the wire.
     */
    void attach(Call attempt) {
        Objects.requireNonNull(attempt, "attempt");
        call.set(attempt);
        if (state.get() == State.ABORTED) {
            attempt.cancel();
        }
    }

    /** Consumer thread, after the stream was drained normally: a later abort has nothing to do. */
    void complete() {
        state.compareAndSet(State.OPEN, State.COMPLETED);
    }

    /** The client's {@code finally}: the call is over, whichever way it ended; drop the lever. */
    void release() {
        state.set(State.RELEASED);
        call.set(null);
    }

    @Override
    public String toString() {
        return "OpenAICallAbort{key=" + key + ", state=" + state.get() + "}";
    }
}
