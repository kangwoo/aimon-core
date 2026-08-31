package at.aimon.core.agent.tool.schema;

import java.util.List;
import java.util.Objects;

/**
 * The outcome of checking one {@code ToolInput} against one tool's declared input schema.
 *
 * <p>
 * Immutable value object. There is no builder because there is nothing to build up incrementally — a result is either
 * clean or it carries the sentences that describe why it is not, and both are known at the moment it is created.
 *
 * <p>
 * <b>The violation strings are model-facing.</b> They are produced by {@link ViolationMessages} and end up verbatim in
 * the {@code ToolResult.error(...)} the model reads, so they are written as instructions to the model rather than as
 * diagnostics for a developer. Do not reformat them at the call site.
 *
 * <p>
 * {@code BindResult} (the {@code GenericTool} binding outcome) has the same shape on purpose — the two layers share one
 * vocabulary of violation sentences so that a model sees the same wording whether the mismatch was caught by the
 * executor's gate or by a tool's own parameter binding.
 *
 * @see ToolInputSchemaValidator
 * @see ViolationMessages
 */
public final class SchemaValidationResult {

    private static final SchemaValidationResult OK = new SchemaValidationResult(List.of());

    /**
     * Returns the result for input that satisfied the schema.
     *
     * @return a valid result with no violations (never null)
     */
    public static SchemaValidationResult ok() {
        return OK;
    }

    /**
     * Creates a result for input that violated the schema.
     *
     * @param violations
     *            the violation sentences, in the order they should be shown to the model (must not be null or empty)
     * @return an invalid result carrying those sentences (never null)
     * @throws NullPointerException
     *             if violations is null
     * @throws IllegalArgumentException
     *             if violations is empty — an invalid result with nothing to say would make {@link #isValid()}
     *             disagree with {@link #getViolations()}; use {@link #ok()} instead
     */
    public static SchemaValidationResult violations(List<String> violations) {
        Objects.requireNonNull(violations, "Violations cannot be null");
        if (violations.isEmpty()) {
            throw new IllegalArgumentException("An invalid result must carry at least one violation; use ok() instead");
        }
        return new SchemaValidationResult(List.copyOf(violations));
    }

    private final List<String> violations;

    private SchemaValidationResult(List<String> violations) {
        this.violations = violations;
    }

    /**
     * Reports whether the input satisfied the schema.
     *
     * @return true if there are no violations
     */
    public boolean isValid() {
        return violations.isEmpty();
    }

    /**
     * Gets the violation sentences.
     *
     * @return an immutable list, empty when {@link #isValid()} is true (never null)
     */
    public List<String> getViolations() {
        return violations;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        return violations.equals(((SchemaValidationResult) o).violations);
    }

    @Override
    public int hashCode() {
        return violations.hashCode();
    }

    @Override
    public String toString() {
        return isValid() ? "SchemaValidationResult{valid}" : "SchemaValidationResult{violations=" + violations + '}';
    }
}
