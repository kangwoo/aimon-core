/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.core.scheduling.quota;

import java.util.Collection;

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

    /**
     * Tells the manager which tasks are already stored, so a ledger that does not outlive the process can be put back.
     *
     * <p>
     * Called when the scheduling engine starts, with one element per stored task — an owner of three tasks appears
     * three times. After a restart over a durable {@code ScheduledTaskRepository} the tasks are still there while an
     * in-memory count of them is not, and without this every owner gets a full quota on top of what they already
     * hold.
     *
     * <p>
     * <b>The default does nothing</b>, which is the right answer for a manager whose ledger is durable: it already
     * counts those tasks, and counting them again would charge each one twice. An in-memory manager overrides this
     * and <em>replaces</em> its counts with the ones given rather than adding to them, so that being told twice is
     * the same as being told once.
     *
     * @param ownersOfStoredTasks
     *            the owner of every stored task, repeated once per task (never null; may be empty)
     */
    default void restoreUsage(Collection<Principal> ownersOfStoredTasks) {
        // A durable ledger has nothing to restore — see above.
    }
}
