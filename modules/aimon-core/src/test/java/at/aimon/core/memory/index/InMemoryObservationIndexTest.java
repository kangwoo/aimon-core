package at.aimon.core.memory.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.base.Principal;
import at.aimon.core.memory.Observation;
import at.aimon.core.memory.ObservationId;
import at.aimon.core.memory.ObservationType;
import at.aimon.core.memory.PeerView;
import at.aimon.core.memory.Workspace;

@DisplayName("InMemoryObservationIndex")
class InMemoryObservationIndexTest {

    private static final Instant T0 = Instant.parse("2024-01-15T10:00:00Z");

    private InMemoryObservationIndex index;
    private Workspace ws;
    private PeerView alice;
    private PeerView bob;
    private PeerView carol;

    @BeforeEach
    void setUp() {
        index = new InMemoryObservationIndex();
        ws = Workspace.builder().id("ws-1").build();
        alice = PeerView.of(ws, Principal.user("alice", "Alice"));
        bob = PeerView.of(ws, Principal.user("bob", "Bob"));
        carol = PeerView.of(ws, Principal.user("carol", "Carol"));
    }

    private Observation obs(String localId, PeerView subject, String content, double confidence, Instant createdAt) {
        return Observation.builder().id(ObservationId.of(ws, localId)).subject(subject).observer(bob).content(content)
                .type(ObservationType.EXPLICIT).confidence(confidence).createdAt(createdAt).build();
    }

    @Test
    @DisplayName("index then search returns matching id")
    void indexThenSearch() {
        Observation o = obs("o1", alice, "Alice prefers tea", 0.8d, T0);
        index.index(o);

        List<ObservationId> ids = index.search(alice, "tea", 10);

        assertThat(ids).containsExactly(o.getId());
    }

    @Test
    @DisplayName("search ranks by confidence DESC then recency DESC")
    void searchRanksByConfidenceThenRecency() {
        Observation lowConfOlder = obs("o1", alice, "tea note", 0.4d, T0);
        Observation lowConfNewer = obs("o2", alice, "tea memo", 0.4d, T0.plusSeconds(60));
        Observation highConf = obs("o3", alice, "tea fact", 0.9d, T0);
        index.index(lowConfOlder);
        index.index(lowConfNewer);
        index.index(highConf);

        List<ObservationId> ids = index.search(alice, "tea", 10);

        assertThat(ids).containsExactly(highConf.getId(), lowConfNewer.getId(), lowConfOlder.getId());
    }

    @Test
    @DisplayName("search is case-insensitive substring matching")
    void searchCaseInsensitive() {
        Observation o = obs("o1", alice, "Alice likes DARK MODE", 0.5d, T0);
        index.index(o);

        assertThat(index.search(alice, "dark", 10)).containsExactly(o.getId());
        assertThat(index.search(alice, "MoDe", 10)).containsExactly(o.getId());
    }

    @Test
    @DisplayName("search filters by subject — other subjects' observations not returned")
    void searchSubjectIsolation() {
        Observation aliceObs = obs("o1", alice, "shared keyword", 0.9d, T0);
        Observation carolObs = obs("o2", carol, "shared keyword", 0.9d, T0);
        index.index(aliceObs);
        index.index(carolObs);

        List<ObservationId> aliceHits = index.search(alice, "shared", 10);
        List<ObservationId> carolHits = index.search(carol, "shared", 10);

        assertThat(aliceHits).containsExactly(aliceObs.getId());
        assertThat(carolHits).containsExactly(carolObs.getId());
    }

    @Test
    @DisplayName("search respects topK limit")
    void searchRespectsTopK() {
        index.index(obs("o1", alice, "match one", 0.9d, T0));
        index.index(obs("o2", alice, "match two", 0.8d, T0));
        index.index(obs("o3", alice, "match three", 0.7d, T0));

        List<ObservationId> ids = index.search(alice, "match", 2);

        assertThat(ids).hasSize(2);
    }

    @Test
    @DisplayName("search with blank query returns empty list")
    void searchBlankQueryEmpty() {
        index.index(obs("o1", alice, "anything", 0.5d, T0));

        assertThat(index.search(alice, "", 10)).isEmpty();
        assertThat(index.search(alice, "   ", 10)).isEmpty();
    }

    @Test
    @DisplayName("search topK < 1 throws IllegalArgumentException")
    void searchTopKValidation() {
        assertThatThrownBy(() -> index.search(alice, "x", 0)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("topK");
    }

    @Test
    @DisplayName("index overwrites existing entry for same id (content updates reflect on next search)")
    void indexOverwritesExisting() {
        ObservationId id = ObservationId.of(ws, "o1");
        Observation original = Observation.builder().id(id).subject(alice).observer(bob).content("original tea note")
                .type(ObservationType.EXPLICIT).confidence(0.5d).createdAt(T0).build();
        index.index(original);

        Observation updated = Observation.builder().id(id).subject(alice).observer(bob).content("updated coffee note")
                .type(ObservationType.EXPLICIT).confidence(0.5d).createdAt(T0).build();
        index.index(updated);

        assertThat(index.search(alice, "tea", 10)).isEmpty();
        assertThat(index.search(alice, "coffee", 10)).containsExactly(id);
        assertThat(index.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("delete removes entry from search results")
    void deleteRemovesEntry() {
        Observation o = obs("o1", alice, "delete me", 0.5d, T0);
        index.index(o);
        assertThat(index.search(alice, "delete", 10)).hasSize(1);

        index.delete(o.getId());

        assertThat(index.search(alice, "delete", 10)).isEmpty();
        assertThat(index.size()).isZero();
    }

    @Test
    @DisplayName("delete is a no-op for unknown id")
    void deleteUnknownIdNoOp() {
        ObservationId unknown = ObservationId.of(ws, "ghost");

        index.delete(unknown);

        assertThat(index.size()).isZero();
    }
}
