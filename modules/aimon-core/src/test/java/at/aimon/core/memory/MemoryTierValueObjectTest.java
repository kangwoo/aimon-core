package at.aimon.core.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.base.Principal;
import at.aimon.core.llm.Message;

/**
 * The invariants the five tiers' value objects hold, and the reasons they hold them.
 *
 * <p>
 * Two run through the whole file. <b>The workspace is never a field</b> — it comes off the query's subject or
 * observer, so a query cannot say A while naming a peer in B. And <b>a signal never contradicts the data it
 * describes</b>: {@code observationsAvailable=false} beside a non-empty list, or a resolved scope that says
 * "whichever answered", are rejected rather than left for a consumer to interpret.
 */
@DisplayName("Memory tier value objects")
class MemoryTierValueObjectTest {

    private static final Workspace WS = Workspace.builder().id("ws-1").build();
    private static final Workspace OTHER_WS = Workspace.builder().id("ws-2").build();

    private static PeerView peer(Workspace workspace, String id) {
        return PeerView.of(workspace, Principal.user(id, id));
    }

    @Nested
    @DisplayName("MemorySnapshotQuery")
    class SnapshotQuery {

        @Test
        @DisplayName("takes its workspace from the subject rather than a field of its own")
        void workspaceComesFromSubject() {
            MemorySnapshotQuery query = MemorySnapshotQuery.builder().subject(peer(WS, "alice")).build();

            assertThat(query.getSubject().getWorkspace()).isEqualTo(WS);
            assertThat(query.getScope()).isEqualTo(MemorySnapshotScope.LOCAL_THEN_GLOBAL);
            assertThat(query.getMode()).isEqualTo(MemoryInjectionMode.SUMMARY_ONLY);
        }

        @Test
        @DisplayName("rejects a subject and observer from different workspaces")
        void crossWorkspacePeersRejected() {
            assertThatThrownBy(() -> MemorySnapshotQuery.builder().subject(peer(WS, "alice"))
                    .observer(peer(OTHER_WS, "bob")).build()).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("same workspace");
        }

        @Test
        @DisplayName("rejects LOCAL scope without an observer — a local view needs someone to be local to")
        void localScopeNeedsObserver() {
            assertThatThrownBy(() -> MemorySnapshotQuery.builder().subject(peer(WS, "alice"))
                    .scope(MemorySnapshotScope.LOCAL).build()).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("LOCAL scope needs an observer");
        }

        @Test
        @DisplayName("rejects a negative token budget")
        void negativeBudgetRejected() {
            assertThatThrownBy(() -> MemorySnapshotQuery.builder().subject(peer(WS, "alice")).maxTokens(-1).build())
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxTokens");
        }
    }

    @Nested
    @DisplayName("MemorySnapshot")
    class Snapshot {

        private MemorySnapshot.Builder valid() {
            return MemorySnapshot.builder().renderedText("Alice prefers dark mode.")
                    .resolvedScope(MemorySnapshotScope.GLOBAL).generatedAt(Instant.parse("2024-01-15T10:00:00Z"));
        }

        @Test
        @DisplayName("defaults to reporting no observations and no confidence, which is the safe reading")
        void conservativeDefaults() {
            MemorySnapshot snapshot = valid().build();

            assertThat(snapshot.isObservationsAvailable()).isFalse();
            assertThat(snapshot.isConfidenceAvailable()).isFalse();
            assertThat(snapshot.getObservations()).isEmpty();
            assertThat(snapshot.isTruncated()).isFalse();
        }

        @Test
        @DisplayName("rejects LOCAL_THEN_GLOBAL as a resolved scope — that value is a preference, not an outcome")
        void resolvedScopeIsAnOutcome() {
            assertThatThrownBy(() -> valid().resolvedScope(MemorySnapshotScope.LOCAL_THEN_GLOBAL).build())
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("request-only");
        }

        @Test
        @DisplayName("rejects observations alongside observationsAvailable=false")
        void flagCannotContradictTheList() {
            Observation observation = Observation.builder().id(ObservationId.of(WS, "o-1")).subject(peer(WS, "alice"))
                    .observer(peer(WS, "bob")).content("x").type(ObservationType.EXPLICIT).confidence(0.5d)
                    .createdAt(Instant.now()).build();

            assertThatThrownBy(() -> valid().observationsAvailable(false).observations(List.of(observation)).build())
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("does not expose them");
        }
    }

    @Nested
    @DisplayName("MemorySearchQuery")
    class SearchQuery {

        @Test
        @DisplayName("rejects a blank phrase and a topK below one")
        void inputBounds() {
            assertThatThrownBy(() -> MemorySearchQuery.builder().subject(peer(WS, "alice")).query("  ").build())
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("blank");
            assertThatThrownBy(
                    () -> MemorySearchQuery.builder().subject(peer(WS, "alice")).query("tea").topK(0).build())
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("topK");
        }

        @Test
        @DisplayName("rejects a minScore outside [0, 1]")
        void minScoreBounds() {
            assertThatThrownBy(
                    () -> MemorySearchQuery.builder().subject(peer(WS, "alice")).query("tea").minScore(1.5d).build())
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("minScore");
        }
    }

    @Nested
    @DisplayName("MemoryHit")
    class Hit {

        @Test
        @DisplayName("leaves score at zero by default — rank is the list position, not an invented number")
        void scoreDefaultsToZero() {
            Observation observation = Observation.builder().id(ObservationId.of(WS, "o-1")).subject(peer(WS, "alice"))
                    .observer(peer(WS, "bob")).content("x").type(ObservationType.EXPLICIT).confidence(0.5d)
                    .createdAt(Instant.now()).build();

            MemoryHit hit = MemoryHit.builder().observation(observation).build();

            assertThat(hit.getScore()).isZero();
            assertThat(hit.getSignals()).isEmpty();
            assertThat(hit.isConfidenceAvailable()).isFalse();
        }
    }

    @Nested
    @DisplayName("ObservationDraft")
    class Draft {

        @Test
        @DisplayName("carries no id — identity is the backend's to assign")
        void draftHasNoIdentity() {
            ObservationDraft draft = ObservationDraft.builder().subject(peer(WS, "alice")).observer(peer(WS, "bob"))
                    .content("Alice prefers dark mode").metadata(Map.of("source", "test")).build();

            assertThat(draft.getType()).isEqualTo(ObservationType.DEDUCTIVE);
            assertThat(draft.getConfidence()).isEqualTo(ObservationDraft.DEFAULT_CONFIDENCE);
            assertThat(draft.getSessionId()).isEmpty();
            assertThat(draft.getMetadata()).containsEntry("source", "test");
        }

        @Test
        @DisplayName("rejects a subject and observer from different workspaces")
        void crossWorkspaceRejected() {
            assertThatThrownBy(() -> ObservationDraft.builder().subject(peer(WS, "alice"))
                    .observer(peer(OTHER_WS, "bob")).content("x").build()).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("same workspace");
        }

        @Test
        @DisplayName("withContent replaces only the content, which is what the redaction decorator needs")
        void withContentPreservesTheRest() {
            ObservationDraft draft = ObservationDraft.builder().subject(peer(WS, "alice")).observer(peer(WS, "bob"))
                    .sessionId("s-1").content("token is sk-123").type(ObservationType.EXPLICIT).confidence(0.9d)
                    .build();

            ObservationDraft redacted = draft.withContent("token is [REDACTED]");

            assertThat(redacted.getContent()).isEqualTo("token is [REDACTED]");
            assertThat(redacted.getSessionId()).contains("s-1");
            assertThat(redacted.getType()).isEqualTo(ObservationType.EXPLICIT);
            assertThat(redacted.getConfidence()).isEqualTo(0.9d);
        }
    }

    @Nested
    @DisplayName("MemoryIngestRequest")
    class IngestRequest {

        @Test
        @DisplayName("rejects an empty message list — there would be nothing to ingest")
        void emptyMessagesRejected() {
            assertThatThrownBy(() -> MemoryIngestRequest.builder().observer(peer(WS, "bob")).sessionId("s-1")
                    .messages(List.of()).build()).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("nothing to ingest");
        }

        @Test
        @DisplayName("withMessages replaces only the messages, which is what the redaction decorator needs")
        void withMessagesPreservesTheRest() {
            MemoryIngestRequest request = MemoryIngestRequest.builder().observer(peer(WS, "bob")).sessionId("s-1")
                    .messages(List.of(Message.user("token is sk-123"))).waitForDerivation(true).build();

            MemoryIngestRequest redacted = request.withMessages(List.of(Message.user("token is [REDACTED]")));

            assertThat(redacted.getMessages()).hasSize(1);
            assertThat(redacted.getSessionId()).isEqualTo("s-1");
            assertThat(redacted.isWaitForDerivation()).isTrue();
        }
    }
}
