package at.aimon.session.postgres.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SignalGapTracker")
class SignalGapTrackerTest {

    private static final long GRACE_MS = 1_000L;
    private static final long T0 = 5_000_000_000L;

    private static long after(long millis) {
        return T0 + TimeUnit.MILLISECONDS.toNanos(millis);
    }

    @Test
    @DisplayName("keeps every id of a hole until it is resolved")
    void tracksAHoleUntilResolved() {
        final SignalGapTracker gaps = new SignalGapTracker(GRACE_MS);

        assertThat(gaps.noteHole(5, 8, T0)).isTrue();
        assertThat(gaps.outstanding(after(10))).containsExactly(5L, 6L, 7L);

        gaps.resolved(6);
        assertThat(gaps.outstanding(after(20))).containsExactly(5L, 7L);
    }

    @Test
    @DisplayName("gives an id up once its grace period has ended, each from when it was first noticed")
    void expiresAfterTheGracePeriod() {
        final SignalGapTracker gaps = new SignalGapTracker(GRACE_MS);
        gaps.noteHole(5, 6, T0);
        gaps.noteHole(9, 10, after(600));
        // Noticing 5 again does not give it a new lease.
        gaps.noteHole(5, 6, after(600));

        assertThat(gaps.outstanding(after(999))).containsExactly(5L, 9L);
        assertThat(gaps.outstanding(after(1_000))).containsExactly(9L);
        assertThat(gaps.outstanding(after(1_600))).isEmpty();
    }

    @Test
    @DisplayName("does not track a hole too wide to be transactions in flight")
    void ignoresAHoleThatIsTooWide() {
        final SignalGapTracker gaps = new SignalGapTracker(GRACE_MS);

        assertThat(gaps.noteHole(1, 1 + SignalGapTracker.MAX_HOLE_SPAN + 1L, T0)).isFalse();
        assertThat(gaps.size()).isZero();
        assertThat(gaps.noteHole(1, 1 + SignalGapTracker.MAX_HOLE_SPAN, T0)).isTrue();
        assertThat(gaps.size()).isEqualTo(SignalGapTracker.MAX_HOLE_SPAN);
    }

    @Test
    @DisplayName("holds no more than its cap, and takes holes again once there is room")
    void isBoundedInTotal() {
        final SignalGapTracker gaps = new SignalGapTracker(GRACE_MS);
        long next = 1;
        while (gaps.size() + SignalGapTracker.MAX_HOLE_SPAN <= SignalGapTracker.MAX_TRACKED) {
            assertThat(gaps.noteHole(next, next + SignalGapTracker.MAX_HOLE_SPAN, T0)).isTrue();
            next += 2L * SignalGapTracker.MAX_HOLE_SPAN;
        }
        assertThat(gaps.size()).isEqualTo(SignalGapTracker.MAX_TRACKED);

        assertThat(gaps.noteHole(next, next + 1, T0)).as("no room").isFalse();
        gaps.outstanding(after(GRACE_MS));
        assertThat(gaps.noteHole(next, next + 1, after(GRACE_MS))).as("room again after expiry").isTrue();
    }

    @Test
    @DisplayName("an empty hole is nothing to track")
    void emptyHole() {
        final SignalGapTracker gaps = new SignalGapTracker(GRACE_MS);

        assertThat(gaps.noteHole(5, 5, T0)).isTrue();
        assertThat(gaps.size()).isZero();
    }
}
