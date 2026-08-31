package at.aimon.rewake.webhook;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentMap;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * Caches the result of recently-processed webhook requests, keyed by idempotency key.
 *
 * <p>
 * The webhook handler consults this cache before doing any work — if the key is already present, the prior result
 * is returned and the rewake pipeline is not re-invoked. This protects against transport redelivery (network
 * blips, sender retry-on-timeout) without bouncing duplicate fires through {@link
 * at.aimon.core.hook.rewake.ExternalEventResolver ExternalEventResolver}.
 *
 * <p>
 * Backed by Caffeine with {@link Caffeine#expireAfterWrite(Duration)}; the default retention window is 24 hours,
 * matching the Mongo-side idempotency store. Keys older than the window are evicted lazily — Caffeine does not
 * spawn a background thread.
 *
 * <p>
 * Thread-safe.
 */
public final class WebhookIdempotencyCache {

    public static final Duration DEFAULT_RETENTION = Duration.ofHours(24);

    private final Cache<String, Integer> cache;

    /**
     * Builds a cache with the default 24-hour retention window.
     */
    public WebhookIdempotencyCache() {
        this(DEFAULT_RETENTION);
    }

    /**
     * Builds a cache with a custom retention window.
     *
     * @param retention
     *            window after which entries are evicted (must not be null or negative)
     */
    public WebhookIdempotencyCache(Duration retention) {
        Objects.requireNonNull(retention, "retention cannot be null");
        if (retention.isNegative() || retention.isZero()) {
            throw new IllegalArgumentException("retention must be positive, got: " + retention);
        }
        this.cache = Caffeine.newBuilder().expireAfterWrite(retention).build();
    }

    /**
     * Returns the prior result if {@code key} was already recorded, otherwise empty.
     *
     * @param key
     *            idempotency key (must not be null)
     * @return prior matched-count if a replay; empty for first-seen
     */
    public Optional<Integer> lookup(String key) {
        Objects.requireNonNull(key, "key cannot be null");
        return Optional.ofNullable(cache.getIfPresent(key));
    }

    /**
     * Records the result of processing {@code key}. If a value is already present this method is a no-op — the
     * first writer wins, so a redelivery cannot overwrite the original outcome.
     *
     * @param key
     *            idempotency key (must not be null)
     * @param matchedCount
     *            the matched-envelope count returned by the resolver (must be {@code >= 0})
     */
    public void record(String key, int matchedCount) {
        Objects.requireNonNull(key, "key cannot be null");
        if (matchedCount < 0) {
            throw new IllegalArgumentException("matchedCount must be >= 0, got: " + matchedCount);
        }
        ((ConcurrentMap<String, Integer>) cache.asMap()).putIfAbsent(key, matchedCount);
    }

    /**
     * @return current entry count (approximate; useful for tests and metrics)
     */
    public long size() {
        cache.cleanUp();
        return cache.estimatedSize();
    }
}
