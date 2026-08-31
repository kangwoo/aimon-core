package at.aimon.core.agent.tool.search;

import java.util.Objects;

import at.aimon.core.agent.tool.Tool;
import at.aimon.core.agent.tool.ToolLoadingMode;

/**
 * Immutable wrapper that pairs a {@link Tool} with its {@link ToolLoadingMode}.
 *
 * <p>
 * This value object determines whether a tool is loaded eagerly (always visible to the LLM) or deferred (only visible
 * after being discovered through a search).
 *
 * @see ToolLoadingMode
 */
public final class SearchableTool {

    private final Tool tool;
    private final ToolLoadingMode loadingMode;

    private SearchableTool(Tool tool, ToolLoadingMode loadingMode) {
        this.tool = Objects.requireNonNull(tool, "Tool cannot be null");
        this.loadingMode = Objects.requireNonNull(loadingMode, "Loading mode cannot be null");
    }

    /**
     * Creates an eager-loading searchable tool.
     *
     * @param tool
     *            the tool (must not be null)
     * @return a new {@code SearchableTool} with {@link ToolLoadingMode#EAGER}
     */
    public static SearchableTool eager(Tool tool) {
        return new SearchableTool(tool, ToolLoadingMode.EAGER);
    }

    /**
     * Creates a deferred-loading searchable tool.
     *
     * @param tool
     *            the tool (must not be null)
     * @return a new {@code SearchableTool} with {@link ToolLoadingMode#DEFERRED}
     */
    public static SearchableTool deferred(Tool tool) {
        return new SearchableTool(tool, ToolLoadingMode.DEFERRED);
    }

    /**
     * Returns the wrapped tool.
     *
     * @return the tool (never null)
     */
    public Tool getTool() {
        return tool;
    }

    /**
     * Returns the loading mode.
     *
     * @return the loading mode (never null)
     */
    public ToolLoadingMode getLoadingMode() {
        return loadingMode;
    }

    /**
     * Returns {@code true} if this tool is loaded eagerly.
     *
     * @return {@code true} for eager loading
     */
    public boolean isEager() {
        return loadingMode == ToolLoadingMode.EAGER;
    }

    /**
     * Returns {@code true} if this tool is loaded on demand.
     *
     * @return {@code true} for deferred loading
     */
    public boolean isDeferred() {
        return loadingMode == ToolLoadingMode.DEFERRED;
    }

    /**
     * Convenience method that returns the tool name from its definition.
     *
     * @return the tool name (never null)
     */
    public String getName() {
        return tool.getDefinition().getName();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SearchableTool that)) {
            return false;
        }
        return getName().equals(that.getName()) && loadingMode == that.loadingMode;
    }

    @Override
    public int hashCode() {
        return Objects.hash(getName(), loadingMode);
    }

    @Override
    public String toString() {
        return "SearchableTool{name='" + getName() + "', mode=" + loadingMode + '}';
    }

}
