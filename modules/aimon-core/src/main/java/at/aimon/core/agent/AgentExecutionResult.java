package at.aimon.core.agent;

import java.util.List;

import at.aimon.core.agent.artifact.FileArtifact;
import at.aimon.core.agent.budget.CompletionReason;

/**
 * Represents the result of an agent execution.
 *
 * <p>
 * An execution result contains the outcome of processing an agent request, including either a successful final answer
 * or an error message if the execution failed.
 *
 * <p>
 * This interface follows the Result pattern, providing a structured way to handle both success and failure cases
 * without throwing exceptions. This allows callers to handle errors gracefully and make decisions based on the
 * execution outcome.
 *
 * <h2>Design Principles</h2>
 * <ul>
 * <li><b>Single Responsibility:</b> Only responsible for carrying result data, not processing or displaying it
 * <li><b>Fail-Safe:</b> Provides a safe way to handle both success and error cases
 * <li><b>Value Object:</b> Represents an execution result without identity
 * </ul>
 *
 * <h2>Usage Patterns</h2>
 *
 * <h3>Success Case</h3>
 *
 * <pre>
 * {
 *     &#64;code
 *     AgentExecutionResult result = executor.execute(context, request);
 *
 *     if (result.isSuccess()) {
 *         String answer = result.getFinalAnswer();
 *         System.out.println("Agent response: " + answer);
 *     }
 * }
 * </pre>
 *
 * <h3>Error Handling</h3>
 *
 * <pre>
 * {
 *     &#64;code
 *     AgentExecutionResult result = executor.execute(context, request);
 *
 *     if (!result.isSuccess()) {
 *         String error = result.getErrorMessage();
 *         System.err.println("Execution failed: " + error);
 *         // Handle error appropriately
 *     }
 * }
 * </pre>
 *
 * <h3>Complete Example</h3>
 *
 * <pre>
 * {
 *     &#64;code
 *     // Execute agent
 *     AgentExecutor executor = new OrcaAgentExecutor(llmClient);
 *     AgentExecutionResult result = executor.execute(context, request);
 *
 *     // Handle result
 *     if (result.isSuccess()) {
 *         processAnswer(result.getFinalAnswer());
 *     } else {
 *         handleError(result.getErrorMessage());
 *     }
 * }
 * </pre>
 *
 * <h2>Implementation Notes</h2>
 * <ul>
 * <li>Implementations should ensure that exactly one of {@code getFinalAnswer()} or {@code getErrorMessage()} returns a
 * non-null value
 * <li>The {@code isSuccess()} method should return true if and only if {@code getFinalAnswer()} returns a non-null
 * value
 * <li>Implementations should be immutable to prevent accidental state changes
 * </ul>
 *
 * @see AgentExecutor
 * @see AgentRuntime
 * @see AgentExecutionRequest
 */
public interface AgentExecutionResult {
    /**
     * Checks if the execution was successful.
     *
     * @return true if the execution succeeded and {@link #getFinalAnswer()} returns a non-null value, false otherwise
     */
    boolean isSuccess();

    /**
     * Gets the final answer from the agent.
     *
     * <p>
     * This method returns a non-null value if and only if {@link #isSuccess()} returns true.
     *
     * @return The final answer (non-null if successful, null otherwise)
     */
    String getFinalAnswer();

    /**
     * Gets the error message if the execution failed.
     *
     * <p>
     * This method returns a non-null value if and only if {@link #isSuccess()} returns false.
     *
     * @return The error message (non-null if failed, null otherwise)
     */
    String getErrorMessage();

    /**
     * Gets the file artifacts generated during execution.
     *
     * <p>
     * Artifacts represent files that were created by tools with {@code artifact=true} and are intended for user
     * download. Both successful and failed executions may contain artifacts if files were generated before the failure
     * occurred.
     *
     * @return An unmodifiable list of file artifacts (never null, may be empty)
     */
    default List<FileArtifact> getArtifacts() {
        return List.of();
    }

    /**
     * Gets the structured reason an execution ended.
     *
     * <p>
     * The default derives the reason from {@link #isSuccess()} so that implementations predating this method keep
     * returning a sensible value: {@link CompletionReason#COMPLETED} on success and {@link CompletionReason#ERROR}
     * otherwise. Implementations that can distinguish budget stops (max iterations, token budget, wall-clock) or
     * external aborts should override this method to return the specific reason.
     *
     * @return the completion reason (never null)
     */
    default CompletionReason getCompletionReason() {
        return isSuccess() ? CompletionReason.COMPLETED : CompletionReason.ERROR;
    }

    /**
     * Indicates whether the terminal assistant text was already delivered to the caller incrementally as streaming
     * text-delta events (see {@link at.aimon.core.agent.stream.AssistantTextDelta}) during the execution.
     *
     * <p>
     * Renderers use this flag to avoid re-printing {@link #getFinalAnswer()} after the streaming deltas have already
     * displayed the same content on screen. The default is {@code false} so existing non-streaming implementations keep
     * working without changes.
     *
     * @return {@code true} if the terminal assistant text was streamed via delta events; {@code false} otherwise
     */
    default boolean wasStreamed() {
        return false;
    }
}
