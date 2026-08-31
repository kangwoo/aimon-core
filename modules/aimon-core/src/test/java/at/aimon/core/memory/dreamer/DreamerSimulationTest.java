package at.aimon.core.memory.dreamer;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.base.Principal;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.memory.InMemoryObservationStore;
import at.aimon.core.memory.Observation;
import at.aimon.core.memory.ObservationId;
import at.aimon.core.memory.ObservationStore;
import at.aimon.core.memory.ObservationType;
import at.aimon.core.memory.PeerView;
import at.aimon.core.memory.Workspace;

/**
 * 1k-observation regression simulation per roadmap stage 6.5.
 *
 * <p>
 * Seeds an in-memory store with 1000 synthetic observations partitioned into
 * topics that share identical content (the "redundant" duplicates), runs one
 * {@link DefaultDreamerEngine} cycle, and asserts that the cycle completes
 * cleanly and shrinks the store. Deterministic — no real LLM, no real
 * embedding service, no sleeps. The test exists to catch regressions in:
 *
 * <ul>
 * <li>quadratic blow-ups in the random walk loop (cycle must finish fast),</li>
 * <li>error-handling regressions (no subject should fail),</li>
 * <li>aggregation drift in {@link DreamerCycleSummary}.</li>
 * </ul>
 */
@DisplayName("Dreamer simulation @ 1k observations")
class DreamerSimulationTest {

    private static final int SUBJECT_COUNT = 5;
    private static final int TOPICS_PER_SUBJECT = 4;
    private static final int OBSERVATIONS_PER_TOPIC = 50;
    private static final int OBSERVATIONS_PER_SUBJECT = TOPICS_PER_SUBJECT * OBSERVATIONS_PER_TOPIC;
    private static final int TOTAL_OBSERVATIONS = SUBJECT_COUNT * OBSERVATIONS_PER_SUBJECT;
    private static final long CYCLE_BUDGET_MS = 10_000L;

    private static final Workspace WS = Workspace.builder().id("ws-sim").build();
    private static final PeerView SYSTEM_OBSERVER = PeerView.of(WS, Principal.system());

    @Test
    @DisplayName("cycle on 1000 observations: clean completion, store shrinks, no errors")
    void thousandObservationCycle() {
        InMemoryObservationStore store = new InMemoryObservationStore();
        seed(store);
        assertThat(store.size()).isEqualTo(TOTAL_OBSERVATIONS);

        SurprisalScorer scorer = (obs,
                neighbors) -> neighbors.stream().anyMatch(n -> n.getContent().equals(obs.getContent())) ? 0.05d : 0.95d;
        LlmClient llm = new CannedJsonLlmClient();
        ConsolidationStrategy strategy = new RandomWalkDreamer(store, scorer, llm, "sim-model", 0.2d,
                OBSERVATIONS_PER_SUBJECT, OBSERVATIONS_PER_TOPIC + 5);
        DefaultDreamerEngine engine = new DefaultDreamerEngine(store, strategy, SUBJECT_COUNT * 2);

        long start = System.nanoTime();
        DreamerCycleSummary summary = engine.consolidate(WS);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        assertThat(elapsedMs).as("cycle must stay under budget").isLessThan(CYCLE_BUDGET_MS);
        assertThat(summary.getSubjectsWalked()).isEqualTo(SUBJECT_COUNT);
        assertThat(summary.getErrors()).isZero();
        assertThat(summary.getObservationsMerged()).as("each topic of 50 should collapse to one survivor")
                .isGreaterThan(0);
        assertThat(store.size()).as("merge() drops losers, so store must shrink").isLessThan(TOTAL_OBSERVATIONS);
    }

    private static void seed(ObservationStore store) {
        Instant base = Instant.parse("2025-01-01T00:00:00Z");
        for (int s = 0; s < SUBJECT_COUNT; s++) {
            PeerView subject = PeerView.of(WS, Principal.user("u" + s, "User-" + s));
            for (int t = 0; t < TOPICS_PER_SUBJECT; t++) {
                String topicContent = "u" + s + "-topic-" + (char) ('A' + t);
                for (int n = 0; n < OBSERVATIONS_PER_TOPIC; n++) {
                    String localId = "u" + s + "-t" + t + "-n" + n;
                    Observation obs = Observation.builder().id(ObservationId.of(WS, localId)).subject(subject)
                            .observer(SYSTEM_OBSERVER).content(topicContent).type(ObservationType.EXPLICIT)
                            .createdAt(base.plusSeconds(
                                    ((long) s * OBSERVATIONS_PER_SUBJECT) + ((long) t * OBSERVATIONS_PER_TOPIC) + n))
                            .confidence(0.5d + (n % 5) * 0.05d).build();
                    store.save(obs);
                }
            }
        }
    }

    /** Returns a fixed merge-style JSON payload regardless of input. */
    private static final class CannedJsonLlmClient implements LlmClient {
        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            return LlmResponse.text("{\"content\":\"consolidated\",\"confidence\":0.95}");
        }

        @Override
        public String getProviderName() {
            return "canned";
        }
    }
}
