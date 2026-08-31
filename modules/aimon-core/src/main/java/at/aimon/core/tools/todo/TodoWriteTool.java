package at.aimon.core.tools.todo;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.ToolCategories;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolContextKey;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;

/**
 * Tool for managing todo lists during agent execution.
 *
 * <p>
 * This tools helps AI agents track progress through complex, multi-step operations by maintaining structured task lists
 * with real-time status updates. It provides visibility into current execution state and helps agents maintain focus.
 *
 * <p>
 * Features:
 *
 * <ul>
 * <li>Task status tracking (pending, in_progress, completed)
 * <li>Dual task descriptions (imperative and active forms)
 * <li>Input validation (ensures only one task is in_progress)
 * <li>Progress reporting
 * <li>Pluggable repository backend (in-memory, file, database)
 * </ul>
 *
 * <p>
 * Design:
 *
 * <ul>
 * <li>Uses {@link TodoRepository} abstraction for extensibility (DIP)
 * <li>Default repository: {@link InMemoryTodoRepository}
 * <li>Thread-safe if underlying repository is thread-safe
 * </ul>
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     TodoRepository repository = new InMemoryTodoRepository();
 *     Tool todoWrite = new TodoWriteTool(repository);
 *     ToolContext context = ToolContext.builder().put(TodoWriteTool.CONTEXT_ID_KEY, "conv-123").build();
 *
 *     Map&lt;String, Object&gt; input = Map.of("todos", List.of(
 *             Map.of("content", "Run tests", "status", "completed", "activeForm", "Running tests"),
 *             Map.of("content", "Fix failing tests", "status", "in_progress", "activeForm", "Fixing failing tests"),
 *             Map.of("content", "Update documentation", "status", "pending", "activeForm", "Updating documentation")));
 *     ToolResult result = todoWrite.execute(input, context);
 * }
 * </pre>
 *
 * @see TodoRepository
 * @see InMemoryTodoRepository
 * @see Todo
 */
public class TodoWriteTool extends AbstractTool {

    public static final String TOOL_NAME = "TodoWrite";

    /**
     * Default typed key for extracting the context identifier from {@link ToolContext}.
     *
     * <p>
     * <b>Set this to the identity of the run that owns the list</b> — and to nothing wider. Which id that is depends
     * on what the run is, so the two production writers differ:
     *
     * <ul>
     * <li>{@code OrcaAgentExecutor} puts {@code SessionId.value()}. The main agent's list is a session's list: it
     * survives across the {@code LiveSession}s that serve one session, and sibling sessions of the same agent keep
     * separate lists.
     * <li>{@code DefaultSubagentExecutor} puts {@code ExecutionId.value()}. A fork has no session, so there is no
     * session id to put; its list belongs to that one fork and dies with it. A resumed fork inherits the execution
     * id of the run it continues, which is what keeps a suspended fork's list from being split in two.
     * </ul>
     *
     * <p>
     * What both have in common is that the id names <b>this run and no other</b>. Widening it merges buckets
     * silently: an {@code AgentRuntimeId} would pool every session of that agent into one list, and an
     * {@code invokingSessionId} — shared with the invoker by construction — would fold a fork's list into its
     * parent's.
     *
     * <p>
     * When the key is absent the tool falls back to the literal id {@code "default"} — a single shared bucket, not a
     * per-caller one. That fallback exists for tests and ad-hoc embedding; any caller running more than one
     * session must set the key.
     *
     * <p>
     * Example:
     *
     * <pre>
     * {@code
     * ToolContext context = ToolContext.builder()
     *         .put(TodoWriteTool.CONTEXT_ID_KEY, sessionId.value())
     *         .build();
     * }
     * </pre>
     */
    public static final ToolContextKey<String> CONTEXT_ID_KEY = ToolContextKey.of("todo_write.context_id",
            String.class);

    private static final String DEFAULT_CONTEXT_ID = "default";

    private final TodoRepository repository;
    private final ObjectMapper objectMapper;
    private final ToolContextKey<String> contextKey;

    /**
     * Creates a new TodoWriteTool with the given repository backend.
     *
     * <p>
     * Uses the default {@link #CONTEXT_ID_KEY} to extract context from ToolContext.
     *
     * <p>
     * The tool is configured with the following schema:
     *
     * <ul>
     * <li>Name: "TodoWrite"
     * <li>Required parameter: "todos" (array) - The complete todo list
     * <li>Each todo must have: content (string), status (enum), activeForm (string)
     * </ul>
     *
     * @param repository
     *            The repository backend (must not be null)
     * @throws NullPointerException
     *             if repository is null
     */
    public TodoWriteTool(TodoRepository repository) {
        this(repository, CONTEXT_ID_KEY);
    }

    /**
     * Creates a new TodoWriteTool with the given repository backend and custom context key.
     *
     * <p>
     * The context key is used to extract the context identifier from ToolContext. For example, if contextKey is
     * {@code ToolContextKey.of("sessionId", String.class)}, the tool will look for context.get(contextKey) to
     * determine which context to use.
     *
     * <p>
     * The tool is configured with the following schema:
     *
     * <ul>
     * <li>Name: "TodoWrite"
     * <li>Required parameter: "todos" (array) - The complete todo list
     * <li>Each todo must have: content (string), status (enum), activeForm (string)
     * </ul>
     *
     * @param repository
     *            The repository backend (must not be null)
     * @param contextKey
     *            The typed key to use for extracting context ID from ToolContext (must not be null)
     * @throws NullPointerException
     *             if repository or contextKey is null
     */
    public TodoWriteTool(TodoRepository repository, ToolContextKey<String> contextKey) {
        super(TOOL_NAME, "Create and manage a structured task list for tracking progress during execution. "
                + "Helps organize complex tasks, track status, and demonstrate thoroughness. "
                + "Each task has content (imperative form), status (pending/in_progress/completed), "
                + "and activeForm (present continuous form). During execution, EXACTLY ONE task must be in_progress. "
                + "IMPORTANT: When all tasks are done, you MUST call TodoWrite one final time "
                + "with all tasks marked as 'completed' before providing your final response.", ToolCategories.WORKFLOW,
                createInputSchema());
        this.repository = Objects.requireNonNull(repository, "Repository cannot be null");
        this.contextKey = Objects.requireNonNull(contextKey, "Context key cannot be null");
        objectMapper = new ObjectMapper();
    }

    /**
     * Creates the JSON Schema for todo_write tools input.
     *
     * @return The input schema map
     */
    private static Map<String, Object> createInputSchema() {
        return Map.of("type", "object", "additionalProperties", false, "properties", Map.of("todos", Map.of("type",
                "array", "description", "The complete todo list with all tasks", "items",
                Map.of("type", "object", "properties", Map.of(
                        "content", Map.of("type", "string", "description",
                                "Imperative form of the task (e.g., 'Run tests', 'Fix bug')", "minLength", 1),
                        "status",
                        Map.of("type", "string", "description", "Current task status", "enum",
                                List.of("pending", "in_progress", "completed")),
                        "activeForm",
                        Map.of("type", "string", "description",
                                "Present continuous form shown during execution (e.g., 'Running tests', 'Fixing bug')",
                                "minLength", 1)),
                        "required", List.of("content", "status", "activeForm"), "additionalProperties", false))),
                "required", List.of("todos"));
    }

    /**
     * Manages the todo list by validating input, saving to repository, and tracking progress.
     *
     * <p>
     * The method performs the following operations:
     *
     * <ol>
     * <li>Extracts the session-scoped contextId from ToolContext (falls back to the shared {@code "default"}
     * bucket when {@link #CONTEXT_ID_KEY} is absent)
     * <li>Validates and converts input todos to {@link Todo} objects
     * <li>Validates exactly ONE task is in_progress, or zero when all tasks are completed
     * <li>Saves the todo list to repository
     * <li>Returns a summary of the todo list state
     * </ol>
     *
     * <p>
     * Returns a summary containing:
     *
     * <ul>
     * <li>Total number of tasks
     * <li>Number of completed tasks
     * <li>Current in_progress task
     * <li>Number of pending tasks
     * </ul>
     *
     * @param input
     *            The input parameters containing todos array
     * @param context
     *            The execution context; its {@link #CONTEXT_ID_KEY} selects which run's list is written
     * @return A success result with todo list summary if valid, or an error result if validation fails
     * @throws NullPointerException
     *             if input or context is null
     */
    @Override
    public ToolResult execute(ToolInput input, ToolContext context) {
        Objects.requireNonNull(input, "Input cannot be null");
        Objects.requireNonNull(context, "Context cannot be null");

        try {
            // Extract contextId from context (default: "default")
            final String contextId = context.get(contextKey).orElse(DEFAULT_CONTEXT_ID);

            // Extract todos parameter (raw access for complex type)
            final Object todosObj = input.get("todos");
            if (todosObj == null) {
                return ToolResult.error("Missing required parameter: 'todos'");
            }

            // Convert to Todo objects using Jackson
            final List<Todo> todos;
            try {
                @SuppressWarnings("unchecked")
                final List<Map<String, Object>> todoMaps = (List<Map<String, Object>>) todosObj;
                todos = objectMapper.convertValue(todoMaps, new TypeReference<List<Todo>>() {
                });
            } catch (Exception e) {
                return ToolResult.error("Failed to parse todos: " + e.getMessage());
            }

            // Validate non-empty
            if (todos.isEmpty()) {
                return ToolResult.error("Todo list cannot be empty.");
            }

            // Validate task status constraints:
            // - Exactly one in_progress task during execution, OR
            // - Zero in_progress tasks when all tasks are completed (final update)
            final long inProgressCount = todos.stream().filter(todo -> todo.getStatus() == TodoStatus.IN_PROGRESS)
                    .count();
            final boolean allCompleted = todos.stream().allMatch(todo -> todo.getStatus() == TodoStatus.COMPLETED);

            if (inProgressCount == 0 && !allCompleted) {
                return ToolResult.error("At least one task must be 'in_progress' unless all tasks are 'completed'. "
                        + "Update task status before starting new tasks.");
            }
            if (inProgressCount > 1) {
                return ToolResult.error(String.format("At most ONE task can be 'in_progress', found %d. "
                        + "Update task status before starting new tasks.", inProgressCount));
            }

            // Save to repository
            repository.save(contextId, todos);

            // Build success message with summary
            final String summary = buildSummary(todos);
            return ToolResult.success(summary);

        } catch (IllegalArgumentException e) {
            return ToolResult.error("Invalid input: " + e.getMessage());
        } catch (Exception e) {
            return ToolResult.error("Unexpected error: " + e.getMessage());
        }
    }

    /**
     * Builds a summary of the todo list state.
     *
     * @param todos
     *            The todo list
     * @return A formatted summary string
     */
    private String buildSummary(List<Todo> todos) {
        final long completedCount = todos.stream().filter(todo -> todo.getStatus() == TodoStatus.COMPLETED).count();

        final long pendingCount = todos.stream().filter(todo -> todo.getStatus() == TodoStatus.PENDING).count();

        final String currentTask = todos.stream().filter(todo -> todo.getStatus() == TodoStatus.IN_PROGRESS).findFirst()
                .map(Todo::getActiveForm).orElse("None");

        if (completedCount == todos.size()) {
            return String.format("Todo list updated: %d total tasks%n  Completed: %d (all done)", todos.size(),
                    completedCount);
        }

        return String.format(
                "Todo list updated: %d total tasks%n" + "  Completed: %d%n" + "  In Progress: %s%n" + "  Pending: %d",
                todos.size(), completedCount, currentTask, pendingCount);
    }
}
