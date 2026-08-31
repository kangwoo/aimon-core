package at.aimon.core.agent;

import java.util.Optional;

import at.aimon.core.agent.budget.ExecutionBudget;
import at.aimon.core.agent.input.UserInput;
import at.aimon.core.base.Principal;

/**
 * Represents a request to execute an agent.
 *
 * <p>
 * An execution request encapsulates all the information needed to process a user's request, including the user's input
 * and metadata about the user making the request.
 *
 * <p>
 * This interface follows the Command pattern, encapsulating a request as an object. This allows for parameterizing
 * clients with different requests, queuing requests, and logging the requests.
 *
 * <h2>Design Principles</h2>
 * <ul>
 * <li><b>Single Responsibility:</b> Only responsible for carrying request data, not processing it
 * <li><b>Immutability:</b> Implementations should be immutable to prevent accidental modifications
 * <li><b>Value Object:</b> Represents a request without identity, focused on the attributes
 * </ul>
 *
 * <h2>Example Usage</h2>
 *
 * <pre>
 * {
 *     &#64;code
 *     // Create user input
 *     UserInput input = TextInput.of("List all files in the current directory");
 *
 *     // Create principal
 *     Principal principal = Principal.user("user123", "john.doe");
 *
 *     // Create execution request
 *     AgentExecutionRequest request = new MyExecutionRequest(input, principal);
 *
 *     // Pass to executor
 *     AgentExecutor executor = new OrcaAgentExecutor(llmClient);
 *     AgentExecutionResult result = executor.execute(context, request);
 * }
 * </pre>
 *
 * @see AgentExecutor
 * @see AgentRuntime
 * @see AgentExecutionResult
 * @see UserInput
 * @see Principal
 */
public interface AgentExecutionRequest {

    /**
     * Gets the user input for this request.
     *
     * @return The user input (never null)
     */
    UserInput getUserInput();

    /**
     * Gets the principal (caller identity) for this request.
     *
     * @return the principal, or {@link Optional#empty()} if not set
     */
    Optional<Principal> getPrincipal();

    /**
     * Gets the optional execution budget that applies to this request.
     *
     * <p>
     * A present budget declares per-turn caps (iterations, tokens, wall-clock) that the executor enforces at iteration
     * boundaries. An empty {@link Optional} means no caller-supplied budget is configured — executors should treat this
     * as unbounded, preserving legacy behavior.
     *
     * <p>
     * The default implementation returns {@link Optional#empty()} so existing {@code AgentExecutionRequest}
     * implementations remain source-compatible.
     *
     * @return the budget, or {@link Optional#empty()} if none is configured
     */
    default Optional<ExecutionBudget> getBudget() {
        return Optional.empty();
    }
}
