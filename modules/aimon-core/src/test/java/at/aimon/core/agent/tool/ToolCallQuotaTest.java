package at.aimon.core.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ToolCallQuota")
class ToolCallQuotaTest {

    @Test
    @DisplayName("admits up to the limit and counts the rest as refused")
    void limited() {
        final ToolCallQuota quota = ToolCallQuota.limitedTo(3);

        assertThat(quota.admit(2)).isEqualTo(2);
        assertThat(quota.isExceeded()).isFalse();
        assertThat(quota.admit(2)).isEqualTo(1);
        assertThat(quota.admit(1)).isZero();

        assertThat(quota.used()).isEqualTo(3);
        assertThat(quota.refused()).isEqualTo(2);
        assertThat(quota.isExceeded()).isTrue();
        assertThat(quota.getLimit()).contains(3);
    }

    @Test
    @DisplayName("without a limit it only counts")
    void unlimited() {
        final ToolCallQuota quota = ToolCallQuota.unlimited();

        assertThat(quota.admit(1000)).isEqualTo(1000);
        assertThat(quota.used()).isEqualTo(1000);
        assertThat(quota.isExceeded()).isFalse();
        assertThat(quota.getLimit()).isEmpty();
    }

    @Test
    @DisplayName("rejects negative limits and requests")
    void rejectsNegatives() {
        assertThatIllegalArgumentException().isThrownBy(() -> ToolCallQuota.limitedTo(-1));
        assertThatIllegalArgumentException().isThrownBy(() -> ToolCallQuota.unlimited().admit(-1));
    }

    @Test
    @DisplayName("concurrent admits never admit more than the limit")
    void concurrentAdmits() throws Exception {
        final ToolCallQuota quota = ToolCallQuota.limitedTo(500);
        final ExecutorService pool = Executors.newFixedThreadPool(8);
        final CountDownLatch start = new CountDownLatch(1);
        try {
            final List<Future<Integer>> admitted = new ArrayList<>();
            for (int i = 0; i < 1000; i++) {
                admitted.add(pool.submit(() -> {
                    start.await();
                    return quota.admit(1);
                }));
            }
            start.countDown();
            int total = 0;
            for (Future<Integer> future : admitted) {
                total += future.get(10, TimeUnit.SECONDS);
            }
            assertThat(total).isEqualTo(500);
            assertThat(quota.used()).isEqualTo(500);
            assertThat(quota.refused()).isEqualTo(500);
        } finally {
            pool.shutdownNow();
        }
    }
}
