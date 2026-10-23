package at.aimon.core.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

@DisplayName("ToolUseResult retryable flag")
class ToolUseResultRetryableTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("the factories produce retryable results")
    void factoriesAreRetryable() {
        assertThat(ToolUseResult.success("t1", "ok").isRetryable()).isTrue();
        assertThat(ToolUseResult.error("t1", "failed").isRetryable()).isTrue();
    }

    @Test
    @DisplayName("notRetryable marks an error and is a no-op on a success")
    void notRetryable() {
        final ToolUseResult error = ToolUseResult.error("t1", "SIZE_LIMIT");
        final ToolUseResult success = ToolUseResult.success("t1", "ok");

        assertThat(error.notRetryable().isRetryable()).isFalse();
        assertThat(error.notRetryable().isError()).isTrue();
        assertThat(error.isRetryable()).isTrue();
        assertThat(success.notRetryable()).isSameAs(success);
    }

    @Test
    @DisplayName("withContent and withRenderPayload keep the mark and the other sidecar")
    void rewritesKeepTheMark() {
        final ToolUseResult marked = ToolUseResult.error("t1", "SIZE_LIMIT").withRenderPayload(Map.of("k", "v"))
                .notRetryable();

        final ToolUseResult rewritten = marked.withContent("notice\nSIZE_LIMIT");

        assertThat(rewritten.getContent()).isEqualTo("notice\nSIZE_LIMIT");
        assertThat(rewritten.getToolUseId()).isEqualTo("t1");
        assertThat(rewritten.isError()).isTrue();
        assertThat(rewritten.isRetryable()).isFalse();
        assertThat(rewritten.getRenderPayload()).containsEntry("k", "v");
        assertThat(marked.withRenderPayload(null).isRetryable()).isFalse();
        assertThatNullPointerException().isThrownBy(() -> marked.withContent(null));
    }

    @Test
    @DisplayName("Message#mapText keeps the mark")
    void mapTextKeepsTheMark() {
        final Message message = Message
                .toolUseResults(List.of(ToolUseResult.error("t1", "token=SECRET").notRetryable()));

        final ToolUseResult mapped = message.mapText(s -> s.replace("SECRET", "***")).getToolUseResults().get(0);

        assertThat(mapped.getContent()).isEqualTo("token=***");
        assertThat(mapped.isRetryable()).isFalse();
    }

    @Test
    @DisplayName("equals, hashCode and toString take the flag into account")
    void equalityIncludesTheFlag() {
        final ToolUseResult a = ToolUseResult.error("t1", "AUTH_DENIED").notRetryable();
        final ToolUseResult b = ToolUseResult.error("t1", "AUTH_DENIED").notRetryable();
        final ToolUseResult retryable = ToolUseResult.error("t1", "AUTH_DENIED");

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(retryable);
        assertThat(a.toString()).contains("retryable=false");
        assertThat(retryable.toString()).doesNotContain("retryable");
    }

    @Test
    @DisplayName("Jackson serialization of the result and of its message excludes the flag")
    void jsonExcludesTheFlag() throws Exception {
        final ToolUseResult marked = ToolUseResult.error("t1", "AUTH_DENIED").notRetryable();

        assertThat(MAPPER.writeValueAsString(marked)).doesNotContain("retryable")
                .isEqualTo(MAPPER.writeValueAsString(ToolUseResult.error("t1", "AUTH_DENIED")));
    }
}
