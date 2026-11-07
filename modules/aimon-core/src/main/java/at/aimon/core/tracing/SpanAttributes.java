package at.aimon.core.tracing;

/**
 * The one thing code below a span may do to it: add an attribute. A tool reaches its own {@code TOOL} span through
 * this view ({@code at.aimon.core.tools.ToolContextKeys#TOOL_SPAN}) and can annotate it, but not end, fail or re-parent
 * it — those stay with the loop that opened the span.
 */
public interface SpanAttributes {

    /**
     * Adds an attribute (e.g. iteration, principal, invokerType).
     *
     * @param key
     *            the attribute key (must not be null)
     * @param value
     *            the attribute value (must not be null)
     */
    void setAttribute(String key, String value);
}
