package at.aimon.core.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.prompt.Staticness;
import at.aimon.core.agent.prompt.SystemPromptPart;
import at.aimon.core.agent.prompt.SystemPromptParts;

/**
 * Tests the {@code renderAsParts(...)} extension introduced in CTX-04 and in particular the default-method
 * round-trip invariant: {@code renderAsParts(x).concatenated()} must equal {@code render(x)} bit-equal.
 */
@DisplayName("AgentContentRenderer.renderAsParts default round-trip")
class AgentContentRendererTest {

    private final AgentContentRenderer renderer = new AgentContentRenderer();

    @Test
    @DisplayName("renderAsParts(content).concatenated() equals render(content) for non-empty content")
    void renderAsParts_concatenationEqualsRender_forNonEmptyContent() {
        final AgentContent content = AgentContent.builder().systemPrompt("Hello {{name}}, version {{v}}")
                .variables(Map.of("name", "World", "v", 42)).build();

        final String rendered = renderer.render(content);
        final SystemPromptParts parts = renderer.renderAsParts(content);

        assertThat(parts.concatenated()).isEqualTo(rendered);
    }

    @Test
    @DisplayName("renderAsParts emits exactly one STATIC part using the AGENT_CONTENT_KIND label")
    void renderAsParts_emitsSingleStaticAgentContentPart() {
        final AgentContent content = AgentContent.builder().systemPrompt("Static instructions.").build();

        final SystemPromptParts parts = renderer.renderAsParts(content);

        assertThat(parts.parts()).hasSize(1);
        final SystemPromptPart only = parts.parts().get(0);
        assertThat(only.getStaticness()).isEqualTo(Staticness.STATIC);
        assertThat(only.getKind()).isEqualTo(AgentContentRenderer.AGENT_CONTENT_KIND);
        assertThat(only.getContent()).isEqualTo("Static instructions.");
    }

    @Test
    @DisplayName("renderAsParts(content, overrides).concatenated() equals render(content, overrides)")
    void renderAsParts_concatenationEqualsRender_withOverrides() {
        final AgentContent content = AgentContent.builder().systemPrompt("Hello {{name}}")
                .variables(Map.of("name", "base")).build();

        final Map<String, Object> overrides = Map.of("name", "override");
        final String rendered = renderer.render(content, overrides);
        final SystemPromptParts parts = renderer.renderAsParts(content, overrides);

        assertThat(parts.concatenated()).isEqualTo(rendered);
        assertThat(parts.parts()).hasSize(1);
        assertThat(parts.parts().get(0).getStaticness()).isEqualTo(Staticness.STATIC);
        assertThat(parts.parts().get(0).getKind()).isEqualTo(AgentContentRenderer.AGENT_CONTENT_KIND);
    }

    @Test
    @DisplayName("renderAsParts returns empty parts (not a part with empty content) when render yields empty string")
    void renderAsParts_emptyRender_returnsEmptyParts() {
        final AgentContent content = AgentContent.builder().systemPrompt("").build();

        final SystemPromptParts parts = renderer.renderAsParts(content);

        // Concatenation must still match render() (both empty strings)
        assertThat(parts.concatenated()).isEqualTo(renderer.render(content)).isEmpty();
        // SystemPromptPart.content forbids empty values, so we must not materialise a zero-content part.
        assertThat(parts.isEmpty()).isTrue();
    }
}
