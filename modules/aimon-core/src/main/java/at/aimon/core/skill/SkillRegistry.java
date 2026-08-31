package at.aimon.core.skill;

import java.util.List;
import java.util.Optional;

import at.aimon.core.base.Reloadable;
import at.aimon.core.skill.exception.SkillException;

/**
 * Registry interface for managing skills.
 *
 * <p>
 * Provides high-level operations for skill discovery, loading, and reloading. Implementations typically use
 * SkillRepository and SkillParser internally.
 *
 * <p>
 * Thread-safety depends on implementation.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     SkillRegistry registry = new FileBasedSkillRegistry(repository, parser);
 *
 *     // Get a skill
 *     Optional<Skill> skill = registry.getSkill("alert-analysis");
 *
 *     // Get all skills
 *     List<Skill> allSkills = registry.getAllSkills();
 *
 *     // Reload skills
 *     registry.reloadAll();
 * }
 * </pre>
 */
public interface SkillRegistry extends Reloadable {

    /**
     * Gets a skill by name.
     *
     * <p>
     * Loads and parses the skill if not already cached.
     *
     * @param skillName
     *            The skill name (must not be null)
     * @return Optional containing the skill, empty if not found
     * @throws SkillException
     *             if loading or parsing fails
     */
    Optional<Skill> getSkill(String skillName);

    /**
     * Gets all available skills.
     *
     * <p>
     * Loads and parses all skills from the repository.
     *
     * @return List of all skills (never null, may be empty)
     * @throws SkillException
     *             if loading or parsing fails
     */
    List<Skill> getAllSkills();

    /**
     * Reloads a specific skill.
     *
     * <p>
     * Forces reloading from the repository, discarding any cached version.
     *
     * @param skillName
     *            The skill name (must not be null)
     * @throws SkillException
     *             if loading or parsing fails
     */
    void reloadSkill(String skillName);

}
