/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.core.scheduling.repository;

import java.util.List;
import java.util.Optional;

import at.aimon.core.scheduling.ScheduledTaskExecutionHistory;
import at.aimon.core.scheduling.ScheduledTaskId;

/**
 * Repository interface for task execution history persistence.
 */
public interface ScheduledTaskExecutionHistoryRepository {

    /**
     * Saves an execution history record.
     *
     * @param history
     *            the history record to save
     */
    void save(ScheduledTaskExecutionHistory history);

    /**
     * Finds a history record by its ID.
     *
     * @param historyId
     *            the history ID
     * @return the history record if found
     */
    Optional<ScheduledTaskExecutionHistory> findById(String historyId);

    /**
     * Returns all history records for a task.
     *
     * @param taskId
     *            the task ID
     * @return list of history records
     */
    List<ScheduledTaskExecutionHistory> findByTaskId(ScheduledTaskId taskId);

    /**
     * Returns the most recent history records for a task.
     *
     * @param taskId
     *            the task ID
     * @param limit
     *            maximum number of records to return
     * @return list of recent history records
     */
    List<ScheduledTaskExecutionHistory> findByTaskIdOrderByStartedAtDesc(ScheduledTaskId taskId, int limit);

    /**
     * Deletes all history records for a task.
     *
     * @param taskId
     *            the task ID
     */
    void deleteByTaskId(ScheduledTaskId taskId);

    /**
     * Clears all history records.
     */
    void clear();
}
