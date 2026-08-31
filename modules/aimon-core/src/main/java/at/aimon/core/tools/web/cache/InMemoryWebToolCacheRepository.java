package at.aimon.core.tools.web.cache;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory cache implementation with LRU eviction and TTL expiration.
 *
 * <p>
 * Uses a {@link LinkedHashMap} in access-order mode for LRU behavior.
 * All operations are synchronized for thread safety.
 *
 * <p>
 * For multi-instance environments, replace with a Redis-backed implementation.
 */
public class InMemoryWebToolCacheRepository implements WebToolCacheRepository {

    private final Map<String, CacheEntry> store;
    private final int maxSize;

    /**
     * Creates a new InMemoryWebToolCacheRepository.
     *
     * @param maxSize
     *            the maximum number of entries (must be &gt; 0)
     * @throws IllegalArgumentException
     *             if maxSize is less than 1
     */
    public InMemoryWebToolCacheRepository(int maxSize) {
        if (maxSize < 1) {
            throw new IllegalArgumentException("maxSize must be > 0");
        }
        this.maxSize = maxSize;
        this.store = new LinkedHashMap<>(maxSize, 0.75f, true) {
            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
                return size() > InMemoryWebToolCacheRepository.this.maxSize;
            }
        };
    }

    @Override
    public synchronized Optional<String> get(String key) {
        CacheEntry entry = store.get(key);
        if (entry == null || entry.isExpired()) {
            if (entry != null) {
                store.remove(key);
            }
            return Optional.empty();
        }
        return Optional.of(entry.getValue());
    }

    @Override
    public synchronized void put(String key, String value, Duration ttl) {
        evictExpired();
        store.put(key, new CacheEntry(value, Instant.now().plus(ttl)));
    }

    private void evictExpired() {
        store.entrySet().removeIf(e -> e.getValue().isExpired());
    }

    @Override
    public synchronized void clear() {
        store.clear();
    }

    private static class CacheEntry {

        private final String value;
        private final Instant expiresAt;

        CacheEntry(String value, Instant expiresAt) {
            this.value = value;
            this.expiresAt = expiresAt;
        }

        String getValue() {
            return value;
        }

        boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }
}
