package at.aimon.core.skill.validation;

import java.util.List;
import java.util.Objects;

/**
 * Result of skill validation.
 *
 * <p>
 * Contains a list of validation errors, if any.
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
 *     ValidationResult result = validator.validate(skill);
 *
 *     if (result.isValid()) {
 *         System.out.println("Skill is valid");
 *     } else {
 *         System.err.println("Validation errors:");
 *         result.getErrors().forEach(System.err::println);
 *     }
 * }
 * </pre>
 */
public final class ValidationResult {

    private final List<String> errors;

    /**
     * Creates a new ValidationResult.
     *
     * @param errors
     *            List of validation errors (must not be null)
     * @throws NullPointerException
     *             if errors is null
     */
    public ValidationResult(List<String> errors) {
        this.errors = List.copyOf(Objects.requireNonNull(errors, "Errors cannot be null"));
    }

    /**
     * Checks if validation passed.
     *
     * @return true if no errors, false otherwise
     */
    public boolean isValid() {
        return errors.isEmpty();
    }

    /**
     * Gets validation errors.
     *
     * @return Unmodifiable list of errors (never null, empty if valid)
     */
    public List<String> getErrors() {
        return errors;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final ValidationResult that = (ValidationResult) o;
        return Objects.equals(errors, that.errors);
    }

    @Override
    public int hashCode() {
        return Objects.hash(errors);
    }

    @Override
    public String toString() {
        if (isValid()) {
            return "ValidationResult{valid}";
        }
        return "ValidationResult{errors=" + errors + '}';
    }
}
