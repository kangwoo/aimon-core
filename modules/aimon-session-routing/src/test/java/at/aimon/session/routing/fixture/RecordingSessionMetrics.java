package at.aimon.session.routing.fixture;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import at.aimon.session.routing.SubmitDisposition;
import at.aimon.session.routing.metrics.SessionMetrics;

/**
 * In-memory {@link SessionMetrics} that counts every callback invocation. Tests assert against the recorded counters
 * to verify that the manager fires the right hook at the right time.
 */
public final class RecordingSessionMetrics implements SessionMetrics {

    public final AtomicInteger lockAcquireSucceeded = new AtomicInteger();
    public final AtomicInteger lockAcquireRejected = new AtomicInteger();
    public final AtomicInteger cacheHits = new AtomicInteger();
    public final AtomicInteger cacheMisses = new AtomicInteger();
    public final Map<CacheEvictionReason, AtomicInteger> cacheEvictions = new EnumMap<>(CacheEvictionReason.class);
    public final AtomicInteger leaseExtendSucceeded = new AtomicInteger();
    public final AtomicInteger leaseExtendFailed = new AtomicInteger();
    public final Map<SubmitDisposition.Kind, AtomicInteger> submitOutcomes = new EnumMap<>(
            SubmitDisposition.Kind.class);
    public final AtomicInteger holderLossRecovered = new AtomicInteger();
    public final AtomicInteger forwardDoorbellRerung = new AtomicInteger();

    public final AtomicReference<Duration> lastLockAcquireLatency = new AtomicReference<>();

    public RecordingSessionMetrics() {
        for (CacheEvictionReason reason : CacheEvictionReason.values()) {
            cacheEvictions.put(reason, new AtomicInteger());
        }
        for (SubmitDisposition.Kind kind : SubmitDisposition.Kind.values()) {
            submitOutcomes.put(kind, new AtomicInteger());
        }
    }

    @Override
    public void onLockAcquireSucceeded(Duration latency) {
        lockAcquireSucceeded.incrementAndGet();
        lastLockAcquireLatency.set(latency);
    }

    @Override
    public void onLockAcquireRejected(Duration latency) {
        lockAcquireRejected.incrementAndGet();
        lastLockAcquireLatency.set(latency);
    }

    @Override
    public void onCacheHit() {
        cacheHits.incrementAndGet();
    }

    @Override
    public void onCacheMiss() {
        cacheMisses.incrementAndGet();
    }

    @Override
    public void onCacheEviction(CacheEvictionReason reason) {
        cacheEvictions.get(reason).incrementAndGet();
    }

    @Override
    public void onLeaseExtendSucceeded() {
        leaseExtendSucceeded.incrementAndGet();
    }

    @Override
    public void onLeaseExtendFailed() {
        leaseExtendFailed.incrementAndGet();
    }

    @Override
    public void onSubmitOutcome(SubmitDisposition.Kind kind) {
        submitOutcomes.get(kind).incrementAndGet();
    }

    @Override
    public void onHolderLossRecovered() {
        holderLossRecovered.incrementAndGet();
    }

    @Override
    public void onForwardDoorbellRerung() {
        forwardDoorbellRerung.incrementAndGet();
    }
}
