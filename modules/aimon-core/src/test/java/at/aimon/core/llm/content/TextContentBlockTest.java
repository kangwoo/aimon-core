package at.aimon.core.llm.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TextContentBlock Tests")
class TextContentBlockTest {

    @Test
    @DisplayName("Should create text content block")
    void shouldCreateTextContentBlock() {
        TextContentBlock block = TextContentBlock.of("Hello, world!");

        assertThat(block.getType()).isEqualTo("text");
        assertThat(block.getText()).isEqualTo("Hello, world!");
        assertThat(block.asText()).isEqualTo("Hello, world!");
    }

    @Test
    @DisplayName("Should handle empty text")
    void shouldHandleEmptyText() {
        TextContentBlock block = TextContentBlock.of("");

        assertThat(block.getText()).isEmpty();
        assertThat(block.asText()).isEmpty();
    }

    @Test
    @DisplayName("Should reject null text")
    void shouldRejectNullText() {
        assertThatThrownBy(() -> TextContentBlock.of(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Text cannot be null");
    }

    @Test
    @DisplayName("Should implement equals correctly")
    void shouldImplementEquals() {
        TextContentBlock block1 = TextContentBlock.of("Hello");
        TextContentBlock block2 = TextContentBlock.of("Hello");
        TextContentBlock block3 = TextContentBlock.of("Different");

        assertThat(block1).isEqualTo(block2);
        assertThat(block1).isNotEqualTo(block3);
        assertThat(block1).isNotEqualTo(null);
        assertThat(block1).isNotEqualTo("string");
    }

    @Test
    @DisplayName("Should implement hashCode correctly")
    void shouldImplementHashCode() {
        TextContentBlock block1 = TextContentBlock.of("Hello");
        TextContentBlock block2 = TextContentBlock.of("Hello");

        assertThat(block1.hashCode()).isEqualTo(block2.hashCode());
    }

    @Test
    @DisplayName("Should have meaningful toString")
    void shouldHaveMeaningfulToString() {
        TextContentBlock block = TextContentBlock.of("Hello");

        assertThat(block.toString()).contains("TextContentBlock");
        assertThat(block.toString()).contains("Hello");
    }

    @Test
    @DisplayName("Should truncate long text in toString")
    void shouldTruncateLongTextInToString() {
        String longText = "A".repeat(100);
        TextContentBlock block = TextContentBlock.of(longText);

        assertThat(block.toString()).contains("...");
        assertThat(block.toString().length()).isLessThan(longText.length() + 50);
    }
}
