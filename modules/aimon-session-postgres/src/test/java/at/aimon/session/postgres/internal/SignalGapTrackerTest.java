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

        assertThat(gaps.noteHole(5, 8, T0)).isZero();
        assertThat(gaps.outstanding()).containsExactly(5L, 6L, 7L);

        gaps.resolved(6);
        assertThat(gaps.outstanding()).containsExactly(5L, 7L);
    }

    @Test
    @DisplayName("gives an id up once its grace period has ended, each from when it was first noticed")
    void expiresAfterTheGracePeriod() {
        final SignalGapTracker gaps = new SignalGapTracker(GRACE_MS);
        gaps.noteHole(5, 6, T0);
        gaps.noteHole(9, 10, after(600));
        // Noticing 5 again does not give it a new lease.
        gaps.noteHole(5, 6, after(600));

        gaps.expire(after(999));
        assertThat(gaps.outstanding()).containsExactly(5L, 9L);
        gaps.expire(after(1_000));
        assertThat(gaps.outstanding()).containsExactly(9L);
        gaps.expire(after(1_600));
        assertThat(gaps.outstanding()).isEmpty();
    }

    @Test
    @DisplayName("asking for the outstanding ids does not expire them")
    void outstandingDoesNotExpire() {
        final SignalGapTracker gaps = new SignalGapTracker(GRACE_MS);
        gaps.noteHole(5, 6, T0);

        assertThat(gaps.outstanding()).containsExactly(5L);
        assertThat(gaps.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("of a hole too wide to be transactions in flight, tracks the highest ids")
    void tracksTheTopOfAHoleThatIsTooWide() {
        final SignalGapTracker gaps = new SignalGapTracker(GRACE_MS);

        assertThat(gaps.noteHole(1, 10_000, T0)).as("cut for width, not for room").isZero();

        assertThat(gaps.size()).isEqualTo(SignalGapTracker.MAX_HOLE_SPAN);
        final Long[] kept = gaps.outstanding();
        assertThat(kept[0]).isEqualTo(10_000L - SignalGapTracker.MAX_HOLE_SPAN);
        assertThat(kept[kept.length - 1]).isEqualTo(9_999L);
    }

    @Test
    @DisplayName("when room runs out keeps the highest ids of the hole, reports the rest, and takes holes again later")
    void isBoundedInTotal() {
        final SignalGapTracker gaps = new SignalGapTracker(GRACE_MS);
        long next = 1;
        while (gaps.size() + SignalGapTracker.MAX_HOLE_SPAN <= SignalGapTracker.MAX_TRACKED) {
            assertThat(gaps.noteHole(next, next + SignalGapTracker.MAX_HOLE_SPAN, T0)).isZero();
            next += 2L * SignalGapTracker.MAX_HOLE_SPAN;
        }
        assertThat(gaps.size()).isEqualTo(SignalGapTracker.MAX_TRACKED);
        gaps.resolved(1);
        gaps.resolved(2);

        assertThat(gaps.noteHole(next, next + 5, T0)).as("ids there was no room for").isEqualTo(3L);
        assertThat(gaps.outstanding()).contains(next + 4, next + 3).doesNotContain(next + 2);

        gaps.expire(after(GRACE_MS));
        assertThat(gaps.noteHole(next, next + 5, after(GRACE_MS))).as("room again after expiry").isZero();
    }

    @Test
    @DisplayName("an empty hole is nothing to track")
    void emptyHole() {
        final SignalGapTracker gaps = new SignalGapTracker(GRACE_MS);

        assertThat(gaps.noteHole(5, 5, T0)).isZero();
        assertThat(gaps.size()).isZero();
    }
}
