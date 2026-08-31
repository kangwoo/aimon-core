package at.aimon.core.memory.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.base.Principal;
import at.aimon.core.knowledge.KeywordKnowledgeStore;
import at.aimon.core.knowledge.KnowledgeStore;
import at.aimon.core.knowledge.SimpleDocumentChunker;
import at.aimon.core.memory.InMemoryObservationStore;
import at.aimon.core.memory.Observation;
import at.aimon.core.memory.ObservationId;
import at.aimon.core.memory.ObservationType;
import at.aimon.core.memory.PeerView;
import at.aimon.core.memory.Workspace;

@DisplayName("KnowledgeStoreObservationIndex (delegates to KeywordKnowledgeStore)")
class KnowledgeStoreObservationIndexTest {

    private static final Instant T0 = Instant.parse("2024-01-15T10:00:00Z");

    private KnowledgeStore knowledgeStore;
    private KnowledgeStoreObservationIndex index;
    private Workspace ws;
    private Workspace otherWs;
    private PeerView alice;
    private PeerView bob;
    private PeerView carol;

    @BeforeEach
    void setUp() {
        knowledgeStore = new KeywordKnowledgeStore(new SimpleDocumentChunker());
        index = new KnowledgeStoreObservationIndex(knowledgeStore);
        ws = Workspace.builder().id("ws-1").build();
        otherWs = Workspace.builder().id("ws-2").build();
        alice = PeerView.of(ws, Principal.user("alice", "Alice"));
        bob = PeerView.of(ws, Principal.user("bob", "Bob"));
        carol = PeerView.of(ws, Principal.user("carol", "Carol"));
    }

    private Observation obs(Workspace workspace, String localId, PeerView subject, PeerView observer, String content,
            double confidence, Instant createdAt) {
        return Observation.builder().id(ObservationId.of(workspace, localId)).subject(subject).observer(observer)
                .content(content).type(ObservationType.EXPLICIT).confidence(confidence).createdAt(createdAt).build();
    }

    private Observation obs(Workspace workspace, String localId, PeerView subject, String content, double confidence,
            Instant createdAt) {
        return obs(workspace, localId, subject, bob, content, confidence, createdAt);
    }

    private Observation obs(String localId, PeerView subject, String content) {
        return obs(ws, localId, subject, content, 0.8d, T0);
    }

    @Test
    @DisplayName("index then search returns the matching id via KnowledgeStore")
    void indexThenSearch() {
        Observation o = obs("o1", alice, "Alice prefers tea over coffee");
        index.index(o);

        List<ObservationId> hits = index.search(alice, "tea", 10);

        assertThat(hits).containsExactly(o.getId());
    }

    @Test
    @DisplayName("search filters by subject — other subjects' observations are not returned")
    void searchSubjectIsolation() {
        Observation aliceObs = obs("o1", alice, "shared keyword note for alice");
        Observation carolObs = obs("o2", carol, "shared keyword note for carol");
        index.index(aliceObs);
        index.index(carolObs);

        List<ObservationId> aliceHits = index.search(alice, "alice", 10);
        List<ObservationId> carolHits = index.search(carol, "carol", 10);

        assertThat(aliceHits).containsExactly(aliceObs.getId());
        assertThat(carolHits).containsExactly(carolObs.getId());
    }

    @Test
    @DisplayName("delete removes the observation from KnowledgeStore search results")
    void deleteRemovesFromSearch() {
        Observation o = obs("o1", alice, "delete me marker text");
        index.index(o);
        assertThat(index.search(alice, "marker", 10)).containsExactly(o.getId());

        index.delete(o.getId());

        assertThat(index.search(alice, "marker", 10)).isEmpty();
    }

    @Test
    @DisplayName("re-indexing the same id overwrites the previous content")
    void reindexOverwrites() {
        ObservationId id = ObservationId.of(ws, "o1");
        Observation original = Observation.builder().id(id).subject(alice).observer(bob)
                .content("original tea-time note").type(ObservationType.EXPLICIT).confidence(0.5d).createdAt(T0)
                .build();
        index.index(original);
        assertThat(index.search(alice, "tea-time", 10)).containsExactly(id);

        Observation updated = Observation.builder().id(id).subject(alice).observer(bob)
                .content("updated coffee-break note").type(ObservationType.EXPLICIT).confidence(0.5d).createdAt(T0)
                .build();
        index.index(updated);

        assertThat(index.search(alice, "tea-time", 10)).isEmpty();
        assertThat(index.search(alice, "coffee-break", 10)).containsExactly(id);
    }

    @Test
    @DisplayName("delete is a no-op for an unknown id")
    void deleteUnknownIdNoOp() {
        ObservationId unknown = ObservationId.of(ws, "ghost");

        index.delete(unknown);

        assertThat(index.search(alice, "anything", 10)).isEmpty();
    }

    @Test
    @DisplayName("blank query returns empty list (does not call KnowledgeStore)")
    void blankQueryShortCircuits() {
        Observation o = obs("o1", alice, "anything");
        index.index(o);

        assertThat(index.search(alice, "", 10)).isEmpty();
        assertThat(index.search(alice, "   ", 10)).isEmpty();
    }

    @Test
    @DisplayName("topK < 1 throws IllegalArgumentException")
    void topKValidation() {
        assertThatThrownBy(() -> index.search(alice, "x", 0)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("topK");
    }

    @Test
    @DisplayName("search respects topK — KnowledgeStore returns at most topK results")
    void searchRespectsTopK() {
        index.index(obs("o1", alice, "match alpha"));
        index.index(obs("o2", alice, "match beta"));
        index.index(obs("o3", alice, "match gamma"));

        List<ObservationId> hits = index.search(alice, "match", 2);

        assertThat(hits).hasSize(2);
    }

    @Test
    @DisplayName("workspaces are isolated — same subject key would collide if not scoped properly")
    void workspaceIsolation() {
        // Same principal id in two different workspaces would share a subject key
        // root segment (TYPE:id) but PeerView.key() includes the workspace id, so
        // the KnowledgeScope context ids differ.
        PeerView aliceWs1 = alice;
        PeerView aliceWs2 = PeerView.of(otherWs, Principal.user("alice", "Alice"));
        PeerView bobWs2 = PeerView.of(otherWs, Principal.user("bob", "Bob"));

        Observation o1 = obs(ws, "o1", aliceWs1, "ws-1 note", 0.8d, T0);
        Observation o2 = obs(otherWs, "o2", aliceWs2, bobWs2, "ws-2 note", 0.8d, T0);
        index.index(o1);
        index.index(o2);

        assertThat(index.search(aliceWs1, "note", 10)).containsExactly(o1.getId());
        assertThat(index.search(aliceWs2, "note", 10)).containsExactly(o2.getId());
    }

    @Test
    @DisplayName("InMemoryObservationStore + KnowledgeStoreObservationIndex end-to-end delegation")
    void storeWithKnowledgeStoreIndex() {
        InMemoryObservationStore store = new InMemoryObservationStore(index);
        Observation o1 = obs("o1", alice, "Alice prefers green tea every morning");
        Observation o2 = obs("o2", alice, "Alice studies Java collections framework");
        store.save(o1);
        store.save(o2);

        // Hydration round-trip: store.semanticSearch -> index.search -> store.findById
        List<Observation> hits = store.semanticSearch(alice, "tea", 10);

        assertThat(hits).extracting(Observation::getId).containsExactly(o1.getId());
        assertThat(hits.get(0).getContent()).contains("green tea");
    }

    @Test
    @DisplayName("constructor rejects null KnowledgeStore")
    void constructorRejectsNullStore() {
        assertThatThrownBy(() -> new KnowledgeStoreObservationIndex(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("knowledgeStore");
    }

    @Test
    @DisplayName("constructor rejects blank agentName")
    void constructorRejectsBlankAgentName() {
        assertThatThrownBy(() -> new KnowledgeStoreObservationIndex(knowledgeStore,
                at.aimon.core.knowledge.IndexOptions.defaults(), "  ")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("agentName");
    }
}
