package at.aimon.core.agent.tool.generic;

import java.util.List;
import java.util.Objects;

/**
 * The outcome of binding one {@code ToolInput} to a tool's input record.
 *
 * <p>
 * Either the record was constructed or it was not, and in the second case this carries the sentences saying why.
 * Deliberately the same shape as {@code SchemaValidationResult}: the two layers catch mismatches at different moments —
 * the executor's gate before dispatch, this one inside the tool — and share one vocabulary of violation sentences so
 * that a model never has to learn that two differently-worded complaints are the same complaint.
 *
 * <p>
 * <b>The violation strings are model-facing.</b> They come from {@code ViolationMessages} and reach the model verbatim.
 * Do not reformat them at the call site.
 *
 * <p>
 * Immutable.
 *
 * @param <I>
 *            the tool's input record type
 * @see ToolInputBinder
 * @see GenericTool
 */
public final class BindResult<I> {

    /**
     * Creates a result for input that bound cleanly.
     *
     * @param value
     *            the constructed input record (must not be null)
     * @param <I>
     *            the input record type
     * @return a bound result (never null)
     */
    public static <I> BindResult<I> bound(I value) {
        Objects.requireNonNull(value, "Bound value cannot be null");
        return new BindResult<>(value, List.of());
    }

    /**
     * Creates a result for input that could not be bound.
     *
     * @param violations
     *            the violation sentences, in the order they should be shown to the model (must not be null or empty)
     * @param <I>
     *            the input record type
     * @return an unbound result carrying those sentences (never null)
     * @throws IllegalArgumentException
     *             if violations is empty — a failure with nothing to say would leave the tool no message to return
     */
    public static <I> BindResult<I> violations(List<String> violations) {
        Objects.requireNonNull(violations, "Violations cannot be null");
        if (violations.isEmpty()) {
            throw new IllegalArgumentException(
                    "A failed binding must carry at least one violation; use bound() " + "instead");
        }
        return new BindResult<>(null, List.copyOf(violations));
    }

    private final I value;
    private final List<String> violations;

    private BindResult(I value, List<String> violations) {
        this.value = value;
        this.violations = violations;
    }

    /**
     * Reports whether the input bound to the record.
     *
     * @return true if there are no violations
     */
    public boolean isBound() {
        return violations.isEmpty();
    }

    /**
     * Gets the constructed input record.
     *
     * @return the record (never null when {@link #isBound()} is true)
     * @throws IllegalStateException
     *             if the binding failed — the caller should have checked {@link #isBound()} and returned the
     *             violations to the model
     */
    public I getValue() {
        if (value == null) {
            throw new IllegalStateException("Binding failed; there is no value. Violations: " + violations);
        }
        return value;
    }

    /**
     * Gets the violation sentences.
     *
     * @return an immutable list, empty when {@link #isBound()} is true (never null)
     */
    public List<String> getViolations() {
        return violations;
    }

    @Override
    public String toString() {
        return isBound() ? "BindResult{bound=" + value + '}' : "BindResult{violations=" + violations + '}';
    }
}
