package at.aimon.core.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

@DisplayName("ToolUseResult render payload Tests")
class ToolUseResultRenderPayloadTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("Should default to no render payload")
    void testRenderPayload_Default_IsNull() {
        ToolUseResult result = ToolUseResult.success("tool_1", "ok");

        assertThat(result.getRenderPayload()).isNull();
    }

    @Test
    @DisplayName("withRenderPayload returns new immutable instance and preserves fields")
    void testWithRenderPayload_PreservesFields() {
        ToolUseResult original = ToolUseResult.success("tool_42", "ok");
        Map<String, Object> payload = Map.of("kind", "metric-series");

        ToolUseResult withPayload = original.withRenderPayload(payload);

        assertThat(withPayload).isNotSameAs(original);
        assertThat(original.getRenderPayload()).isNull();
        assertThat(withPayload.getToolUseId()).isEqualTo("tool_42");
        assertThat(withPayload.getContent()).isEqualTo("ok");
        assertThat(withPayload.isSuccess()).isTrue();
        assertThat(withPayload.getRenderPayload()).isEqualTo(payload);
    }

    @Test
    @DisplayName("withRenderPayload defensively copies the supplied map")
    void testWithRenderPayload_DefensiveCopy() {
        Map<String, Object> source = new HashMap<>();
        source.put("kind", "metric-series");

        ToolUseResult result = ToolUseResult.success("tool_1", "body").withRenderPayload(source);

        source.put("injected", "later");

        assertThat(result.getRenderPayload()).containsOnlyKeys("kind");
    }

    @Test
    @DisplayName("withRenderPayload attached payload map is unmodifiable")
    void testWithRenderPayload_Unmodifiable() {
        ToolUseResult result = ToolUseResult.success("tool_1", "body").withRenderPayload(Map.of("kind", "log-entries"));

        Map<String, Object> payload = result.getRenderPayload();

        assertThatThrownBy(() -> payload.put("mutate", "x")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("withRenderPayload rejects non-null map containing null values")
    void testWithRenderPayload_RejectsNullValuesInMap() {
        Map<String, Object> source = new HashMap<>();
        source.put("kind", null);

        ToolUseResult base = ToolUseResult.success("tool_1", "body");

        assertThatThrownBy(() -> base.withRenderPayload(source)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("withRenderPayload(null) clears the payload")
    void testWithRenderPayload_NullClears() {
        ToolUseResult result = ToolUseResult.success("tool_1", "body")
                .withRenderPayload(Map.of("kind", "metric-series")).withRenderPayload(null);

        assertThat(result.getRenderPayload()).isNull();
    }

    @Test
    @DisplayName("Jackson serialization excludes renderPayload")
    void testJsonIgnore_ExcludesRenderPayload() throws Exception {
        ToolUseResult result = ToolUseResult.success("tool_1", "body")
                .withRenderPayload(Map.of("kind", "metric-series", "block", Map.of("series", "cpu.usage")));

        String json = MAPPER.writeValueAsString(result);

        assertThat(json).doesNotContain("renderPayload");
        assertThat(json).doesNotContain("metric-series");
        assertThat(json).doesNotContain("cpu.usage");
    }

    @Test
    @DisplayName("equals/hashCode includes renderPayload")
    void testEqualsHashCode_IncludesPayload() {
        ToolUseResult a = ToolUseResult.success("tool_1", "body").withRenderPayload(Map.of("kind", "metric-series"));
        ToolUseResult b = ToolUseResult.success("tool_1", "body").withRenderPayload(Map.of("kind", "metric-series"));
        ToolUseResult c = ToolUseResult.success("tool_1", "body").withRenderPayload(Map.of("kind", "log-entries"));
        ToolUseResult none = ToolUseResult.success("tool_1", "body");

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(c);
        assertThat(a).isNotEqualTo(none);
    }
}
