package at.aimon.core.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class McpCallResultTest {

    @Test
    @DisplayName("success() creates non-error result")
    void successCreatesNonError() {
        McpCallResult result = McpCallResult.success("hello");

        assertThat(result.getContent()).isEqualTo("hello");
        assertThat(result.isError()).isFalse();
    }

    @Test
    @DisplayName("error() creates error result")
    void errorCreatesError() {
        McpCallResult result = McpCallResult.error("boom");

        assertThat(result.getContent()).isEqualTo("boom");
        assertThat(result.isError()).isTrue();
    }

    @Test
    @DisplayName("Null content rejected")
    void nullContentRejected() {
        assertThatNullPointerException().isThrownBy(() -> McpCallResult.success(null));
        assertThatNullPointerException().isThrownBy(() -> McpCallResult.error(null));
    }

    @Test
    @DisplayName("Empty content allowed")
    void emptyContentAllowed() {
        assertThat(McpCallResult.success("").getContent()).isEmpty();
        assertThat(McpCallResult.error("").getContent()).isEmpty();
    }
}
