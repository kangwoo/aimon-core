package at.aimon.core.base.text;

/**
 * Strips a single Markdown code fence around an LLM text/JSON response.
 *
 * <p>
 * Models occasionally wrap their answer in a {@code ```json … ```} (or bare {@code ``` … ```}) fence despite being
 * told not to. Rather than fail extraction over a cosmetic mistake, callers peel one fence layer before parsing. This
 * is the single shared implementation for what used to be five near-identical private copies across the memory, wiki,
 * and workflow LLM-response parsers.
 *
 * <p>
 * It reconciles the divergent behaviour of those copies:
 * <ul>
 * <li>{@code null} input yields {@code ""} (never throws).
 * <li>Text without a leading {@code ```} is returned stripped, unchanged.
 * <li>A leading fence is removed together with its whole {@code ```lang} line when a newline follows, or just the
 * {@code ```} marker for a single-line fenced payload (so {@code ```{"a":1}```} is not lost).
 * <li>The closing fence and anything the model appended <em>after</em> it are dropped. A real closing fence begins a
 * line ({@code "\n```"}), so trailing prose after the fence is discarded — yet an inner {@code ```} embedded in a JSON
 * string value is never mistaken for the terminator, because a JSON string cannot contain a raw newline. A same-line
 * trailing {@code ```} (no newline before it) is also removed.
 * </ul>
 */
public final class CodeFences {

    private static final String FENCE = "```";
    private static final String LINE_FENCE = "\n" + FENCE;

    private CodeFences() {
        throw new UnsupportedOperationException("Utility class must not be instantiated");
    }

    /**
     * Removes one leading {@code ```}/{@code ```lang} fence and one trailing {@code ```} fence, null-safe.
     *
     * @param text
     *            the raw response text (nullable)
     * @return the de-fenced, stripped text; {@code ""} when {@code text} is null
     */
    public static String strip(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.strip();
        if (!trimmed.startsWith(FENCE)) {
            return trimmed;
        }
        // Drop the opening fence: the whole "```lang" line when there is a newline, otherwise just the "```" marker.
        final int firstNewline = trimmed.indexOf('\n');
        trimmed = (firstNewline >= 0 ? trimmed.substring(firstNewline + 1) : trimmed.substring(FENCE.length())).strip();
        // Drop the closing fence and anything after it. A real closing fence begins a line ("\n```"), so this discards
        // trailing prose the model tacked on after the fence, yet never truncates at an inner ``` inside a JSON string
        // value (a JSON string cannot hold a raw newline, so "\n```" only occurs at a structural fence boundary).
        final int closingFence = trimmed.lastIndexOf(LINE_FENCE);
        if (closingFence >= 0) {
            trimmed = trimmed.substring(0, closingFence);
        } else if (trimmed.endsWith(FENCE)) {
            // Closing fence sits on the same line as the payload (no preceding newline).
            trimmed = trimmed.substring(0, trimmed.length() - FENCE.length());
        }
        return trimmed.strip();
    }
}
