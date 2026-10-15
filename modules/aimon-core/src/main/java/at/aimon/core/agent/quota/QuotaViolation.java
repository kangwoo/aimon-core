package at.aimon.core.agent.quota;

import java.util.Objects;

/**
 * Which quota stopped an execution: the key, the limit it reached, and the usage that reached it.
 *
 * <p>
 * Carried on the result of an execution that ended with
 * {@link at.aimon.core.agent.budget.CompletionReason#QUOTA_EXCEEDED}, so a caller can tell the user which scope and
 * window is exhausted — and, for a daily window, that waiting will lift it where raising the turn budget will not.
 *
 * <p>
 * Immutable value object.
 */
public final class QuotaViolation {

    private final QuotaKey key;
    private final QuotaLimit limit;
    private final QuotaUsage used;

    private QuotaViolation(QuotaKey key, QuotaLimit limit, QuotaUsage used) {
        this.key = Objects.requireNonNull(key, "Key cannot be null");
        this.limit = Objects.requireNonNull(limit, "Limit cannot be null");
        this.used = Objects.requireNonNull(used, "Used cannot be null");
    }

    /**
     * Creates a violation.
     *
     * @param key
     *            the key whose usage reached the limit (must not be null)
     * @param limit
     *            the limit reached (must not be null)
     * @param used
     *            the key's accumulated usage when it was checked (must not be null)
     * @return a new violation (never null)
     */
    public static QuotaViolation of(QuotaKey key, QuotaLimit limit, QuotaUsage used) {
        return new QuotaViolation(key, limit, used);
    }

    /**
     * Gets the key whose usage reached the limit.
     *
     * @return the key (never null)
     */
    public QuotaKey getKey() {
        return key;
    }

    /**
     * Gets the limit reached.
     *
     * @return the limit (never null)
     */
    public QuotaLimit getLimit() {
        return limit;
    }

    /**
     * Gets the key's accumulated usage when it was checked.
     *
     * @return the usage (never null)
     */
    public QuotaUsage getUsed() {
        return used;
    }

    /**
     * Describes the violation in one line, e.g.
     * {@code quota principal:user/alice:daily:2026-10-15 reached: 1200 tokens / 1000 tokens}. Only the ceilings the
     * limit sets are named.
     *
     * @return the description (never null)
     */
    public String describe() {
        final StringBuilder sb = new StringBuilder("quota ").append(key.asString()).append(" reached:");
        limit.getMaxTokens().ifPresent(
                max -> sb.append(' ').append(used.getTokens()).append(" tokens / ").append(max).append(" tokens"));
        limit.getMaxCost().ifPresent(max -> sb.append(limit.getMaxTokens().isPresent() ? "," : "").append(' ')
                .append(used.getCost()).append(" / ").append(max));
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final QuotaViolation that = (QuotaViolation) o;
        return key.equals(that.key) && limit.equals(that.limit) && used.equals(that.used);
    }

    @Override
    public int hashCode() {
        return Objects.hash(key, limit, used);
    }

    @Override
    public String toString() {
        return "QuotaViolation{" + describe() + '}';
    }
}
