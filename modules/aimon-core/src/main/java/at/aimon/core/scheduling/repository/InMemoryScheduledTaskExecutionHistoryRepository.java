/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.core.scheduling.repository;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import at.aimon.core.scheduling.ScheduledTaskExecutionHistory;
import at.aimon.core.scheduling.ScheduledTaskId;

/**
 * In-memory implementation of {@link ScheduledTaskExecutionHistoryRepository}.
 *
 * <p>
 * This implementation is thread-safe and suitable for testing and single-node deployments.
 * </p>
 */
public class InMemoryScheduledTaskExecutionHistoryRepository implements ScheduledTaskExecutionHistoryRepository {

    private final Map<String, ScheduledTaskExecutionHistory> histories = new ConcurrentHashMap<>();

    @Override
    public void save(ScheduledTaskExecutionHistory history) {
        histories.put(history.getId(), history);
    }

    @Override
    public Optional<ScheduledTaskExecutionHistory> findById(String historyId) {
        return Optional.ofNullable(histories.get(historyId));
    }

    @Override
    public List<ScheduledTaskExecutionHistory> findByTaskId(ScheduledTaskId taskId) {
        return histories.values().stream().filter(h -> h.getTaskId().equals(taskId))
                .sorted(Comparator.comparing(ScheduledTaskExecutionHistory::getStartedAt).reversed()).toList();
    }

    @Override
    public List<ScheduledTaskExecutionHistory> findByTaskIdOrderByStartedAtDesc(ScheduledTaskId taskId, int limit) {
        return histories.values().stream().filter(h -> h.getTaskId().equals(taskId))
                .sorted(Comparator.comparing(ScheduledTaskExecutionHistory::getStartedAt).reversed()).limit(limit)
                .toList();
    }

    @Override
    public void deleteByTaskId(ScheduledTaskId taskId) {
        histories.entrySet().removeIf(entry -> entry.getValue().getTaskId().equals(taskId));
    }

    @Override
    public void clear() {
        histories.clear();
    }
}
