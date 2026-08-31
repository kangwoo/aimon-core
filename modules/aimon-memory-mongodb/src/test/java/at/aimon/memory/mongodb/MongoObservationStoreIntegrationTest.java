/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.memory.mongodb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import at.aimon.core.base.Principal;
import at.aimon.core.memory.Observation;
import at.aimon.core.memory.ObservationId;
import at.aimon.core.memory.ObservationType;
import at.aimon.core.memory.PeerView;
import at.aimon.core.memory.Workspace;

@DisplayName("MongoObservationStore integration")
@Tag("docker")
class MongoObservationStoreIntegrationTest {

    private static final Instant T0 = Instant.parse("2025-01-15T10:00:00Z");
    private static final Workspace WS = Workspace.builder().id("ws-1").displayName("Acme").build();
    private static final PeerView ALICE = PeerView.of(WS, Principal.user("alice", "Alice"));
    private static final PeerView OBSERVER = PeerView.of(WS, Principal.system());

    private MongoObservationStore store;

    @BeforeEach
    void setUp() {
        MongoMemoryTestSupport.dropAndApplyDdl();
        // Seed the workspace so findById can hydrate the full Workspace.
        new MongoWorkspaceStore(MongoMemoryTestSupport.sharedDatabase()).create(WS);
        store = new MongoObservationStore(MongoMemoryTestSupport.sharedDatabase());
    }

    @Test
    @DisplayName("save + findById round-trips all fields")
    void saveAndFindById() {
        Observation o = Observation.builder().id(ObservationId.of(WS, "o-1")).subject(ALICE).observer(OBSERVER)
                .content("alice likes tea").type(ObservationType.EXPLICIT).sourceMessageIds(List.of("m1", "m2"))
                .confidence(0.9d).metadata(Map.of("k", "v")).createdAt(T0).build();
        store.save(o);

        Observation read = store.findById(ObservationId.of(WS, "o-1")).orElseThrow();
        assertThat(read.getContent()).isEqualTo("alice likes tea");
        assertThat(read.getType()).isEqualTo(ObservationType.EXPLICIT);
        assertThat(read.getSourceMessageIds()).containsExactly("m1", "m2");
        assertThat(read.getConfidence()).isEqualTo(0.9d);
        assertThat(read.getMetadata()).containsEntry("k", "v");
        assertThat(read.getSubject().getWorkspace().getDisplayName()).isEqualTo("Acme");
    }

    @Test
    @DisplayName("findBySubject returns newest first and respects the limit")
    void findBySubjectNewestFirst() {
        store.save(obs("old", "older", 0.5d, T0));
        store.save(obs("new", "newer", 0.5d, T0.plusSeconds(60)));
        store.save(obs("newest", "newest", 0.5d, T0.plusSeconds(120)));

        assertThat(store.findBySubject(ALICE, 10)).extracting(o -> o.getId().getLocalId()).containsExactly("newest",
                "new", "old");
        assertThat(store.findBySubject(ALICE, 2)).hasSize(2);
        assertThat(store.count(ALICE)).isEqualTo(3);
    }

    @Test
    @DisplayName("findByConfidenceBelow returns ascending confidence under the threshold")
    void findByConfidenceBelow() {
        store.save(obs("a", "a", 0.2d, T0));
        store.save(obs("b", "b", 0.5d, T0));
        store.save(obs("c", "c", 0.9d, T0));

        assertThat(store.findByConfidenceBelow(ALICE, 0.6d, 10)).extracting(Observation::getConfidence)
                .containsExactly(0.2d, 0.5d);
    }

    @Test
    @DisplayName("findSubjects lists distinct live subjects in the workspace")
    void findSubjects() {
        PeerView bob = PeerView.of(WS, Principal.user("bob", "Bob"));
        store.save(obs("a1", "a", 0.5d, T0));
        store.save(obs("a2", "a", 0.5d, T0));
        store.save(Observation.builder().id(ObservationId.of(WS, "b1")).subject(bob).observer(OBSERVER).content("b")
                .type(ObservationType.EXPLICIT).confidence(0.5d).createdAt(T0).build());

        assertThat(store.findSubjects(WS, 10)).extracting(PeerView::key).containsExactlyInAnyOrder(ALICE.key(),
                bob.key());
    }

    @Test
    @DisplayName("merge soft-deletes the loser; purge then removes it")
    void mergeSoftDeletesLoserThenPurge() {
        store.save(obs("win", "winner", 0.5d, T0));
        store.save(obs("lose", "loser", 0.5d, T0));

        store.merge(ObservationId.of(WS, "win"), ObservationId.of(WS, "lose"), obs("win", "merged", 0.9d, T0));

        assertThat(store.findById(ObservationId.of(WS, "lose"))).isEmpty();
        assertThat(store.findById(ObservationId.of(WS, "win")).orElseThrow().getContent()).isEqualTo("merged");
        assertThat(store.count(ALICE)).isEqualTo(1);

        // Past-cutoff purge removes the soft-deleted loser permanently.
        assertThat(store.purgeSoftDeletedBefore(WS, Instant.now().plusSeconds(60))).isEqualTo(1);
    }

    @Test
    @DisplayName("softDelete hides from queries; save with the same id resurrects it")
    void softDeleteThenResurrect() {
        store.save(obs("o-1", "x", 0.5d, T0));
        store.softDelete(ObservationId.of(WS, "o-1"));
        assertThat(store.findById(ObservationId.of(WS, "o-1"))).isEmpty();
        assertThat(store.count(ALICE)).isZero();

        store.save(obs("o-1", "x-again", 0.6d, T0));
        assertThat(store.findById(ObservationId.of(WS, "o-1"))).isPresent();
        assertThat(store.count(ALICE)).isEqualTo(1);
    }

    @Test
    @DisplayName("purge keeps soft-deleted rows newer than the cutoff")
    void purgeRespectsCutoff() {
        store.save(obs("o-1", "x", 0.5d, T0));
        store.softDelete(ObservationId.of(WS, "o-1"));
        // Cutoff in the past → nothing purged.
        assertThat(store.purgeSoftDeletedBefore(WS, T0)).isZero();
    }

    @Test
    @DisplayName("semanticSearch throws (metadata-only store)")
    void semanticSearchThrows() {
        assertThatThrownBy(() -> store.semanticSearch(ALICE, "tea", 5))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("observations are isolated by workspace")
    void multiTenantIsolation() {
        Workspace ws2 = Workspace.builder().id("ws-2").build();
        PeerView aliceWs2 = PeerView.of(ws2, Principal.user("alice", "Alice"));
        store.save(obs("a", "ws1 fact", 0.5d, T0));
        store.save(Observation.builder().id(ObservationId.of(ws2, "b")).subject(aliceWs2).observer(aliceWs2)
                .content("ws2 fact").type(ObservationType.EXPLICIT).confidence(0.5d).createdAt(T0).build());

        assertThat(store.count(ALICE)).isEqualTo(1);
        assertThat(store.count(aliceWs2)).isEqualTo(1);
        assertThat(store.findBySubject(ALICE, 10)).extracting(Observation::getContent).containsExactly("ws1 fact");
    }

    @Test
    @DisplayName("merge rejects winner/loser in different workspaces")
    void mergeRejectsCrossWorkspace() {
        store.save(obs("win", "w", 0.5d, T0));
        ObservationId loserOtherWs = ObservationId.of(Workspace.builder().id("ws-2").build(), "lose");
        assertThatThrownBy(() -> store.merge(ObservationId.of(WS, "win"), loserOtherWs, obs("win", "m", 0.9d, T0)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Observation obs(String localId, String content, double confidence, Instant createdAt) {
        return Observation.builder().id(ObservationId.of(WS, localId)).subject(ALICE).observer(OBSERVER)
                .content(content).type(ObservationType.EXPLICIT).confidence(confidence).createdAt(createdAt).build();
    }
}
