package at.aimon.core.agent.input;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TextInput Tests")
class TextInputTest {

    @Test
    @DisplayName("Should create text input via factory method")
    void shouldCreateTextInput() {
        TextInput input = TextInput.of("Hello, world!");

        assertThat(input.getText()).isEqualTo("Hello, world!");
        assertThat(input.asText()).isEqualTo("Hello, world!");
    }

    @Test
    @DisplayName("Should return TEXT type")
    void shouldReturnTextType() {
        TextInput input = TextInput.of("test");

        assertThat(input.getType()).isEqualTo(InputType.TEXT);
    }

    @Test
    @DisplayName("Should reject null text")
    void shouldRejectNullText() {
        assertThatThrownBy(() -> TextInput.of(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Text cannot be null");
    }

    @Test
    @DisplayName("Should allow empty string")
    void shouldAllowEmptyString() {
        TextInput input = TextInput.of("");

        assertThat(input.getText()).isEmpty();
        assertThat(input.asText()).isEmpty();
    }

    @Test
    @DisplayName("Should implement equals correctly")
    void shouldImplementEquals() {
        TextInput input1 = TextInput.of("hello");
        TextInput input2 = TextInput.of("hello");
        TextInput input3 = TextInput.of("world");

        assertThat(input1).isEqualTo(input2);
        assertThat(input1).isNotEqualTo(input3);
        assertThat(input1).isNotEqualTo(null);
    }

    @Test
    @DisplayName("Should implement hashCode correctly")
    void shouldImplementHashCode() {
        TextInput input1 = TextInput.of("hello");
        TextInput input2 = TextInput.of("hello");

        assertThat(input1.hashCode()).isEqualTo(input2.hashCode());
    }

    @Test
    @DisplayName("Should truncate long text in toString")
    void shouldTruncateLongTextInToString() {
        String longText = "a".repeat(100);
        TextInput input = TextInput.of(longText);

        assertThat(input.toString()).contains("TextInput").contains("...");
        assertThat(input.toString().length()).isLessThan(longText.length());
    }

    @Test
    @DisplayName("Should not truncate short text in toString")
    void shouldNotTruncateShortTextInToString() {
        TextInput input = TextInput.of("short");

        assertThat(input.toString()).contains("TextInput").contains("short").doesNotContain("...");
    }
}
