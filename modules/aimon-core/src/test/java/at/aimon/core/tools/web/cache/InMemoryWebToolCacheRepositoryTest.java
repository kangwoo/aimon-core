package at.aimon.core.tools.web.cache;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("InMemoryWebToolCacheRepository Tests")
class InMemoryWebToolCacheRepositoryTest {

    private InMemoryWebToolCacheRepository cache;

    @BeforeEach
    void setUp() {
        cache = new InMemoryWebToolCacheRepository(3);
    }

    @Nested
    @DisplayName("Constructor")
    class Constructor {

        @Test
        @DisplayName("Should throw exception for maxSize < 1")
        void testInvalidMaxSize() {
            assertThatThrownBy(() -> new InMemoryWebToolCacheRepository(0)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxSize must be > 0");
        }

        @Test
        @DisplayName("Should create cache with valid maxSize")
        void testValidMaxSize() {
            InMemoryWebToolCacheRepository repo = new InMemoryWebToolCacheRepository(10);
            assertThat(repo).isNotNull();
        }
    }

    @Nested
    @DisplayName("get and put")
    class GetAndPut {

        @Test
        @DisplayName("Should return value after put")
        void testPutAndGet() {
            cache.put("key1", "value1", Duration.ofMinutes(5));

            Optional<String> result = cache.get("key1");
            assertThat(result).isPresent().hasValue("value1");
        }

        @Test
        @DisplayName("Should return empty for non-existent key")
        void testGetNonExistent() {
            Optional<String> result = cache.get("nonexistent");
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Should overwrite existing value")
        void testOverwrite() {
            cache.put("key1", "value1", Duration.ofMinutes(5));
            cache.put("key1", "value2", Duration.ofMinutes(5));

            Optional<String> result = cache.get("key1");
            assertThat(result).isPresent().hasValue("value2");
        }
    }

    @Nested
    @DisplayName("TTL expiration")
    class TtlExpiration {

        @Test
        @DisplayName("Should return empty after TTL expiration")
        void testExpiredEntry() throws InterruptedException {
            cache.put("key1", "value1", Duration.ofMillis(50));

            // Wait for expiration
            Thread.sleep(100);

            Optional<String> result = cache.get("key1");
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Should return value before TTL expiration")
        void testNotExpired() {
            cache.put("key1", "value1", Duration.ofMinutes(5));

            Optional<String> result = cache.get("key1");
            assertThat(result).isPresent();
        }

        @Test
        @DisplayName("Should evict expired entries on put")
        void testExpiredEvictionOnPut() throws InterruptedException {
            // Fill cache with short-lived entries
            cache.put("exp1", "v1", Duration.ofMillis(50));
            cache.put("exp2", "v2", Duration.ofMillis(50));
            cache.put("exp3", "v3", Duration.ofMillis(50));

            // Wait for expiration
            Thread.sleep(100);

            // Put new entries - expired entries should be evicted first,
            // allowing more entries without hitting LRU limit
            cache.put("new1", "n1", Duration.ofMinutes(5));
            cache.put("new2", "n2", Duration.ofMinutes(5));
            cache.put("new3", "n3", Duration.ofMinutes(5));

            assertThat(cache.get("new1")).isPresent();
            assertThat(cache.get("new2")).isPresent();
            assertThat(cache.get("new3")).isPresent();
        }
    }

    @Nested
    @DisplayName("LRU eviction")
    class LruEviction {

        @Test
        @DisplayName("Should evict least recently used entry when full")
        void testLruEviction() {
            cache.put("key1", "value1", Duration.ofMinutes(5));
            cache.put("key2", "value2", Duration.ofMinutes(5));
            cache.put("key3", "value3", Duration.ofMinutes(5));

            // This should evict key1 (LRU)
            cache.put("key4", "value4", Duration.ofMinutes(5));

            assertThat(cache.get("key1")).isEmpty();
            assertThat(cache.get("key2")).isPresent();
            assertThat(cache.get("key3")).isPresent();
            assertThat(cache.get("key4")).isPresent();
        }

        @Test
        @DisplayName("Should promote accessed entry in LRU order")
        void testLruPromotion() {
            cache.put("key1", "value1", Duration.ofMinutes(5));
            cache.put("key2", "value2", Duration.ofMinutes(5));
            cache.put("key3", "value3", Duration.ofMinutes(5));

            // Access key1 to promote it
            cache.get("key1");

            // This should evict key2 (now LRU) instead of key1
            cache.put("key4", "value4", Duration.ofMinutes(5));

            assertThat(cache.get("key1")).isPresent();
            assertThat(cache.get("key2")).isEmpty();
            assertThat(cache.get("key3")).isPresent();
            assertThat(cache.get("key4")).isPresent();
        }
    }

    @Nested
    @DisplayName("clear")
    class Clear {

        @Test
        @DisplayName("Should remove all entries")
        void testClear() {
            cache.put("key1", "value1", Duration.ofMinutes(5));
            cache.put("key2", "value2", Duration.ofMinutes(5));

            cache.clear();

            assertThat(cache.get("key1")).isEmpty();
            assertThat(cache.get("key2")).isEmpty();
        }
    }
}
