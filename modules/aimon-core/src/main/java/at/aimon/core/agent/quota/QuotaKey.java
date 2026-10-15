package at.aimon.core.agent.quota;

import java.util.Locale;
import java.util.Objects;

/**
 * The key of a {@link UsageLedger}: a scope, a window, and the window's bucket.
 *
 * <p>
 * Two charges land on the same counter exactly when their keys are equal. A {@link QuotaWindow#TOTAL} key has the
 * empty bucket; a {@link QuotaWindow#DAILY} key has the local date, so the next day's charges start a new counter
 * instead of resetting the old one.
 *
 * <p>
 * Immutable value object.
 */
public final class QuotaKey {

    private final QuotaScope scope;
    private final QuotaWindow window;
    private final String bucket;

    private QuotaKey(QuotaScope scope, QuotaWindow window, String bucket) {
        this.scope = Objects.requireNonNull(scope, "Scope cannot be null");
        this.window = Objects.requireNonNull(window, "Window cannot be null");
        this.bucket = Objects.requireNonNull(bucket, "Bucket cannot be null");
    }

    /**
     * Creates a key.
     *
     * @param scope
     *            the scope (must not be null)
     * @param window
     *            the window (must not be null)
     * @param bucket
     *            the bucket within the window, {@code ""} for {@link QuotaWindow#TOTAL} (must not be null)
     * @return a new key (never null)
     */
    public static QuotaKey of(QuotaScope scope, QuotaWindow window, String bucket) {
        return new QuotaKey(scope, window, bucket);
    }

    /**
     * Gets the scope.
     *
     * @return the scope (never null)
     */
    public QuotaScope getScope() {
        return scope;
    }

    /**
     * Gets the window.
     *
     * @return the window (never null)
     */
    public QuotaWindow getWindow() {
        return window;
    }

    /**
     * Gets the bucket within the window.
     *
     * @return {@code ""} for {@link QuotaWindow#TOTAL}, otherwise the bucket name (never null)
     */
    public String getBucket() {
        return bucket;
    }

    /**
     * Renders the key as one string, the form a remote ledger stores it under: {@code <kind>:<id>:<window>} with
     * {@code :<bucket>} appended when the bucket is not empty — {@code session:s-1:total},
     * {@code principal:user/alice:daily:2026-10-15}.
     *
     * @return the string form (never null)
     */
    public String asString() {
        final String base = scope + ":" + window.name().toLowerCase(Locale.ROOT);
        return bucket.isEmpty() ? base : base + ":" + bucket;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final QuotaKey that = (QuotaKey) o;
        return scope.equals(that.scope) && window == that.window && bucket.equals(that.bucket);
    }

    @Override
    public int hashCode() {
        return Objects.hash(scope, window, bucket);
    }

    @Override
    public String toString() {
        return asString();
    }
}
