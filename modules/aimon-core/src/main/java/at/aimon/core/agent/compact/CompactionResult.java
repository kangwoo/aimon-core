package at.aimon.core.agent.compact;

import java.util.Objects;
import java.util.Optional;

/**
 * Outcome of a {@link CompactionEngine#compact} invocation.
 *
 * <p>
 * Carries the generated summary text on success, or the underlying exception on failure. {@link CompactionMetadata} is
 * always present so callers can record metrics regardless of outcome.
 *
 * <p>
 * Immutable value object.
 */
public final class CompactionResult {

    private final boolean success;
    private final String summaryText;
    private final CompactionMetadata metadata;
    private final Exception error;

    private CompactionResult(boolean success, String summaryText, CompactionMetadata metadata, Exception error) {
        this.success = success;
        this.summaryText = summaryText;
        this.metadata = Objects.requireNonNull(metadata, "Metadata cannot be null");
        this.error = error;
    }

    /**
     * Creates a successful result.
     *
     * @param summaryText
     *            the summary produced by the LLM (must not be null)
     * @param metadata
     *            execution metadata (must not be null)
     */
    public static CompactionResult success(String summaryText, CompactionMetadata metadata) {
        Objects.requireNonNull(summaryText, "Summary text cannot be null");
        return new CompactionResult(true, summaryText, metadata, null);
    }

    /**
     * Creates a failure result.
     *
     * @param error
     *            the cause of failure (must not be null)
     * @param metadata
     *            execution metadata (must not be null)
     */
    public static CompactionResult failure(Exception error, CompactionMetadata metadata) {
        Objects.requireNonNull(error, "Error cannot be null");
        return new CompactionResult(false, null, metadata, error);
    }

    public boolean isSuccess() {
        return success;
    }

    public boolean isFailure() {
        return !success;
    }

    public Optional<String> getSummaryText() {
        return Optional.ofNullable(summaryText);
    }

    public CompactionMetadata getMetadata() {
        return metadata;
    }

    public Optional<Exception> getError() {
        return Optional.ofNullable(error);
    }

    @Override
    public String toString() {
        return "CompactionResult{success=" + success + ", metadata=" + metadata
                + (error != null ? ", error=" + error.getClass().getSimpleName() + ": " + error.getMessage() : "")
                + '}';
    }
}
