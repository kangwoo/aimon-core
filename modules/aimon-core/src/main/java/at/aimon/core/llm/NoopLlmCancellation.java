package at.aimon.core.llm;

import java.util.Objects;

/**
 * No-op {@link LlmCancellation} that is never cancelled and drops every registered callback. Obtained via
 * {@link LlmCancellation#none()}; this is the default token used wherever cancellation is not wired, so the historical
 * iteration-boundary cancellation behaviour is preserved with zero overhead.
 */
final class NoopLlmCancellation implements LlmCancellation {

    static final NoopLlmCancellation INSTANCE = new NoopLlmCancellation();

    private NoopLlmCancellation() {
    }

    @Override
    public boolean isCancelled() {
        return false;
    }

    @Override
    public boolean isSupported() {
        // This token is never trippable, so a provider must not re-route a blocking call through streaming on its
        // account — keep the cheaper single-shot path.
        return false;
    }

    @Override
    public void onCancel(Runnable abort) {
        // The token can never be cancelled, so the callback would never fire — drop it (but honour the null-check
        // contract so a wiring bug surfaces the same way regardless of which token is in use).
        Objects.requireNonNull(abort, "abort");
    }
}
