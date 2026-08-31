package at.aimon.core.skill.render;

import java.util.ArrayList;
import java.util.List;

/**
 * POSIX-style tokenizer that splits a raw argument string into individual tokens, honoring single quotes, double
 * quotes, and backslash escapes.
 *
 * <p>
 * Tokenization rules:
 *
 * <ul>
 * <li>Whitespace separates tokens when not inside quotes.
 * <li>Single quotes ({@code '...'}) preserve the contents literally — no escapes are processed inside.
 * <li>Double quotes ({@code "..."}) preserve the contents but allow {@code \"} and {@code \\} escapes.
 * <li>An unquoted backslash escapes the next character.
 * <li>An unterminated quote or trailing backslash is consumed leniently (no exception).
 * </ul>
 *
 * <p>
 * Stateless and thread-safe.
 */
public final class ShellArgumentTokenizer {

    /**
     * Tokenizes the given input string.
     *
     * @param input
     *            The raw argument string (may be null or empty)
     * @return An immutable list of tokens (never null, may be empty)
     */
    public List<String> tokenize(String input) {
        if (input == null || input.isEmpty()) {
            return List.of();
        }

        final List<String> tokens = new ArrayList<>();
        final StringBuilder current = new StringBuilder();
        boolean inToken = false;
        char quote = 0; // 0 = none, '\'' = single, '"' = double
        boolean escaped = false;

        for (int i = 0; i < input.length(); i++) {
            final char c = input.charAt(i);

            if (escaped) {
                current.append(c);
                escaped = false;
                inToken = true;
                continue;
            }

            if (quote == '\'') {
                if (c == '\'') {
                    quote = 0;
                } else {
                    current.append(c);
                }
                continue;
            }

            if (quote == '"') {
                if (c == '\\' && i + 1 < input.length()) {
                    final char next = input.charAt(i + 1);
                    if (next == '"' || next == '\\') {
                        current.append(next);
                        i++;
                        continue;
                    }
                    current.append(c);
                } else if (c == '"') {
                    quote = 0;
                } else {
                    current.append(c);
                }
                continue;
            }

            // Outside any quote
            if (c == '\\') {
                escaped = true;
                inToken = true;
                continue;
            }

            if (c == '\'' || c == '"') {
                quote = c;
                inToken = true;
                continue;
            }

            if (Character.isWhitespace(c)) {
                if (inToken) {
                    tokens.add(current.toString());
                    current.setLength(0);
                    inToken = false;
                }
                continue;
            }

            current.append(c);
            inToken = true;
        }

        if (inToken) {
            tokens.add(current.toString());
        }

        return List.copyOf(tokens);
    }
}
