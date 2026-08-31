package at.aimon.core.tools.task;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.Constants;
import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.ToolCategories;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.subagent.ScopedSubagentTaskController;
import at.aimon.core.subagent.SubagentTaskController;
import at.aimon.core.subagent.execution.SubagentResultFormatter;
import at.aimon.core.subagent.task.BackgroundTask;
import at.aimon.core.subagent.task.BackgroundTaskState;
import at.aimon.core.subagent.task.OutputSlice;
import at.aimon.core.subagent.task.TaskOutputStore;
import at.aimon.core.subagent.task.TaskResult;
import at.aimon.core.subagent.task.TaskResultStore;
import at.aimon.core.tools.ToolContextKeys;

/**
 * Tool for retrieving output from background agent tasks.
 *
 * <p>
 * This tools allows checking on the progress or retrieving results from agents running in the background.
 *
 * <p>
 * Features:
 *
 * <ul>
 * <li>Non-blocking status checks
 * <li>Blocking result retrieval
 * <li>Incremental progress tailing via {@code from_offset}/{@code max_chars}
 * <li>Bounded final-result inlining via {@link SubagentResultFormatter}
 * </ul>
 *
 * <p>
 * <b>Everything it reads comes from a store.</b> Lifecycle state is resolved through the
 * {@link SubagentTaskController} (backed by {@code BackgroundTaskStore}), the final result through
 * {@link TaskResultStore}, and the live progress log through {@link TaskOutputStore} — all three keyed by the same
 * {@code taskId}. The tool holds no node-local handle on a running task, so a task launched on another node, or one
 * that settled before this process started, reads back the same way as a local one.
 *
 * <p>
 * <b>What {@code block=true} means.</b> It polls: the tool re-reads the task's state until it becomes terminal, then
 * loads the result. Because a result is always saved <em>before</em> the terminal transition (see
 * {@link TaskResultStore}), observing a terminal state is enough — a terminal task with no stored result produced
 * none, and is reported as such rather than waited on forever. Waiting is bounded by {@code wait_up_to} (default 150s);
 * a non-positive value degrades to a single poll.
 *
 * <p>
 * Thread-safe as long as the injected controller and stores are.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     Tool agentOutputTool = new AgentOutputTool(subagentExecutionManager, taskOutputStore, taskResultStore);
 *
 *     // Check status without blocking
 *     Map&lt;String, Object&gt; input1 = Map.of("taskId", "task_12345", "block", false);
 *     ToolResult result1 = agentOutputTool.execute(input1, context);
 *
 *     // Wait for completion
 *     Map&lt;String, Object&gt; input2 = Map.of("taskId", "task_12345", "block", true);
 *     ToolResult result2 = agentOutputTool.execute(input2, context);
 *
 *     // Tail the live progress log incrementally
 *     Map&lt;String, Object&gt; input3 = Map.of("taskId", "task_12345", "from_offset", 0);
 *     ToolResult result3 = agentOutputTool.execute(input3, context);
 * }
 * </pre>
 */
public class AgentOutputTool extends AbstractTool {

    public static final String TOOL_NAME = "AgentOutput";

    /** Default character budget for a single incremental read. */
    private static final int DEFAULT_DELTA_MAX_CHARS = 8_000;
    /** Upper bound for a single incremental read, guarding against oversized deltas. */
    private static final int MAX_DELTA_CHARS = 100_000;
    /**
     * Interval between state re-reads while {@code block=true} is waiting.
     *
     * <p>
     * Deliberately coarse. The waiting agent is idle either way and half a second is invisible next to the seconds a
     * background task takes, while a tighter loop would multiply reads against a shared backend for every agent
     * waiting on a task.
     */
    private static final long POLL_INTERVAL_MILLIS = 500L;

    private final SubagentTaskController taskController;
    private final TaskOutputStore taskOutputStore;
    private final TaskResultStore taskResultStore;

    /**
     * Creates a new AgentOutputTool without live-output tailing or result retrieval.
     *
     * <p>
     * Incremental reads ({@code from_offset}) report that streaming is not configured, and a settled task reports that
     * its result was not retained; status checks work.
     *
     * @param taskController
     *            The background task control plane (must not be null)
     * @throws NullPointerException
     *             if taskController is null
     */
    public AgentOutputTool(SubagentTaskController taskController) {
        this(taskController, null, null);
    }

    /**
     * Creates a new AgentOutputTool with an optional live-output store and no result retrieval.
     *
     * @param taskController
     *            The background task control plane (must not be null)
     * @param taskOutputStore
     *            The task output store backing incremental reads and the full-output retrieval pointer (nullable;
     *            incremental reads are unavailable when absent)
     * @throws NullPointerException
     *             if taskController is null
     */
    public AgentOutputTool(SubagentTaskController taskController, TaskOutputStore taskOutputStore) {
        this(taskController, taskOutputStore, null);
    }

    /**
     * Creates a new AgentOutputTool over the background-task control plane and its two optional output stores.
     *
     * <p>
     * The control plane is also what authorizes retrieval: a task whose stored {@link AgentRuntimeId} differs from the
     * caller's is reported as not-found instead of having its status, final result, or live progress log served — so
     * one agent cannot read another's background output merely by knowing its globally-unique task id. When the caller
     * carries no agent runtime id (unit tests, non-Orca embeddings) retrieval is unscoped, preserving legacy behavior.
     *
     * @param taskController
     *            The background task control plane, used both to resolve lifecycle state and to authorize retrieval by
     *            owning context (must not be null)
     * @param taskOutputStore
     *            The task output store backing incremental reads and the full-output retrieval pointer (nullable;
     *            incremental reads are unavailable when absent)
     * @param taskResultStore
     *            The task result store holding what each task finally produced (nullable; a settled task then reports
     *            that its result was not retained)
     * @throws NullPointerException
     *             if taskController is null
     */
    public AgentOutputTool(SubagentTaskController taskController, TaskOutputStore taskOutputStore,
            TaskResultStore taskResultStore) {
        super(TOOL_NAME, "Retrieves output from a background agent task by task ID. "
                + "Use 'block: false' to check status without waiting, or 'block: true' to wait for completion. "
                + "Set 'from_offset' to tail the live progress log incrementally instead of fetching the final result. "
                + "Returns the agent's execution result when available.", ToolCategories.EXECUTION,
                createInputSchema());
        this.taskController = Objects.requireNonNull(taskController, "Task controller cannot be null");
        this.taskOutputStore = taskOutputStore;
        this.taskResultStore = taskResultStore;
    }

    /**
     * Creates the JSON Schema for agent_output tools input.
     *
     * @return The input schema map
     */
    private static Map<String, Object> createInputSchema() {
        return Map.of("type", "object", "additionalProperties", false, "properties", Map.of("taskId",
                Map.of("type", "string", "description", "The task ID to retrieve results for"), "block",
                Map.of("type", "boolean", "description", "Whether to block until results are ready. Default: true"),
                "wait_up_to",
                Map.of("type", "number", "description",
                        "Maximum time to wait in seconds (only used when block=true). Default: 150"),
                "from_offset",
                Map.of("type", "number", "description",
                        "If set, read the live progress log incrementally starting at this character offset instead of "
                                + "returning the final result. Use the returned next offset to advance the cursor."),
                "max_chars",
                Map.of("type", "number", "description",
                        "Maximum characters to return for an incremental read (only used with from_offset). "
                                + "Default: 8000")),
                "required", List.of("taskId"));
    }

    /**
     * Executes the agent_output tools to retrieve background task results.
     *
     * @param input
     *            The input parameters containing taskId and optional block parameter
     * @param context
     *            The execution context; its {@link ToolContextKeys#AGENT_RUNTIME_ID} confines retrieval to the
     *            calling agent's own background tasks
     * @return A success result with agent output if available, or a status message if still running
     * @throws NullPointerException
     *             if input or context is null
     */
    @Override
    public ToolResult execute(ToolInput input, ToolContext context) {
        Objects.requireNonNull(input, "Input cannot be null");
        Objects.requireNonNull(context, "Context cannot be null");

        try {
            // Extract required taskId parameter
            final String taskId = input.getRequiredString("taskId");

            // Cross-agent isolation: when the caller carries an agent runtime id, only a task owned by that runtime
            // resolves. A foreign or unknown task is reported not-found with the same message as a genuinely absent
            // task, so the tool never leaks the existence of another agent's task. This single lookup answers both
            // "may the caller read it" and "what state is it in", and precedes both the incremental-tail and the
            // final-result paths, so neither serves foreign output.
            final SubagentTaskController controller = scopedController(context);
            final Optional<BackgroundTask> task = controller.status(taskId);
            if (task.isEmpty()) {
                return notFound(taskId);
            }

            // Incremental tail path: when from_offset is present, read the live progress log delta instead of
            // returning the final result. This is a non-blocking poll of the output store.
            final Long fromOffset = input.getLongOrNull("from_offset");
            if (fromOffset != null) {
                return readIncremental(taskId, task.get().getState(), fromOffset, input);
            }

            // Extract optional block parameter (default: true)
            final boolean block = input.getBoolean("block", true);
            final BackgroundTaskState state = task.get().getState();

            // Non-blocking path: a single poll, never waits.
            if (!block) {
                return state.isTerminal() ? settled(taskId, state) : stillRunning(taskId);
            }

            // Blocking path: poll the task's state until it settles, bounded by wait_up_to seconds so a slow task
            // does not hang the calling execution. Default 150s; a non-positive value degrades to a single poll.
            final int waitUpTo = input.getInteger("wait_up_to", 150);
            return awaitSettled(controller, taskId, state, waitUpTo);

        } catch (Exception e) {
            return ToolResult.error("Failed to retrieve agent output: " + e.getMessage());
        }
    }

    /**
     * Polls the task's state until it becomes terminal or the deadline passes.
     *
     * <p>
     * State is read before the result on every pass, never the other way around: the writer saves the result before the
     * terminal transition, so a terminal state read here is a guarantee that the subsequent load sees the result. The
     * reverse order would race — a load that missed by a microsecond, followed by a terminal state, would look like a
     * task that produced nothing.
     */
    private ToolResult awaitSettled(SubagentTaskController controller, String taskId, BackgroundTaskState initialState,
            int waitUpTo) {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(Math.max(0, waitUpTo));
        BackgroundTaskState state = initialState;
        while (true) {
            if (state.isTerminal()) {
                return settled(taskId, state);
            }
            final long remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (remainingMillis <= 0) {
                return ToolResult.success("Task " + taskId + " is still running after " + waitUpTo
                        + "s. Call AgentOutput again to keep waiting, or continue working and check back later.");
            }
            try {
                Thread.sleep(Math.min(POLL_INTERVAL_MILLIS, remainingMillis));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ToolResult
                        .success("Task " + taskId + " is still running; the wait was interrupted before it finished.");
            }
            final Optional<BackgroundTask> refreshed = controller.status(taskId);
            if (refreshed.isEmpty()) {
                // Evicted (or its lease released) while we waited — indistinguishable from never having existed.
                return notFound(taskId);
            }
            state = refreshed.get().getState();
        }
    }

    /**
     * Renders a settled task: its stored result when one is available, otherwise a statement of the terminal state that
     * says why there is nothing to show. The two cases are kept apart deliberately — "no result store is wired" is a
     * configuration fact, while a missing result under a configured store means the task genuinely produced none.
     */
    private ToolResult settled(String taskId, BackgroundTaskState state) {
        final Optional<TaskResult> result = taskResultStore == null ? Optional.empty() : taskResultStore.load(taskId);
        if (result.isPresent()) {
            return ToolResult.success(formatAgentResult(result.get(), taskId));
        }
        final StringBuilder message = new StringBuilder();
        message.append("Task ").append(taskId).append(" finished with status ").append(state)
                .append(" but no result is available");
        if (taskResultStore == null) {
            message.append(": result retention is not configured for this agent.");
        } else {
            message.append(": the task produced none, or its result was evicted.");
        }
        if (taskOutputStore != null) {
            message.append(" Call ").append(TOOL_NAME).append("(taskId=\"").append(taskId)
                    .append("\", from_offset=0) to read its progress log.");
        }
        return ToolResult.error(message.toString());
    }

    private static ToolResult stillRunning(String taskId) {
        return ToolResult.success("Task " + taskId + " is still running. "
                + "Call again with block=true to wait for completion, " + "or continue working and check back later.");
    }

    private static ToolResult notFound(String taskId) {
        return ToolResult
                .error("Task not found: " + taskId + ". " + "The task may not exist or may have been removed.");
    }

    /**
     * Returns the control plane confined to the caller's own agent runtime, or the unscoped one on a call path that
     * carries no runtime id (unit tests, non-Orca embeddings).
     */
    private SubagentTaskController scopedController(ToolContext context) {
        final Optional<AgentRuntimeId> callerRuntimeId = context.get(ToolContextKeys.AGENT_RUNTIME_ID);
        return ScopedSubagentTaskController.scopeOrPassThrough(taskController, callerRuntimeId);
    }

    /**
     * Reads a delta of the task's live progress log starting at {@code fromOffset}. Non-blocking: returns whatever the
     * output store currently holds.
     */
    private ToolResult readIncremental(String taskId, BackgroundTaskState state, long fromOffset, ToolInput input) {
        if (taskOutputStore == null) {
            return ToolResult.error("Incremental output streaming is not configured for this agent; "
                    + "call AgentOutput without from_offset to fetch the final result.");
        }
        final long from = Math.max(0, fromOffset);
        final int maxChars = clampDeltaMaxChars(input.getInteger("max_chars", DEFAULT_DELTA_MAX_CHARS));
        final OutputSlice slice = taskOutputStore.read(taskId, from, maxChars);

        final StringBuilder out = new StringBuilder();
        out.append("=== Background Task Output (incremental) ===").append(Constants.NEWLINE);
        out.append("Task ID: ").append(taskId).append(Constants.NEWLINE);
        out.append("Status: ").append(state).append(Constants.NEWLINE);
        out.append("Next offset: ").append(slice.getNextOffset())
                .append(slice.hasMore() ? " (more output available)" : " (caught up)").append(Constants.NEWLINE);
        if (slice.isTruncatedHead()) {
            out.append("[note: output before this offset was skipped]").append(Constants.NEWLINE);
        }
        out.append(Constants.NEWLINE);
        out.append(slice.getText().isEmpty() ? "[no new output at this offset yet]" : slice.getText());
        return ToolResult.success(out.toString());
    }

    private static int clampDeltaMaxChars(int requested) {
        return Math.max(1, Math.min(requested, MAX_DELTA_CHARS));
    }

    /**
     * Formats the stored task result for display, bounding the (potentially large) final answer so it does not
     * pollute the parent context. When a live-output store is present the truncation marker carries a pointer to
     * the full progress log.
     *
     * @param result
     *            The stored task result
     * @param taskId
     *            The task ID
     * @return A formatted string representation
     */
    private String formatAgentResult(TaskResult result, String taskId) {
        final StringBuilder output = new StringBuilder();

        output.append("=== Background Task Result ===").append(Constants.NEWLINE);
        output.append("Task ID: ").append(taskId).append(Constants.NEWLINE);
        output.append("Status: ").append(result.getStatus()).append(Constants.DOUBLE_NEWLINE);

        final String pointer = taskOutputStore != null
                ? TOOL_NAME + "(taskId=\"" + taskId + "\", from_offset=0) for the full progress log"
                : null;
        output.append("Result:").append(Constants.NEWLINE);
        output.append(SubagentResultFormatter.truncateTailKeep(result.getSummary(), pointer))
                .append(Constants.DOUBLE_NEWLINE);

        output.append("Execution Details:").append(Constants.NEWLINE);
        output.append("- Duration: ").append(result.getDurationMillis()).append(" ms").append(Constants.NEWLINE);
        output.append("- Iterations: ").append(result.getIterationCount()).append(Constants.NEWLINE);
        output.append("- Tokens: ").append(result.getTotalTokens()).append(Constants.NEWLINE);

        return output.toString();
    }
}
