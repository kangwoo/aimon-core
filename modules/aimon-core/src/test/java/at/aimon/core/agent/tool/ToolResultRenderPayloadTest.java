package at.aimon.core.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

@DisplayName("ToolResult render payload Tests")
class ToolResultRenderPayloadTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("Should default to no render payload")
    void testRenderPayload_Default_IsNull() {
        ToolResult result = ToolResult.success("ok");

        assertThat(result.getRenderPayload()).isNull();
    }

    @Test
    @DisplayName("withRenderPayload returns new immutable instance")
    void testWithRenderPayload_ReturnsNewInstance() {
        ToolResult original = ToolResult.success("ok");
        Map<String, Object> payload = Map.of("kind", "metric-series");

        ToolResult withPayload = original.withRenderPayload(payload);

        assertThat(withPayload).isNotSameAs(original);
        assertThat(original.getRenderPayload()).isNull();
        assertThat(withPayload.getRenderPayload()).isEqualTo(payload);
        assertThat(withPayload.getContent()).isEqualTo("ok");
        assertThat(withPayload.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("withRenderPayload defensively copies the supplied map")
    void testWithRenderPayload_DefensiveCopy() {
        Map<String, Object> source = new HashMap<>();
        source.put("kind", "metric-series");

        ToolResult result = ToolResult.success("body").withRenderPayload(source);

        source.put("injected", "later");

        assertThat(result.getRenderPayload()).containsOnlyKeys("kind");
    }

    @Test
    @DisplayName("withRenderPayload attached payload map is unmodifiable")
    void testWithRenderPayload_Unmodifiable() {
        ToolResult result = ToolResult.success("body").withRenderPayload(Map.of("kind", "log-entries"));

        Map<String, Object> payload = result.getRenderPayload();

        assertThatThrownBy(() -> payload.put("mutate", "x")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("withRenderPayload rejects non-null map containing null values")
    void testWithRenderPayload_RejectsNullValuesInMap() {
        Map<String, Object> source = new HashMap<>();
        source.put("kind", null);

        ToolResult base = ToolResult.success("body");

        assertThatThrownBy(() -> base.withRenderPayload(source)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("withRenderPayload(null) clears the payload")
    void testWithRenderPayload_NullClears() {
        ToolResult result = ToolResult.success("body").withRenderPayload(Map.of("kind", "metric-series"))
                .withRenderPayload(null);

        assertThat(result.getRenderPayload()).isNull();
    }

    @Test
    @DisplayName("Last withRenderPayload wins")
    void testWithRenderPayload_LastWins() {
        Map<String, Object> first = Map.of("kind", "metric-series");
        Map<String, Object> second = Map.of("kind", "log-entries");

        ToolResult result = ToolResult.success("body").withRenderPayload(first).withRenderPayload(second);

        assertThat(result.getRenderPayload()).isEqualTo(second);
    }

    @Test
    @DisplayName("withRenderPayload works on error results")
    void testWithRenderPayload_OnError() {
        ToolResult result = ToolResult.error("failed").withRenderPayload(Map.of("kind", "retry-hint"));

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).isEqualTo("failed");
        assertThat(result.getRenderPayload()).containsEntry("kind", "retry-hint");
    }

    @Test
    @DisplayName("Jackson serialization excludes renderPayload")
    void testJsonIgnore_ExcludesRenderPayload() throws Exception {
        ToolResult result = ToolResult.success("body")
                .withRenderPayload(Map.of("kind", "metric-series", "block", Map.of("series", "cpu.usage")));

        String json = MAPPER.writeValueAsString(result);

        assertThat(json).doesNotContain("renderPayload");
        assertThat(json).doesNotContain("metric-series");
        assertThat(json).doesNotContain("cpu.usage");
    }

    @Test
    @DisplayName("equals/hashCode includes renderPayload")
    void testEqualsHashCode_IncludesPayload() {
        ToolResult a = ToolResult.success("body").withRenderPayload(Map.of("kind", "metric-series"));
        ToolResult b = ToolResult.success("body").withRenderPayload(Map.of("kind", "metric-series"));
        ToolResult c = ToolResult.success("body").withRenderPayload(Map.of("kind", "log-entries"));
        ToolResult none = ToolResult.success("body");

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(c);
        assertThat(a).isNotEqualTo(none);
    }

    @Test
    @DisplayName("Default success/error factories still produce legacy-equivalent JSON")
    void testBackwardCompatibility_DefaultJson() throws Exception {
        String successJson = MAPPER.writeValueAsString(ToolResult.success("ok"));
        String errorJson = MAPPER.writeValueAsString(ToolResult.error("failed"));

        assertThat(successJson).doesNotContain("renderPayload");
        assertThat(errorJson).doesNotContain("renderPayload");
    }
}
