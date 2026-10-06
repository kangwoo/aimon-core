/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.core.scheduling.quota;

import java.util.Objects;

import at.aimon.core.base.Principal;
import at.aimon.core.scheduling.exception.QuotaExceededException;
import at.aimon.core.scheduling.repository.ScheduledTaskRepository;

/**
 * {@link TaskQuotaManager} that keeps no ledger: an owner's usage is the number of tasks the
 * {@link ScheduledTaskRepository} holds for them, asked each time.
 *
 * <p>
 * A ledger beside the repository is a second copy of one fact, and it goes wrong exactly where the repository is made
 * to last or to be shared. Over a durable repository a restart empties an in-memory ledger while the tasks remain, so
 * every owner gets a full quota on top of what they hold. Over a repository several nodes share, a registration or a
 * cancellation on one node moves the truth without moving the others' ledgers: N nodes admit N times the quota, and
 * a node that never heard of a cancellation goes on refusing an owner who has room. Counting the stored tasks has
 * neither failure, because there is nothing to fall out of step.
 *
 * <p>
 * {@link #incrementUsage} and {@link #decrementUsage} therefore do nothing — saving and deleting the task is what
 * moves the count. The check is not atomic with the save that follows it, so two registrations racing for an owner's
 * last slot can both be admitted; that was equally true of the ledger, and a quota is a guard against runaway
 * registration rather than a hard limit.
 *
 * <p>
 * This is what {@code SchedulingEngineBuilder} installs when no manager is supplied. {@link DefaultTaskQuotaManager}
 * remains for callers that want a ledger of their own or per-principal overrides.
 */
public final class StoredTaskQuotaManager implements TaskQuotaManager {

    private final ScheduledTaskRepository taskRepository;
    private final int maxQuota;

    /**
     * Creates a manager that counts the tasks in {@code taskRepository}.
     *
     * @param taskRepository
     *            the repository whose stored tasks are the usage (must not be null)
     * @param maxQuota
     *            the maximum number of tasks per principal (must be positive)
     */
    public StoredTaskQuotaManager(ScheduledTaskRepository taskRepository, int maxQuota) {
        this.taskRepository = Objects.requireNonNull(taskRepository, "Task repository cannot be null");
        if (maxQuota <= 0) {
            throw new IllegalArgumentException("Max quota must be positive");
        }
        this.maxQuota = maxQuota;
    }

    @Override
    public void checkQuota(Principal principal) throws QuotaExceededException {
        final int current = getCurrentUsage(principal);
        if (current >= maxQuota) {
            throw new QuotaExceededException(principal, current, maxQuota);
        }
    }

    @Override
    public void incrementUsage(Principal principal) {
        Objects.requireNonNull(principal, "Principal cannot be null");
        // Nothing to record: the task that was just saved is the usage.
    }

    @Override
    public void decrementUsage(Principal principal) {
        Objects.requireNonNull(principal, "Principal cannot be null");
        // Nothing to record: the task that was just deleted is no longer counted.
    }

    @Override
    public int getCurrentUsage(Principal principal) {
        Objects.requireNonNull(principal, "Principal cannot be null");
        return taskRepository.countByOwner(principal);
    }

    @Override
    public int getMaxQuota(Principal principal) {
        Objects.requireNonNull(principal, "Principal cannot be null");
        return maxQuota;
    }
}
