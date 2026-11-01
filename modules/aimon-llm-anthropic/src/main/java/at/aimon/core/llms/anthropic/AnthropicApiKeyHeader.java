package at.aimon.core.llms.anthropic;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.StringJoiner;

/**
 * Which HTTP header carries {@link AnthropicConfig#getApiKey()}.
 *
 * <p>
 * The Anthropic API takes either. The choice exists for a gateway behind {@link AnthropicConfig#getBaseUrl()} that
 * reads only one of them: SDK 3 sends {@code Authorization: Bearer}, where SDK 2 sent {@code X-Api-Key}.
 *
 * @see AnthropicConfig.Builder#apiKeyHeader(AnthropicApiKeyHeader)
 */
public enum AnthropicApiKeyHeader {

    /** {@code Authorization: Bearer <key>}. The default, and what the SDK does on its own. */
    AUTHORIZATION,

    /** {@code X-Api-Key: <key>}, for a gateway that reads nothing else. */
    X_API_KEY;

    /**
     * Folds a configuration value onto a constant: case-insensitive, with {@code -} and {@code _} interchangeable. Both
     * configuration surfaces fold through this method, so they cannot come to accept different spellings.
     *
     * @param value
     *            the written value (must not be null)
     * @return the constant, or empty when the value names none
     */
    public static Optional<AnthropicApiKeyHeader> fromConfigValue(String value) {
        Objects.requireNonNull(value, "value cannot be null");
        final String folded = value.trim().replace('-', '_').toUpperCase(Locale.ROOT);
        for (AnthropicApiKeyHeader candidate : values()) {
            if (candidate.name().equals(folded)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /**
     * The spelling the configuration surfaces document: lower case, words joined by {@code -}.
     *
     * @return e.g. {@code x-api-key}
     */
    public String configValue() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /**
     * Every {@link #configValue()}, comma-separated in declaration order — what a refusal of an unknown value lists.
     *
     * @return {@code authorization, x-api-key}
     */
    public static String acceptedConfigValues() {
        final StringJoiner accepted = new StringJoiner(", ");
        for (AnthropicApiKeyHeader header : values()) {
            accepted.add(header.configValue());
        }
        return accepted.toString();
    }
}
