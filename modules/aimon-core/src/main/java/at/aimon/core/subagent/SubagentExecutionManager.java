package at.aimon.core.subagent;

import java.util.concurrent.CompletableFuture;

import at.aimon.core.agent.AgentExecutionRequest;
import at.aimon.core.agent.session.transcript.TranscriptBuffer;
import at.aimon.core.subagent.execution.SubagentExecutionResult;

/**
 * Manages subagent execution lifecycle.
 *
 * <p>
 * Provides methods for synchronous and asynchronous subagent execution. The common execution environment parameters
 * (registries, environment, model, etc.) are grouped into {@link SubagentExecutionEnvironment}.
 *
 * <p>
 * Also acts as the {@link SubagentTaskController control plane} for the background tasks it spawns — listing, status,
 * and cooperative stopping — so the {@code TaskList} / {@code TaskStop} tools govern tasks through the same component
 * that created them, while depending only on the narrow controller interface.
 */
public interface SubagentExecutionManager extends SubagentTaskController {

    /**
     * Executes a subagent from an agent execution request.
     *
     * @param env
     *            The execution environment (must not be null)
     * @param agentExecutionRequest
     *            The agent execution request (must not be null)
     * @param transcriptBuffer
     *            The transcript buffer (must not be null)
     * @return The subagent execution result (never null)
     */
    SubagentExecutionResult execute(SubagentExecutionEnvironment env, AgentExecutionRequest agentExecutionRequest,
            TranscriptBuffer transcriptBuffer);

    /**
     * Executes a subagent with explicit task parameters.
     *
     * @param env
     *            The execution environment (must not be null)
     * @param taskId
     *            The task ID for tracking (must not be null)
     * @param subagentName
     *            The subagent name (must not be null)
     * @param goal
     *            The goal description (must not be null)
     * @param description
     *            The task description
     * @return The subagent execution result (never null)
     */
    SubagentExecutionResult execute(SubagentExecutionEnvironment env, String taskId, String subagentName, String goal,
            String description);

    /**
     * Executes an inline, code-defined {@link Subagent} once in the foreground, without requiring it to be registered
     * in the environment's {@link SubagentRegistry}.
     *
     * <p>
     * This is the single-subagent execution primitive that higher-level workflow builds on: it lets a caller run a
     * {@code Subagent.builder()...build()} instance in one call, with no prior registration and no caller-supplied task
     * id. A unique task id is generated internally for hook/attribution tracking, cancellation follows the
     * environment's {@link SubagentExecutionEnvironment#getCancellationSignal() signal}, and the result is returned
     * inline (no live output tailing). The environment's {@link SubagentRegistry} is <em>not</em> consulted for this
     * path.
     *
     * <p>
     * All other environment forwarding (tool registry, hooks, principal, knowledge store/scope, tool-context enrichers,
     * model override, previous snapshot, LLM call metadata) is applied identically to the registry-based
     * {@link #execute(SubagentExecutionEnvironment, String, String, String, String)} overload. If a
     * {@link at.aimon.core.subagent.behavior.SubagentBehavior} happens to be registered under the inline subagent's
     * name, it replaces the ReAct loop exactly as it would for a registered subagent.
     *
     * <p>
     * Like the other execution methods, this never throws for an execution failure — a failed run (including a thrown
     * executor error) is returned as an unsuccessful {@link SubagentExecutionResult}.
     *
     * @param env
     *            The execution environment (must not be null)
     * @param subagent
     *            The inline subagent definition to run (must not be null)
     * @param goal
     *            The goal for the subagent (must not be null)
     * @return The subagent execution result (never null)
     * @throws NullPointerException
     *             if env, subagent or goal is null
     */
    SubagentExecutionResult execute(SubagentExecutionEnvironment env, Subagent subagent, String goal);

    /**
     * Executes a subagent in the background.
     *
     * @param env
     *            The execution environment (must not be null)
     * @param taskId
     *            The task ID for tracking (must not be null)
     * @param subagentName
     *            The subagent name (must not be null)
     * @param goal
     *            The goal description (must not be null)
     * @param description
     *            The task description
     * @return A future containing the subagent execution result
     */
    CompletableFuture<SubagentExecutionResult> executeInBackground(SubagentExecutionEnvironment env, String taskId,
            String subagentName, String goal, String description);

}
