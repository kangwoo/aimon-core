package at.aimon.core.agent.compact;

import at.aimon.core.llm.LlmCallMetadata;

/**
 * Hears of each compaction attempt from the inside: when it begins, the metadata its summary calls carry, and how it
 * ended.
 *
 * <p>
 * The compaction decision is taken inside the context engine and the guard, and only there is it known that an attempt
 * is about to run, which summary calls belong to it, and — after the summary is installed — what the view came to. The
 * caller hands an observer in with the request ({@code ContextRequest}, {@link CompactionGuardRequest}), and the code
 * that performs the attempt calls {@link #begin} just before its first summary call (or elision), passes each summary
 * call's metadata through {@link Attempt#enrich}, and {@linkplain Attempt#record records} the result once it is
 * final. A pass that ends without attempting anything — nothing to compact, the circuit breaker open, a concurrent
 * compaction, a warning — never calls {@code begin}.
 *
 * <p>
 * The tracing executors use this seam to open a {@code COMPACTION} span around each attempt and nest its summary LLM
 * span under it; nothing here depends on tracing.
 *
 * <p>
 * <b>Fail-safe contract:</b> an observer must never break a compaction. The request builders wrap whatever they are
 * given in {@link #failSafe}, which swallows an observer's runtime exceptions and falls back to {@link #none()}
 * behaviour, so engines may call it without guarding each call.
 */
@FunctionalInterface
public interface CompactionObserver {

    /**
     * Begins one attempt.
     *
     * @param start
     *            what is about to run (must not be null)
     * @return the attempt, to be closed once the compaction ends (never null)
     */
    Attempt begin(CompactionStart start);

    /**
     * Returns the observer that observes nothing: its attempts leave metadata as it was — the same instance, a
     * {@code null} staying {@code null} — and record nothing. The default of every request.
     *
     * @return the shared no-op observer (never null)
     */
    static CompactionObserver none() {
        return CompactionObservers.NONE;
    }

    /**
     * Wraps {@code observer} so that a runtime exception it throws — from {@code begin} or from any method of an
     * attempt — is logged and swallowed: a failing {@code begin} yields a {@link #none()} attempt, a failing
     * {@code enrich} returns its argument. Idempotent: {@link #none()} and an already wrapped observer are returned as
     * they are.
     *
     * @param observer
     *            the observer to wrap (must not be null)
     * @return the fail-safe observer (never null)
     */
    static CompactionObserver failSafe(CompactionObserver observer) {
        return CompactionObservers.failSafe(observer);
    }

    /**
     * One compaction attempt. Closing it without having {@linkplain #record recorded} a result reports an attempt that
     * did not complete — the engine threw.
     */
    interface Attempt extends AutoCloseable {

        /**
         * Returns the metadata a summary call of this attempt should carry, so the call is seen as part of it.
         *
         * @param base
         *            the caller's metadata for the summary call, or {@code null} when it has none
         * @return the metadata to send; {@link CompactionObserver#none()} returns {@code base} itself
         */
        LlmCallMetadata enrich(LlmCallMetadata base);

        /**
         * Records how the attempt ended.
         *
         * @param result
         *            the attempt's final result (must not be null)
         * @return {@code result}, so a return statement can pass through
         */
        CompactionResult record(CompactionResult result);

        /**
         * Ends the attempt. Idempotent; never throws.
         */
        @Override
        void close();
    }
}
