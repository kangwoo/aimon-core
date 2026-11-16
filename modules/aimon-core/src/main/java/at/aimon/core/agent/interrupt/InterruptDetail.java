package at.aimon.core.agent.interrupt;

import java.util.Optional;

/**
 * Normalises the free-text explanation a caller may attach to an interrupt, next to its {@link InterruptReason}.
 *
 * <p>
 * The reason classifies; the detail explains — "the agent was about to delete the wrong directory", "deploy freeze
 * declared by the on-call". It is for observability only: logs, the terminal {@code ExecutionCompleted} event, and
 * {@link CancellationSignal#getDetail()}. It is never sent to the model.
 *
 * <p>
 * The text comes from whoever asked for the interrupt, and it travels to other nodes and into log lines, so it is
 * treated as untrusted. {@link #normalize(String)} is applied wherever a detail enters: the coordinator, the router
 * before it sends one across the bus, and the {@code ExecutionCompleted} builder (which covers a detail relayed from a
 * peer). It is idempotent, so applying it again on the way through changes nothing, and every reader sees the same
 * bounded single-line value. It never rejects: an interrupt must land however malformed its explanation, so an
 * oversized detail is cut rather than refused.
 */
public final class InterruptDetail {

    /** The longest detail kept, in UTF-16 code units, including the trailing ellipsis of a cut one. */
    public static final int MAX_LENGTH = 500;

    private static final char ELLIPSIS = '…';

    private InterruptDetail() {
    }

    /**
     * Normalises a caller-supplied detail.
     *
     * <p>
     * Control characters (line breaks included), the Unicode line and paragraph separators, invisible format
     * characters (bidi overrides, zero-width characters, the tag characters of the U+E0000 block) and unpaired
     * surrogate halves become spaces, so a detail can neither forge a log line nor visually reorder one. An emoji built
     * with a zero-width joiner therefore comes out as its separate parts. Surrounding whitespace is stripped, and a
     * blank result means no detail. A value longer than {@link #MAX_LENGTH} is cut to fit, never between the two halves
     * of a surrogate pair, and ends with an ellipsis. Normalising a normalised value returns it unchanged.
     *
     * @param raw
     *            the detail as given; may be null
     * @return the normalised detail, or empty when {@code raw} is null or blank
     */
    public static Optional<String> normalize(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        // By code point, so a character outside the BMP (tag characters, U+E0000 block) is judged by its own type
        // rather than as two SURROGATE halves. The copy is bounded: leading whitespace is skipped, and once the builder
        // holds MAX_LENGTH chars only the first non-whitespace code point after them is still taken — it proves a cut
        // is needed, and the cut keeps only the first MAX_LENGTH - 1 chars, which are already in place. Whitespace past
        // that point is not copied: it would either be stripped or fall beyond the cut.
        final StringBuilder sb = new StringBuilder(Math.min(raw.length(), MAX_LENGTH + 2));
        for (int i = 0; i < raw.length();) {
            final int cp = raw.codePointAt(i);
            i += Character.charCount(cp);
            final int kept = isUnsafe(cp) ? ' ' : cp;
            final boolean whitespace = Character.isWhitespace(kept);
            if (whitespace && (sb.length() == 0 || sb.length() >= MAX_LENGTH)) {
                continue;
            }
            sb.appendCodePoint(kept);
            if (sb.length() > MAX_LENGTH) {
                break;
            }
        }
        final String flat = sb.toString().strip();
        if (flat.isEmpty()) {
            return Optional.empty();
        }
        if (flat.length() <= MAX_LENGTH) {
            return Optional.of(flat);
        }
        int cut = MAX_LENGTH - 1;
        if (Character.isLowSurrogate(flat.charAt(cut)) && Character.isHighSurrogate(flat.charAt(cut - 1))) {
            cut--;
        }
        return Optional.of(flat.substring(0, cut) + ELLIPSIS);
    }

    private static boolean isUnsafe(int cp) {
        if (Character.isISOControl(cp)) {
            return true;
        }
        final int type = Character.getType(cp);
        // SURROGATE here means an unpaired half: codePointAt joins a well-formed pair into one supplementary code
        // point.
        return type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR || type == Character.FORMAT
                || type == Character.SURROGATE;
    }
}
