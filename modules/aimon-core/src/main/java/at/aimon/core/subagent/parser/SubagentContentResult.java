package at.aimon.core.subagent.parser;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import at.aimon.core.agent.tool.ProgrammaticToolSettings;
import at.aimon.core.base.DefinitionAttributes;

/**
 * Result of parsing subagent content including metadata and system prompt.
 *
 * <p>
 * Immutable value object returned by SubagentContentParser.
 *
 * <p>
 * The description field should include when and how to use this subagent; the optional {@code when-to-use} field
 * captures the trigger conditions separately when authors prefer to split them.
 *
 * @see SubagentContentParser
 */
public final class SubagentContentResult {
    private final String description;
    private final String whenToUse;
    private final List<String> tools;
    private final String model;
    private final Integer maxIterations;
    private final String systemPrompt;
    private final Map<String, String> attributes;
    private final boolean hidden;
    private final ProgrammaticToolSettings programmatic;

    /**
     * Creates a new SubagentContentResult.
     *
     * @param description
     *            The subagent description including when to use (may be null)
     * @param whenToUse
     *            The trigger conditions for selecting this subagent (may be null)
     * @param tools
     *            The list of allowed tools (must not be null)
     * @param model
     *            The model to use (may be null)
     * @param maxIterations
     *            The maximum ReAct loop iterations, or null to use the default (may be null)
     * @param systemPrompt
     *            The system prompt (must not be null)
     */
    public SubagentContentResult(String description, String whenToUse, List<String> tools, String model,
            Integer maxIterations, String systemPrompt) {
        this(description, whenToUse, tools, model, maxIterations, systemPrompt, Map.of());
    }

    /**
     * Creates a new SubagentContentResult that carries the definition's free-form attributes.
     *
     * @param description
     *            The subagent description including when to use (may be null)
     * @param whenToUse
     *            The trigger conditions for selecting this subagent (may be null)
     * @param tools
     *            The list of allowed tools (must not be null)
     * @param model
     *            The model to use (may be null)
     * @param maxIterations
     *            The maximum ReAct loop iterations, or null to use the default (may be null)
     * @param systemPrompt
     *            The system prompt (must not be null)
     * @param attributes
     *            The flattened {@code attributes} block (must not be null; may be empty)
     */
    public SubagentContentResult(String description, String whenToUse, List<String> tools, String model,
            Integer maxIterations, String systemPrompt, Map<String, String> attributes) {
        this.attributes = DefinitionAttributes.copyOf(Objects.requireNonNull(attributes, "Attributes cannot be null"));
        this.description = description;
        this.whenToUse = whenToUse;
        this.tools = Objects.requireNonNull(tools, "Tools cannot be null");
        this.model = model;
        this.maxIterations = maxIterations;
        this.systemPrompt = Objects.requireNonNull(systemPrompt, "System prompt cannot be null");
        this.hidden = false;
        this.programmatic = ProgrammaticToolSettings.none();
    }

    /** Copies {@code base} with its {@code hidden} flag and programmatic settings replaced. */
    private SubagentContentResult(SubagentContentResult base, boolean hidden, ProgrammaticToolSettings programmatic) {
        this.attributes = base.attributes;
        this.description = base.description;
        this.whenToUse = base.whenToUse;
        this.tools = base.tools;
        this.model = base.model;
        this.maxIterations = base.maxIterations;
        this.systemPrompt = base.systemPrompt;
        this.hidden = hidden;
        this.programmatic = Objects.requireNonNull(programmatic, "programmatic cannot be null");
    }

    /**
     * Returns this result with the definition's {@code hidden} flag set. The constructors leave it {@code false}; the
     * parser calls this with what the frontmatter says.
     *
     * @param hidden
     *            the parsed {@code hidden} value
     * @return a result identical but for the flag (never null)
     */
    public SubagentContentResult withHidden(boolean hidden) {
        return new SubagentContentResult(this, hidden, programmatic);
    }

    /**
     * Returns this result with the definition's programmatic tool calling settings set. The constructors leave them
     * {@link ProgrammaticToolSettings#none()}; the parser calls this with what the frontmatter says.
     *
     * @param programmatic
     *            the parsed settings (must not be null)
     * @return a result identical but for the settings (never null)
     */
    public SubagentContentResult withProgrammatic(ProgrammaticToolSettings programmatic) {
        return new SubagentContentResult(this, hidden, programmatic);
    }

    public String getDescription() {
        return description;
    }

    public String getWhenToUse() {
        return whenToUse;
    }

    public List<String> getTools() {
        return tools;
    }

    public String getModel() {
        return model;
    }

    /**
     * Returns the parsed {@code max-iterations} value, or null when the frontmatter did not specify one.
     *
     * @return the maximum iterations, or null to fall back to the metadata default
     */
    public Integer getMaxIterations() {
        return maxIterations;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    /**
     * Returns the flattened {@code attributes} block.
     *
     * @return the attributes (never null; empty when the frontmatter had none)
     */
    public Map<String, String> getAttributes() {
        return attributes;
    }

    /**
     * Returns the parsed {@code hidden} value.
     *
     * @return {@code true} when the frontmatter says {@code hidden: true}; {@code false} when it says {@code false} or
     *         does not set the key
     */
    public boolean isHidden() {
        return hidden;
    }

    /**
     * Returns the parsed {@code programmatic-tools} / {@code programmatic-only} / {@code programmatic-engine} /
     * {@code shell-engine-tool-scope} settings.
     *
     * @return the settings (never null; {@link ProgrammaticToolSettings#none()} when the frontmatter sets none)
     */
    public ProgrammaticToolSettings getProgrammatic() {
        return programmatic;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final SubagentContentResult that = (SubagentContentResult) o;
        return Objects.equals(description, that.description) && Objects.equals(whenToUse, that.whenToUse)
                && Objects.equals(tools, that.tools) && Objects.equals(model, that.model)
                && Objects.equals(maxIterations, that.maxIterations) && Objects.equals(systemPrompt, that.systemPrompt)
                && attributes.equals(that.attributes) && hidden == that.hidden
                && programmatic.equals(that.programmatic);
    }

    @Override
    public int hashCode() {
        return Objects.hash(description, whenToUse, tools, model, maxIterations, systemPrompt, attributes, hidden,
                programmatic);
    }

    @Override
    public String toString() {
        return "SubagentContentResult{" + "description='" + description + '\'' + ", whenToUse='" + whenToUse + '\''
                + ", tools=" + tools + ", model='" + model + '\'' + ", maxIterations=" + maxIterations
                + ", systemPrompt='" + systemPrompt + '\'' + '}';
    }
}
