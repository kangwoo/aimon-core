package at.aimon.core.agent;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import at.aimon.core.agent.prompt.Staticness;
import at.aimon.core.agent.prompt.SystemPromptPart;
import at.aimon.core.agent.prompt.SystemPromptParts;
import at.aimon.core.agent.template.MustacheTemplateRenderer;
import at.aimon.core.agent.template.TemplateRenderException;
import at.aimon.core.agent.template.TemplateRenderer;

/**
 * Renders Mustache templates with variables.
 *
 * <p>
 * Thread-safe renderer using Mustache.java library.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     AgentContentRenderer renderer = new AgentContentRenderer();
 *     String template = "Hello {{name}}, you are {{age}} years old.";
 *     Map&lt;String, Object&gt; variables = Map.of("name", "John", "age", 30);
 *     String result = renderer.render(template, variables);
 *     // result: "Hello John, you are 30 years old."
 * }
 * </pre>
 */
public final class AgentContentRenderer {

    /**
     * {@link SystemPromptPart#getKind() kind} used by the default {@code renderAsParts(...)} fallback when the renderer
     * cannot distinguish static from dynamic regions of the agent content.
     */
    public static final String AGENT_CONTENT_KIND = "agent-content";

    private final TemplateRenderer templateRenderer;

    /**
     * Creates a new AgentContentRenderer with default Mustache factory.
     */
    public AgentContentRenderer() {
        this.templateRenderer = new MustacheTemplateRenderer();
    }

    /**
     * Creates a new AgentContentRenderer with a custom template renderer.
     *
     * @param templateRenderer
     *            the template renderer to use (must not be null)
     */
    public AgentContentRenderer(TemplateRenderer templateRenderer) {
        this.templateRenderer = Objects.requireNonNull(templateRenderer, "Template renderer cannot be null");
    }

    /**
     * Renders AgentContent's system prompt with its variables.
     *
     * @param content
     *            The AgentContent to render (must not be null)
     * @return Rendered string
     * @throws TemplateRenderException
     *             if rendering fails
     */
    public String render(AgentContent content) {
        Objects.requireNonNull(content, "AgentContent cannot be null");
        return templateRenderer.render(content.getSystemPrompt(), content.getVariables());
    }

    /**
     * Renders AgentContent's system prompt with its variables overridden by given variables.
     *
     * <p>
     * Override variables take precedence over AgentContent's variables.
     *
     * @param content
     *            The AgentContent to render (must not be null)
     * @param overrideVariables
     *            User-provided override variables (must not be null)
     * @return Rendered string
     * @throws TemplateRenderException
     *             if rendering fails
     */
    public String render(AgentContent content, Map<String, Object> overrideVariables) {
        Objects.requireNonNull(content, "AgentContent cannot be null");
        Objects.requireNonNull(overrideVariables, "Override variables cannot be null");
        return templateRenderer.render(content.getSystemPrompt(), content.getVariables(), overrideVariables);
    }

    /**
     * Renders AgentContent's system prompt as structured {@link SystemPromptParts}.
     *
     * <p>
     * This overload is additive to the {@link #render(AgentContent)} API so existing callers continue to work unchanged
     * — the concatenated form is always available via {@link SystemPromptParts#concatenated()}.
     *
     * <p>
     * <b>Concatenation invariant.</b> The following holds for every {@link AgentContent}:
     *
     * <pre>
     * renderer.renderAsParts(content).concatenated().equals(renderer.render(content))
     * </pre>
     *
     * That is, switching to the parts-based form never changes the text that downstream LLM clients receive.
     *
     * <p>
     * <b>Extension point.</b> The default implementation here treats the rendered output as opaque and wraps it in a
     * single {@link Staticness#STATIC STATIC} part with {@link #AGENT_CONTENT_KIND kind {@code "agent-content"}}.
     * Future
     * specialised renderers that can cleanly separate static regions (for example, the agent identity) from dynamic
     * ones (for example, per-request variables) <b>should</b> override this method to emit multiple parts with more
     * precise staticness classifications. Doing so enables downstream provider adapters to place cache boundaries at
     * the right seams.
     *
     * @param content
     *            the AgentContent to render (must not be null)
     * @return the rendered parts, containing at least one part; never {@code null}
     * @throws TemplateRenderException
     *             if rendering fails
     */
    public SystemPromptParts renderAsParts(AgentContent content) {
        Objects.requireNonNull(content, "AgentContent cannot be null");
        final String rendered = render(content);
        return wrapAsSingleStaticPart(rendered);
    }

    /**
     * Renders AgentContent's system prompt as structured {@link SystemPromptParts} with override variables.
     *
     * <p>
     * This overload is additive to {@link #render(AgentContent, Map)} and preserves the concatenation invariant:
     *
     * <pre>
     * renderer.renderAsParts(content, overrides).concatenated().equals(renderer.render(content, overrides))
     * </pre>
     *
     * <p>
     * See {@link #renderAsParts(AgentContent)} for the rationale behind the single-part default and when to override.
     *
     * @param content
     *            the AgentContent to render (must not be null)
     * @param overrideVariables
     *            user-provided override variables (must not be null)
     * @return the rendered parts, containing at least one part; never {@code null}
     * @throws TemplateRenderException
     *             if rendering fails
     */
    public SystemPromptParts renderAsParts(AgentContent content, Map<String, Object> overrideVariables) {
        Objects.requireNonNull(content, "AgentContent cannot be null");
        Objects.requireNonNull(overrideVariables, "Override variables cannot be null");
        final String rendered = render(content, overrideVariables);
        return wrapAsSingleStaticPart(rendered);
    }

    /**
     * Wraps an opaque rendered string in a single {@link Staticness#STATIC STATIC} part.
     *
     * <p>
     * {@link SystemPromptPart} forbids empty content; when the rendered output is empty this method returns
     * {@link SystemPromptParts#empty()} so the concatenation invariant still holds (both sides yield the empty string).
     */
    private static SystemPromptParts wrapAsSingleStaticPart(String rendered) {
        if (rendered.isEmpty()) {
            return SystemPromptParts.empty();
        }
        return SystemPromptParts.of(List.of(SystemPromptPart.builder().content(rendered).staticness(Staticness.STATIC)
                .kind(AGENT_CONTENT_KIND).build()));
    }

}
