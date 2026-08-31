package at.aimon.core.llm;

/**
 * Cooperative cancellation signal for an in-flight LLM HTTP call.
 *
 * <p>
 * This is the {@code llm}-package counterpart of {@code at.aimon.core.agent.interrupt.CancellationSignal}. It is
 * defined
 * here — rather than reusing the agent-side signal — because the {@code at.aimon.core.llm} package may only depend on
 * {@code at.aimon.core.base} and {@code at.aimon.core.agent.prompt} (enforced by ArchUnit). Referencing the agent-side
 * signal from {@link LlmClient} would create an {@code llm -> agent.interrupt} back-edge and break the build. The two
 * worlds are joined at the executor boundary by a small adapter.
 *
 * <p>
 * Semantics:
 * <ul>
 * <li><b>Single-shot</b> — once cancelled it stays cancelled.</li>
 * <li><b>Listener firing</b> — a listener registered via {@link #onCancel(Runnable)} runs when the signal is tripped;
 * a listener registered <em>after</em> the signal is already cancelled runs synchronously on the registering
 * thread.</li>
 * <li><b>Off-thread firing</b> — the trip typically arrives on a different thread than the {@code sendMessage} worker
 * (a {@code TaskStop} handler or a parent-cancellation cascade), so a registered abort callback must be thread-safe,
 * idempotent, non-blocking, and must not throw. Typical implementations call
 * {@code StreamResponse.close()} or {@code future.cancel(true)}, both of which satisfy that contract.</li>
 * </ul>
 *
 * <p>
 * Providers that support active abort register their SDK-specific abort lever through {@link #onCancel(Runnable)} at
 * the
 * start of a call. Providers (and gateways/decorators) that do not support abort simply ignore the token and fall back
 * to iteration-boundary cancellation — the default overloads on {@link LlmClient} do exactly this, so no provider is
 * required to change.
 */
public interface LlmCancellation {

    /**
     * Returns a no-op cancellation token that is never cancelled and discards any registered callback. This is the
     * default passed by callers that do not (yet) wire cancellation, preserving the historical iteration-boundary
     * cancellation behaviour.
     *
     * @return the shared no-op token (never {@code null})
     */
    static LlmCancellation none() {
        return NoopLlmCancellation.INSTANCE;
    }

    /**
     * @return {@code true} if cancellation has been requested
     */
    boolean isCancelled();

    /**
     * Reports whether this token can <em>ever</em> observe a cancellation — i.e. whether it is backed by a real signal
     * that could be tripped, as opposed to the inert {@link #none()} token.
     *
     * <p>
     * The default is {@code true}: any token wired to a live signal is cancellable, and returning {@code true} is the
     * safe direction because it merely enables a provider's active-abort path. Only {@link #none()} overrides this to
     * {@code false}.
     *
     * <p>
     * Providers use this as a routing hint. A provider whose <em>only</em> way to make a blocking, non-streaming call
     * abortable is to re-route it through its streaming path (which owns a thread-safe abort lever) should do so
     * <b>only when this returns {@code true}</b>. For a {@link #none()} token there is nothing to abort, so the cheaper
     * single-shot blocking call must be kept unchanged — otherwise every non-cancellation caller (the common case)
     * would be silently converted to a streaming call.
     *
     * @return {@code true} if this token is backed by a real, trippable signal; {@code false} for the inert no-op token
     */
    default boolean isSupported() {
        return true;
    }

    /**
     * Registers a callback to run when this token is cancelled. If the token is <em>already</em> cancelled at
     * registration time, the callback runs immediately and synchronously on the calling thread.
     *
     * <p>
     * The callback must be idempotent, non-blocking, and must not throw — it may be invoked from a thread other than
     * the
     * one that registered it, possibly concurrently with the call it aborts. A typical callback is
     * {@code streamResponse::close}.
     *
     * @param abort
     *            the abort callback (must not be {@code null})
     * @throws NullPointerException
     *             if {@code abort} is {@code null}
     */
    void onCancel(Runnable abort);
}
