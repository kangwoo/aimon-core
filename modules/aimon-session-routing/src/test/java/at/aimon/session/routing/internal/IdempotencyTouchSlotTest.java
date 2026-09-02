package at.aimon.session.routing.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentExecutionResult;
import at.aimon.core.agent.session.idempotency.IdempotencyEntry;
import at.aimon.core.agent.session.idempotency.IdempotencyStore;
import at.aimon.core.agent.session.idempotency.PutResult;

/**
 * What one lease's touch slots have to keep alive at once.
 *
 * <p>
 * A submitted turn opens a drain pass that also runs whatever the inbox was holding, so two reservations can be this
 * node's responsibility at the same moment: the submission's own, which its caller is waiting on for the whole pass,
 * and the queued message's, which the pass took over for the length of that one turn. A single-reference slot makes
 * them evict each other — and a sibling LLM turn comfortably outlasts the thirty-second secondary TTL, so whichever
 * lost the slot would be swept as a lost holder while its node was working perfectly well.
 */
@DisplayName("IdempotencyTouchSlot keeps every reservation this node is running alive")
class IdempotencyTouchSlotTest {

    private final RecordingStore store = new RecordingStore();
    private final IdempotencyTouchSlot slot = new IdempotencyTouchSlot(store);

    @Test
    @DisplayName("a tick refreshes every bound reservation, each against its own reserver id")
    void touchesEveryBinding() {
        slot.bind("k-own", "reserver-own");
        slot.bind("k-queued", "reserver-queued");

        slot.touch();

        // Pairwise, not merely both present: touch is silently ignored on a holder mismatch, so a slot that crossed
        // one key with another's reserver id would look identical here and refresh nothing at all.
        assertThat(store.touched).containsExactlyInAnyOrder("k-own|reserver-own", "k-queued|reserver-queued");
    }

    @Test
    @DisplayName("unbinding the message that finished leaves the submission that opened the pass still refreshed")
    void unbindIsScopedToOneKey() {
        slot.bind("k-own", "reserver-own");
        slot.bind("k-queued", "reserver-queued");

        slot.unbind("k-queued");
        slot.touch();

        assertThat(store.touched).containsExactly("k-own|reserver-own");
    }

    @Test
    @DisplayName("binding the same key again replaces its reserver rather than accumulating a stale one")
    void rebindReplacesTheReserver() {
        slot.bind("k", "reserver-1");
        slot.bind("k", "reserver-2");

        slot.touch();

        assertThat(store.touched).containsExactly("k|reserver-2");
    }

    @Test
    @DisplayName("a half-null binding binds nothing — a turn with no key has nothing to refresh")
    void ignoresNullBindings() {
        slot.bind(null, "reserver");
        slot.bind("k", null);

        slot.touch();

        assertThat(store.touched).isEmpty();
    }

    @Test
    @DisplayName("clear drops every binding, so later renewals of the same lease touch nothing")
    void clearDropsEverything() {
        slot.bind("k-own", "reserver-own");
        slot.bind("k-queued", "reserver-queued");

        slot.clear();
        slot.touch();

        assertThat(store.touched).isEmpty();
    }

    @Test
    @DisplayName("a throwing store costs one refresh, not the rest of the tick")
    void oneFailingTouchDoesNotStopTheOthers() {
        store.failOnKey = "k-broken";
        slot.bind("k-broken", "reserver-broken");
        slot.bind("k-own", "reserver-own");

        slot.touch();

        // The renewal tick runs on the scheduler thread every lease has to share; an exception escaping here would
        // cancel the scheduled task and silently stop renewing every session on this node.
        assertThat(store.touched).containsExactly("k-own|reserver-own");
    }

    /** Records the (key, holder) pairs {@code touch} was called with; every other operation is unused here. */
    private static final class RecordingStore implements IdempotencyStore {

        private final List<String> touched = new ArrayList<>();
        private String failOnKey;

        @Override
        public boolean touch(String key, String holderId) {
            if (key.equals(failOnKey)) {
                throw new IllegalStateException("simulated idempotency backend failure");
            }
            touched.add(key + "|" + holderId);
            return true;
        }

        @Override
        public PutResult putIfAbsent(String key, IdempotencyEntry entry, Duration ttl) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void markDone(String key, AgentExecutionResult result) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<IdempotencyEntry> find(String key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean releaseHolder(String key, String expectedHolderId, Duration ttl) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean acquireHolder(String key, String holderId, Duration ttl) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean discardReservation(String key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean compareAndReset(String key, String expectedHolderId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<IdempotencyEntry> findStaleInFlight(Instant cutoff) {
            throw new UnsupportedOperationException();
        }
    }
}
