package at.aimon.core.agent.prompt;

import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Utility for wrapping ephemeral, synthetic context into {@code <system-reminder>} XML-like blocks.
 *
 * <p>
 * AIMON injects certain pieces of user-role context (e.g. current working directory, date, CLAUDE.md contents, or
 * reminders for the LLM) into the conversation. These must not be mistaken for genuine end-user intent, so they are
 * wrapped in an unambiguous {@code <system-reminder>} marker. The convention is documented in
 * {@code docs/features/agent-execution/system-reminder-convention.md}.
 *
 * <p>
 * Output shape for a single wrap:
 *
 * <pre>
 * &lt;system-reminder key="&lt;escaped-key&gt;"&gt;
 * &lt;escaped-body&gt;
 * &lt;/system-reminder&gt;
 * </pre>
 *
 * <p>
 * The formatter is stateless and thread-safe. It enforces the following rules:
 * <ul>
 * <li>The {@code key} must match {@code [A-Za-z0-9._-]+} (non-empty).
 * <li>The {@code body} is XML-escaped ({@code &amp;}, {@code <}, {@code >}) and must not contain an existing
 * {@code <system-reminder} or {@code </system-reminder>} substring (prevents nested/forged reminders).
 * <li>An empty body is allowed (yields a block with a single blank line).
 * </ul>
 */
public final class SystemReminderFormatter {

    private static final Pattern KEY_PATTERN = Pattern.compile("[A-Za-z0-9._-]+");
    private static final String OPEN_MARKER = "<system-reminder";
    private static final String CLOSE_MARKER = "</system-reminder>";
    // Angle-bracket-free renderings substituted for the reserved markers by sanitizeBody. Neither contains a reserved
    // marker substring, so a sanitized body always passes validateBody yet stays human-readable.
    private static final String SANITIZED_OPEN_MARKER = "[system-reminder";
    private static final String SANITIZED_CLOSE_MARKER = "[/system-reminder]";
    private static final String BLOCK_SEPARATOR = "\n\n";

    private SystemReminderFormatter() {
        throw new AssertionError("This class should not be instantiated");
    }

    /**
     * Wraps the given body in a {@code <system-reminder>} block tagged with the given key.
     *
     * <p>
     * The returned string has the exact shape:
     *
     * <pre>
     * &lt;system-reminder key="&lt;escaped-key&gt;"&gt;
     * &lt;escaped-body&gt;
     * &lt;/system-reminder&gt;
     * </pre>
     *
     * @param key
     *            the reminder key; must match {@code [A-Za-z0-9._-]+} and be non-empty
     * @param body
     *            the reminder body; may be empty; must not contain {@code <system-reminder} or
     *            {@code </system-reminder>}
     * @return a single {@code <system-reminder>} block with the body XML-escaped
     * @throws NullPointerException
     *             if {@code key} or {@code body} is {@code null}
     * @throws IllegalArgumentException
     *             if {@code key} is empty or contains disallowed characters, or if {@code body}
     *             contains a nested {@code <system-reminder>} marker
     */
    public static String wrap(String key, String body) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(body, "body must not be null");

        // Validate BEFORE escaping: the nested-marker check must see raw <system-reminder> sequences, which
        // escape() would mangle into &lt;system-reminder&gt; and silently slip past validation.
        validateKey(key);
        validateBody(body);

        final String escapedBody = escape(body);

        final StringBuilder sb = new StringBuilder(OPEN_MARKER.length() + escapedBody.length() + 32);
        sb.append(OPEN_MARKER).append(" key=\"").append(key).append("\">\n");
        sb.append(escapedBody).append('\n');
        sb.append(CLOSE_MARKER);
        return sb.toString();
    }

    /**
     * Wraps each entry of the given map as a {@code <system-reminder>} block and joins the blocks with a blank line.
     *
     * <p>
     * Iteration order is preserved as given by the map's {@link Map#entrySet()} iterator. Callers that need a
     * deterministic order should pass a {@link java.util.LinkedHashMap} or similar ordered implementation.
     *
     * @param entries
     *            ordered map of reminder {@code key} → {@code body} pairs; must not be {@code null}
     * @return the concatenation of each wrapped block joined by {@code "\n\n"}; an empty string when {@code entries} is
     *         empty
     * @throws NullPointerException
     *             if {@code entries} is {@code null}, or if any key or value is {@code null}
     * @throws IllegalArgumentException
     *             if any key or body violates the rules enforced by {@link #wrap(String, String)}
     */
    public static String wrapMany(Map<String, String> entries) {
        Objects.requireNonNull(entries, "entries must not be null");

        if (entries.isEmpty()) {
            return "";
        }

        final StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            if (!first) {
                sb.append(BLOCK_SEPARATOR);
            }
            sb.append(wrap(entry.getKey(), entry.getValue()));
            first = false;
        }
        return sb.toString();
    }

    /**
     * Neutralizes any reserved {@code <system-reminder} / {@code </system-reminder>} marker inside a would-be body so
     * it
     * can be safely passed to {@link #wrap(String, String)} without tripping {@link #validateBody(String)}.
     *
     * <p>
     * Use this for bodies assembled from untrusted or free-form text (e.g. a subagent's own summary/error message) that
     * could legitimately or maliciously contain the reserved markers. A caller that controls the entire body statically
     * does not need it. The reserved markers are replaced with angle-bracket-free, human-readable renderings; a body
     * with no reserved markers is returned unchanged.
     *
     * @param body
     *            the candidate reminder body; must not be {@code null}
     * @return the body with any reserved marker neutralized
     * @throws NullPointerException
     *             if {@code body} is {@code null}
     */
    public static String sanitizeBody(String body) {
        Objects.requireNonNull(body, "body must not be null");
        if (!body.contains(OPEN_MARKER) && !body.contains(CLOSE_MARKER)) {
            return body;
        }
        return body.replace(CLOSE_MARKER, SANITIZED_CLOSE_MARKER).replace(OPEN_MARKER, SANITIZED_OPEN_MARKER);
    }

    private static void validateKey(String key) {
        if (key.isEmpty()) {
            throw new IllegalArgumentException("key must not be empty");
        }
        if (!KEY_PATTERN.matcher(key).matches()) {
            throw new IllegalArgumentException(
                    "key must match [A-Za-z0-9._-]+ (alphanumeric, '.', '_', '-'), got: " + key);
        }
    }

    private static void validateBody(String body) {
        if (body.contains(OPEN_MARKER) || body.contains(CLOSE_MARKER)) {
            throw new IllegalArgumentException("body must not contain a nested <system-reminder> marker");
        }
    }

    /**
     * Escapes XML-significant characters in the body. The order matters: {@code &} is replaced first to avoid
     * double-escaping the ampersands introduced by subsequent replacements.
     */
    private static String escape(String body) {
        final StringBuilder sb = new StringBuilder(body.length());
        for (int i = 0; i < body.length(); i++) {
            final char c = body.charAt(i);
            switch (c) {
                case '&' :
                    sb.append("&amp;");
                    break;
                case '<' :
                    sb.append("&lt;");
                    break;
                case '>' :
                    sb.append("&gt;");
                    break;
                default :
                    sb.append(c);
                    break;
            }
        }
        return sb.toString();
    }
}
