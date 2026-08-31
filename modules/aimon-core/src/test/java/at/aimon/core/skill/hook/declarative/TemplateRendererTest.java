package at.aimon.core.skill.hook.declarative;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.tool.ToolInput;

@DisplayName("TemplateRenderer")
class TemplateRendererTest {

    @Nested
    @DisplayName("string rendering")
    class StringRendering {

        @Test
        @DisplayName("renders ${tool_input.X} from the supplied tool input")
        void rendersToolInputPlaceholder() {
            final TemplateRenderer renderer = TemplateRenderer.builder()
                    .toolInput(ToolInput.of("file_path", "/tmp/foo")).build();

            assertThat(renderer.render("path=${tool_input.file_path}")).isEqualTo("path=/tmp/foo");
        }

        @Test
        @DisplayName("renders nested tool_input via dot path")
        void rendersNestedToolInputPath() {
            final TemplateRenderer renderer = TemplateRenderer.builder()
                    .toolInput(ToolInput.of(Map.of("payload", Map.of("id", 42)))).build();

            assertThat(renderer.render("id=${tool_input.payload.id}")).isEqualTo("id=42");
        }

        @Test
        @DisplayName("renders ${env.X} only from the whitelist (process env is ignored)")
        void rendersEnvFromWhitelistOnly() {
            final TemplateRenderer renderer = TemplateRenderer.builder().envWhitelist(Map.of("API_TOKEN", "secret"))
                    .build();

            assertThat(renderer.render("auth=${env.API_TOKEN}")).isEqualTo("auth=secret");
            // PATH is almost always set in process env; we must NOT leak it.
            assertThat(renderer.render("path=${env.PATH}")).isEqualTo("path=");
        }

        @Test
        @DisplayName("renders ${context.X} from the supplied context map")
        void rendersContextAttribute() {
            final TemplateRenderer renderer = TemplateRenderer.builder().contextAttribute("session_id", "abc-123")
                    .build();

            assertThat(renderer.render("sid=${context.session_id}")).isEqualTo("sid=abc-123");
        }

        @Test
        @DisplayName("missing tool_input/context keys render to empty string")
        void missingKeysRenderToEmpty() {
            final TemplateRenderer renderer = TemplateRenderer.builder().build();
            assertThat(renderer.render("[${tool_input.x}]/[${context.y}]")).isEqualTo("[]/[]");
        }

        @Test
        @DisplayName("unknown ${...} prefixes are left untouched (no replacement)")
        void unknownPrefixIsUntouched() {
            final TemplateRenderer renderer = TemplateRenderer.builder().build();
            // ${foo.bar} is not a valid prefix; the placeholder regex won't match, and the literal stays.
            assertThat(renderer.render("$${foo.bar}")).isEqualTo("$${foo.bar}");
        }

        @Test
        @DisplayName("null template throws NPE")
        void nullTemplateThrows() {
            final TemplateRenderer renderer = TemplateRenderer.builder().build();
            assertThatThrownBy(() -> renderer.render(null)).isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("object rendering")
    class ObjectRendering {

        @Test
        @DisplayName("recursively renders maps and lists, keeping non-string leaves intact")
        void rendersNestedStructures() {
            final TemplateRenderer renderer = TemplateRenderer.builder()
                    .toolInput(ToolInput.of("name", "alice", "count", 3)).build();

            final Object rendered = renderer.renderObject(Map.of("user", "${tool_input.name}", "count",
                    "${tool_input.count}", "tags", List.of("hi-${tool_input.name}", 42)));

            assertThat(rendered).isInstanceOf(Map.class);
            @SuppressWarnings("unchecked")
            final Map<String, Object> map = (Map<String, Object>) rendered;
            assertThat(map).containsEntry("user", "alice").containsEntry("count", "3");
            @SuppressWarnings("unchecked")
            final List<Object> tags = (List<Object>) map.get("tags");
            assertThat(tags).containsExactly("hi-alice", 42);
        }

        @Test
        @DisplayName("null input returns null")
        void nullReturnsNull() {
            final TemplateRenderer renderer = TemplateRenderer.builder().build();
            assertThat(renderer.renderObject(null)).isNull();
        }
    }

    @Test
    @DisplayName("env whitelist is exposed via getEnvWhitelistKeys")
    void exposesEnvWhitelistKeys() {
        final TemplateRenderer renderer = TemplateRenderer.builder().envWhitelist(Map.of("A", "1", "B", "2")).build();

        assertThat(renderer.getEnvWhitelistKeys()).containsExactlyInAnyOrder("A", "B");
    }
}
