package at.aimon.rewake.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class WebhookIdempotencyCacheTest {

    @Test
    void firstLookupReturnsEmpty() {
        final WebhookIdempotencyCache cache = new WebhookIdempotencyCache();
        assertThat(cache.lookup("idem-1")).isEmpty();
    }

    @Test
    void recordedKeyIsReturnedOnReplay() {
        final WebhookIdempotencyCache cache = new WebhookIdempotencyCache();
        cache.record("idem-1", 3);

        assertThat(cache.lookup("idem-1")).contains(3);
        assertThat(cache.size()).isEqualTo(1L);
    }

    @Test
    void firstWriterWinsOnConflict() {
        final WebhookIdempotencyCache cache = new WebhookIdempotencyCache();
        cache.record("idem-1", 3);
        cache.record("idem-1", 99); // re-record

        assertThat(cache.lookup("idem-1")).contains(3);
    }

    @Test
    void rejectsNegativeMatchedCount() {
        final WebhookIdempotencyCache cache = new WebhookIdempotencyCache();
        assertThatIllegalArgumentException().isThrownBy(() -> cache.record("idem-1", -1));
    }

    @Test
    void rejectsNullKeys() {
        final WebhookIdempotencyCache cache = new WebhookIdempotencyCache();
        assertThatNullPointerException().isThrownBy(() -> cache.record(null, 1));
        assertThatNullPointerException().isThrownBy(() -> cache.lookup(null));
    }

    @Test
    void rejectsNonPositiveRetention() {
        assertThatIllegalArgumentException().isThrownBy(() -> new WebhookIdempotencyCache(Duration.ZERO));
        assertThatIllegalArgumentException().isThrownBy(() -> new WebhookIdempotencyCache(Duration.ofMinutes(-1)));
    }

    @Test
    void rejectsNullRetention() {
        assertThatNullPointerException().isThrownBy(() -> new WebhookIdempotencyCache(null));
    }
}
