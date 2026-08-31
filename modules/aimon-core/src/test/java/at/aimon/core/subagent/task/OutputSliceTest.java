package at.aimon.core.subagent.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

class OutputSliceTest {

    @Test
    void ofExposesAllFields() {
        OutputSlice slice = OutputSlice.of("hello", 5L, true, true);

        assertThat(slice.getText()).isEqualTo("hello");
        assertThat(slice.getNextOffset()).isEqualTo(5L);
        assertThat(slice.isTruncatedHead()).isTrue();
        assertThat(slice.hasMore()).isTrue();
    }

    @Test
    void emptyAtZeroIsNotTruncatedHead() {
        OutputSlice slice = OutputSlice.empty(0L);

        assertThat(slice.getText()).isEmpty();
        assertThat(slice.getNextOffset()).isZero();
        assertThat(slice.isTruncatedHead()).isFalse();
        assertThat(slice.hasMore()).isFalse();
    }

    @Test
    void emptyAtNonZeroOffsetIsTruncatedHead() {
        OutputSlice slice = OutputSlice.empty(42L);

        assertThat(slice.getText()).isEmpty();
        assertThat(slice.getNextOffset()).isEqualTo(42L);
        assertThat(slice.isTruncatedHead()).isTrue();
        assertThat(slice.hasMore()).isFalse();
    }

    @Test
    void ofRejectsNullText() {
        assertThatNullPointerException().isThrownBy(() -> OutputSlice.of(null, 0L, false, false))
                .withMessageContaining("text");
    }

    @Test
    void toStringReportsLengthNotContent() {
        OutputSlice slice = OutputSlice.of("secret", 6L, false, false);

        assertThat(slice.toString()).contains("length=6").contains("nextOffset=6").doesNotContain("secret");
    }
}
