package at.aimon.core.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.base.Principal;

@DisplayName("InMemoryObservationStore")
class InMemoryObservationStoreTest {

    private static final Instant T0 = Instant.parse("2024-01-15T10:00:00Z");

    private InMemoryObservationStore store;
    private Workspace ws;
    private PeerView alice;
    private PeerView bob;
    private PeerView carol;

    @BeforeEach
    void setUp() {
        store = new InMemoryObservationStore();
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
    @DisplayName("save and findById round-trip")
    void saveFindById() {
        Observation o = obs("o1", alice, "hello", 0.5d, T0);
        store.save(o);

        assertThat(store.findById(o.getId())).isPresent().contains(o);
    }

    @Test
    @DisplayName("delete removes the observation")
    void deleteRemoves() {
        Observation o = obs("o1", alice, "hello", 0.5d, T0);
        store.save(o);

        store.delete(o.getId());

        assertThat(store.findById(o.getId())).isEmpty();
    }

    @Test
    @DisplayName("findBySubject returns newest first and respects limit")
    void findBySubjectNewestFirst() {
        Observation older = obs("o-old", alice, "older", 0.5d, T0);
        Observation newer = obs("o-new", alice, "newer", 0.5d, T0.plusSeconds(60));
        Observation newest = obs("o-newest", alice, "newest", 0.5d, T0.plusSeconds(120));
        store.save(older);
        store.save(newer);
        store.save(newest);

        List<Observation> all = store.findBySubject(alice, 10);
        List<Observation> limited = store.findBySubject(alice, 2);

        assertThat(all).containsExactly(newest, newer, older);
        assertThat(limited).containsExactly(newest, newer);
    }

    @Test
    @DisplayName("findBySubject limit < 1 throws IllegalArgumentException")
    void findBySubjectLimitValidation() {
        assertThatThrownBy(() -> store.findBySubject(alice, 0)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit");
    }

    @Test
    @DisplayName("count returns number of observations for subject")
    void countSubject() {
        store.save(obs("o1", alice, "a", 0.5d, T0));
        store.save(obs("o2", alice, "b", 0.5d, T0));
        store.save(obs("o3", carol, "c", 0.5d, T0));

        assertThat(store.count(alice)).isEqualTo(2);
        assertThat(store.count(carol)).isEqualTo(1);
        assertThat(store.count(bob)).isZero();
    }

    @Test
    @DisplayName("semanticSearch matches by case-insensitive substring")
    void semanticSearchSubstring() {
        store.save(obs("o1", alice, "Alice prefers DARK mode", 0.9d, T0));
        store.save(obs("o2", alice, "Alice likes coffee", 0.8d, T0));
        store.save(obs("o3", alice, "Bob is happy", 0.7d, T0));

        List<Observation> found = store.semanticSearch(alice, "dark", 10);

        assertThat(found).hasSize(1);
        assertThat(found.get(0).getContent()).contains("DARK");
    }

    @Test
    @DisplayName("semanticSearch with blank query returns empty list")
    void semanticSearchBlankQuery() {
        store.save(obs("o1", alice, "anything", 0.5d, T0));

        assertThat(store.semanticSearch(alice, "   ", 10)).isEmpty();
        assertThat(store.semanticSearch(alice, "", 10)).isEmpty();
    }

    @Test
    @DisplayName("semanticSearch respects topK limit")
    void semanticSearchTopK() {
        store.save(obs("o1", alice, "match one", 0.9d, T0));
        store.save(obs("o2", alice, "match two", 0.8d, T0));
        store.save(obs("o3", alice, "match three", 0.7d, T0));

        List<Observation> found = store.semanticSearch(alice, "match", 2);

        assertThat(found).hasSize(2);
    }

    @Test
    @DisplayName("semanticSearch topK < 1 throws IllegalArgumentException")
    void semanticSearchTopKValidation() {
        assertThatThrownBy(() -> store.semanticSearch(alice, "x", 0)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("topK");
    }

    @Test
    @DisplayName("findByConfidenceBelow returns ascending confidence under threshold")
    void findByConfidenceBelow() {
        store.save(obs("low", alice, "a", 0.1d, T0));
        store.save(obs("mid", alice, "b", 0.4d, T0));
        store.save(obs("high", alice, "c", 0.9d, T0));

        List<Observation> below = store.findByConfidenceBelow(alice, 0.5d, 10);

        assertThat(below).hasSize(2);
        assertThat(below.get(0).getConfidence()).isEqualTo(0.1d);
        assertThat(below.get(1).getConfidence()).isEqualTo(0.4d);
    }

    @Test
    @DisplayName("findByConfidenceBelow limit < 1 throws IllegalArgumentException")
    void findByConfidenceBelowLimitValidation() {
        assertThatThrownBy(() -> store.findByConfidenceBelow(alice, 0.5d, 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("limit");
    }

    @Test
    @DisplayName("merge replaces winner content and removes loser")
    void mergeReplacesWinnerRemovesLoser() {
        Observation winner = obs("win", alice, "winner content", 0.5d, T0);
        Observation loser = obs("lose", alice, "loser content", 0.5d, T0);
        store.save(winner);
        store.save(loser);

        Observation merged = obs("win", alice, "merged content", 0.9d, T0.plusSeconds(60));
        Observation result = store.merge(winner.getId(), loser.getId(), merged);

        assertThat(result).isEqualTo(merged);
        assertThat(store.findById(loser.getId())).isEmpty();
        assertThat(store.findById(winner.getId())).isPresent();
        assertThat(store.findById(winner.getId()).get().getContent()).isEqualTo("merged content");
    }

    @Test
    @DisplayName("merge rejects winner == loser")
    void mergeRejectsSameId() {
        Observation o = obs("o1", alice, "a", 0.5d, T0);
        store.save(o);

        assertThatThrownBy(() -> store.merge(o.getId(), o.getId(), o)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("merge rejects merged.id != winner")
    void mergeRejectsMismatchedId() {
        Observation winner = obs("win", alice, "w", 0.5d, T0);
        Observation loser = obs("lose", alice, "l", 0.5d, T0);
        Observation badMerged = obs("other", alice, "m", 0.5d, T0);
        store.save(winner);
        store.save(loser);

        assertThatThrownBy(() -> store.merge(winner.getId(), loser.getId(), badMerged))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("merge soft-deletes the loser into the audit window")
    void mergeSoftDeletesLoser() {
        Observation winner = obs("win", alice, "winner", 0.5d, T0);
        Observation loser = obs("lose", alice, "loser", 0.5d, T0);
        store.save(winner);
        store.save(loser);

        store.merge(winner.getId(), loser.getId(), obs("win", alice, "merged", 0.9d, T0.plusSeconds(60)));

        assertThat(store.findById(loser.getId())).isEmpty();
        assertThat(store.size()).isEqualTo(1);
        assertThat(store.auditSize()).isEqualTo(1);
    }

    @Test
    @DisplayName("merge rejects winner/loser in different workspaces")
    void mergeRejectsCrossWorkspace() {
        Workspace ws2 = Workspace.builder().id("ws-2").build();
        Observation winner = obs("win", alice, "w", 0.5d, T0);
        store.save(winner);
        ObservationId loserInOtherWs = ObservationId.of(ws2, "lose");

        assertThatThrownBy(() -> store.merge(winner.getId(), loserInOtherWs, obs("win", alice, "m", 0.9d, T0)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("softDelete hides from live queries but retains an audit entry")
    void softDeleteRetainsAudit() {
        Observation o = obs("o1", alice, "x", 0.5d, T0);
        store.save(o);

        store.softDelete(o.getId());

        assertThat(store.findById(o.getId())).isEmpty();
        assertThat(store.count(alice)).isZero();
        assertThat(store.size()).isZero();
        assertThat(store.auditSize()).isEqualTo(1);
    }

    @Test
    @DisplayName("softDelete on an absent id is a no-op")
    void softDeleteAbsentNoOp() {
        store.softDelete(ObservationId.of(ws, "nope"));
        assertThat(store.auditSize()).isZero();
    }

    @Test
    @DisplayName("purgeSoftDeletedBefore removes only audit entries older than the cutoff")
    void purgeRemovesExpired() {
        Observation o = obs("o1", alice, "x", 0.5d, T0);
        store.save(o);
        store.softDelete(o.getId());
        assertThat(store.auditSize()).isEqualTo(1);

        // Cutoff in the past → nothing purged (the entry was soft-deleted "now").
        assertThat(store.purgeSoftDeletedBefore(ws, T0)).isZero();
        assertThat(store.auditSize()).isEqualTo(1);

        // Cutoff in the future → the entry is past retention and removed.
        assertThat(store.purgeSoftDeletedBefore(ws, Instant.now().plusSeconds(60))).isEqualTo(1);
        assertThat(store.auditSize()).isZero();
    }

    @Test
    @DisplayName("operations on different subjects are isolated")
    void subjectIsolation() {
        store.save(obs("o1", alice, "alice content", 0.5d, T0));
        store.save(obs("o2", carol, "carol content", 0.5d, T0));

        assertThat(store.findBySubject(alice, 10)).hasSize(1);
        assertThat(store.findBySubject(carol, 10)).hasSize(1);
        assertThat(store.semanticSearch(alice, "carol", 10)).isEmpty();
    }
}
