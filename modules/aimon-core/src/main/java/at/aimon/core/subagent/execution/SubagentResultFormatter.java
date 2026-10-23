package at.aimon.core.subagent.execution;

import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.budget.CompletionReason;
import at.aimon.core.subagent.SubagentResultTruncation;

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
 * <b>Other shapes.</b> An answer that puts what matters first — findings, then the list of what was read — loses
 * exactly that to a tail-keep. A trusted execution policy can ask for the head, or for head and tail
 * ({@link #truncate(String, int, SubagentResultTruncation, String)}); the foreground {@code Task} result applies it.
 *
 * <p>
 * Stateless utility; not instantiable.
 */
public final class SubagentResultFormatter {

    /** Default tail-keep budget in characters (~32k), overridable per call. */
    public static final int DEFAULT_MAX_CHARS = 32_000;

    /**
     * What a {@code QUOTA_EXCEEDED} reason line adds for the parent model. The quota is shared by the whole execution
     * tree, so a retry or another subagent would be stopped before its first LLM call, the same way.
     */
    public static final String QUOTA_EXCEEDED_HINT = "a usage quota this whole task is charged to is used up; "
            + "retrying or launching another subagent will stop the same way — finish with what you have";

    /**
     * What a {@code MAX_ITERATIONS_GRACE} reason line adds for the parent model: the answer is what the subagent had
     * when it ran out of iterations, written in a final round without tools.
     */
    public static final String MAX_ITERATIONS_GRACE_HINT = "the subagent reached its iteration limit and wrote up "
            + "what it had in a final round without tools; its work is incomplete";

    private SubagentResultFormatter() {
    }

    /**
     * The {@code Completion reason:} line that follows a subagent's result when it did not finish on its own terms,
     * shared by the foreground {@code Task} result, the background {@code AgentOutput} result and the background
     * completion notice so the three say it in the same words.
     *
     * <p>
     * A {@link CompletionReason#QUOTA_EXCEEDED} line also tells the parent model what not to do: the quota is the whole
     * execution tree's, so launching another subagent, or retrying this one, is stopped the same way.
     *
     * @param reason
     *            the result's completion reason
     * @param success
     *            whether the result carries an answer; a successful result with an unsuccessful reason is an answer
     *            that is not whole (a final answer cut at {@code max_tokens}), and the line says so
     * @return the line without a trailing newline, or empty when the reason is a successful one
     */
    public static Optional<String> completionReasonLine(CompletionReason reason, boolean success) {
        Objects.requireNonNull(reason, "Completion reason cannot be null");
        if (reason.isSuccessful()) {
            return Optional.empty();
        }
        if (reason == CompletionReason.QUOTA_EXCEEDED) {
            return Optional.of("Completion reason: " + reason.name() + " (" + QUOTA_EXCEEDED_HINT + ")");
        }
        if (reason == CompletionReason.MAX_ITERATIONS_GRACE) {
            return Optional.of("Completion reason: " + reason.name() + " (" + MAX_ITERATIONS_GRACE_HINT + ")");
        }
        // It answered, but not on its own terms: the answer is there and it is not whole.
        return Optional.of("Completion reason: " + reason.name()
                + (success ? " (the subagent's final answer is incomplete)" : ""));
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

    /**
     * Bounds {@code content} to {@code maxChars}, keeping the part {@code truncation} names and marking what was left
     * out with an {@code …[N chars omitted …]…} marker where it was: before the tail, after the head, or between the
     * two.
     *
     * @param content
     *            the content to bound (null treated as empty)
     * @param maxChars
     *            the budget in characters; a non-positive value disables truncation
     * @param truncation
     *            which part to keep (must not be null)
     * @param retrievalPointer
     *            a short hint on how to retrieve the full output (nullable/blank = omitted from the marker)
     * @return the bounded content (never null)
     */
    public static String truncate(String content, int maxChars, SubagentResultTruncation truncation,
            String retrievalPointer) {
        Objects.requireNonNull(truncation, "truncation cannot be null");
        if (content == null || content.isEmpty()) {
            return "";
        }
        if (maxChars <= 0 || content.length() <= maxChars) {
            return content;
        }
        return switch (truncation) {
            case TAIL -> truncateTailKeep(content, maxChars, retrievalPointer);
            case HEAD -> {
                final int end = headEnd(content, maxChars);
                yield content.substring(0, end) + "\n" + marker(content.length() - end, retrievalPointer);
            }
            case HEAD_AND_TAIL -> {
                final int end = headEnd(content, maxChars - maxChars / 2);
                final int start = tailStart(content, maxChars / 2);
                yield content.substring(0, end) + "\n" + marker(start - end, retrievalPointer) + "\n"
                        + content.substring(start);
            }
        };
    }

    /** Where a head of at most {@code chars} ends, not splitting a surrogate pair. */
    private static int headEnd(String content, int chars) {
        int end = chars;
        if (end > 0 && Character.isHighSurrogate(content.charAt(end - 1))) {
            end--;
        }
        return end;
    }

    /** Where a tail of at most {@code chars} starts, not splitting a surrogate pair. */
    private static int tailStart(String content, int chars) {
        int start = content.length() - chars;
        if (start < content.length() && start > 0 && Character.isLowSurrogate(content.charAt(start))
                && Character.isHighSurrogate(content.charAt(start - 1))) {
            start++;
        }
        return start;
    }

    private static String marker(int omitted, String retrievalPointer) {
        final StringBuilder marker = new StringBuilder();
        marker.append("…[").append(omitted).append(" chars omitted");
        if (retrievalPointer != null && !retrievalPointer.isBlank()) {
            marker.append(" — ").append(retrievalPointer.strip());
        }
        return marker.append("]…").toString();
    }
}
