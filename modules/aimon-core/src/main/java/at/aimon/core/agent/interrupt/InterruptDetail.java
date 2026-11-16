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
 * treated as untrusted. {@link #normalize(String)} is applied once, at the coordinator, so every reader sees the same
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
     * Control characters (line breaks included) become spaces, so a detail cannot forge a log line; surrounding
     * whitespace is stripped; a blank result means no detail. A value longer than {@link #MAX_LENGTH} is cut to fit,
     * never between the two halves of a surrogate pair, and ends with an ellipsis.
     *
     * @param raw
     *            the detail as given; may be null
     * @return the normalised detail, or empty when {@code raw} is null or blank
     */
    public static Optional<String> normalize(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        final StringBuilder sb = new StringBuilder(Math.min(raw.length(), MAX_LENGTH + 1));
        for (int i = 0; i < raw.length(); i++) {
            final char c = raw.charAt(i);
            sb.append(Character.isISOControl(c) ? ' ' : c);
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
}
