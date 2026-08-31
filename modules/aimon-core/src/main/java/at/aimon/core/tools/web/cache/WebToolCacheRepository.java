package at.aimon.core.tools.web.cache;

import java.time.Duration;
import java.util.Optional;

/**
 * Cache repository interface for web tools.
 *
 * <p>
 * The default implementation is in-memory (LRU + TTL).
 * For multi-instance environments, replace with a Redis or similar implementation.
 */
public interface WebToolCacheRepository {

    /**
     * Retrieves a cached value.
     *
     * @param key
     *            the cache key
     * @return the cached value, or empty if not found or expired
     */
    Optional<String> get(String key);

    /**
     * Stores a value in the cache.
     *
     * @param key
     *            the cache key
     * @param value
     *            the value to store
     * @param ttl
     *            the time-to-live duration
     */
    void put(String key, String value, Duration ttl);

    /**
     * Clears all entries from the cache.
     */
    void clear();
}
