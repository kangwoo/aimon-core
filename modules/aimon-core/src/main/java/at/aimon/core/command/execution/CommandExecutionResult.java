package at.aimon.core.command.execution;

import java.util.Objects;
import java.util.Optional;

/**
 * Represents the result of executing a command.
 *
 * <p>
 * Contains the final response from the LLM, execution status, execution metadata, and any error information if the
 * execution failed.
 *
 * <p>
 * Immutable value object.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     CommandExecutionResult result = CommandExecutionResult
 *             .success("I've created a commit with the message 'Update README'");
 *
 *     if (result.isSuccess()) {
 *         System.out.println(result.getResponse());
 *         result.getMetadata()
 *                 .ifPresent(meta -> System.out.println("Duration: " + meta.getDuration().toMillis() + "ms"));
 *     }
 * }
 * </pre>
 */
public final class CommandExecutionResult {
    /**
     * Creates a successful execution result.
     *
     * @param response
     *            The LLM's response (must not be null)
     * @return A successful result
     * @throws NullPointerException
     *             if response is null
     */
    public static CommandExecutionResult success(String response) {
        return new CommandExecutionResult(true, response, null, null, false);
    }

    /**
     * Creates a successful execution result with metadata.
     *
     * @param response
     *            The LLM's response (must not be null)
     * @param metadata
     *            The execution metadata (must not be null)
     * @return A successful result with metadata
     * @throws NullPointerException
     *             if response or metadata is null
     */
    public static CommandExecutionResult success(String response, ExecutionMetadata metadata) {
        Objects.requireNonNull(metadata, "Metadata cannot be null");
        return new CommandExecutionResult(true, response, null, metadata, false);
    }

    /**
     * Creates a successful execution result whose response is a final answer the provider cut off at its output-token
     * limit. The partial text is kept — it ends in the truncation marker
     * ({@code TruncatedResponses.TRUNCATION_MARKER}) — and {@link #isTruncated()} says it is not whole, so the turn
     * that
     * ran the command ends {@code CompletionReason.TRUNCATED} instead of {@code COMPLETED}.
     *
     * @param response
     *            The partial response, marker included (must not be null)
     * @param metadata
     *            The execution metadata (nullable)
     * @return A successful, truncated result
     * @throws NullPointerException
     *             if response is null
     */
    public static CommandExecutionResult truncated(String response, ExecutionMetadata metadata) {
        return new CommandExecutionResult(true, response, null, metadata, true);
    }

    /**
     * Creates a failed execution result.
     *
     * @param error
     *            The error that occurred (must not be null)
     * @return A failed result
     * @throws NullPointerException
     *             if error is null
     */
    public static CommandExecutionResult failure(Throwable error) {
        Objects.requireNonNull(error, "Error cannot be null");
        final String errorMessage = "Command execution failed: " + error.getMessage();
        return new CommandExecutionResult(false, errorMessage, error, null, false);
    }

    /**
     * Creates a failed execution result with metadata.
     *
     * @param error
     *            The error that occurred (must not be null)
     * @param metadata
     *            The execution metadata (must not be null)
     * @return A failed result with metadata
     * @throws NullPointerException
     *             if error or metadata is null
     */
    public static CommandExecutionResult failure(Throwable error, ExecutionMetadata metadata) {
        Objects.requireNonNull(error, "Error cannot be null");
        Objects.requireNonNull(metadata, "Metadata cannot be null");
        final String errorMessage = "Command execution failed: " + error.getMessage();
        return new CommandExecutionResult(false, errorMessage, error, metadata, false);
    }

    /**
     * Creates a failed execution result with a custom message.
     *
     * @param message
     *            The error message (must not be null)
     * @param error
     *            The underlying error (must not be null)
     * @return A failed result
     * @throws NullPointerException
     *             if message or error is null
     */
    public static CommandExecutionResult failure(String message, Throwable error) {
        Objects.requireNonNull(message, "Message cannot be null");
        Objects.requireNonNull(error, "Error cannot be null");
        return new CommandExecutionResult(false, message, error, null, false);
    }

    /**
     * Creates a failed execution result with a custom message and metadata.
     *
     * @param message
     *            The error message (must not be null)
     * @param error
     *            The underlying error (must not be null)
     * @param metadata
     *            The execution metadata (must not be null)
     * @return A failed result with metadata
     * @throws NullPointerException
     *             if message, error, or metadata is null
     */
    public static CommandExecutionResult failure(String message, Throwable error, ExecutionMetadata metadata) {
        Objects.requireNonNull(message, "Message cannot be null");
        Objects.requireNonNull(error, "Error cannot be null");
        Objects.requireNonNull(metadata, "Metadata cannot be null");
        return new CommandExecutionResult(false, message, error, metadata, false);
    }

    private final boolean success;
    private final String response;
    private final Throwable error;
    private final ExecutionMetadata metadata;
    private final boolean truncated;

    /**
     * Creates a new CommandExecutionResult.
     *
     * @param success
     *            Whether the execution was successful
     * @param response
     *            The LLM's final response (must not be null)
     * @param error
     *            The error that occurred (null if success)
     * @param metadata
     *            The execution metadata (can be null)
     * @param truncated
     *            Whether the response is a final answer cut off at the output-token limit
     */
    private CommandExecutionResult(boolean success, String response, Throwable error, ExecutionMetadata metadata,
            boolean truncated) {
        this.success = success;
        this.response = Objects.requireNonNull(response, "Response cannot be null");
        this.error = error;
        this.metadata = metadata;
        this.truncated = truncated;
    }

    /**
     * Whether the response is a final answer the provider cut off at its output-token limit. Only a successful result
     * can be truncated, and only a command that runs a model produces one — a slash skill, inline or fork-mode. A
     * command that computes its own response is never truncated.
     *
     * @return true if the response is a final answer that is not whole
     */
    public boolean isTruncated() {
        return truncated;
    }

    /**
     * Checks if the execution was successful.
     *
     * @return true if successful, false otherwise
     */
    public boolean isSuccess() {
        return success;
    }

    /**
     * Checks if the execution failed.
     *
     * @return true if failed, false otherwise
     */
    public boolean isFailure() {
        return !success;
    }

    /**
     * Gets the response text.
     *
     * @return The response (never null)
     */
    public String getResponse() {
        return response;
    }

    /**
     * Gets the error if the execution failed.
     *
     * @return An Optional containing the error, or empty if successful
     */
    public Optional<Throwable> getError() {
        return Optional.ofNullable(error);
    }

    /**
     * Gets the execution metadata.
     *
     * @return An Optional containing the metadata, or empty if not available
     */
    public Optional<ExecutionMetadata> getMetadata() {
        return Optional.ofNullable(metadata);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final CommandExecutionResult that = (CommandExecutionResult) o;
        return success == that.success && truncated == that.truncated && response.equals(that.response)
                && Objects.equals(error, that.error) && Objects.equals(metadata, that.metadata);
    }

    @Override
    public int hashCode() {
        return Objects.hash(success, response, error, metadata, truncated);
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("CommandExecutionResult{");
        sb.append("success=").append(success);
        if (success) {
            sb.append(", response='").append(response).append('\'');
            if (truncated) {
                sb.append(", truncated=true");
            }
        } else {
            sb.append(", error=").append(error != null ? error.getClass().getSimpleName() : "unknown");
        }
        if (metadata != null) {
            sb.append(", metadata=").append(metadata);
        }
        sb.append('}');
        return sb.toString();
    }
}
