package at.aimon.core.subagent.parser;

import java.util.List;
import java.util.Objects;

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
        this.description = description;
        this.whenToUse = whenToUse;
        this.tools = Objects.requireNonNull(tools, "Tools cannot be null");
        this.model = model;
        this.maxIterations = maxIterations;
        this.systemPrompt = Objects.requireNonNull(systemPrompt, "System prompt cannot be null");
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
                && Objects.equals(maxIterations, that.maxIterations) && Objects.equals(systemPrompt, that.systemPrompt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(description, whenToUse, tools, model, maxIterations, systemPrompt);
    }

    @Override
    public String toString() {
        return "SubagentContentResult{" + "description='" + description + '\'' + ", whenToUse='" + whenToUse + '\''
                + ", tools=" + tools + ", model='" + model + '\'' + ", maxIterations=" + maxIterations
                + ", systemPrompt='" + systemPrompt + '\'' + '}';
    }
}
