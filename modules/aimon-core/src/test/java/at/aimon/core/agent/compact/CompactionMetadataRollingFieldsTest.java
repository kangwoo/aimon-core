package at.aimon.core.agent.compact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;

/**
 * The observability fields a rolling engine reports (context-engine §10): kind, view shape, summary size and the
 * absorbed seq range.
 */
class CompactionMetadataRollingFieldsTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private static CompactionMetadata.Builder base() {
        return CompactionMetadata.builder().trigger(CompactionTrigger.AUTO).startedAt(NOW).completedAt(NOW);
    }

    @Test
    void aMetadataThatSaysNothingIsAFullCompaction() {
        final CompactionMetadata metadata = base().build();

        assertThat(metadata.getKind()).isEqualTo(CompactionKind.FULL);
        assertThat(metadata.getAbsorbedFromSeq()).isEmpty();
        assertThat(metadata.getAbsorbedToSeq()).isEmpty();
        assertThat(metadata.getHeadTokens()).isZero();
    }

    @Test
    void carriesTheRollingShape() {
        final CompactionMetadata metadata = base().kind(CompactionKind.ROLLING).viewShape(10, 20, 30).summaryTokens(5)
                .absorbedRange(3, 9).build();

        assertThat(metadata.getKind()).isEqualTo(CompactionKind.ROLLING);
        assertThat(metadata.getHeadTokens()).isEqualTo(10);
        assertThat(metadata.getSpanTokens()).isEqualTo(20);
        assertThat(metadata.getTailTokens()).isEqualTo(30);
        assertThat(metadata.getSummaryTokens()).isEqualTo(5);
        assertThat(metadata.getAbsorbedFromSeq()).hasValue(3);
        assertThat(metadata.getAbsorbedToSeq()).hasValue(9);
        assertThat(metadata).isEqualTo(
                base().kind(CompactionKind.ROLLING).viewShape(10, 20, 30).summaryTokens(5).absorbedRange(3, 9).build())
                .isNotEqualTo(base().kind(CompactionKind.ROLLING).build());
        assertThat(metadata.toString()).contains("kind=ROLLING");
    }

    @Test
    void refusesAnEmptyOrNegativeRange() {
        assertThatThrownBy(() -> base().absorbedRange(5, 5).build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> base().absorbedRange(-1, 5).build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> base().viewShape(-1, 0, 0).build()).isInstanceOf(IllegalArgumentException.class);
    }
}
