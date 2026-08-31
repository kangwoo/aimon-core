package at.aimon.core.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the {@link LlmCancellation#none()} no-op contract that every non-cancellation code path relies on: it never
 * reports cancelled and never fires (nor retains) the abort it is handed.
 */
@DisplayName("LlmCancellation.none() (NoopLlmCancellation)")
class NoopLlmCancellationTest {

    @Test
    @DisplayName("is a shared singleton")
    void isSingleton() {
        assertThat(LlmCancellation.none()).isSameAs(LlmCancellation.none());
    }

    @Test
    @DisplayName("never reports cancelled")
    void neverCancelled() {
        assertThat(LlmCancellation.none().isCancelled()).isFalse();
    }

    @Test
    @DisplayName("onCancel drops the abort — it is never run")
    void onCancelDropsAbort() {
        final AtomicBoolean fired = new AtomicBoolean(false);

        LlmCancellation.none().onCancel(() -> fired.set(true));

        assertThat(fired).as("noop cancellation must never run the abort").isFalse();
    }

    @Test
    @DisplayName("onCancel still rejects a null abort")
    void onCancelRejectsNull() {
        assertThatThrownBy(() -> LlmCancellation.none().onCancel(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("is not supported — providers must keep the cheap blocking path for it")
    void isNotSupported() {
        assertThat(LlmCancellation.none().isSupported())
                .as("the inert no-op token must never trigger streaming re-routing").isFalse();
    }

    @Test
    @DisplayName("a real (trippable) token reports supported by default")
    void realTokenSupportedByDefault() {
        // A minimal token backed by a real flag inherits the default isSupported() == true, so providers route it
        // through the abortable streaming path.
        final LlmCancellation trippable = new LlmCancellation() {
            @Override
            public boolean isCancelled() {
                return false;
            }

            @Override
            public void onCancel(Runnable abort) {
                // identity/contract test only — no real abort lever
            }
        };

        assertThat(trippable.isSupported()).isTrue();
    }
}
