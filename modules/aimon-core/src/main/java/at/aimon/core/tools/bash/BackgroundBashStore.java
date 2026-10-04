package at.aimon.core.tools.bash;

import java.time.Instant;
import java.util.Optional;

/**
 * Where a {@link BackgroundBashManager} records the background commands it started — the part of the task list that
 * can be shared between nodes.
 *
 * <p>
 * A store holds {@link BackgroundBashRecord metadata} only. The process behind a task, the handle that stops it and
 * its output exist on one node and cannot be moved, so a node that finds a record without a local task can say the
 * command runs elsewhere, and nothing more. The default {@link InMemoryBackgroundBashStore} serves a single node; a
 * shared implementation is what lets a session reopened on another node be told where its task went instead of "not
 * found".
 *
 * <p>
 * A record must not carry command text or command output, and an implementation must not add either: both may
 * contain secrets, and a shared store keeps them beyond the node and the process they belong to.
 *
 * <p>
 * A store must return a record's three ownership fields — runtime, session, execution — exactly as they were written.
 * A lookup matches all three against the caller, so a store that loses one does not widen who can see the task: it
 * makes the task invisible to everyone, its owner included.
 *
 * <p>
 * Implementations must be thread-safe. A method that fails throws an unchecked exception: the manager refuses to start
 * a command it could not record, and otherwise carries on with what it knows locally.
 */
public interface BackgroundBashStore {

    /**
     * Records a newly started task unless the id is taken.
     *
     * @param record
     *            the record (must not be null)
     * @return {@code true} if it was stored, {@code false} if a record with this task id already exists — the manager
     *         then draws another id
     */
    boolean putIfAbsent(BackgroundBashRecord record);

    /**
     * @param taskId
     *            the task id (must not be null)
     * @return the record, or empty if there is none
     */
    Optional<BackgroundBashRecord> find(String taskId);

    /**
     * Moves a record from {@link BashTaskStatus#RUNNING} to the status its command ended in. One-way and idempotent: a
     * record that has already ended is returned unchanged.
     *
     * @param taskId
     *            the task id (must not be null)
     * @param terminal
     *            the status the command ended in (must not be null, and not {@code RUNNING})
     * @param exitCode
     *            the exit code, or null if the command ended without one
     * @param at
     *            when it ended (must not be null)
     * @return the record as stored after the call, or empty if there is none
     */
    Optional<BackgroundBashRecord> settle(String taskId, BashTaskStatus terminal, Integer exitCode, Instant at);

    /**
     * Removes a record. Does nothing if there is none.
     *
     * @param taskId
     *            the task id (must not be null)
     */
    void remove(String taskId);
}
