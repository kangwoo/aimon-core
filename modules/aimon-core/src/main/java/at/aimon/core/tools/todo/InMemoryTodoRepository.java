package at.aimon.core.tools.todo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory implementation of TodoRepository.
 *
 * <p>
 * Stores todo lists in a thread-safe concurrent map. This is the default implementation used by TodoWriteTool.
 *
 * <p>
 * Features:
 *
 * <ul>
 * <li>Thread-safe using ConcurrentHashMap
 * <li>Fast access (O(1) for get/save/remove)
 * <li>Defensive copies to prevent external modification
 * <li>Volatile data (lost on application restart)
 * </ul>
 *
 * <p>
 * <b>Two scopes at once.</b> The map is keyed by session id (see {@link TodoRepository}), but the instance
 * itself is created once per agent runtime — {@code OrcaTodoToolProvider} constructs one while populating that
 * runtime's {@code ToolRegistry}. So a single store holds the lists of every session the agent has served,
 * partitioned by key. Two agents never share a store; two sessions of one agent share the store but not a list.
 *
 * <p>
 * <b>Nothing evicts.</b> No production code calls {@link #remove(String)} or {@link #clear()} when a session
 * ends, so the map grows for the lifetime of the agent runtime — which outlives individual sessions and
 * sessions. That is acceptable here only because the data dies with the process; a long-running host with many
 * short sessions should expect the map to accumulate one entry per session.
 *
 * <p>
 * Use cases:
 *
 * <ul>
 * <li>Development and testing
 * <li>Single-process deployments where todos need not survive a restart
 * </ul>
 *
 * <p>
 * For persistence — or for any deployment that needs eviction or multi-instance sharing — implement a file-based or
 * database-backed repository instead.
 *
 * @see TodoRepository
 */
public class InMemoryTodoRepository implements TodoRepository {
    private final Map<String, List<Todo>> repository = new ConcurrentHashMap<>();

    /** Creates a new InMemoryTodoRepository. */
    public InMemoryTodoRepository() {
    }

    /**
     * Saves a todo list for a given context.
     *
     * <p>
     * Creates a defensive copy of the input list to prevent external modification.
     *
     * @param contextId
     *            The context identifier (must not be null)
     * @param todos
     *            The todo list to save (must not be null)
     * @throws NullPointerException
     *             if contextId or todos is null
     */
    @Override
    public void save(String contextId, List<Todo> todos) {
        Objects.requireNonNull(contextId, "Context ID cannot be null");
        Objects.requireNonNull(todos, "Todos cannot be null");

        // Create defensive copy
        final List<Todo> copy = new ArrayList<>(todos);
        repository.put(contextId, copy);
    }

    /**
     * Retrieves the todo list for a given context.
     *
     * <p>
     * Returns a defensive copy to prevent external modification.
     *
     * @param contextId
     *            The context identifier (must not be null)
     * @return An Optional containing a copy of the todo list if present, empty otherwise
     * @throws NullPointerException
     *             if contextId is null
     */
    @Override
    public Optional<List<Todo>> get(String contextId) {
        Objects.requireNonNull(contextId, "Context ID cannot be null");

        final List<Todo> todos = repository.get(contextId);
        if (todos == null) {
            return Optional.empty();
        }

        // Return defensive copy
        return Optional.of(new ArrayList<>(todos));
    }

    /**
     * Removes the todo list for a given context.
     *
     * @param contextId
     *            The context identifier (must not be null)
     * @throws NullPointerException
     *             if contextId is null
     */
    @Override
    public void remove(String contextId) {
        Objects.requireNonNull(contextId, "Context ID cannot be null");
        repository.remove(contextId);
    }

    /** Removes all todo lists from the repository. */
    @Override
    public void clear() {
        repository.clear();
    }

    /**
     * Gets the number of stored todo lists.
     *
     * @return The number of contexts with todo lists
     */
    public int size() {
        return repository.size();
    }
}
