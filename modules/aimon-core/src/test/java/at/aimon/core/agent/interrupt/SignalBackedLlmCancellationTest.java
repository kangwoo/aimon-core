package at.aimon.core.agent.interrupt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Behavioural tests for {@link SignalBackedLlmCancellation}, the adapter that bridges an execution's
 * {@link CancellationSignal} to the llm-side {@link at.aimon.core.llm.LlmCancellation} so a trip can actively abort the
 * in-flight LLM call.
 *
 * <p>
 * The headline guarantee is the <b>single-listener invariant</b>: no matter how many LLM calls an execution issues
 * (each swapping
 * in its
 * own abort via {@link SignalBackedLlmCancellation#onCancel(Runnable)}), the adapter registers <em>exactly one</em>
 * listener on the underlying signal — it does not leak a listener per call.
 */
@DisplayName("SignalBackedLlmCancellation (LLM-cancellation bridge)")
class SignalBackedLlmCancellationTest {

    @Test
    @DisplayName("registers exactly one signal listener regardless of how many aborts are swapped in")
    void registersExactlyOneListener() {
        final CountingSignal signal = new CountingSignal();

        final SignalBackedLlmCancellation bridge = new SignalBackedLlmCancellation(signal);
        // Simulate a long execution: many successive LLM calls each register their own abort lever.
        for (int i = 0; i < 50; i++) {
            bridge.onCancel(() -> {
            });
            bridge.clearAbort();
        }

        assertThat(signal.listenerCount()).as("bridge must add exactly one listener for the whole execution")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("isCancelled delegates to the underlying signal")
    void isCancelledDelegates() {
        final DefaultCancellationSignal signal = new DefaultCancellationSignal();
        final SignalBackedLlmCancellation bridge = new SignalBackedLlmCancellation(signal);

        assertThat(bridge.isCancelled()).isFalse();
        signal.trip(InterruptReason.USER_SIGINT);
        assertThat(bridge.isCancelled()).isTrue();
    }

    @Test
    @DisplayName("a trip fires the currently-active abort")
    void tripFiresActiveAbort() {
        final DefaultCancellationSignal signal = new DefaultCancellationSignal();
        final SignalBackedLlmCancellation bridge = new SignalBackedLlmCancellation(signal);
        final AtomicInteger fired = new AtomicInteger();

        bridge.onCancel(fired::incrementAndGet);
        assertThat(fired).hasValue(0);

        signal.trip(InterruptReason.USER_SIGINT);

        assertThat(fired).as("the active abort must fire once when the signal trips").hasValue(1);
    }

    @Test
    @DisplayName("swapping the abort means only the latest fires on trip")
    void swappedAbortOnlyLatestFires() {
        final DefaultCancellationSignal signal = new DefaultCancellationSignal();
        final SignalBackedLlmCancellation bridge = new SignalBackedLlmCancellation(signal);
        final AtomicInteger first = new AtomicInteger();
        final AtomicInteger second = new AtomicInteger();

        bridge.onCancel(first::incrementAndGet);
        bridge.onCancel(second::incrementAndGet); // second call (next LLM call) supersedes the first

        signal.trip(InterruptReason.USER_SIGINT);

        assertThat(first).as("the superseded abort must not fire").hasValue(0);
        assertThat(second).as("only the latest abort fires").hasValue(1);
    }

    @Test
    @DisplayName("clearAbort prevents a between-calls trip from firing a stale abort")
    void clearedAbortDoesNotFire() {
        final DefaultCancellationSignal signal = new DefaultCancellationSignal();
        final SignalBackedLlmCancellation bridge = new SignalBackedLlmCancellation(signal);
        final AtomicInteger fired = new AtomicInteger();

        bridge.onCancel(fired::incrementAndGet);
        bridge.clearAbort(); // the LLM call completed; its stream is closed

        signal.trip(InterruptReason.USER_SIGINT);

        assertThat(fired).as("a cleared abort must not run when the signal trips later").hasValue(0);
    }

    @Test
    @DisplayName("registering an abort after the signal already tripped fires it synchronously")
    void alreadyTrippedFiresSynchronously() {
        final DefaultCancellationSignal signal = new DefaultCancellationSignal();
        signal.trip(InterruptReason.USER_SIGINT);
        final SignalBackedLlmCancellation bridge = new SignalBackedLlmCancellation(signal);
        final AtomicInteger fired = new AtomicInteger();

        bridge.onCancel(fired::incrementAndGet);

        assertThat(fired).as("already-cancelled contract: the abort runs immediately on the registering thread")
                .hasValue(1);
    }

    @Test
    @DisplayName("onCancel rejects a null abort")
    void onCancelRejectsNull() {
        final SignalBackedLlmCancellation bridge = new SignalBackedLlmCancellation(new DefaultCancellationSignal());
        assertThatThrownBy(() -> bridge.onCancel(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("constructor rejects a null signal")
    void constructorRejectsNull() {
        assertThatThrownBy(() -> new SignalBackedLlmCancellation(null)).isInstanceOf(NullPointerException.class);
    }

    /**
     * Test signal that counts {@link #onCancel(Runnable)} registrations so the single-listener invariant can be
     * asserted directly. Never actually trips.
     */
    private static final class CountingSignal implements CancellationSignal {
        private int listeners;

        int listenerCount() {
            return listeners;
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public Optional<InterruptReason> getReason() {
            return Optional.empty();
        }

        @Override
        public void checkpoint() {
            // never tripped
        }

        @Override
        public Registration onCancel(Runnable listener) {
            listeners++;
            return () -> listeners--;
        }
    }
}
