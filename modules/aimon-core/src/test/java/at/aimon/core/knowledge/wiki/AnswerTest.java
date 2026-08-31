package at.aimon.core.knowledge.wiki;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Answer")
class AnswerTest {

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("builder with all fields stores values")
        void builderWithAllFields() {
            Answer answer = Answer.builder().question("How?").title("How To Foo").text("# How To Foo\n\nDo X.")
                    .sourceRefs(List.of("/wiki/foo.md", "/wiki/bar.md")).llmCallCount(2).build();

            assertThat(answer.getQuestion()).isEqualTo("How?");
            assertThat(answer.getTitle()).isEqualTo("How To Foo");
            assertThat(answer.getText()).contains("# How To Foo");
            assertThat(answer.getSourceRefs()).containsExactly("/wiki/foo.md", "/wiki/bar.md");
            assertThat(answer.getLlmCallCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("sourceRefs default to empty when not set")
        void sourceRefsDefaultEmpty() {
            Answer answer = Answer.builder().question("Q").title("T").text("# T").build();

            assertThat(answer.getSourceRefs()).isEmpty();
        }

        @Test
        @DisplayName("llmCallCount defaults to 0")
        void llmCallCountDefaultZero() {
            Answer answer = Answer.builder().question("Q").title("T").text("# T").build();

            assertThat(answer.getLlmCallCount()).isZero();
        }
    }

    @Nested
    @DisplayName("Validation")
    class Validation {

        @Test
        @DisplayName("blank title throws IllegalArgumentException")
        void blankTitleThrows() {
            assertThatThrownBy(() -> Answer.builder().question("Q").title(" ").text("# T").build())
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("empty text throws IllegalArgumentException")
        void emptyTextThrows() {
            assertThatThrownBy(() -> Answer.builder().question("Q").title("T").text("").build())
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("negative llmCallCount throws IllegalArgumentException")
        void negativeLlmCallCount() {
            assertThatThrownBy(() -> Answer.builder().question("Q").title("T").text("# T").llmCallCount(-1).build())
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("toFiledAnswer")
    class ToFiledAnswer {

        @Test
        @DisplayName("produces a FiledAnswer with same title, text, and source refs")
        void roundTripsToFiledAnswer() {
            Answer answer = Answer.builder().question("Q").title("How To Foo").text("# How To Foo\n\nBody.")
                    .sourceRefs(List.of("/wiki/foo.md")).build();

            FiledAnswer filed = answer.toFiledAnswer();

            assertThat(filed.getTitle()).isEqualTo("How To Foo");
            assertThat(filed.getContent()).contains("Body.");
            assertThat(filed.getSourceRefs()).containsExactly("/wiki/foo.md");
        }
    }

    @Nested
    @DisplayName("Immutability")
    class Immutability {

        @Test
        @DisplayName("sourceRefs list is unmodifiable")
        void sourceRefsImmutable() {
            Answer answer = Answer.builder().question("Q").title("T").text("# T")
                    .sourceRefs(new ArrayList<>(List.of("/a"))).build();

            assertThatThrownBy(() -> answer.getSourceRefs().add("/b"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
