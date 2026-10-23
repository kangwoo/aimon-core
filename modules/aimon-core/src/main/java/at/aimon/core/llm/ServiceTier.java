package at.aimon.core.llm;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * The processing tier a provider <em>actually applied</em> to a call, in provider-neutral vocabulary.
 *
 * <p>
 * Tiers are priced differently, so usage accounting needs the tier the provider reports on the response — not the
 * tier the request asked for, which a provider may downgrade or resolve (OpenAI's {@code auto}). Four values are
 * normalized: {@link #STANDARD}, {@link #FAST}, {@link #FLEX} and {@link #BATCH}. The mapping from a provider's own
 * vocabulary is done explicitly by the provider module (for example OpenAI {@code default} → {@code standard} and
 * {@code priority} → {@code fast}); a value with no neutral equivalent is preserved as reported (lower-cased) rather
 * than guessed, and {@link #isNormalized()} tells the two apart.
 *
 * <p>
 * A class rather than an enum so that unknown values survive: a new provider tier must reach the recorder as itself,
 * not collapse into a default that would be priced as something it is not.
 *
 * <p>
 * Immutable value object.
 */
public final class ServiceTier {

    /** The provider's default processing tier (OpenAI {@code default}, Anthropic {@code standard}). */
    public static final ServiceTier STANDARD = new ServiceTier("standard");
    /** Priority processing at a premium (OpenAI {@code priority}). */
    public static final ServiceTier FAST = new ServiceTier("fast");
    /** Discounted, higher-latency processing (OpenAI {@code flex}). */
    public static final ServiceTier FLEX = new ServiceTier("flex");
    /** Asynchronous batch processing. */
    public static final ServiceTier BATCH = new ServiceTier("batch");

    private final String value;

    private ServiceTier(String value) {
        this.value = value;
    }

    /**
     * Returns the tier for an already-normalized or provider-specific value.
     *
     * <p>
     * The value is trimmed and lower-cased; one of the four normalized names yields the matching constant, anything
     * else a tier that carries the value unchanged. Provider modules translate their own vocabulary <em>before</em>
     * calling this — {@code of("priority")} is a preserved, non-normalized tier, not {@link #FAST}.
     *
     * @param value
     *            the tier name (may be null or blank)
     * @return the tier, or empty when {@code value} is null or blank
     */
    public static Optional<ServiceTier> of(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        final String normalized = value.trim().toLowerCase(Locale.ROOT);
        final ServiceTier tier = switch (normalized) {
            case "standard" -> STANDARD;
            case "fast" -> FAST;
            case "flex" -> FLEX;
            case "batch" -> BATCH;
            default -> new ServiceTier(normalized);
        };
        return Optional.of(tier);
    }

    /**
     * @return the tier name (never null, lower-case)
     */
    public String getValue() {
        return value;
    }

    /**
     * Reports whether this is one of the four provider-neutral tiers.
     *
     * @return {@code true} for {@link #STANDARD}, {@link #FAST}, {@link #FLEX} and {@link #BATCH}; {@code false} for
     *         a value preserved as the provider reported it
     */
    public boolean isNormalized() {
        return this.equals(STANDARD) || this.equals(FAST) || this.equals(FLEX) || this.equals(BATCH);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        return value.equals(((ServiceTier) o).value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
