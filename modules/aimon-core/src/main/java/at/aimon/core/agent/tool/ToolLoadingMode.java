package at.aimon.core.agent.tool;

/**
 * Defines the loading strategy for a tool.
 *
 * <ul>
 * <li>{@link #EAGER} — The tool is loaded into the LLM context immediately and is always available.
 * <li>{@link #DEFERRED} — The tool is not exposed to the LLM until it is discovered via a search tool.
 * </ul>
 *
 * @see at.aimon.core.agent.tool.search.SearchableTool
 */
public enum ToolLoadingMode {

    /**
     * The tool is loaded into the LLM context at the start of every session. Always available.
     */
    EAGER,

    /**
     * The tool is hidden from the LLM until explicitly searched for and activated via the tool search mechanism.
     */
    DEFERRED

}
