package at.aimon.core.agent.template;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.Mustache;
import com.github.mustachejava.MustacheFactory;

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
 *
 * <p>
 * <b>Implementation note:</b> this renderer intentionally does not participate in the
 * {@link at.aimon.core.agent.prompt.SystemPromptParts parts-based} extension added for
 * {@link at.aimon.core.agent.AgentContentRenderer}. Splitting a raw Mustache template into &quot;system-identity&quot;
 * and &quot;user-content&quot; parts would require parsing the template and classifying each variable as static or
 * dynamic — both out of scope for a minimum-change refactor. The default
 * {@code AgentContentRenderer.renderAsParts(...)} behaviour (wrap the rendered output as a single {@code STATIC}
 * part) therefore applies whenever this renderer is used.
 */
public final class MustacheTemplateRenderer implements TemplateRenderer {
    private final MustacheFactory mustacheFactory;

    /**
     * Creates a new AgentContentRenderer with default Mustache factory.
     */
    public MustacheTemplateRenderer() {
        this.mustacheFactory = new DefaultMustacheFactory();
    }

    /**
     * Creates a new AgentContentRenderer with custom Mustache factory.
     *
     * @param mustacheFactory
     *            The Mustache factory to use (must not be null)
     * @throws NullPointerException
     *             if mustacheFactory is null
     */
    public MustacheTemplateRenderer(MustacheFactory mustacheFactory) {
        this.mustacheFactory = Objects.requireNonNull(mustacheFactory, "Mustache factory cannot be null");
    }

    /**
     * Renders template with given variables.
     *
     * @param template
     *            The Mustache template string (must not be null)
     * @param variables
     *            Variables to substitute (must not be null)
     * @return Rendered string
     * @throws TemplateRenderException
     *             if rendering fails
     */
    @Override
    public String render(String template, Map<String, Object> variables) {
        Objects.requireNonNull(template, "Template cannot be null");
        Objects.requireNonNull(variables, "Variables cannot be null");

        try {
            Mustache mustache = mustacheFactory.compile(new StringReader(template), "agent-template");

            StringWriter writer = new StringWriter();
            mustache.execute(writer, variables).flush();
            return writer.toString();
        } catch (Exception e) {
            throw new TemplateRenderException("Failed to render template: " + e.getMessage(), e);
        }
    }

    /**
     * Renders template with default and override variables.
     *
     * <p>
     * Override variables take precedence over default variables.
     *
     * @param template
     *            The Mustache template string (must not be null)
     * @param defaultVariables
     *            Default variables from template (must not be null)
     * @param overrideVariables
     *            User-provided override variables (must not be null)
     * @return Rendered string
     * @throws TemplateRenderException
     *             if rendering fails
     */
    @Override
    public String render(String template, Map<String, Object> defaultVariables, Map<String, Object> overrideVariables) {
        Objects.requireNonNull(template, "Template cannot be null");
        Objects.requireNonNull(defaultVariables, "Default variables cannot be null");
        Objects.requireNonNull(overrideVariables, "Override variables cannot be null");

        Map<String, Object> merged = new HashMap<>(defaultVariables);
        merged.putAll(overrideVariables); // Override takes precedence
        return render(template, merged);
    }
}
