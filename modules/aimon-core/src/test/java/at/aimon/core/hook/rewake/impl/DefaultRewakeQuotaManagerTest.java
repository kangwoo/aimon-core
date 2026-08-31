package at.aimon.core.hook.rewake.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.hook.rewake.RewakeQuotaManager;

class DefaultRewakeQuotaManagerTest {

    private static final AgentRuntimeId CTX_A = AgentRuntimeId.fromName("agent-a");
    private static final AgentRuntimeId CTX_B = AgentRuntimeId.fromName("agent-b");

    @Test
    void noopAlwaysAcquiresAndReportsZeroUsage() {
        final RewakeQuotaManager noop = RewakeQuotaManager.NOOP;
        for (int i = 0; i < 1000; i++) {
            assertThat(noop.tryAcquire(CTX_A)).isTrue();
        }
        assertThat(noop.getCurrentUsage(CTX_A)).isZero();
        assertThat(noop.getMaxQuota(CTX_A)).isEqualTo(Integer.MAX_VALUE);
        // release is a no-op — must not throw.
        noop.release(CTX_A);
    }

    @Test
    void defaultsToConfiguredCap() {
        final DefaultRewakeQuotaManager mgr = new DefaultRewakeQuotaManager(3);
        assertThat(mgr.getMaxQuota(CTX_A)).isEqualTo(3);
        assertThat(mgr.tryAcquire(CTX_A)).isTrue();
        assertThat(mgr.tryAcquire(CTX_A)).isTrue();
        assertThat(mgr.tryAcquire(CTX_A)).isTrue();
        assertThat(mgr.tryAcquire(CTX_A)).isFalse();
        assertThat(mgr.getCurrentUsage(CTX_A)).isEqualTo(3);
    }

    @Test
    void releaseDecrementsUsageAndAllowsReacquire() {
        final DefaultRewakeQuotaManager mgr = new DefaultRewakeQuotaManager(1);
        assertThat(mgr.tryAcquire(CTX_A)).isTrue();
        assertThat(mgr.tryAcquire(CTX_A)).isFalse();
        mgr.release(CTX_A);
        assertThat(mgr.getCurrentUsage(CTX_A)).isZero();
        assertThat(mgr.tryAcquire(CTX_A)).isTrue();
    }

    @Test
    void releaseStaysAboveZero() {
        final DefaultRewakeQuotaManager mgr = new DefaultRewakeQuotaManager(2);
        // Release without acquire — must not go negative.
        mgr.release(CTX_A);
        mgr.release(CTX_A);
        assertThat(mgr.getCurrentUsage(CTX_A)).isZero();
        // Acquire still works after spurious releases.
        assertThat(mgr.tryAcquire(CTX_A)).isTrue();
        assertThat(mgr.getCurrentUsage(CTX_A)).isEqualTo(1);
    }

    @Test
    void contextsAreIsolated() {
        final DefaultRewakeQuotaManager mgr = new DefaultRewakeQuotaManager(1);
        assertThat(mgr.tryAcquire(CTX_A)).isTrue();
        // CTX_B has its own counter.
        assertThat(mgr.tryAcquire(CTX_B)).isTrue();
        assertThat(mgr.tryAcquire(CTX_A)).isFalse();
        assertThat(mgr.tryAcquire(CTX_B)).isFalse();
    }

    @Test
    void customQuotaOverridesDefault() {
        final DefaultRewakeQuotaManager mgr = new DefaultRewakeQuotaManager(1);
        mgr.setCustomQuota(CTX_A, 5);
        assertThat(mgr.getMaxQuota(CTX_A)).isEqualTo(5);
        for (int i = 0; i < 5; i++) {
            assertThat(mgr.tryAcquire(CTX_A)).isTrue();
        }
        assertThat(mgr.tryAcquire(CTX_A)).isFalse();

        mgr.removeCustomQuota(CTX_A);
        assertThat(mgr.getMaxQuota(CTX_A)).isEqualTo(1);
    }

    @Test
    void resetAllUsageClearsCounters() {
        final DefaultRewakeQuotaManager mgr = new DefaultRewakeQuotaManager(1);
        mgr.tryAcquire(CTX_A);
        mgr.tryAcquire(CTX_B);
        mgr.resetAllUsage();
        assertThat(mgr.getCurrentUsage(CTX_A)).isZero();
        assertThat(mgr.getCurrentUsage(CTX_B)).isZero();
    }

    @Test
    void defaultMaxQuotaConstructorRejectsNonPositive() {
        assertThatIllegalArgumentException().isThrownBy(() -> new DefaultRewakeQuotaManager(0));
        assertThatIllegalArgumentException().isThrownBy(() -> new DefaultRewakeQuotaManager(-1));
    }

    @Test
    void setCustomQuotaRejectsNonPositive() {
        final DefaultRewakeQuotaManager mgr = new DefaultRewakeQuotaManager(1);
        assertThatIllegalArgumentException().isThrownBy(() -> mgr.setCustomQuota(CTX_A, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> mgr.setCustomQuota(CTX_A, -2));
    }

    @Test
    void allMethodsRejectNullContext() {
        final DefaultRewakeQuotaManager mgr = new DefaultRewakeQuotaManager(1);
        assertThatNullPointerException().isThrownBy(() -> mgr.tryAcquire(null));
        assertThatNullPointerException().isThrownBy(() -> mgr.release(null));
        assertThatNullPointerException().isThrownBy(() -> mgr.getCurrentUsage(null));
        assertThatNullPointerException().isThrownBy(() -> mgr.getMaxQuota(null));
        assertThatNullPointerException().isThrownBy(() -> mgr.setCustomQuota(null, 1));
        assertThatNullPointerException().isThrownBy(() -> mgr.removeCustomQuota(null));
    }

    @Test
    void concurrentAcquireRespectsCap() throws Exception {
        final int cap = 32;
        final int threads = 16;
        final int callsPerThread = 50;
        final DefaultRewakeQuotaManager mgr = new DefaultRewakeQuotaManager(cap);
        final ExecutorService pool = Executors.newFixedThreadPool(threads);
        final CountDownLatch start = new CountDownLatch(1);
        final AtomicInteger acquired = new AtomicInteger();

        try {
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    for (int i = 0; i < callsPerThread; i++) {
                        if (mgr.tryAcquire(CTX_A)) {
                            acquired.incrementAndGet();
                        }
                    }
                });
            }
            start.countDown();
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(acquired.get()).isEqualTo(cap);
        assertThat(mgr.getCurrentUsage(CTX_A)).isEqualTo(cap);
    }
}
