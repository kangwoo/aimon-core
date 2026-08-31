package at.aimon.core.memory.dreamer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
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
@DisplayName("LlmJudgeSurprisalScorer")
class LlmJudgeSurprisalScorerTest {

    private static final Workspace WS = Workspace.builder().id("ws-1").build();
    private static final PeerView SUBJECT = PeerView.of(WS, Principal.user("alice", "Alice"));
    private static final PeerView OBSERVER = PeerView.of(WS, Principal.user("bob", "Bob"));

    @Mock
    private LlmClient llmClient;

    @Test
    @DisplayName("empty neighbors → 1.0 with zero LLM calls")
    void emptyNeighbors() {
        LlmJudgeSurprisalScorer scorer = new LlmJudgeSurprisalScorer(llmClient, "test-model");

        double score = scorer.score(observation("obs-1", "alpha"), List.of());

        assertThat(score).isEqualTo(1.0d);
        verify(llmClient, never()).sendMessage(anyString(), anyList(), anyList(), any(), any());
    }

    @Test
    @DisplayName("similarity=0.9 → surprisal=0.1")
    void redundantPair() {
        when(llmClient.sendMessage(anyString(), anyList(), anyList(), any(), any()))
                .thenReturn(LlmResponse.text("{\"similarity\": 0.9, \"rationale\": \"near-duplicate\"}"));
        LlmJudgeSurprisalScorer scorer = new LlmJudgeSurprisalScorer(llmClient, "test-model");

        double score = scorer.score(observation("obs-1", "alpha"), List.of(observation("obs-2", "alpha-twin")));

        assertThat(score).isCloseTo(0.1d, within(1e-9d));
        verify(llmClient, times(1)).sendMessage(anyString(), anyList(), anyList(), any(), any());
    }

    @Test
    @DisplayName("similarity=0.0 → surprisal=1.0 (fully novel)")
    void novelPair() {
        when(llmClient.sendMessage(anyString(), anyList(), anyList(), any(), any()))
                .thenReturn(LlmResponse.text("{\"similarity\": 0.0}"));
        LlmJudgeSurprisalScorer scorer = new LlmJudgeSurprisalScorer(llmClient, "test-model");

        double score = scorer.score(observation("obs-1", "alpha"), List.of(observation("obs-2", "beta")));

        assertThat(score).isCloseTo(1.0d, within(1e-9d));
    }

    @Test
    @DisplayName("response wrapped in ```json fences is parsed")
    void codeFencesStripped() {
        when(llmClient.sendMessage(anyString(), anyList(), anyList(), any(), any()))
                .thenReturn(LlmResponse.text("```json\n{\"similarity\": 0.5}\n```"));
        LlmJudgeSurprisalScorer scorer = new LlmJudgeSurprisalScorer(llmClient, "test-model");

        double score = scorer.score(observation("obs-1", "x"), List.of(observation("obs-2", "y")));

        assertThat(score).isCloseTo(0.5d, within(1e-9d));
    }

    @Test
    @DisplayName("similarity above 1.0 is clamped → surprisal=0.0")
    void clampHigh() {
        when(llmClient.sendMessage(anyString(), anyList(), anyList(), any(), any()))
                .thenReturn(LlmResponse.text("{\"similarity\": 1.5}"));
        LlmJudgeSurprisalScorer scorer = new LlmJudgeSurprisalScorer(llmClient, "test-model");

        double score = scorer.score(observation("obs-1", "x"), List.of(observation("obs-2", "y")));

        assertThat(score).isCloseTo(0.0d, within(1e-9d));
    }

    @Test
    @DisplayName("similarity below 0.0 is clamped → surprisal=1.0")
    void clampLow() {
        when(llmClient.sendMessage(anyString(), anyList(), anyList(), any(), any()))
                .thenReturn(LlmResponse.text("{\"similarity\": -0.3}"));
        LlmJudgeSurprisalScorer scorer = new LlmJudgeSurprisalScorer(llmClient, "test-model");

        double score = scorer.score(observation("obs-1", "x"), List.of(observation("obs-2", "y")));

        assertThat(score).isCloseTo(1.0d, within(1e-9d));
    }

    @Test
    @DisplayName("malformed JSON → 1.0 (fail-closed)")
    void malformedJsonFailsClosed() {
        when(llmClient.sendMessage(anyString(), anyList(), anyList(), any(), any()))
                .thenReturn(LlmResponse.text("not-json at all"));
        LlmJudgeSurprisalScorer scorer = new LlmJudgeSurprisalScorer(llmClient, "test-model");

        double score = scorer.score(observation("obs-1", "x"), List.of(observation("obs-2", "y")));

        assertThat(score).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("trailing tokens after the JSON object are rejected → 1.0")
    void trailingTokensRejected() {
        when(llmClient.sendMessage(anyString(), anyList(), anyList(), any(), any())).thenReturn(
                LlmResponse.text("{\"similarity\": 0.9} but actually I think these are unrelated."));
        LlmJudgeSurprisalScorer scorer = new LlmJudgeSurprisalScorer(llmClient, "test-model");

        double score = scorer.score(observation("obs-1", "x"), List.of(observation("obs-2", "y")));

        // Without FAIL_ON_TRAILING_TOKENS the object would parse and surprisal would be 0.1, masking the
        // contradiction in the trailing prose.
        assertThat(score).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("non-object JSON (e.g. array) → 1.0")
    void nonObjectJsonFailsClosed() {
        when(llmClient.sendMessage(anyString(), anyList(), anyList(), any(), any()))
                .thenReturn(LlmResponse.text("[0.5]"));
        LlmJudgeSurprisalScorer scorer = new LlmJudgeSurprisalScorer(llmClient, "test-model");

        double score = scorer.score(observation("obs-1", "x"), List.of(observation("obs-2", "y")));

        assertThat(score).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("missing 'similarity' field → 1.0")
    void missingSimilarityFailsClosed() {
        when(llmClient.sendMessage(anyString(), anyList(), anyList(), any(), any()))
                .thenReturn(LlmResponse.text("{\"rationale\": \"hmm\"}"));
        LlmJudgeSurprisalScorer scorer = new LlmJudgeSurprisalScorer(llmClient, "test-model");

        double score = scorer.score(observation("obs-1", "x"), List.of(observation("obs-2", "y")));

        assertThat(score).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("non-numeric 'similarity' → 1.0")
    void nonNumericSimilarityFailsClosed() {
        when(llmClient.sendMessage(anyString(), anyList(), anyList(), any(), any()))
                .thenReturn(LlmResponse.text("{\"similarity\": \"high\"}"));
        LlmJudgeSurprisalScorer scorer = new LlmJudgeSurprisalScorer(llmClient, "test-model");

        double score = scorer.score(observation("obs-1", "x"), List.of(observation("obs-2", "y")));

        assertThat(score).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("blank LLM response → 1.0")
    void blankResponseFailsClosed() {
        when(llmClient.sendMessage(anyString(), anyList(), anyList(), any(), any())).thenReturn(LlmResponse.text("   "));
        LlmJudgeSurprisalScorer scorer = new LlmJudgeSurprisalScorer(llmClient, "test-model");

        double score = scorer.score(observation("obs-1", "x"), List.of(observation("obs-2", "y")));

        assertThat(score).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("LLM throws RuntimeException → 1.0")
    void runtimeExceptionFailsClosed() {
        when(llmClient.sendMessage(anyString(), anyList(), anyList(), any(), any()))
                .thenThrow(new RuntimeException("provider down"));
        LlmJudgeSurprisalScorer scorer = new LlmJudgeSurprisalScorer(llmClient, "test-model");

        double score = scorer.score(observation("obs-1", "x"), List.of(observation("obs-2", "y")));

        assertThat(score).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("constructor rejects null LlmClient")
    void nullClientRejected() {
        assertThatThrownBy(() -> new LlmJudgeSurprisalScorer(null, "model")).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("constructor rejects null/blank model name")
    void blankModelRejected() {
        assertThatThrownBy(() -> new LlmJudgeSurprisalScorer(llmClient, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new LlmJudgeSurprisalScorer(llmClient, "  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("null observation rejected")
    void nullObservationRejected() {
        LlmJudgeSurprisalScorer scorer = new LlmJudgeSurprisalScorer(llmClient, "model");
        assertThatThrownBy(() -> scorer.score(null, List.of())).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("null neighbor list rejected")
    void nullNeighborsRejected() {
        LlmJudgeSurprisalScorer scorer = new LlmJudgeSurprisalScorer(llmClient, "model");
        assertThatThrownBy(() -> scorer.score(observation("obs-1", "x"), null))
                .isInstanceOf(NullPointerException.class);
    }

    private static Observation observation(String localId, String content) {
        return Observation.builder().id(ObservationId.of(WS, localId)).subject(SUBJECT).observer(OBSERVER)
                .content(content).type(ObservationType.EXPLICIT).createdAt(Instant.parse("2024-01-15T10:00:00Z"))
                .confidence(0.8d).build();
    }
}
