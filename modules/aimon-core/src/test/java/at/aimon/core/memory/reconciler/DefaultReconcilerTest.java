package at.aimon.core.memory.reconciler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import at.aimon.core.base.Principal;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.memory.Observation;
import at.aimon.core.memory.ObservationId;
import at.aimon.core.memory.ObservationType;
import at.aimon.core.memory.PeerView;
import at.aimon.core.memory.Workspace;

@ExtendWith(MockitoExtension.class)
@DisplayName("DefaultReconciler")
class DefaultReconcilerTest {

    private static final Workspace WS = Workspace.builder().id("ws-1").build();
    private static final PeerView SUBJECT = PeerView.of(WS, Principal.user("alice", "Alice"));
    private static final PeerView OBSERVER = PeerView.of(WS, Principal.user("bob", "Bob"));

    @Mock
    private LlmClient llmClient;

    private DefaultReconciler reconciler() {
        return new DefaultReconciler(llmClient, "test-model");
    }

    @Test
    @DisplayName("constructor rejects null/blank dependencies")
    void constructorValidates() {
        assertThatThrownBy(() -> new DefaultReconciler(null, "m")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DefaultReconciler(llmClient, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DefaultReconciler(llmClient, "  ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("evaluate rejects null candidate / null conflicts")
    void evaluateRejectsNullArgs() {
        DefaultReconciler r = reconciler();
        assertThatThrownBy(() -> r.evaluate(null, List.of())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> r.evaluate(observation("c", "x", 0.5d), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Nested
    @DisplayName("heuristic fast path")
    class HeuristicFastPath {

        @Test
        @DisplayName("empty conflict list → Accept, no LLM call")
        void emptyConflictsAccept() {
            ReconcileDecision decision = reconciler().evaluate(observation("c", "alice likes tea", 0.7d), List.of());

            assertThat(decision).isInstanceOf(ReconcileDecision.Accept.class);
            verify(llmClient, never()).sendMessage(anyString(), anyList(), anyList(), any(), any());
        }

        @Test
        @DisplayName("exact-content duplicate (case-insensitive, trimmed) → Reject without LLM call")
        void exactDuplicateRejects() {
            Observation existing = observation("obs-7", "Alice likes Tea", 0.9d);
            Observation candidate = observation("obs-c", "  alice likes tea  ", 0.6d);

            ReconcileDecision decision = reconciler().evaluate(candidate, List.of(existing));

            assertThat(decision).isInstanceOf(ReconcileDecision.Reject.class);
            ReconcileDecision.Reject reject = (ReconcileDecision.Reject) decision;
            assertThat(reject.getReason()).contains("obs-7");
            verify(llmClient, never()).sendMessage(anyString(), anyList(), anyList(), any(), any());
        }
    }

    @Nested
    @DisplayName("LLM judge path")
    class LlmJudgePath {

        @Test
        @DisplayName("judge returns 'accept' → Accept")
        void llmAcceptDecision() {
            stubLlm("{\"decision\":\"accept\"}");

            ReconcileDecision decision = reconciler().evaluate(observation("c", "candidate", 0.6d),
                    List.of(observation("obs-1", "different fact", 0.7d)));

            assertThat(decision).isInstanceOf(ReconcileDecision.Accept.class);
        }

        @Test
        @DisplayName("judge returns 'reject' with reason → Reject(reason)")
        void llmRejectDecision() {
            stubLlm("{\"decision\":\"reject\", \"reason\":\"contradicts higher-confidence fact\"}");

            ReconcileDecision decision = reconciler().evaluate(observation("c", "alice dislikes tea", 0.4d),
                    List.of(observation("obs-7", "alice loves tea", 0.95d)));

            assertThat(decision).isInstanceOf(ReconcileDecision.Reject.class);
            assertThat(((ReconcileDecision.Reject) decision).getReason()).contains("contradicts");
        }

        @Test
        @DisplayName("judge returns 'replace' with valid target_local_id → Replace")
        void llmReplaceDecision() {
            stubLlm("{\"decision\":\"replace\", \"target_local_id\":\"obs-7\"}");

            ReconcileDecision decision = reconciler().evaluate(observation("c", "alice likes oolong tea", 0.95d),
                    List.of(observation("obs-7", "alice likes tea", 0.6d)));

            assertThat(decision).isInstanceOf(ReconcileDecision.Replace.class);
            assertThat(((ReconcileDecision.Replace) decision).getSupersededId().getLocalId()).isEqualTo("obs-7");
        }

        @Test
        @DisplayName("judge returns 'merge' with target + content + confidence → Merge")
        void llmMergeDecision() {
            stubLlm("""
                    {"decision":"merge",
                     "target_local_id":"obs-7",
                     "merged_content":"alice prefers tea with milk",
                     "merged_confidence":0.9}
                    """);

            ReconcileDecision decision = reconciler().evaluate(observation("c", "alice puts milk in tea", 0.7d),
                    List.of(observation("obs-7", "alice drinks tea", 0.85d)));

            assertThat(decision).isInstanceOf(ReconcileDecision.Merge.class);
            ReconcileDecision.Merge merge = (ReconcileDecision.Merge) decision;
            assertThat(merge.getOtherId().getLocalId()).isEqualTo("obs-7");
            assertThat(merge.getMerged().getContent()).isEqualTo("alice prefers tea with milk");
            assertThat(merge.getMerged().getConfidence()).isEqualTo(0.9d);
        }

        @Test
        @DisplayName("judge merge: confidence clamped to [0, 1]")
        void llmMergeConfidenceClamped() {
            stubLlm("""
                    {"decision":"merge","target_local_id":"obs-7",
                     "merged_content":"x","merged_confidence":42.0}
                    """);

            ReconcileDecision decision = reconciler().evaluate(observation("c", "candidate", 0.6d),
                    List.of(observation("obs-7", "existing", 0.7d)));

            assertThat(((ReconcileDecision.Merge) decision).getMerged().getConfidence()).isEqualTo(1.0d);
        }

        @Test
        @DisplayName("judge replace with hallucinated target_local_id → fallback Accept")
        void llmReplaceWithHallucinatedTarget() {
            stubLlm("{\"decision\":\"replace\", \"target_local_id\":\"obs-NEVER-SEEN\"}");

            ReconcileDecision decision = reconciler().evaluate(observation("c", "candidate", 0.6d),
                    List.of(observation("obs-7", "existing", 0.7d)));

            assertThat(decision).isInstanceOf(ReconcileDecision.Accept.class);
        }

        @Test
        @DisplayName("judge merge missing merged_content → fallback Accept")
        void llmMergeMissingContent() {
            stubLlm("""
                    {"decision":"merge","target_local_id":"obs-7",
                     "merged_confidence":0.5}
                    """);

            ReconcileDecision decision = reconciler().evaluate(observation("c", "candidate", 0.6d),
                    List.of(observation("obs-7", "existing", 0.7d)));

            assertThat(decision).isInstanceOf(ReconcileDecision.Accept.class);
        }

        @Test
        @DisplayName("judge unknown decision keyword → fallback Accept")
        void llmUnknownDecision() {
            stubLlm("{\"decision\":\"defer\"}");

            ReconcileDecision decision = reconciler().evaluate(observation("c", "candidate", 0.6d),
                    List.of(observation("obs-7", "existing", 0.7d)));

            assertThat(decision).isInstanceOf(ReconcileDecision.Accept.class);
        }

        @Test
        @DisplayName("malformed JSON → fallback Accept")
        void llmMalformedJson() {
            stubLlm("not-json-at-all");

            ReconcileDecision decision = reconciler().evaluate(observation("c", "candidate", 0.6d),
                    List.of(observation("obs-7", "existing", 0.7d)));

            assertThat(decision).isInstanceOf(ReconcileDecision.Accept.class);
        }

        @Test
        @DisplayName("LLM client throws → fallback Accept (never propagates)")
        void llmThrows() {
            when(llmClient.sendMessage(anyString(), anyList(), anyList(), any(), any()))
                    .thenThrow(new RuntimeException("LLM down"));

            ReconcileDecision decision = reconciler().evaluate(observation("c", "candidate", 0.6d),
                    List.of(observation("obs-7", "existing", 0.7d)));

            assertThat(decision).isInstanceOf(ReconcileDecision.Accept.class);
        }

        @Test
        @DisplayName("response wrapped in markdown code fences is still parseable")
        void llmResponseWithCodeFences() {
            stubLlm("```json\n{\"decision\":\"accept\"}\n```");

            ReconcileDecision decision = reconciler().evaluate(observation("c", "candidate", 0.6d),
                    List.of(observation("obs-7", "existing", 0.7d)));

            assertThat(decision).isInstanceOf(ReconcileDecision.Accept.class);
        }
    }

    /**
     * Conflict-corpus regression test (roadmap §11 stage 7 acceptance gate). Each
     * row maps a canonical conflict shape to the decision a healthy
     * {@link DefaultReconciler} must produce.
     */
    @Nested
    @DisplayName("conflict corpus regression")
    class ConflictCorpusRegression {

        @Test
        @DisplayName("no conflicts → Accept")
        void noConflicts() {
            ReconcileDecision decision = reconciler().evaluate(observation("c", "fresh fact", 0.7d), List.of());
            assertThat(decision).isInstanceOf(ReconcileDecision.Accept.class);
        }

        @Test
        @DisplayName("exact duplicate → Reject (handled by heuristic, no LLM)")
        void exactDuplicate() {
            ReconcileDecision decision = reconciler().evaluate(observation("c", "alice likes tea", 0.6d),
                    List.of(observation("obs-7", "Alice Likes Tea ", 0.9d)));
            assertThat(decision).isInstanceOf(ReconcileDecision.Reject.class);
            verify(llmClient, never()).sendMessage(anyString(), anyList(), anyList(), any(), any());
        }

        @Test
        @DisplayName("contradictory fact, candidate weaker → Reject (LLM)")
        void contradictoryWeakCandidate() {
            stubLlm("{\"decision\":\"reject\",\"reason\":\"contradicts obs-7 with higher confidence\"}");

            ReconcileDecision decision = reconciler().evaluate(observation("c", "alice dislikes tea", 0.4d),
                    List.of(observation("obs-7", "alice loves tea", 0.95d)));

            assertThat(decision).isInstanceOf(ReconcileDecision.Reject.class);
        }

        @Test
        @DisplayName("contradictory fact, candidate stronger → Replace (LLM)")
        void contradictoryStrongCandidate() {
            stubLlm("{\"decision\":\"replace\",\"target_local_id\":\"obs-7\"}");

            ReconcileDecision decision = reconciler().evaluate(observation("c", "alice loves green tea", 0.95d),
                    List.of(observation("obs-7", "alice likes tea", 0.5d)));

            assertThat(decision).isInstanceOf(ReconcileDecision.Replace.class);
            assertThat(((ReconcileDecision.Replace) decision).getSupersededId().getLocalId()).isEqualTo("obs-7");
        }

        @Test
        @DisplayName("partial overlap → Merge (LLM)")
        void partialOverlapMerge() {
            stubLlm("""
                    {"decision":"merge","target_local_id":"obs-7",
                     "merged_content":"alice drinks oolong tea every morning",
                     "merged_confidence":0.9}
                    """);

            ReconcileDecision decision = reconciler().evaluate(observation("c", "alice drinks tea every morning", 0.8d),
                    List.of(observation("obs-7", "alice drinks oolong tea", 0.7d)));

            assertThat(decision).isInstanceOf(ReconcileDecision.Merge.class);
            assertThat(((ReconcileDecision.Merge) decision).getMerged().getContent())
                    .isEqualTo("alice drinks oolong tea every morning");
        }

        @Test
        @DisplayName("unrelated subject content → Accept (LLM)")
        void unrelatedSubject() {
            stubLlm("{\"decision\":\"accept\"}");

            ReconcileDecision decision = reconciler().evaluate(observation("c", "alice plays chess on weekends", 0.7d),
                    List.of(observation("obs-7", "alice prefers tea", 0.8d)));

            assertThat(decision).isInstanceOf(ReconcileDecision.Accept.class);
        }
    }

    private void stubLlm(String json) {
        when(llmClient.sendMessage(anyString(), anyList(), anyList(), any(), any())).thenReturn(LlmResponse.text(json));
    }

    private static Observation observation(String localId, String content, double confidence) {
        return Observation.builder().id(ObservationId.of(WS, localId)).subject(SUBJECT).observer(OBSERVER)
                .content(content).type(ObservationType.EXPLICIT).createdAt(Instant.parse("2024-01-15T10:00:00Z"))
                .confidence(confidence).build();
    }
}
