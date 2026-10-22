package at.aimon.session.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.wake.WakeLedger;
import at.aimon.core.agent.session.wake.WakeState;

/**
 * The contract every {@link WakeLedger} backend must honour (idle-wake design §4.3), written once and run against
 * each.
 *
 * <p>
 * The assertion that matters most is atomicity: the idle-session waker changes a session's state from a wake and from
 * the end of that wake's turn, and with a shared ledger other nodes do the same. An {@code update} that read, computed
 * and wrote without a guard would lose counts under exactly that load, and a lost count is a cap that does not hold.
 *
 * <p>
 * Subclasses return a ledger over an emptied backend from {@link #ledger()}; each test uses its own session ids.
 */
public abstract class AbstractWakeLedgerContractTest {

    /** Millisecond-truncated: Mongo stores {@code Date}, which has no finer precision. */
    private static final Instant FIRST = Instant.parse("2026-10-22T10:15:30.123Z");

    /**
     * @return the ledger under test, over an emptied backend
     */
    protected abstract WakeLedger ledger();

    @Test
    @DisplayName("a session never written reads as the empty state")
    void unknownSessionIsEmpty() {
        assertThat(ledger().get(SessionId.of("wake-unknown"))).isEqualTo(WakeState.empty());
    }

    @Test
    @DisplayName("every field of an update reads back exactly")
    void updateRoundTrips() {
        final SessionId session = SessionId.of("wake-roundtrip");
        final WakeState state = WakeState.of(List.of(FIRST, FIRST.plusSeconds(60)), 2, 7, true);

        final WakeState returned = ledger().update(session, current -> state);

        assertThat(returned).isEqualTo(state);
        assertThat(ledger().get(session)).isEqualTo(state);
        final WakeState cleared = WakeState.of(List.of(), 0, null, false);
        ledger().update(session, current -> cleared);
        assertThat(ledger().get(session)).isEqualTo(cleared);
    }

    @Test
    @DisplayName("an update sees the current state, the empty one for a new session")
    void updateSeesTheCurrentState() {
        final SessionId session = SessionId.of("wake-current");
        final List<WakeState> seen = new ArrayList<>();

        ledger().update(session, current -> {
            seen.add(current);
            return WakeState.of(List.of(FIRST), 1, null, false);
        });
        ledger().update(session, current -> {
            seen.add(current);
            return current;
        });

        assertThat(seen.get(0)).isEqualTo(WakeState.empty());
        assertThat(seen.get(seen.size() - 1)).isEqualTo(WakeState.of(List.of(FIRST), 1, null, false));
    }

    @Test
    @DisplayName("remove forgets one session and leaves the others")
    void removeForgetsOneSession() {
        final SessionId gone = SessionId.of("wake-gone");
        final SessionId kept = SessionId.of("wake-kept");
        ledger().update(gone, current -> WakeState.of(List.of(FIRST), 1, null, false));
        ledger().update(kept, current -> WakeState.of(List.of(FIRST), 1, null, false));

        ledger().remove(gone);
        ledger().remove(SessionId.of("wake-never-written"));

        assertThat(ledger().get(gone)).isEqualTo(WakeState.empty());
        assertThat(ledger().get(kept).getConsecutive()).isEqualTo(1);
    }

    @Test
    @DisplayName("concurrent updates of one session lose nothing")
    void concurrentUpdatesAreAtomic() throws Exception {
        final SessionId session = SessionId.of("wake-concurrent");
        final int threads = 8;
        final int perThread = 25;
        final ExecutorService pool = Executors.newFixedThreadPool(threads);
        final CountDownLatch start = new CountDownLatch(1);
        try {
            final List<Future<?>> done = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                done.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        ledger().update(session, current -> WakeState.of(current.getRecentWakes(),
                                current.getConsecutive() + 1, null, false));
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : done) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(ledger().get(session).getConsecutive()).isEqualTo(threads * perThread);
    }
}
