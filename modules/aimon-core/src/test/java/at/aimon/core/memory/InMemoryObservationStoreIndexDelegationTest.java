package at.aimon.core.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.base.Principal;
import at.aimon.core.memory.index.ObservationIndex;

/**
 * Verifies that {@link InMemoryObservationStore} delegates all
 * search-side operations to the injected {@link ObservationIndex}, per design
 * doc §5.2 C3 split (metadata vs index).
 */
@DisplayName("InMemoryObservationStore <-> ObservationIndex delegation")
class InMemoryObservationStoreIndexDelegationTest {

    private static final Instant T0 = Instant.parse("2024-01-15T10:00:00Z");

    private RecordingIndex recordingIndex;
    private InMemoryObservationStore store;
    private Workspace ws;
    private PeerView alice;
    private PeerView bob;

    @BeforeEach
    void setUp() {
        recordingIndex = new RecordingIndex();
        store = new InMemoryObservationStore(recordingIndex);
        ws = Workspace.builder().id("ws-1").build();
        alice = PeerView.of(ws, Principal.user("alice", "Alice"));
        bob = PeerView.of(ws, Principal.user("bob", "Bob"));
    }

    private Observation obs(String localId, String content, double confidence) {
        return Observation.builder().id(ObservationId.of(ws, localId)).subject(alice).observer(bob).content(content)
                .type(ObservationType.EXPLICIT).confidence(confidence).createdAt(T0).build();
    }

    @Test
    @DisplayName("save calls index(observation)")
    void saveDelegatesToIndex() {
        Observation o = obs("o1", "hello", 0.5d);

        store.save(o);

        assertThat(recordingIndex.indexed).containsExactly(o.getId());
        assertThat(recordingIndex.deleted).isEmpty();
    }

    @Test
    @DisplayName("delete calls index.delete(id)")
    void deleteDelegatesToIndex() {
        Observation o = obs("o1", "hello", 0.5d);
        store.save(o);
        recordingIndex.reset();

        store.delete(o.getId());

        assertThat(recordingIndex.deleted).containsExactly(o.getId());
        assertThat(recordingIndex.indexed).isEmpty();
    }

    @Test
    @DisplayName("merge calls index.delete(loser) and index.index(merged)")
    void mergeDelegatesToIndex() {
        Observation winner = obs("win", "winner content", 0.5d);
        Observation loser = obs("lose", "loser content", 0.5d);
        store.save(winner);
        store.save(loser);
        recordingIndex.reset();

        Observation merged = obs("win", "merged content", 0.9d);
        store.merge(winner.getId(), loser.getId(), merged);

        assertThat(recordingIndex.deleted).containsExactly(loser.getId());
        assertThat(recordingIndex.indexed).containsExactly(winner.getId());
    }

    @Test
    @DisplayName("semanticSearch uses index.search and hydrates Observations from store")
    void semanticSearchDelegatesAndHydrates() {
        Observation a = obs("o1", "tea note", 0.9d);
        Observation b = obs("o2", "coffee memo", 0.8d);
        store.save(a);
        store.save(b);

        recordingIndex.nextHits = List.of(b.getId(), a.getId());

        List<Observation> result = store.semanticSearch(alice, "anything", 10);

        assertThat(recordingIndex.lastSearchSubject).isEqualTo(alice);
        assertThat(recordingIndex.lastSearchQuery).isEqualTo("anything");
        assertThat(recordingIndex.lastSearchTopK).isEqualTo(10);
        assertThat(result).containsExactly(b, a);
    }

    @Test
    @DisplayName("semanticSearch skips ids that are not in the metadata store (defensive hydration)")
    void semanticSearchSkipsMissingIds() {
        Observation a = obs("o1", "tea note", 0.9d);
        store.save(a);

        ObservationId ghost = ObservationId.of(ws, "ghost");
        recordingIndex.nextHits = List.of(a.getId(), ghost);

        List<Observation> result = store.semanticSearch(alice, "any", 10);

        assertThat(result).containsExactly(a);
    }

    @Test
    @DisplayName("semanticSearch returns empty list when index returns no hits")
    void semanticSearchEmptyHits() {
        store.save(obs("o1", "anything", 0.5d));
        recordingIndex.nextHits = List.of();

        List<Observation> result = store.semanticSearch(alice, "missing", 10);

        assertThat(result).isEmpty();
    }

    /**
     * Test double that records every {@link ObservationIndex} call and lets the
     * test seed search results without doing any real ranking.
     */
    private static final class RecordingIndex implements ObservationIndex {
        final List<ObservationId> indexed = new ArrayList<>();
        final List<ObservationId> deleted = new ArrayList<>();
        List<ObservationId> nextHits = List.of();
        PeerView lastSearchSubject;
        String lastSearchQuery;
        int lastSearchTopK;

        @Override
        public void index(Observation observation) {
            indexed.add(Objects.requireNonNull(observation, "observation").getId());
        }

        @Override
        public void delete(ObservationId id) {
            deleted.add(Objects.requireNonNull(id, "id"));
        }

        @Override
        public List<ObservationId> search(PeerView subject, String query, int topK) {
            this.lastSearchSubject = subject;
            this.lastSearchQuery = query;
            this.lastSearchTopK = topK;
            return nextHits;
        }

        void reset() {
            indexed.clear();
            deleted.clear();
        }
    }
}
