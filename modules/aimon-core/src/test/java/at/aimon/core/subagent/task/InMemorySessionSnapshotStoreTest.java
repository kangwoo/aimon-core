package at.aimon.core.subagent.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.List;

import org.junit.jupiter.api.Test;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.transcript.SessionSnapshot;

class InMemorySessionSnapshotStoreTest {

    private final InMemorySessionSnapshotStore store = new InMemorySessionSnapshotStore();

    private static SessionSnapshot snapshot(String systemPrompt) {
        return SessionSnapshot.of(SessionId.generate(), systemPrompt, List.of());
    }

    @Test
    void loadUnknownTaskReturnsEmpty() {
        assertThat(store.load("nope")).isEmpty();
    }

    @Test
    void saveThenLoadReturnsSameSnapshotAndOwner() {
        SessionSnapshot snapshot = snapshot("sys");

        store.save("t", "Explore", snapshot);

        assertThat(store.load("t")).get().satisfies(resumable -> {
            assertThat(resumable.getSnapshot()).isSameAs(snapshot);
            assertThat(resumable.getSubagentName()).isEqualTo("Explore");
        });
    }

    @Test
    void saveOverwritesPreviousSnapshotForSameTask() {
        SessionSnapshot first = snapshot("first");
        SessionSnapshot second = snapshot("second");

        store.save("t", "Explore", first);
        store.save("t", "Plan", second);

        assertThat(store.load("t")).get().satisfies(resumable -> {
            assertThat(resumable.getSnapshot()).isSameAs(second);
            assertThat(resumable.getSubagentName()).isEqualTo("Plan");
        });
    }

    @Test
    void evictDiscardsSnapshot() {
        store.save("t", "Explore", snapshot("sys"));

        store.evict("t");

        assertThat(store.load("t")).isEmpty();
    }

    @Test
    void evictUnknownTaskIsNoOp() {
        store.evict("nope");

        assertThat(store.load("nope")).isEmpty();
    }

    @Test
    void tasksAreIsolatedById() {
        SessionSnapshot a = snapshot("a");
        SessionSnapshot b = snapshot("b");

        store.save("a", "Explore", a);
        store.save("b", "Plan", b);

        assertThat(store.load("a")).get().extracting(ResumableSession::getSnapshot).isSameAs(a);
        assertThat(store.load("b")).get().extracting(ResumableSession::getSnapshot).isSameAs(b);
    }

    @Test
    void evictsLeastRecentlyUsedSnapshotWhenCapacityExceeded() {
        InMemorySessionSnapshotStore bounded = new InMemorySessionSnapshotStore(2);

        bounded.save("a", "Explore", snapshot("a"));
        bounded.save("b", "Explore", snapshot("b"));
        // Touch "a" so it is more-recently-used than "b".
        assertThat(bounded.load("a")).isPresent();
        // Adding a third entry evicts the least-recently-used, which is now "b".
        bounded.save("c", "Explore", snapshot("c"));

        assertThat(bounded.load("a")).isPresent();
        assertThat(bounded.load("c")).isPresent();
        assertThat(bounded.load("b")).isEmpty();
    }

    @Test
    void constructorRejectsNonPositiveCapacity() {
        assertThatIllegalArgumentException().isThrownBy(() -> new InMemorySessionSnapshotStore(0));
    }

    @Test
    void nullArgumentsRejected() {
        SessionSnapshot snapshot = snapshot("sys");
        assertThatNullPointerException().isThrownBy(() -> store.save(null, "Explore", snapshot));
        assertThatNullPointerException().isThrownBy(() -> store.save("t", null, snapshot));
        assertThatNullPointerException().isThrownBy(() -> store.save("t", "Explore", null));
        assertThatNullPointerException().isThrownBy(() -> store.load(null));
        assertThatNullPointerException().isThrownBy(() -> store.evict(null));
    }
}
