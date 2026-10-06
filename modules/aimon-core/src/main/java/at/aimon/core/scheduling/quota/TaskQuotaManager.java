/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.core.scheduling.quota;

import at.aimon.core.base.Principal;
import at.aimon.core.scheduling.exception.QuotaExceededException;

/**
 * Interface for managing task quotas per principal.
 */
public interface TaskQuotaManager {

    /**
     * Checks if the principal can create more tasks.
     *
     * @param principal
     *            the owning principal
     * @throws QuotaExceededException
     *             if the quota is exceeded
     */
    void checkQuota(Principal principal) throws QuotaExceededException;

    /**
     * Increments the usage count for a principal.
     *
     * @param principal
     *            the owning principal
     */
    void incrementUsage(Principal principal);

    /**
     * Decrements the usage count for a principal.
     *
     * @param principal
     *            the owning principal
     */
    void decrementUsage(Principal principal);

    /**
     * Returns the current usage count for a principal.
     *
     * @param principal
     *            the owning principal
     * @return the current usage count
     */
    int getCurrentUsage(Principal principal);

    /**
     * Returns the maximum quota for a principal.
     *
     * @param principal
     *            the owning principal
     * @return the maximum allowed tasks
     */
    int getMaxQuota(Principal principal);
}
