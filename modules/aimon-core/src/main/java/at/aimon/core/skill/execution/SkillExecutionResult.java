package at.aimon.core.skill.execution;

import java.util.Objects;
import java.util.Optional;

/**
 * Result of a {@link SkillExecutor#execute(SkillExecutionContext, SkillExecutionRequest)} call.
 *
 * <p>
 * Mirrors {@code CommandExecutionResult} but kept in the skill package to avoid a skill→command dependency. Introduced
 * in SK-08-C.
 *
 * <p>
 * Immutable value object.
 */
public final class SkillExecutionResult {

    /**
     * Creates a successful result.
     *
     * @param response
     *            The final assistant text (must not be null)
     * @return A successful result
     */
    public static SkillExecutionResult success(String response) {
        return new SkillExecutionResult(true, response, null, null);
    }

    /**
     * Creates a successful result with metadata.
     *
     * @param response
     *            The final assistant text (must not be null)
     * @param metadata
     *            The execution metadata (must not be null)
     * @return A successful result
     */
    public static SkillExecutionResult success(String response, SkillExecutionMetadata metadata) {
        Objects.requireNonNull(metadata, "Metadata cannot be null");
        return new SkillExecutionResult(true, response, null, metadata);
    }

    /**
     * Creates a failed result wrapping a {@link Throwable}.
     *
     * @param error
     *            The underlying error (must not be null)
     * @return A failed result
     */
    public static SkillExecutionResult failure(Throwable error) {
        Objects.requireNonNull(error, "Error cannot be null");
        return new SkillExecutionResult(false, "Skill execution failed: " + error.getMessage(), error, null);
    }

    /**
     * Creates a failed result wrapping a {@link Throwable} with metadata.
     *
     * @param error
     *            The underlying error (must not be null)
     * @param metadata
     *            The execution metadata (must not be null)
     * @return A failed result
     */
    public static SkillExecutionResult failure(Throwable error, SkillExecutionMetadata metadata) {
        Objects.requireNonNull(error, "Error cannot be null");
        Objects.requireNonNull(metadata, "Metadata cannot be null");
        return new SkillExecutionResult(false, "Skill execution failed: " + error.getMessage(), error, metadata);
    }

    /**
     * Creates a failed result with a custom message.
     *
     * @param message
     *            The error message (must not be null)
     * @param error
     *            The underlying error (must not be null)
     * @return A failed result
     */
    public static SkillExecutionResult failure(String message, Throwable error) {
        Objects.requireNonNull(message, "Message cannot be null");
        Objects.requireNonNull(error, "Error cannot be null");
        return new SkillExecutionResult(false, message, error, null);
    }

    /**
     * Creates a failed result with a custom message and metadata.
     *
     * @param message
     *            The error message (must not be null)
     * @param error
     *            The underlying error (must not be null)
     * @param metadata
     *            The execution metadata (must not be null)
     * @return A failed result
     */
    public static SkillExecutionResult failure(String message, Throwable error, SkillExecutionMetadata metadata) {
        Objects.requireNonNull(message, "Message cannot be null");
        Objects.requireNonNull(error, "Error cannot be null");
        Objects.requireNonNull(metadata, "Metadata cannot be null");
        return new SkillExecutionResult(false, message, error, metadata);
    }

    private final boolean success;
    private final String response;
    private final Throwable error;
    private final SkillExecutionMetadata metadata;

    private SkillExecutionResult(boolean success, String response, Throwable error, SkillExecutionMetadata metadata) {
        this.success = success;
        this.response = Objects.requireNonNull(response, "Response cannot be null");
        this.error = error;
        this.metadata = metadata;
    }

    /**
     * @return {@code true} if the execution succeeded
     */
    public boolean isSuccess() {
        return success;
    }

    /**
     * @return {@code true} if the execution failed
     */
    public boolean isFailure() {
        return !success;
    }

    /**
     * Gets the assistant response or error message.
     *
     * @return The response (never null)
     */
    public String getResponse() {
        return response;
    }

    /**
     * Gets the failure cause if any.
     *
     * @return Optional throwable (empty when successful)
     */
    public Optional<Throwable> getError() {
        return Optional.ofNullable(error);
    }

    /**
     * Gets the execution metadata if present.
     *
     * @return Optional metadata
     */
    public Optional<SkillExecutionMetadata> getMetadata() {
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
        final SkillExecutionResult that = (SkillExecutionResult) o;
        return success == that.success && response.equals(that.response) && Objects.equals(error, that.error)
                && Objects.equals(metadata, that.metadata);
    }

    @Override
    public int hashCode() {
        return Objects.hash(success, response, error, metadata);
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("SkillExecutionResult{");
        sb.append("success=").append(success);
        if (success) {
            sb.append(", response='").append(response).append('\'');
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
