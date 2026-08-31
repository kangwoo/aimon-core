package at.aimon.core.knowledge.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class EmbeddingResultTest {

    @Test
    void shouldBuildWithValidValues() {
        final float[] vector = {0.1f, 0.2f, 0.3f};
        final EmbeddingResult result = EmbeddingResult.builder().vector(vector).tokenCount(5).build();

        assertThat(result.getVector()).containsExactly(0.1f, 0.2f, 0.3f);
        assertThat(result.getDimensions()).isEqualTo(3);
        assertThat(result.getTokenCount()).isEqualTo(5);
    }

    @Test
    void shouldDefensivelyCopyVector() {
        final float[] original = {1.0f, 2.0f};
        final EmbeddingResult result = EmbeddingResult.builder().vector(original).tokenCount(1).build();

        // Mutate original — should not affect result
        original[0] = 999.0f;
        assertThat(result.getVector()[0]).isEqualTo(1.0f);

        // Mutate returned vector — should not affect result
        final float[] returned = result.getVector();
        returned[0] = 888.0f;
        assertThat(result.getVector()[0]).isEqualTo(1.0f);
    }

    @Test
    void shouldRejectNullVector() {
        assertThatThrownBy(() -> EmbeddingResult.builder().tokenCount(0).build())
                .isInstanceOf(NullPointerException.class).hasMessageContaining("vector");
    }

    @Test
    void shouldRejectEmptyVector() {
        assertThatThrownBy(() -> EmbeddingResult.builder().vector(new float[0]).tokenCount(0).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("vector must not be empty");
    }

    @Test
    void shouldRejectNegativeTokenCount() {
        assertThatThrownBy(() -> EmbeddingResult.builder().vector(new float[]{1.0f}).tokenCount(-1).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("tokenCount");
    }

    @Test
    void shouldAllowZeroTokenCount() {
        final EmbeddingResult result = EmbeddingResult.builder().vector(new float[]{1.0f}).tokenCount(0).build();
        assertThat(result.getTokenCount()).isEqualTo(0);
    }

    @Test
    void toStringShouldContainKeyInfo() {
        final EmbeddingResult result = EmbeddingResult.builder().vector(new float[]{1.0f, 2.0f, 3.0f}).tokenCount(10)
                .build();
        assertThat(result.toString()).contains("dimensions=3");
        assertThat(result.toString()).contains("tokenCount=10");
    }
}
