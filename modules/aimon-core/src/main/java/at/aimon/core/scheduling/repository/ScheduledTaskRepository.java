/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.core.scheduling.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import at.aimon.core.base.Principal;
import at.aimon.core.scheduling.ScheduledTask;
import at.aimon.core.scheduling.ScheduledTaskId;

/**
 * Repository interface for scheduled task persistence.
 */
public interface ScheduledTaskRepository {

    /**
     * Saves a scheduled task.
     *
     * @param task
     *            the task to save
     */
    void save(ScheduledTask task);

    /**
     * Replaces an already-stored task, and does nothing at all if it is no longer stored.
     *
     * <p>
     * The difference from {@link #save(ScheduledTask)} is what happens when the record has been deleted meanwhile:
     * {@code save} recreates it, this does not. That is the whole point of the method. A scheduled run reads its task
     * at fire time and writes it back when it finishes, so anything that deletes the task in between — a cancellation
     * — is undone by the write-back unless the write is conditional. What it leaves behind is worse than a stale row:
     * an unscheduled task that never fires again, yet is still listed and still found by id, with its quota unit
     * already refunded.
     *
     * <p>
     * IMPORTANT: implementations must make the check and the write <b>atomic</b>. A {@code findById} followed by a
     * {@code save} is not an implementation of this method — it reintroduces exactly the window the method exists to
     * close, only narrower. That is also why there is no {@code default} here: a durable backend must decide how it
     * gets atomicity (a conditional update, a compare-and-set, a transaction) rather than inherit a racy one.
     *
     * @param task
     *            the task to write in place of the stored one with the same id (must not be null)
     * @return {@code true} if a stored task was replaced, {@code false} if no task with that id exists — in which case
     *         nothing was written
     */
    boolean updateIfPresent(ScheduledTask task);

    /**
     * Records that a stored task ran, changing nothing else about it, and does nothing if it is no longer stored.
     *
     * <p>
     * This is what a finished run writes back. It is narrower than {@link #updateIfPresent(ScheduledTask)} on
     * purpose: a run holds the copy of the task it read at fire time, and writing that whole copy back undoes
     * whatever changed while the run was in flight. A task its owner disabled mid-run would be stored as enabled
     * again — and scheduled again at the next start — and a field written by a newer build would be erased by an
     * older one that does not know it. Writing only the one value the run owns has neither effect.
     *
     * <p>
     * Like {@code updateIfPresent}, the presence check and the write must be <b>atomic</b>, and a deleted task must
     * not be recreated.
     *
     * @param taskId
     *            the task that ran (must not be null)
     * @param executedAt
     *            when it ran (must not be null)
     * @return {@code true} if a stored task was updated, {@code false} if no task with that id exists — in which case
     *         nothing was written
     */
    boolean recordExecution(ScheduledTaskId taskId, Instant executedAt);

    /**
     * Finds a task by its ID.
     *
     * @param taskId
     *            the task ID
     * @return the task if found
     */
    Optional<ScheduledTask> findById(ScheduledTaskId taskId);

    /**
     * Returns all scheduled tasks.
     *
     * @return list of all tasks
     */
    List<ScheduledTask> findAll();

    /**
     * Returns all enabled tasks.
     *
     * <p>
     * This is what the engine asks at start ({@code ScheduledTaskManager.rehydrate}): "which tasks should be
     * scheduled right now", without holding an owner. A durable implementation is read here once per start, across
     * all owners and with their routines, so it should not be a scan the deployment cannot afford.
     *
     * @return list of enabled tasks
     */
    List<ScheduledTask> findByEnabledTrue();

    /**
     * Returns all tasks owned by the specified principal.
     *
     * @param owner
     *            the owning principal
     * @return list of tasks owned by the principal
     */
    List<ScheduledTask> findByOwner(Principal owner);

    /**
     * Counts the tasks owned by the specified principal, enabled or not.
     *
     * <p>
     * This is what a quota is measured against ({@code StoredTaskQuotaManager}), so it is asked on every
     * registration. The default counts {@link #findByOwner(Principal)}; a store should override it with a count that
     * does not load the tasks — and that counts a record it cannot decode, which {@code findByOwner} may leave out.
     *
     * @param owner
     *            the owning principal
     * @return how many tasks the principal owns
     */
    default int countByOwner(Principal owner) {
        return findByOwner(owner).size();
    }

    /**
     * Returns all enabled tasks owned by the specified principal.
     *
     * @param owner
     *            the owning principal
     * @return list of enabled tasks owned by the principal
     */
    List<ScheduledTask> findByOwnerAndEnabledTrue(Principal owner);

    /**
     * Deletes a task by its ID.
     *
     * @param taskId
     *            the task ID
     */
    void deleteById(ScheduledTaskId taskId);

    /**
     * Checks if a task exists by its ID.
     *
     * @param taskId
     *            the task ID
     * @return true if the task exists
     */
    default boolean existsById(ScheduledTaskId taskId) {
        return findById(taskId).isPresent();
    }

    /**
     * Clears all tasks.
     */
    void clear();
}
