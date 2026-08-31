/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.core.scheduling.repository;

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
     * <b>No production code calls this, and that is not a reason to remove it.</b> This is the seam a rehydration
     * loop needs: after a restart, something has to ask "which tasks should be scheduled right now" without holding
     * an owner, and this is the only query that answers it. No such loop exists yet, because the only repository
     * implementation in the tree is {@link InMemoryScheduledTaskRepository} and there is nothing to rehydrate from.
     * When a durable implementation arrives, the in-memory scheduler path needs this method; the Quartz JDBC job
     * store path does not, since {@code DelegatingJob} stores only the task id and re-reads the record at fire time.
     * Deleting it as dead code would move that work further away rather than closer.
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
