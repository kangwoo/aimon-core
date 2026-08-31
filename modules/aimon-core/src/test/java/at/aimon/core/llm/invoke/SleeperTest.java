package at.aimon.core.llm.invoke;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.llm.LlmCancellation;

/**
 * Behaviour of {@link Sleeper#threadSleep()}'s cancellation-aware overload: a retry backoff must wake promptly when the
 * cancellation token trips <em>during</em> the sleep (instead of only after the full — possibly {@code Retry-After}
 * -driven — delay elapses), while an untripped or inert token still sleeps the whole duration.
 */
@DisplayName("Sleeper cancellation-aware backoff")
class SleeperTest {

    private static final Sleeper SLEEPER = Sleeper.threadSleep();

    @Test
    @DisplayName("wakes early when the token trips during the sleep")
    void wakesEarlyOnTripDuringSleep() throws InterruptedException {
        final TripToken token = new TripToken();
        final Thread tripper = new Thread(() -> {
            sleepUninterruptibly(30);
            token.trip();
        });

        final long start = System.nanoTime();
        tripper.start();
        SLEEPER.sleep(Duration.ofSeconds(10), token);
        final long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        tripper.join();

        assertThat(elapsedMs).as("a trip mid-sleep must wake well before the 10s ceiling").isLessThan(3_000L);
        assertThat(token.registeredAbort).as("the sleep must register a wake-up lever via onCancel").isTrue();
    }

    @Test
    @DisplayName("returns immediately when the token is already cancelled")
    void returnsImmediatelyWhenAlreadyCancelled() throws InterruptedException {
        final TripToken token = new TripToken();
        token.trip();

        final long start = System.nanoTime();
        SLEEPER.sleep(Duration.ofSeconds(10), token);
        final long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        assertThat(elapsedMs).as("an already-cancelled token must not burn the backoff").isLessThan(1_000L);
    }

    @Test
    @DisplayName("sleeps the full duration when the token never trips")
    void sleepsFullDurationWhenNotCancelled() throws InterruptedException {
        final TripToken token = new TripToken();

        final long start = System.nanoTime();
        SLEEPER.sleep(Duration.ofMillis(120), token);
        final long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        assertThat(elapsedMs).as("an untripped token must wait out the whole delay").isGreaterThanOrEqualTo(100L);
    }

    @Test
    @DisplayName("the inert none() token sleeps the full duration (delegates to a plain sleep)")
    void noneTokenSleepsFullDuration() throws InterruptedException {
        final long start = System.nanoTime();
        SLEEPER.sleep(Duration.ofMillis(120), LlmCancellation.none());
        final long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        assertThat(elapsedMs).as("the unsupported none() token routes to a plain sleep").isGreaterThanOrEqualTo(100L);
    }

    private static void sleepUninterruptibly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Minimal {@link LlmCancellation} mirroring {@code SignalBackedLlmCancellation}'s contract: a single abort lever
     * that fires on {@link #trip()}, and an already-cancelled registration that fires synchronously.
     */
    private static final class TripToken implements LlmCancellation {
        private final AtomicReference<Runnable> abort = new AtomicReference<>();
        private volatile boolean cancelled;
        private volatile boolean registeredAbort;

        void trip() {
            cancelled = true;
            final Runnable a = abort.get();
            if (a != null) {
                a.run();
            }
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public void onCancel(Runnable a) {
            Objects.requireNonNull(a, "abort");
            registeredAbort = true;
            abort.set(a);
            if (cancelled) {
                a.run();
            }
        }
    }
}
