package at.aimon.core.tools.code;

/**
 * Makes model-written text safe to put in a host log ({@code docs/design/tool/programmatic-tool-calling.md} §12.2):
 * every control character becomes {@code \xNN}, each line is capped, and so is the number of lines. A CLI whose log
 * goes to the terminal therefore cannot be made to paint a forged screen with ANSI escapes or to show a fake log line.
 *
 * <p>
 * Stateless.
 */
final class ModelText {

    /** The most characters one logged line keeps. */
    static final int MAX_LINE_CHARS = 500;

    /** The most lines one log statement keeps. */
    static final int MAX_LINES = 20;

    private ModelText() {
        throw new AssertionError("This class should not be instantiated");
    }

    /**
     * Escapes {@code text} for a log statement. Line breaks are kept as line breaks (each line is escaped and capped
     * on its own), up to {@link #MAX_LINES}.
     *
     * @param text
     *            model-written text, or {@code null}
     * @return the escaped text (never null)
     */
    static String forLog(String text) {
        if (text == null) {
            return "";
        }
        final String[] lines = text.split("\n", -1);
        final StringBuilder out = new StringBuilder();
        final int kept = Math.min(lines.length, MAX_LINES);
        for (int i = 0; i < kept; i++) {
            if (i > 0) {
                out.append('\n');
            }
            out.append(escapeLine(lines[i]));
        }
        if (lines.length > kept) {
            out.append("\n[").append(lines.length - kept).append(" more lines]");
        }
        return out.toString();
    }

    /** Escapes one line: every control character as {@code \xNN}, capped at {@link #MAX_LINE_CHARS}. */
    static String escapeLine(String line) {
        final StringBuilder out = new StringBuilder(Math.min(line.length(), MAX_LINE_CHARS) + 8);
        for (int i = 0; i < line.length(); i++) {
            if (out.length() >= MAX_LINE_CHARS) {
                out.append("…");
                break;
            }
            final char c = line.charAt(i);
            if (Character.isISOControl(c)) {
                out.append(String.format("\\x%02X", (int) c & 0xFF));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
