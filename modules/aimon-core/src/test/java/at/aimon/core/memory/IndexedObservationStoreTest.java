package at.aimon.core.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.base.Principal;
import at.aimon.core.memory.index.ObservationIndex;

/**
 * Verifies that {@link IndexedObservationStore} delegates metadata operations to
 * the wrapped {@link ObservationStore} and keeps the injected
 * {@link ObservationIndex} in sync (write-through), restoring
 * {@link ObservationStore#semanticSearch} on a metadata-only delegate.
 */
@DisplayName("IndexedObservationStore decorator")
class IndexedObservationStoreTest {

    private static final Instant T0 = Instant.parse("2024-01-15T10:00:00Z");

    private RecordingStore delegate;
    private RecordingIndex index;
    private IndexedObservationStore store;
    private Workspace ws;
    private PeerView alice;
    private PeerView bob;

    @BeforeEach
    void setUp() {
        delegate = new RecordingStore();
        index = new RecordingIndex();
        store = new IndexedObservationStore(delegate, index);
        ws = Workspace.builder().id("ws-1").build();
        alice = PeerView.of(ws, Principal.user("alice", "Alice"));
        bob = PeerView.of(ws, Principal.user("bob", "Bob"));
    }

    private Observation obs(String localId, String content, double confidence) {
        return Observation.builder().id(ObservationId.of(ws, localId)).subject(alice).observer(bob).content(content)
                .type(ObservationType.EXPLICIT).confidence(confidence).createdAt(T0).build();
    }

    @Test
    @DisplayName("constructor rejects null collaborators")
    void constructorRejectsNulls() {
        assertThatNullPointerException().isThrownBy(() -> new IndexedObservationStore(null, index));
        assertThatNullPointerException().isThrownBy(() -> new IndexedObservationStore(delegate, null));
    }

    @Test
    @DisplayName("save persists to delegate then indexes the stored instance")
    void saveDelegatesAndIndexes() {
        Observation o = obs("o1", "hello", 0.5d);

        Observation returned = store.save(o);

        assertThat(returned).isEqualTo(o);
        assertThat(delegate.saved).containsExactly(o.getId());
        assertThat(index.indexed).containsExactly(o.getId());
        assertThat(index.deleted).isEmpty();
    }

    @Test
    @DisplayName("delete removes from delegate then from index")
    void deleteDelegatesAndDeindexes() {
        Observation o = obs("o1", "hello", 0.5d);
        store.save(o);
        index.reset();

        store.delete(o.getId());

        assertThat(delegate.deleted).containsExactly(o.getId());
        assertThat(index.deleted).containsExactly(o.getId());
        assertThat(index.indexed).isEmpty();
    }

    @Test
    @DisplayName("merge delegates and reindexes (delete loser, index merged winner)")
    void mergeDelegatesAndReindexes() {
        Observation winner = obs("win", "winner content", 0.5d);
        Observation loser = obs("lose", "loser content", 0.5d);
        store.save(winner);
        store.save(loser);
        index.reset();

        Observation merged = obs("win", "merged content", 0.9d);
        Observation returned = store.merge(winner.getId(), loser.getId(), merged);

        assertThat(returned).isEqualTo(merged);
        assertThat(index.deleted).containsExactly(loser.getId());
        assertThat(index.indexed).containsExactly(winner.getId());
    }

    @Test
    @DisplayName("semanticSearch uses index.search and hydrates from the delegate, preserving order")
    void semanticSearchUsesIndexAndHydrates() {
        Observation a = obs("o1", "tea note", 0.9d);
        Observation b = obs("o2", "coffee memo", 0.8d);
        store.save(a);
        store.save(b);

        index.nextHits = List.of(b.getId(), a.getId());

        List<Observation> result = store.semanticSearch(alice, "anything", 10);

        assertThat(index.lastSearchSubject).isEqualTo(alice);
        assertThat(index.lastSearchQuery).isEqualTo("anything");
        assertThat(index.lastSearchTopK).isEqualTo(10);
        assertThat(result).containsExactly(b, a);
    }

    @Test
    @DisplayName("semanticSearch skips ids absent from the delegate (defensive hydration)")
    void semanticSearchSkipsMissingIds() {
        Observation a = obs("o1", "tea note", 0.9d);
        store.save(a);

        ObservationId ghost = ObservationId.of(ws, "ghost");
        index.nextHits = List.of(a.getId(), ghost);

        List<Observation> result = store.semanticSearch(alice, "any", 10);

        assertThat(result).containsExactly(a);
    }

    @Test
    @DisplayName("semanticSearch returns empty when the index has no hits (delegate not queried)")
    void semanticSearchEmptyHits() {
        store.save(obs("o1", "anything", 0.5d));
        index.nextHits = List.of();
        delegate.findByIdCalls = 0;

        List<Observation> result = store.semanticSearch(alice, "missing", 10);

        assertThat(result).isEmpty();
        assertThat(delegate.findByIdCalls).isZero();
    }

    @Test
    @DisplayName("semanticSearch rejects topK < 1")
    void semanticSearchRejectsBadTopK() {
        assertThatThrownBy(() -> store.semanticSearch(alice, "q", 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("read-side methods delegate verbatim")
    void readMethodsDelegate() {
        Observation o = obs("o1", "hello", 0.3d);
        store.save(o);

        assertThat(store.findById(o.getId())).contains(o);
        assertThat(store.findBySubject(alice, 10)).containsExactly(o);
        assertThat(store.count(alice)).isEqualTo(1L);
        assertThat(store.findByConfidenceBelow(alice, 0.5d, 10)).containsExactly(o);
        assertThat(store.findSubjects(ws, 10)).containsExactly(alice);
    }

    /**
     * Minimal in-memory {@link ObservationStore} stand-in that records writes and
     * throws from {@link #semanticSearch}, so the test proves the decorator (not
     * the delegate) answers searches. It stands in for a metadata-only store —
     * a shape this repository no longer ships one of, since the two that had it
     * left with the distributed memory backends.
     */
    private static final class RecordingStore implements ObservationStore {
        final Map<ObservationId, Observation> storage = new LinkedHashMap<>();
        final List<ObservationId> saved = new ArrayList<>();
        final List<ObservationId> deleted = new ArrayList<>();
        int findByIdCalls;

        @Override
        public Observation save(Observation observation) {
            storage.put(observation.getId(), observation);
            saved.add(observation.getId());
            return observation;
        }

        @Override
        public Optional<Observation> findById(ObservationId id) {
            findByIdCalls++;
            return Optional.ofNullable(storage.get(id));
        }

        @Override
        public List<Observation> findBySubject(PeerView subject, int limit) {
            return storage.values().stream().filter(o -> o.getSubject().equals(subject)).limit(limit).toList();
        }

        @Override
        public long count(PeerView subject) {
            return storage.values().stream().filter(o -> o.getSubject().equals(subject)).count();
        }

        @Override
        public List<Observation> semanticSearch(PeerView subject, String query, int topK) {
            throw new UnsupportedOperationException("metadata-only store: wrap with an ObservationIndex");
        }

        @Override
        public List<Observation> findByConfidenceBelow(PeerView subject, double threshold, int limit) {
            return storage.values().stream().filter(o -> o.getSubject().equals(subject))
                    .filter(o -> o.getConfidence() < threshold).limit(limit).toList();
        }

        @Override
        public List<PeerView> findSubjects(Workspace workspace, int limit) {
            return storage.values().stream().filter(o -> o.getSubject().getWorkspace().equals(workspace))
                    .map(Observation::getSubject).distinct().limit(limit).toList();
        }

        @Override
        public void delete(ObservationId id) {
            storage.remove(id);
            deleted.add(id);
        }

        @Override
        public Observation merge(ObservationId winner, ObservationId loser, Observation merged) {
            storage.remove(loser);
            storage.put(winner, merged);
            return merged;
        }
    }

    /**
     * Test double that records every {@link ObservationIndex} call and lets the
     * test seed search results without real ranking.
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
