package at.aimon.core.agent.compact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CompactionRange}: structural invariants ({@code fromIndex >= 0},
 * {@code toIndex > fromIndex}), accessors, equality, and the {@link CompactionRange#prefix(int)} convenience.
 */
class CompactionRangeTest {

    @Test
    void ofExposesBoundsAndSize() {
        final CompactionRange range = CompactionRange.of(2, 7);

        assertThat(range.getFromIndex()).isEqualTo(2);
        assertThat(range.getToIndex()).isEqualTo(7);
        assertThat(range.size()).isEqualTo(5);
    }

    @Test
    void prefixCreatesRangeStartingAtZero() {
        final CompactionRange range = CompactionRange.prefix(4);

        assertThat(range.getFromIndex()).isZero();
        assertThat(range.getToIndex()).isEqualTo(4);
        assertThat(range.size()).isEqualTo(4);
    }

    @Test
    void singleElementRangeAllowed() {
        final CompactionRange range = CompactionRange.of(3, 4);

        assertThat(range.size()).isEqualTo(1);
    }

    @Test
    void negativeFromIndexRejected() {
        assertThatThrownBy(() -> CompactionRange.of(-1, 5)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fromIndex");
    }

    @Test
    void toIndexEqualToFromIndexRejected() {
        assertThatThrownBy(() -> CompactionRange.of(2, 2)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("toIndex");
    }

    @Test
    void toIndexLessThanFromIndexRejected() {
        assertThatThrownBy(() -> CompactionRange.of(5, 2)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("toIndex");
    }

    @Test
    void prefixWithZeroLengthRejected() {
        assertThatThrownBy(() -> CompactionRange.prefix(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void prefixWithNegativeLengthRejected() {
        assertThatThrownBy(() -> CompactionRange.prefix(-3)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void equalsAndHashCodeReflectBounds() {
        final CompactionRange a = CompactionRange.of(1, 5);
        final CompactionRange b = CompactionRange.of(1, 5);
        final CompactionRange c = CompactionRange.of(1, 6);

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b).isNotEqualTo(c);
    }

    @Test
    void toStringContainsBoundsInHalfOpenNotation() {
        assertThat(CompactionRange.of(2, 5)).hasToString("CompactionRange[2, 5)");
    }
}
