package at.aimon.core.subagent.execution;

/**
 * Bounds how much of a subagent's final answer is inlined into the parent agent's context (design §6).
 *
 * <p>
 * A subagent can return a very large final answer; inlining it verbatim into the {@code Task}/{@code AgentOutput} tool
 * result pollutes — and can blow — the parent's context window. This formatter keeps the <b>tail</b> of the answer
 * (agent conclusions typically land at the end) up to a character budget and replaces the elided head with an explicit
 * marker, optionally carrying a pointer to where the full output can be retrieved.
 *
 * <p>
 * <b>Tail-keep trade-off.</b> Cutting the middle can break structured output (a JSON body, a fenced code block, a
 * Markdown table). The marker makes the elision explicit, and for background tasks the complete output remains
 * retrievable from the {@code TaskOutputStore} via the supplied pointer, so the loss is recoverable.
 *
 * <p>
 * Stateless utility; not instantiable.
 */
public final class SubagentResultFormatter {

    /** Default tail-keep budget in characters (~32k), overridable per call. */
    public static final int DEFAULT_MAX_CHARS = 32_000;

    private SubagentResultFormatter() {
    }

    /**
     * Truncates {@code content} to the {@link #DEFAULT_MAX_CHARS} tail budget.
     *
     * @param content
     *            the content to bound (null treated as empty)
     * @param retrievalPointer
     *            a short hint on how to retrieve the full output (nullable/blank = omitted from the marker)
     * @return the bounded content (never null)
     */
    public static String truncateTailKeep(String content, String retrievalPointer) {
        return truncateTailKeep(content, DEFAULT_MAX_CHARS, retrievalPointer);
    }

    /**
     * Truncates {@code content} so at most {@code maxChars} tail characters remain, prefixing an
     * {@code …[N chars omitted …]…} marker when anything was dropped.
     *
     * @param content
     *            the content to bound (null treated as empty)
     * @param maxChars
     *            the tail-keep budget in characters; a non-positive value disables truncation
     * @param retrievalPointer
     *            a short hint on how to retrieve the full output (nullable/blank = omitted from the marker)
     * @return the bounded content (never null)
     */
    public static String truncateTailKeep(String content, int maxChars, String retrievalPointer) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        if (maxChars <= 0 || content.length() <= maxChars) {
            return content;
        }

        int cut = content.length() - maxChars;
        // Avoid starting the tail on an orphaned low surrogate whose high surrogate was cut away.
        if (Character.isLowSurrogate(content.charAt(cut)) && Character.isHighSurrogate(content.charAt(cut - 1))) {
            cut++;
        }
        final int omitted = cut;
        final String tail = content.substring(cut);

        final StringBuilder marker = new StringBuilder();
        marker.append("…[").append(omitted).append(" chars omitted");
        if (retrievalPointer != null && !retrievalPointer.isBlank()) {
            marker.append(" — ").append(retrievalPointer.strip());
        }
        marker.append("]…\n");
        return marker.append(tail).toString();
    }
}
