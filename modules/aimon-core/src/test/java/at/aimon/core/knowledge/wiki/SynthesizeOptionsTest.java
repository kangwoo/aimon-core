package at.aimon.core.knowledge.wiki;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("SynthesizeOptions")
class SynthesizeOptionsTest {

    @Nested
    @DisplayName("defaults() factory")
    class DefaultsFactory {

        @Test
        @DisplayName("returns instance with expected default values")
        void defaultsReturnsExpectedValues() {
            SynthesizeOptions options = SynthesizeOptions.defaults();

            assertThat(options.getTypes()).containsExactlyInAnyOrder(WikiPageType.OVERVIEW, WikiPageType.SYNTHESIS);
            assertThat(options.getMaxClusters()).isEqualTo(SynthesizeOptions.DEFAULT_MAX_CLUSTERS);
            assertThat(options.getMaxLlmCalls()).isEqualTo(SynthesizeOptions.DEFAULT_MAX_LLM_CALLS);
            assertThat(options.isOverwrite()).isFalse();
        }

        @Test
        @DisplayName("DEFAULT_TYPES contains OVERVIEW and SYNTHESIS")
        void defaultTypesContent() {
            assertThat(SynthesizeOptions.DEFAULT_TYPES).containsExactlyInAnyOrder(WikiPageType.OVERVIEW,
                    WikiPageType.SYNTHESIS);
        }
    }

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("builder with custom values stores all values")
        void builderWithCustomValues() {
            SynthesizeOptions options = SynthesizeOptions.builder().types(EnumSet.of(WikiPageType.OVERVIEW))
                    .maxClusters(5).maxLlmCalls(7).overwrite(true).build();

            assertThat(options.getTypes()).containsExactly(WikiPageType.OVERVIEW);
            assertThat(options.getMaxClusters()).isEqualTo(5);
            assertThat(options.getMaxLlmCalls()).isEqualTo(7);
            assertThat(options.isOverwrite()).isTrue();
        }

        @Test
        @DisplayName("returned types set is unmodifiable")
        void typesSetUnmodifiable() {
            SynthesizeOptions options = SynthesizeOptions.defaults();

            assertThatThrownBy(() -> options.getTypes().add(WikiPageType.ENTITY))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("Validation")
    class Validation {

        @Test
        @DisplayName("zero maxClusters throws IllegalArgumentException")
        void zeroMaxClustersThrows() {
            assertThatThrownBy(() -> SynthesizeOptions.builder().maxClusters(0).build())
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("zero maxLlmCalls throws IllegalArgumentException")
        void zeroMaxLlmCallsThrows() {
            assertThatThrownBy(() -> SynthesizeOptions.builder().maxLlmCalls(0).build())
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("empty types set throws IllegalArgumentException")
        void emptyTypesThrows() {
            assertThatThrownBy(() -> SynthesizeOptions.builder().types(EnumSet.noneOf(WikiPageType.class)).build())
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("equals/hashCode")
    class Equality {

        @Test
        @DisplayName("two defaults() instances are equal")
        void defaultsAreEqual() {
            assertThat(SynthesizeOptions.defaults()).isEqualTo(SynthesizeOptions.defaults());
            assertThat(SynthesizeOptions.defaults().hashCode()).isEqualTo(SynthesizeOptions.defaults().hashCode());
        }

        @Test
        @DisplayName("differing maxClusters produces inequality")
        void differingMaxClustersUnequal() {
            assertThat(SynthesizeOptions.builder().maxClusters(3).build())
                    .isNotEqualTo(SynthesizeOptions.builder().maxClusters(5).build());
        }
    }
}
