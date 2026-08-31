package at.aimon.cli.budget;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import at.aimon.core.agent.budget.ExecutionBudget;

/**
 * Utility that parses CLI-friendly strings into values accepted by {@link ExecutionBudget.Builder}.
 *
 * <p>
 * Supports two duration input styles:
 *
 * <ul>
 * <li>Compact suffix form — {@code 500ms}, {@code 30s}, {@code 5m}, {@code 2h}. A numeric-only value is treated as
 * seconds ({@code 30} → 30 seconds). Whitespace between number and unit is allowed.
 * <li>ISO-8601 — anything parseable by {@link Duration#parse(CharSequence)} (e.g. {@code PT30S}, {@code PT5M}).
 * </ul>
 *
 * <p>
 * Integer parsing rejects non-positive or non-numeric input with {@link IllegalArgumentException} so that callers can
 * surface human-friendly validation errors.
 *
 * <p>
 * Stateless and thread-safe.
 */
public final class BudgetOptionParser {

    private static final Pattern COMPACT_PATTERN = Pattern.compile("^(?<value>\\d+)\\s*(?<unit>ms|s|m|h|d)?$",
            Pattern.CASE_INSENSITIVE);

    private BudgetOptionParser() {
        throw new AssertionError("Cannot instantiate BudgetOptionParser");
    }

    /**
     * Parses a positive integer from its string representation.
     *
     * @param raw
     *            the raw value (must not be null)
     * @param fieldName
     *            descriptive name of the field (used in error messages)
     * @return the parsed integer
     * @throws IllegalArgumentException
     *             if the value is not a parseable positive integer
     * @throws NullPointerException
     *             if either argument is null
     */
    public static int parsePositiveInt(String raw, String fieldName) {
        Objects.requireNonNull(raw, "raw cannot be null");
        Objects.requireNonNull(fieldName, "fieldName cannot be null");
        final String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " cannot be empty");
        }
        final int value;
        try {
            value = Integer.parseInt(trimmed);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid " + fieldName + ": '" + raw + "' is not a valid integer");
        }
        if (value < 1) {
            throw new IllegalArgumentException(fieldName + " must be >= 1, got: " + value);
        }
        return value;
    }

    /**
     * Parses a duration from CLI input.
     *
     * <p>
     * Accepts compact suffix form and ISO-8601. Returns a non-null, strictly positive {@link Duration}.
     *
     * @param raw
     *            the raw value (must not be null)
     * @param fieldName
     *            descriptive name of the field (used in error messages, e.g. {@code "timeout"})
     * @return the parsed duration
     * @throws IllegalArgumentException
     *             if the value cannot be parsed or is not strictly positive
     * @throws NullPointerException
     *             if either argument is null
     */
    public static Duration parseDuration(String raw, String fieldName) {
        Objects.requireNonNull(raw, "raw cannot be null");
        Objects.requireNonNull(fieldName, "fieldName cannot be null");
        final String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " cannot be empty");
        }

        final Duration parsed = tryParseCompact(trimmed);
        final Duration result = parsed != null ? parsed : tryParseIso(trimmed);
        if (result == null) {
            throw new IllegalArgumentException(
                    "Invalid " + fieldName + ": '" + raw + "'. Expected formats: 500ms, 30s, 5m, 2h, PT30S");
        }
        if (result.isZero() || result.isNegative()) {
            throw new IllegalArgumentException(fieldName + " must be positive, got: " + raw);
        }
        return result;
    }

    private static Duration tryParseCompact(String value) {
        final Matcher matcher = COMPACT_PATTERN.matcher(value);
        if (!matcher.matches()) {
            return null;
        }
        final long amount;
        try {
            amount = Long.parseLong(matcher.group("value"));
        } catch (NumberFormatException e) {
            return null;
        }
        final String unit = matcher.group("unit");
        final String normalizedUnit = unit == null ? "s" : unit.toLowerCase(Locale.ROOT);
        return switch (normalizedUnit) {
            case "ms" -> Duration.ofMillis(amount);
            case "s" -> Duration.ofSeconds(amount);
            case "m" -> Duration.ofMinutes(amount);
            case "h" -> Duration.ofHours(amount);
            case "d" -> Duration.ofDays(amount);
            default -> null;
        };
    }

    private static Duration tryParseIso(String value) {
        try {
            return Duration.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
