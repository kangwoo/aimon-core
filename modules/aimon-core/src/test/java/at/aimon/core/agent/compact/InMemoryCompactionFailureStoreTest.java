package at.aimon.core.agent.compact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import at.aimon.core.agent.session.SessionId;

/**
 * Unit tests for {@link InMemoryCompactionFailureStore}: validates increment/reset semantics, the LRU eviction bound,
 * and constructor parameter validation.
 */
class InMemoryCompactionFailureStoreTest {

    @Test
    void unknownConversationReturnsZero() {
        final InMemoryCompactionFailureStore store = new InMemoryCompactionFailureStore();

        assertThat(store.get(SessionId.generate())).isZero();
    }

    @Test
    void recordFailureIncrementsAndReturnsNewValue() {
        final InMemoryCompactionFailureStore store = new InMemoryCompactionFailureStore();
        final SessionId id = SessionId.generate();

        assertThat(store.recordFailure(id)).isEqualTo(1);
        assertThat(store.recordFailure(id)).isEqualTo(2);
        assertThat(store.recordFailure(id)).isEqualTo(3);
        assertThat(store.get(id)).isEqualTo(3);
    }

    @Test
    void resetClearsCounter() {
        final InMemoryCompactionFailureStore store = new InMemoryCompactionFailureStore();
        final SessionId id = SessionId.generate();
        store.recordFailure(id);
        store.recordFailure(id);

        store.reset(id);

        assertThat(store.get(id)).isZero();
    }

    @Test
    void resetOnUnknownConversationIsNoop() {
        final InMemoryCompactionFailureStore store = new InMemoryCompactionFailureStore();

        store.reset(SessionId.generate());

        // No exception, still zero.
        assertThat(store.get(SessionId.generate())).isZero();
    }

    @Test
    void independentConversationsHaveSeparateCounters() {
        final InMemoryCompactionFailureStore store = new InMemoryCompactionFailureStore();
        final SessionId a = SessionId.generate();
        final SessionId b = SessionId.generate();

        store.recordFailure(a);
        store.recordFailure(a);
        store.recordFailure(b);

        assertThat(store.get(a)).isEqualTo(2);
        assertThat(store.get(b)).isEqualTo(1);
    }

    @Test
    void lruBoundEvictsOldestEntry() {
        final InMemoryCompactionFailureStore store = new InMemoryCompactionFailureStore(2);
        final SessionId a = SessionId.generate();
        final SessionId b = SessionId.generate();
        final SessionId c = SessionId.generate();

        store.recordFailure(a);
        store.recordFailure(b);
        store.recordFailure(c); // evicts 'a' (LRU)

        assertThat(store.get(a)).isZero();
        assertThat(store.get(b)).isEqualTo(1);
        assertThat(store.get(c)).isEqualTo(1);
    }

    @Test
    void constructorRejectsNonPositiveBound() {
        assertThatThrownBy(() -> new InMemoryCompactionFailureStore(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InMemoryCompactionFailureStore(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
