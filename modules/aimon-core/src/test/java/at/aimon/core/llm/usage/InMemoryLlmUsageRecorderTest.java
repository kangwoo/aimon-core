package at.aimon.core.llm.usage;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.TokenUsage;

class InMemoryLlmUsageRecorderTest {

    private InMemoryLlmUsageRecorder recorder;

    @BeforeEach
    void setUp() {
        recorder = new InMemoryLlmUsageRecorder();
    }

    @Test
    @DisplayName("같은 key 에 대한 호출은 합산되어야 한다")
    void aggregatesCallsForSameKey() {
        final LlmCallMetadata metadata = LlmCallMetadata.builder().component("orca").feature("react").build();

        recorder.record("OpenAI", "gpt-4", TokenUsage.of(100, 50, 150), metadata);
        recorder.record("OpenAI", "gpt-4", TokenUsage.of(80, 40, 120), metadata);

        final List<LlmUsageSnapshot> snapshots = recorder.snapshot();

        assertThat(snapshots).hasSize(1);
        final LlmUsageSnapshot snapshot = snapshots.get(0);
        assertThat(snapshot.getCallCount()).isEqualTo(2L);
        assertThat(snapshot.getTotalUsage()).isEqualTo(TokenUsage.of(180, 90, 270));
        assertThat(snapshot.getKey().getComponent()).contains("orca");
        assertThat(snapshot.getKey().getFeature()).contains("react");
    }

    @Test
    @DisplayName("component/feature 가 다르면 별도 엔트리로 집계되어야 한다")
    void keepsSeparateEntriesPerAttribution() {
        recorder.record("OpenAI", "gpt-4", TokenUsage.of(10, 5, 15),
                LlmCallMetadata.builder().component("orca").build());
        recorder.record("OpenAI", "gpt-4", TokenUsage.of(20, 10, 30),
                LlmCallMetadata.builder().component("wiki").build());

        assertThat(recorder.snapshot()).hasSize(2);
    }

    @Test
    @DisplayName("empty metadata 도 정상적으로 집계되어야 한다")
    void handlesEmptyMetadata() {
        recorder.record("Anthropic", "claude-3", TokenUsage.of(50, 25, 75), LlmCallMetadata.empty());

        final List<LlmUsageSnapshot> snapshots = recorder.snapshot();

        assertThat(snapshots).hasSize(1);
        assertThat(snapshots.get(0).getKey().getComponent()).isEmpty();
        assertThat(snapshots.get(0).getCallCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("reset 후에는 스냅샷이 비어 있어야 한다")
    void resetClearsAggregates() {
        recorder.record("OpenAI", "gpt-4", TokenUsage.of(10, 5, 15), LlmCallMetadata.empty());
        recorder.reset();

        assertThat(recorder.snapshot()).isEmpty();
    }
}
