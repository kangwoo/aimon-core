package at.aimon.bootstrap.assemble;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.store.InMemorySessionRecordStore;
import at.aimon.core.agent.session.store.SessionRecordStore;
import at.aimon.core.agent.session.store.SessionTotals;
import at.aimon.core.agent.session.transcript.SessionSnapshot;

class LateBoundFencedRecordStoreTest {

    private static final SessionId SESSION = SessionId.of("late-bound-record");

    private final InMemorySessionRecordStore raw = new InMemorySessionRecordStore();
    private final InMemorySessionRecordStore fenced = new InMemorySessionRecordStore();
    private final LateBoundFencedRecordStore store = new LateBoundFencedRecordStore(raw);

    private static final List<Consumer<SessionRecordStore>> MUTATIONS = List.of(
            s -> s.mergeFromSnapshot(SessionSnapshot.of(SESSION, "prompt", List.of())),
            s -> s.provision(SESSION, "agent:ops"), s -> s.provision(SESSION),
            s -> s.setTotalsAndBudgetOverride(SESSION, SessionTotals.empty(), null),
            s -> s.incrementCompactionFailureCount(SESSION), s -> s.resetCompactionFailureCount(SESSION),
            s -> s.delete(SESSION), SessionRecordStore::clear);

    @Test
    @DisplayName("every mutation before binding fails instead of going unfenced")
    void writesBeforeBindFail() {
        assertThat(store.isBound()).isFalse();
        for (Consumer<SessionRecordStore> mutation : MUTATIONS) {
            assertThatThrownBy(() -> mutation.accept(store)).isInstanceOf(IllegalStateException.class);
        }
        assertThat(raw.listSessionIds()).isEmpty();
    }

    @Test
    @DisplayName("reads go to the raw store before and after binding")
    void readsGoRaw() {
        raw.mergeFromSnapshot(SessionSnapshot.of(SESSION, "raw", List.of()));

        assertThat(store.load(SESSION)).isPresent();
        assertThat(store.exists(SESSION)).isTrue();
        assertThat(store.listSessionIds()).containsExactly(SESSION);
        store.bind(fenced);
        assertThat(store.load(SESSION).orElseThrow().getSystemPrompt()).isEqualTo("raw");
    }

    @Test
    @DisplayName("once bound, every mutation goes to the fenced view and only there")
    void writesGoToTheBoundView() {
        store.bind(fenced);

        store.mergeFromSnapshot(SessionSnapshot.of(SESSION, "prompt", List.of()));
        store.provision(SESSION, "agent:ops");
        store.setTotalsAndBudgetOverride(SESSION, SessionTotals.empty(), null);
        assertThat(store.incrementCompactionFailureCount(SESSION)).isEqualTo(1);
        store.resetCompactionFailureCount(SESSION);

        assertThat(fenced.load(SESSION).orElseThrow().getAgentRef()).contains("agent:ops");
        assertThat(raw.exists(SESSION)).as("the raw store is reached only through the view").isFalse();
        store.delete(SESSION);
        assertThat(fenced.exists(SESSION)).isFalse();
    }

    @Test
    @DisplayName("binds once")
    void bindsOnce() {
        store.bind(fenced);
        assertThatThrownBy(() -> store.bind(fenced)).isInstanceOf(IllegalStateException.class);
    }
}
