package at.aimon.core.skill.validation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import at.aimon.core.skill.Skill;
import at.aimon.core.skill.SkillMetadata;

/**
 * Validator for skill definitions.
 *
 * <p>
 * Validates skill structure, metadata, and security constraints. Ensures skills meet quality and safety standards
 * before activation.
 *
 * <p>
 * Validates against the Agent Skills standard specification:
 *
 * <ul>
 * <li>Name: 1-64 characters, lowercase/numbers/hyphens, no start/end hyphen, no consecutive hyphens
 * <li>Description: 1-1024 characters, non-empty
 * <li>Compatibility: Max 500 characters (if specified)
 * <li>SKILL.md: Should be under 500 lines (recommended)
 * </ul>
 *
 * <p>
 * Immutable and thread-safe.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     SkillValidator validator = new SkillValidator();
 *     ValidationResult result = validator.validate(skill);
 *
 *     if (!result.isValid()) {
 *         System.err.println("Validation errors: " + result.getErrors());
 *     }
 * }
 * </pre>
 */
public class SkillValidator {

    /**
     * Validates a skill.
     *
     * @param skill
     *            The skill to validate (must not be null)
     * @return Validation result with any errors found (never null)
     * @throws NullPointerException
     *             if skill is null
     */
    public ValidationResult validate(Skill skill) {
        Objects.requireNonNull(skill, "Skill cannot be null");

        final List<String> errors = new ArrayList<>();

        validateMetadata(skill.getMetadata(), errors);
        validateContent(skill, errors);

        return new ValidationResult(errors);
    }

    private void validateMetadata(SkillMetadata metadata, List<String> errors) {
        // Name validation (Agent Skills Standard)
        final String name = metadata.getName();
        if (name == null || name.length() < 1 || name.length() > 64) {
            errors.add("name must be 1-64 characters");
        }
        if (name != null && !name.matches("^[a-z0-9]+(-[a-z0-9]+)*$")) {
            errors.add("name must be lowercase letters, numbers, hyphens only "
                    + "(cannot start/end with hyphen, no consecutive hyphens)");
        }

        // Description validation (Agent Skills Standard)
        final String description = metadata.getDescription();
        if (description == null || description.length() < 1 || description.length() > 1024) {
            errors.add("description must be 1-1024 characters");
        }

        // Compatibility validation (Agent Skills Standard)
        final String compatibility = metadata.getCompatibility();
        if (compatibility != null && compatibility.length() > 500) {
            errors.add("compatibility must be max 500 characters");
        }
    }

    private void validateContent(Skill skill, List<String> errors) {
        final String instructions = skill.getContent().getInstructions();

        if (instructions == null || instructions.isBlank()) {
            errors.add("SKILL.md content cannot be empty");
        }

        // Agent Skills Standard: SKILL.md should be under 500 lines (recommended)
        if (instructions != null) {
            final long lineCount = instructions.lines().count();
            if (lineCount > 500) {
                errors.add("SKILL.md exceeds 500 lines (recommended limit): " + lineCount + " lines");
            }
        }
    }
}
