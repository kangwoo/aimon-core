/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.core.scheduling;

import java.time.Duration;

/** Duration arithmetic shared by the scheduling package's bounded waits. */
final class SchedulingDurations {

    private SchedulingDurations() {
    }

    /**
     * Returns {@code timeout} in nanoseconds, saturating at {@link Long#MAX_VALUE} instead of throwing: a caller who
     * passes a huge timeout (say {@code Duration.ofSeconds(Long.MAX_VALUE)}) means "wait as long as it takes", and
     * {@link Duration#toNanos()} would throw {@link ArithmeticException} past about 292 years. A deadline computed as
     * {@code System.nanoTime() + saturatedNanos(timeout)} may wrap, which is harmless: it is only ever compared through
     * the difference {@code deadline - System.nanoTime()}.
     *
     * @param timeout
     *            a non-negative duration
     * @return its length in nanoseconds, at most {@link Long#MAX_VALUE}
     */
    static long saturatedNanos(Duration timeout) {
        try {
            return timeout.toNanos();
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }
}
