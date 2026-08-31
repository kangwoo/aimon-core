package at.aimon.core.agent.compact;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Stateless utility that applies a list of {@link RedactionPattern} rules to text, returning the redacted form.
 *
 * <p>
 * Used by {@link MessageStripper} to strip secrets and PII from message content before it is forwarded to the summary
 * LLM call (design §9.3 — mitigation for secret/PII leakage in summary prompts). The redactor is intentionally
 * conservative: it operates on raw text only, applies patterns in order, and never inspects structured fields.
 *
 * <p>
 * Built-in patterns ({@link #defaults()}) cover the most common high-confidence credentials:
 * <ul>
 * <li>AWS access key IDs ({@code AKIA…}, {@code ASIA…})
 * <li>Generic API tokens with the {@code sk-} prefix (e.g. OpenAI keys)
 * <li>Slack bot/user tokens ({@code xox[abprs]-…})
 * <li>{@code Bearer …} authorization headers
 * <li>JSON Web Tokens ({@code eyJ…} three-part)
 * <li>PEM-armoured private key blocks
 * </ul>
 * Higher-precision domain rules (database URIs, internal customer IDs) should be added by the application via
 * {@link #of(List)}.
 *
 * <p>
 * Thread-safe.
 */
public final class SensitivePatternRedactor {

    private static final SensitivePatternRedactor NONE = new SensitivePatternRedactor(List.of());

    private final List<RedactionPattern> patterns;

    private SensitivePatternRedactor(List<RedactionPattern> patterns) {
        this.patterns = List.copyOf(Objects.requireNonNull(patterns, "patterns cannot be null"));
    }

    /**
     * Returns a redactor that performs no substitution. Use this as the default when secret/PII redaction is not
     * configured.
     *
     * @return A no-op redactor
     */
    public static SensitivePatternRedactor none() {
        return NONE;
    }

    /**
     * Creates a redactor with the supplied custom pattern list (no defaults included).
     *
     * @param patterns
     *            The redaction rules (must not be null; may be empty)
     * @return A new redactor
     */
    public static SensitivePatternRedactor of(List<RedactionPattern> patterns) {
        return new SensitivePatternRedactor(patterns);
    }

    /**
     * Creates a redactor pre-loaded with the framework's default high-confidence patterns. See class-level Javadoc for
     * the list.
     *
     * @return A new redactor
     */
    public static SensitivePatternRedactor defaults() {
        return new SensitivePatternRedactor(defaultPatterns());
    }

    /**
     * Returns the framework's default high-confidence redaction patterns.
     *
     * @return An immutable list of default patterns
     */
    public static List<RedactionPattern> defaultPatterns() {
        return List.of(RedactionPattern.of("AWS_ACCESS_KEY", Pattern.compile("\\b(?:AKIA|ASIA)[0-9A-Z]{16}\\b")),
                RedactionPattern.of("OPENAI_API_KEY", Pattern.compile("\\bsk-[A-Za-z0-9_-]{20,}\\b")),
                RedactionPattern.of("SLACK_TOKEN", Pattern.compile("\\bxox[abprs]-[A-Za-z0-9-]{10,}\\b")),
                RedactionPattern.of("BEARER_TOKEN", Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]{16,}")),
                RedactionPattern.of("JWT",
                        Pattern.compile("\\beyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\b")),
                RedactionPattern.of("PRIVATE_KEY_BLOCK", Pattern.compile(
                        "-----BEGIN (?:[A-Z ]+ )?PRIVATE KEY-----[\\s\\S]*?-----END (?:[A-Z ]+ )?PRIVATE KEY-----")));
    }

    /**
     * Applies all configured patterns in order to {@code text} and returns the redacted result.
     *
     * <p>
     * If {@code text} is null, returns null. If no patterns are configured (e.g. {@link #none()}), returns the input
     * unchanged. The method does not allocate when there are no matches.
     *
     * @param text
     *            The text to redact (may be null)
     * @return Redacted text, or null if input was null
     */
    public String redact(String text) {
        if (text == null || text.isEmpty() || patterns.isEmpty()) {
            return text;
        }
        String current = text;
        for (RedactionPattern pattern : patterns) {
            current = pattern.getPattern().matcher(current).replaceAll(pattern.getReplacement());
        }
        return current;
    }

    /**
     * Returns true if this redactor has no configured patterns and would always return its input unchanged.
     *
     * @return true if no-op
     */
    public boolean isNoop() {
        return patterns.isEmpty();
    }

    /**
     * Returns the configured patterns. The returned list is unmodifiable.
     *
     * @return The patterns
     */
    public List<RedactionPattern> getPatterns() {
        return patterns;
    }
}
