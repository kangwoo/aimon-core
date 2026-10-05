package at.aimon.core.subagent;

import java.util.concurrent.CompletableFuture;

import at.aimon.core.agent.AgentExecutionRequest;
import at.aimon.core.agent.session.transcript.TranscriptBuffer;
import at.aimon.core.subagent.execution.SubagentExecutionResult;

/**
 * Manages subagent execution lifecycle.
 *
 * <p>
 * Provides methods for synchronous and asynchronous subagent execution. The collaborators a launch needs
 * (registries, default model, the spawning execution's environment, etc.) are grouped into
 * {@link SubagentLaunchContext}.
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
     * @param launchContext
     *            The launch context (must not be null)
     * @param agentExecutionRequest
     *            The agent execution request (must not be null)
     * @param transcriptBuffer
     *            The transcript buffer (must not be null)
     * @return The subagent execution result (never null)
     */
    SubagentExecutionResult execute(SubagentLaunchContext launchContext, AgentExecutionRequest agentExecutionRequest,
            TranscriptBuffer transcriptBuffer);

    /**
     * Executes a subagent with explicit task parameters.
     *
     * @param launchContext
     *            The launch context (must not be null)
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
    SubagentExecutionResult execute(SubagentLaunchContext launchContext, String taskId, String subagentName,
            String goal, String description);

    /**
     * Executes an inline, code-defined {@link Subagent} once in the foreground, without requiring it to be registered
     * in the launch context's {@link SubagentRegistry}.
     *
     * <p>
     * This is the single-subagent execution primitive that higher-level workflow builds on: it lets a caller run a
     * {@code Subagent.builder()...build()} instance in one call, with no prior registration and no caller-supplied task
     * id. A unique task id is generated internally for hook/attribution tracking, cancellation follows the
     * launch context's {@link SubagentLaunchContext#getCancellationSignal() signal}, and the result is returned
     * inline (no live output tailing). The launch context's {@link SubagentRegistry} is <em>not</em> consulted for this
     * path.
     *
     * <p>
     * All other launch-context forwarding (tool registry, hooks, principal, knowledge store/scope, tool-context
     * enrichers, model override, previous snapshot, LLM call metadata) is applied identically to the registry-based
     * {@link #execute(SubagentLaunchContext, String, String, String, String)} overload. If a
     * {@link at.aimon.core.subagent.behavior.SubagentBehavior} happens to be registered under the inline subagent's
     * name, it replaces the ReAct loop exactly as it would for a registered subagent.
     *
     * <p>
     * Like the other execution methods, this never throws for an execution failure — a failed run (including a thrown
     * executor error) is returned as an unsuccessful {@link SubagentExecutionResult}.
     *
     * @param launchContext
     *            The launch context (must not be null)
     * @param subagent
     *            The inline subagent definition to run (must not be null)
     * @param goal
     *            The goal for the subagent (must not be null)
     * @return The subagent execution result (never null)
     * @throws NullPointerException
     *             if launchContext, subagent or goal is null
     */
    SubagentExecutionResult execute(SubagentLaunchContext launchContext, Subagent subagent, String goal);

    /**
     * Executes a pre-resolved {@link Subagent} in the foreground with a caller-supplied task id and description.
     *
     * <p>
     * Same as {@link #execute(SubagentLaunchContext, String, String, String, String)} in every respect except
     * that the subagent is supplied rather than looked up, so the launch context's {@link SubagentRegistry} is
     * <em>not</em> consulted. It exists for a caller that must adjust the definition before it runs — narrowing its
     * allow-list to the caller's own, say — and still wants the task id and description the name-based method carries
     * into hooks and task records. Behaviour lookup is by name and so is unaffected.
     *
     * <p>
     * Deliberately <b>not</b> an overload of {@code execute}: a name and a definition are not interchangeable, and
     * overloading them reads as though they were — besides making a call with a {@code null} or a matcher in that
     * position ambiguous to the compiler.
     *
     * @param launchContext
     *            The launch context (must not be null)
     * @param taskId
     *            Caller-supplied task id for hook and attribution tracking (must not be null)
     * @param subagent
     *            The subagent definition to run (must not be null)
     * @param goal
     *            The goal handed to the subagent (must not be null)
     * @param description
     *            Short description for hooks and task records (must not be null)
     * @return The execution result
     */
    SubagentExecutionResult executeInline(SubagentLaunchContext launchContext, String taskId, Subagent subagent,
            String goal, String description);

    /**
     * Executes a subagent in the background.
     *
     * @param launchContext
     *            The launch context (must not be null)
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
    CompletableFuture<SubagentExecutionResult> executeInBackground(SubagentLaunchContext launchContext, String taskId,
            String subagentName, String goal, String description);

}
