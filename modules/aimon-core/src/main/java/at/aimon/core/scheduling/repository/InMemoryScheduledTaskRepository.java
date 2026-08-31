/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.core.scheduling.repository;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import at.aimon.core.base.Principal;
import at.aimon.core.scheduling.ScheduledTask;
import at.aimon.core.scheduling.ScheduledTaskId;

/**
 * In-memory implementation of {@link ScheduledTaskRepository}.
 *
 * <p>
 * This implementation is thread-safe and suitable for testing and single-node deployments.
 * </p>
 */
public class InMemoryScheduledTaskRepository implements ScheduledTaskRepository {

    private final Map<ScheduledTaskId, ScheduledTask> tasks = new ConcurrentHashMap<>();

    @Override
    public void save(ScheduledTask task) {
        tasks.put(task.getId(), task);
    }

    @Override
    public boolean updateIfPresent(ScheduledTask task) {
        Objects.requireNonNull(task, "Task cannot be null");
        // computeIfPresent is the atomicity the contract asks for: the presence check and the write happen under the
        // same bin lock, so a concurrent deleteById either loses the race entirely or wins it and leaves nothing here.
        return tasks.computeIfPresent(task.getId(), (id, stored) -> task) != null;
    }

    @Override
    public Optional<ScheduledTask> findById(ScheduledTaskId taskId) {
        return Optional.ofNullable(tasks.get(taskId));
    }

    @Override
    public List<ScheduledTask> findAll() {
        return List.copyOf(tasks.values());
    }

    @Override
    public List<ScheduledTask> findByEnabledTrue() {
        return tasks.values().stream().filter(ScheduledTask::isEnabled).toList();
    }

    @Override
    public List<ScheduledTask> findByOwner(Principal owner) {
        return tasks.values().stream().filter(task -> task.getOwner().equals(owner)).toList();
    }

    @Override
    public List<ScheduledTask> findByOwnerAndEnabledTrue(Principal owner) {
        return tasks.values().stream().filter(task -> task.getOwner().equals(owner) && task.isEnabled()).toList();
    }

    @Override
    public void deleteById(ScheduledTaskId taskId) {
        tasks.remove(taskId);
    }

    @Override
    public void clear() {
        tasks.clear();
    }
}
