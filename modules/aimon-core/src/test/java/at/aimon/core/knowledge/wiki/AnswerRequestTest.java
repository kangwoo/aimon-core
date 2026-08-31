package at.aimon.core.knowledge.wiki;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("AnswerRequest")
class AnswerRequestTest {

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("question alone derives a default search query")
        void questionDerivesSearchQuery() {
            AnswerRequest req = AnswerRequest.builder().question("How does kubernetes schedule pods?").build();

            assertThat(req.getQuestion()).isEqualTo("How does kubernetes schedule pods?");
            assertThat(req.getMaxContextPages()).isEqualTo(AnswerRequest.DEFAULT_MAX_CONTEXT_PAGES);
            assertThat(req.getSearchQuery().getQueryText()).isEqualTo("How does kubernetes schedule pods?");
            assertThat(req.getSearchQuery().getMaxResults()).isEqualTo(AnswerRequest.DEFAULT_MAX_CONTEXT_PAGES);
        }

        @Test
        @DisplayName("explicit searchQuery is preserved")
        void explicitSearchQueryPreserved() {
            WikiSearchQuery custom = WikiSearchQuery.builder().queryText("kubernetes pods").maxResults(3).build();

            AnswerRequest req = AnswerRequest.builder().question("Q").searchQuery(custom).build();

            assertThat(req.getSearchQuery()).isSameAs(custom);
        }

        @Test
        @DisplayName("custom maxContextPages is preserved")
        void maxContextPagesPreserved() {
            AnswerRequest req = AnswerRequest.builder().question("Q").maxContextPages(8).build();

            assertThat(req.getMaxContextPages()).isEqualTo(8);
        }

        @Test
        @DisplayName("format hint defaults to null")
        void formatDefaultsToNull() {
            AnswerRequest req = AnswerRequest.builder().question("Q").build();

            assertThat(req.getFormat()).isNull();
        }

        @Test
        @DisplayName("format hint is preserved when set")
        void formatPreserved() {
            AnswerRequest req = AnswerRequest.builder().question("Q").format("comparison table").build();

            assertThat(req.getFormat()).isEqualTo("comparison table");
        }

        @Test
        @DisplayName("format hint is trimmed")
        void formatTrimmed() {
            AnswerRequest req = AnswerRequest.builder().question("Q").format("  slide deck  ").build();

            assertThat(req.getFormat()).isEqualTo("slide deck");
        }

        @Test
        @DisplayName("blank format hint is normalized to null")
        void blankFormatNormalizedToNull() {
            AnswerRequest req = AnswerRequest.builder().question("Q").format("   ").build();

            assertThat(req.getFormat()).isNull();
        }
    }

    @Nested
    @DisplayName("Validation")
    class Validation {

        @Test
        @DisplayName("null question throws NullPointerException")
        void nullQuestionThrows() {
            assertThatThrownBy(() -> AnswerRequest.builder().build()).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("blank question throws IllegalArgumentException")
        void blankQuestionThrows() {
            assertThatThrownBy(() -> AnswerRequest.builder().question("   ").build())
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("zero maxContextPages throws IllegalArgumentException")
        void zeroMaxContextPagesThrows() {
            assertThatThrownBy(() -> AnswerRequest.builder().question("Q").maxContextPages(0).build())
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
