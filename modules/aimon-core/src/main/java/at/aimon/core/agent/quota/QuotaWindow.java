package at.aimon.core.agent.quota;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;

/**
 * The period after which a quota's accumulated usage starts again from zero.
 */
public enum QuotaWindow {
    /** Never resets. Every charge accumulates into the same bucket for as long as the ledger keeps the key. */
    TOTAL,
    /** Resets at midnight in the configured zone. The bucket is the ISO local date, e.g. {@code 2026-10-15}. */
    DAILY;

    /**
     * Names the bucket the given instant falls into.
     *
     * @param at
     *            the instant being charged or checked (must not be null)
     * @param zone
     *            the zone whose midnight separates {@link #DAILY} buckets (must not be null)
     * @return {@code ""} for {@link #TOTAL}; the ISO local date in {@code zone} for {@link #DAILY} (never null)
     */
    public String bucketAt(Instant at, ZoneId zone) {
        Objects.requireNonNull(at, "Instant cannot be null");
        Objects.requireNonNull(zone, "Zone cannot be null");
        return switch (this) {
            case TOTAL -> "";
            case DAILY -> LocalDate.ofInstant(at, zone).toString();
        };
    }
}
