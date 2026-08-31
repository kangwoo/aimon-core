package at.aimon.core.tools.bash;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import at.aimon.core.shell.ShellCommandResult;

/**
 * Manages background bash task execution and output retrieval.
 *
 * <p>
 * This manager provides:
 *
 * <ul>
 * <li>Background task registration and tracking
 * <li>Read-once output consumption
 * <li>Task status monitoring
 * <li>Output filtering with regex
 * <li>Task lifecycle management
 * </ul>
 *
 * <p>
 * Output is not streamed: a task's buffer is filled in one go when its command finishes, so polling a running task
 * returns nothing. See {@link BackgroundBashTask} for why.
 *
 * <p>
 * Thread-safe with concurrent access support via ConcurrentHashMap.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     BackgroundBashManager manager = new BackgroundBashManager();
 *
 *     // Register a background task
 *     CompletableFuture<ShellCommandResult> future = CompletableFuture
 *             .supplyAsync(() -> shell.execute(() -> "npm install", options));
 *     manager.registerTask("bash_123", "npm install", future);
 *
 *     // Consume output not yet read
 *     Optional<String> output = manager.readNewOutput("bash_123", null);
 *
 *     // Check status
 *     BashTaskStatus status = manager.getTaskStatus("bash_123");
 * }
 * </pre>
 */
public class BackgroundBashManager {

    private final Map<String, BackgroundBashTask> tasks;

    /** Creates a new BackgroundBashManager. */
    public BackgroundBashManager() {
        tasks = new ConcurrentHashMap<>();
    }

    /**
     * Registers a background bash task.
     *
     * @param taskId
     *            The task ID (must not be null)
     * @param command
     *            The bash command (must not be null)
     * @param future
     *            The future representing the command execution (must not be null)
     * @throws NullPointerException
     *             if any parameter is null
     */
    public void registerTask(String taskId, String command, CompletableFuture<ShellCommandResult> future) {
        Objects.requireNonNull(taskId, "Task ID cannot be null");
        Objects.requireNonNull(command, "Command cannot be null");
        Objects.requireNonNull(future, "Future cannot be null");

        final BackgroundBashTask task = new BackgroundBashTask(taskId, command, future);
        tasks.put(taskId, task);
    }

    /**
     * Reads the output of a background task that has not been consumed yet.
     *
     * <p>
     * Each line is returned only once. A task that is still running has nothing to return — its buffer is filled at
     * completion, not as the command runs.
     *
     * @param taskId
     *            The task ID (must not be null)
     * @param filterRegex
     *            Optional regex pattern to filter output lines, or null for no filtering
     * @return An Optional containing the new output if the task exists, empty otherwise
     * @throws NullPointerException
     *             if taskId is null
     */
    public Optional<String> readNewOutput(String taskId, String filterRegex) {
        Objects.requireNonNull(taskId, "Task ID cannot be null");

        final BackgroundBashTask task = tasks.get(taskId);
        if (task == null) {
            return Optional.empty();
        }

        final String output = task.readNewOutput(filterRegex);
        return Optional.of(output);
    }

    /**
     * Gets the status of a background task.
     *
     * @param taskId
     *            The task ID (must not be null)
     * @return The task status
     * @throws NullPointerException
     *             if taskId is null
     */
    public BashTaskStatus getTaskStatus(String taskId) {
        Objects.requireNonNull(taskId, "Task ID cannot be null");

        final BackgroundBashTask task = tasks.get(taskId);
        if (task == null) {
            return BashTaskStatus.NOT_FOUND;
        }

        return task.getStatus();
    }

    /**
     * Gets a background task.
     *
     * @param taskId
     *            The task ID (must not be null)
     * @return An Optional containing the task if it exists, empty otherwise
     * @throws NullPointerException
     *             if taskId is null
     */
    public Optional<BackgroundBashTask> getTask(String taskId) {
        Objects.requireNonNull(taskId, "Task ID cannot be null");
        return Optional.ofNullable(tasks.get(taskId));
    }

    /**
     * Waits for a task to complete.
     *
     * @param taskId
     *            The task ID (must not be null)
     * @param timeoutSeconds
     *            Maximum time to wait in seconds (must be positive)
     * @return true if the task completed within the timeout, false otherwise
     * @throws NullPointerException
     *             if taskId is null
     * @throws IllegalArgumentException
     *             if timeoutSeconds is not positive
     */
    public boolean awaitCompletion(String taskId, int timeoutSeconds) {
        Objects.requireNonNull(taskId, "Task ID cannot be null");
        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException("Timeout must be positive");
        }

        final BackgroundBashTask task = tasks.get(taskId);
        if (task == null) {
            return false;
        }

        try {
            // Use CompletableFuture.get with timeout
            final Future<String> future = CompletableFuture.supplyAsync(() -> {
                task.awaitCompletion();
                return "";
            });

            future.get(timeoutSeconds, TimeUnit.SECONDS);
            return true;
        } catch (TimeoutException e) {
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Removes a task from the manager.
     *
     * @param taskId
     *            The task ID (must not be null)
     * @throws NullPointerException
     *             if taskId is null
     */
    public void removeTask(String taskId) {
        Objects.requireNonNull(taskId, "Task ID cannot be null");
        tasks.remove(taskId);
    }

    /**
     * Checks if a task exists.
     *
     * @param taskId
     *            The task ID (must not be null)
     * @return true if the task exists, false otherwise
     * @throws NullPointerException
     *             if taskId is null
     */
    public boolean hasTask(String taskId) {
        Objects.requireNonNull(taskId, "Task ID cannot be null");
        return tasks.containsKey(taskId);
    }

    /**
     * Gets the number of active tasks.
     *
     * @return The number of active tasks
     */
    public int getActiveTaskCount() {
        return tasks.size();
    }

    /** Clears all tasks. */
    public void clear() {
        tasks.clear();
    }
}
