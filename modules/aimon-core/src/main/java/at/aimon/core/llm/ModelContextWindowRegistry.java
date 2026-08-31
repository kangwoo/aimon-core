package at.aimon.core.llm;

/**
 * Resolves a model name (e.g. {@code "gpt-4o"}, {@code "claude-3-7-sonnet-20250219"}) to its
 * {@link ModelContextLimits}.
 *
 * <p>
 * Implementations must be thread-safe. Callers must always receive a non-null result; unknown models fall back to a
 * registry-defined default.
 */
public interface ModelContextWindowRegistry {

    /**
     * Resolves the {@link ModelContextLimits} for the given model name.
     *
     * @param modelName
     *            the model name; may be {@code null} or empty (returns the default)
     * @return the resolved limits (never null)
     */
    ModelContextLimits resolve(String modelName);
}
