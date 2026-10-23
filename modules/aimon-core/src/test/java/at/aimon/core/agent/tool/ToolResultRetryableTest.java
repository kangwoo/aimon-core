package at.aimon.core.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

@DisplayName("ToolResult retryable flag")
class ToolResultRetryableTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("every existing factory produces a retryable result")
    void factoriesAreRetryable() {
        assertThat(ToolResult.success("ok").isRetryable()).isTrue();
        assertThat(ToolResult.error("failed").isRetryable()).isTrue();
        assertThat(ToolResult.error("failed", new IOException("io")).isRetryable()).isTrue();
        assertThat(ToolResult.error(new IOException("io")).isRetryable()).isTrue();
    }

    @Test
    @DisplayName("notRetryable on an error returns a new error that keeps everything else")
    void notRetryableKeepsTheRest() {
        final IOException cause = new IOException("too big");
        final ToolResult original = ToolResult.error("SIZE_LIMIT: too large", cause)
                .withRenderPayload(Map.of("kind", "size"));

        final ToolResult marked = original.notRetryable();

        assertThat(marked).isNotSameAs(original);
        assertThat(original.isRetryable()).isTrue();
        assertThat(marked.isRetryable()).isFalse();
        assertThat(marked.isError()).isTrue();
        assertThat(marked.getContent()).isEqualTo("SIZE_LIMIT: too large");
        assertThat(marked.getException()).containsSame(cause);
        assertThat(marked.getRenderPayload()).containsEntry("kind", "size");
    }

    @Test
    @DisplayName("notRetryable on a success is a no-op returning the same instance")
    void notRetryableOnSuccessIsANoOp() {
        final ToolResult success = ToolResult.success("ok");

        assertThat(success.notRetryable()).isSameAs(success);
        assertThat(success.notRetryable().isRetryable()).isTrue();
    }

    @Test
    @DisplayName("withRenderPayload keeps the mark")
    void renderPayloadKeepsTheMark() {
        final ToolResult marked = ToolResult.error("AUTH_DENIED").notRetryable().withRenderPayload(Map.of("k", "v"));

        assertThat(marked.isRetryable()).isFalse();
    }

    @Test
    @DisplayName("equals, hashCode and toString take the flag into account")
    void equalityIncludesTheFlag() {
        final ToolResult a = ToolResult.error("AUTH_DENIED").notRetryable();
        final ToolResult b = ToolResult.error("AUTH_DENIED").notRetryable();
        final ToolResult retryable = ToolResult.error("AUTH_DENIED");

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(retryable);
        assertThat(a.toString()).contains("retryable=false");
        assertThat(retryable.toString()).doesNotContain("retryable");
    }

    @Test
    @DisplayName("Jackson serialization excludes the flag")
    void jsonExcludesTheFlag() throws Exception {
        final String json = MAPPER.writeValueAsString(ToolResult.error("AUTH_DENIED").notRetryable());

        assertThat(json).doesNotContain("retryable");
    }
}
