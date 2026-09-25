package at.aimon.core.agent.compact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;

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
    void theBlockingLimitIsUnreportedUnlessSet() {
        final CompactionMetadata metadata = base().postCompactTokenCount(5000).build();

        assertThat(metadata.getBlockingLimit()).isZero();
        assertThat(metadata.isOverBlockingLimit()).as("no limit, no claim").isFalse();
    }

    @Test
    void aViewAtOrAboveTheReportedLimitIsOverIt() {
        assertThat(base().postCompactTokenCount(950).blockingLimit(950).build().isOverBlockingLimit()).isTrue();
        assertThat(base().postCompactTokenCount(1028).blockingLimit(950).build().isOverBlockingLimit()).isTrue();
        assertThat(base().postCompactTokenCount(949).blockingLimit(950).build().isOverBlockingLimit()).isFalse();
        assertThatThrownBy(() -> base().blockingLimit(-1).build()).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void withBlockingLimitCopiesEveryOtherField() {
        final CompactionMetadata original = base().kind(CompactionKind.ROLLING).preCompactTokenCount(1100)
                .postCompactTokenCount(400).messagesSummarized(7).viewShape(10, 20, 30).summaryTokens(5)
                .absorbedRange(3, 9).discoveredToolNames(List.of("Read")).build();

        final CompactionMetadata stamped = original.withBlockingLimit(950);

        assertThat(stamped.getBlockingLimit()).isEqualTo(950);
        assertThat(stamped).isNotEqualTo(original);
        assertThat(stamped.withBlockingLimit(0)).isEqualTo(original);
        final CompactionMetadata plain = base().build().withBlockingLimit(950);
        assertThat(plain.getAbsorbedFromSeq()).as("an empty range stays empty").isEmpty();
    }

    @Test
    void refusesAnEmptyOrNegativeRange() {
        assertThatThrownBy(() -> base().absorbedRange(5, 5).build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> base().absorbedRange(-1, 5).build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> base().viewShape(-1, 0, 0).build()).isInstanceOf(IllegalArgumentException.class);
    }
}
