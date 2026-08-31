/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.core.scheduling.quota;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import at.aimon.core.base.Principal;
import at.aimon.core.scheduling.exception.QuotaExceededException;

/**
 * Default implementation of {@link TaskQuotaManager}.
 *
 * <p>
 * Uses a default maximum quota that can be customized per principal.
 * </p>
 */
public class DefaultTaskQuotaManager implements TaskQuotaManager {

    private final int defaultMaxQuota;
    private final Map<String, AtomicInteger> usageMap = new ConcurrentHashMap<>();
    private final Map<String, Integer> customQuotas = new ConcurrentHashMap<>();

    /**
     * Creates a quota manager with the specified default maximum.
     *
     * @param defaultMaxQuota
     *            the default maximum quota per principal
     */
    public DefaultTaskQuotaManager(int defaultMaxQuota) {
        if (defaultMaxQuota <= 0) {
            throw new IllegalArgumentException("Default max quota must be positive");
        }
        this.defaultMaxQuota = defaultMaxQuota;
    }

    @Override
    public void checkQuota(Principal principal) throws QuotaExceededException {
        Objects.requireNonNull(principal, "Principal cannot be null");

        final int current = getCurrentUsage(principal);
        final int max = getMaxQuota(principal);

        if (current >= max) {
            throw new QuotaExceededException(principal, current, max);
        }
    }

    @Override
    public void incrementUsage(Principal principal) {
        Objects.requireNonNull(principal, "Principal cannot be null");

        final String key = quotaKey(principal);
        usageMap.computeIfAbsent(key, k -> new AtomicInteger(0)).incrementAndGet();
    }

    @Override
    public void decrementUsage(Principal principal) {
        Objects.requireNonNull(principal, "Principal cannot be null");

        final String key = quotaKey(principal);
        final AtomicInteger usage = usageMap.get(key);
        if (usage != null) {
            usage.updateAndGet(v -> Math.max(0, v - 1));
        }
    }

    @Override
    public int getCurrentUsage(Principal principal) {
        Objects.requireNonNull(principal, "Principal cannot be null");

        final String key = quotaKey(principal);
        final AtomicInteger usage = usageMap.get(key);
        return usage != null ? usage.get() : 0;
    }

    @Override
    public int getMaxQuota(Principal principal) {
        Objects.requireNonNull(principal, "Principal cannot be null");

        final String key = quotaKey(principal);
        return customQuotas.getOrDefault(key, defaultMaxQuota);
    }

    /**
     * Sets a custom quota for a principal.
     *
     * @param principal
     *            the principal
     * @param maxQuota
     *            the maximum quota
     */
    public void setCustomQuota(Principal principal, int maxQuota) {
        Objects.requireNonNull(principal, "Principal cannot be null");
        if (maxQuota <= 0) {
            throw new IllegalArgumentException("Max quota must be positive");
        }

        final String key = quotaKey(principal);
        customQuotas.put(key, maxQuota);
    }

    /**
     * Removes a custom quota for a principal, reverting to the default.
     *
     * @param principal
     *            the principal
     */
    public void removeCustomQuota(Principal principal) {
        Objects.requireNonNull(principal, "Principal cannot be null");

        final String key = quotaKey(principal);
        customQuotas.remove(key);
    }

    /**
     * Resets all usage counts.
     */
    public void resetAllUsage() {
        usageMap.clear();
    }

    private String quotaKey(Principal principal) {
        return principal.getType() + ":" + principal.getId();
    }
}
