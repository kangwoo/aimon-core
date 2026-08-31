package at.aimon.core.tools.todo;

import java.util.List;
import java.util.Optional;

/**
 * Repository abstraction for managing todo lists.
 *
 * <p>
 * This interface follows the Repository pattern from Domain-Driven Design (DDD) and the Dependency Inversion Principle
 * (DIP). TodoWriteTool depends on this abstraction, allowing different repository implementations to be plugged in
 * without modifying the tools:
 *
 * <ul>
 * <li>{@link InMemoryTodoRepository} - Default in-memory implementation
 * <li>FileTodoRepository - Persistent file-based repository (future)
 * <li>DatabaseTodoRepository - Database-backed repository (future)
 * </ul>
 *
 * <p>
 * <b>What {@code contextId} is.</b> Every method is keyed by a {@code contextId} string naming <b>the identity of the
 * run that owns the list</b> — which is not always a session, so the two production writers differ:
 *
 * <ul>
 * <li>{@code OrcaAgentExecutor} passes {@code SessionId.value()}. The main agent's list is a session's list: it is
 * visible to every {@code LiveSession} that serves that session, and sibling sessions of the same agent keep separate
 * lists.
 * <li>{@code DefaultSubagentExecutor} passes {@code ExecutionId.value()}. A fork has no session, so there is no
 * session id to pass; its list belongs to that one fork and dies with it.
 * </ul>
 *
 * <p>
 * It is never an {@code AgentRuntimeId} — that would pool every session of one agent into a single list. An
 * implementation must treat the string as an opaque key and must not parse it or infer which of the two kinds it got.
 *
 * <p>
 * <b>Lifetime is the implementation's choice — and it is not the session's.</b> The interface defines no eviction:
 * nothing calls {@link #remove(String)} when a run ends, so an implementation that never evicts grows without bound.
 * The default {@link InMemoryTodoRepository} accepts that because it is discarded with the process. A persistent
 * implementation must decide its own retention policy and must not borrow a session's: it cannot bind rows to
 * {@code SessionRecord} lifetime or evict them when a session is deleted, because a fork's entries match no session
 * record and would be missed by every such sweep.
 *
 * <p>
 * Thread-safety requirements depend on the implementation.
 *
 * @see InMemoryTodoRepository
 */
public interface TodoRepository {
    /**
     * Saves a todo list for a given context.
     *
     * <p>
     * If a todo list already exists for this context, it should be replaced.
     *
     * @param contextId
     *            The scope key — the identity of the run that owns the list: a {@code SessionId.value()}, or an
     *            {@code ExecutionId.value()} for a subagent fork (must not be null)
     * @param todos
     *            The todo list to save (must not be null)
     * @throws NullPointerException
     *             if contextId or todos is null
     */
    void save(String contextId, List<Todo> todos);

    /**
     * Retrieves the todo list for a given context.
     *
     * @param contextId
     *            The scope key — the identity of the run that owns the list: a {@code SessionId.value()}, or an
     *            {@code ExecutionId.value()} for a subagent fork (must not be null)
     * @return An Optional containing the todo list if present, empty otherwise
     * @throws NullPointerException
     *             if contextId is null
     */
    Optional<List<Todo>> get(String contextId);

    /**
     * Removes the todo list for a given context.
     *
     * @param contextId
     *            The scope key — the identity of the run that owns the list: a {@code SessionId.value()}, or an
     *            {@code ExecutionId.value()} for a subagent fork (must not be null)
     * @throws NullPointerException
     *             if contextId is null
     */
    void remove(String contextId);

    /**
     * Checks if a todo list exists for the given context.
     *
     * @param contextId
     *            The scope key — the identity of the run that owns the list: a {@code SessionId.value()}, or an
     *            {@code ExecutionId.value()} for a subagent fork (must not be null)
     * @return true if a todo list exists, false otherwise
     * @throws NullPointerException
     *             if contextId is null
     */
    default boolean exists(String contextId) {
        return get(contextId).isPresent();
    }

    /** Removes all todo lists from the repository. */
    void clear();
}
