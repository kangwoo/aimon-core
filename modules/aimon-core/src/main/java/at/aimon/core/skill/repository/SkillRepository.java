package at.aimon.core.skill.repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import at.aimon.core.skill.exception.SkillRepositoryException;

/**
 * Repository interface for accessing skill files.
 *
 * <p>
 * Abstracts the underlying storage mechanism (filesystem, database, etc.) for skill files. Implementations should
 * handle I/O errors and convert them to SkillRepositoryException.
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
 *     SkillRepository repository = new VfsSkillRepository(fileSystem, "skills");
 *
 *     // Find skill by name
 *     Optional<String> content = repository.findByName("alert-analysis");
 *
 *     // List all skills
 *     List<String> skillNames = repository.findAllNames();
 *
 *     // Check existence
 *     boolean exists = repository.exists("alert-analysis");
 * }
 * </pre>
 */
public interface SkillRepository {

    /**
     * Finds a skill by name.
     *
     * <p>
     * Reads the SKILL.md file content for the specified skill.
     *
     * @param skillName
     *            The skill name (must not be null)
     * @return Optional containing the skill file content, empty if not found
     * @throws SkillRepositoryException
     *             if an I/O error occurs
     */
    Optional<String> findByName(String skillName);

    /**
     * Finds all skill names.
     *
     * <p>
     * Lists all available skills by scanning the skills directory.
     *
     * @return List of skill names (never null, may be empty)
     * @throws SkillRepositoryException
     *             if an I/O error occurs
     */
    List<String> findAllNames();

    /**
     * Checks if a skill exists.
     *
     * @param skillName
     *            The skill name (must not be null)
     * @return true if the skill exists, false otherwise
     * @throws SkillRepositoryException
     *             if an I/O error occurs
     */
    boolean exists(String skillName);

    /**
     * Finds all root files for a skill.
     *
     * <p>
     * Scans the skill's root directory for files (excluding SKILL.md and subdirectories).
     *
     * @param skillName
     *            The skill name (must not be null)
     * @return Map of root file filename to VFS full path (never null, may be empty)
     * @throws SkillRepositoryException
     *             if an I/O error occurs
     */
    Map<String, String> findRootFiles(String skillName);

    /**
     * Finds all script files for a skill.
     *
     * <p>
     * Scans the scripts/ directory for the specified skill.
     *
     * @param skillName
     *            The skill name (must not be null)
     * @return Map of script filename to VFS full path (never null, may be empty)
     * @throws SkillRepositoryException
     *             if an I/O error occurs
     */
    Map<String, String> findScripts(String skillName);

    /**
     * Finds all reference files for a skill.
     *
     * <p>
     * Scans the references/ directory for the specified skill.
     *
     * @param skillName
     *            The skill name (must not be null)
     * @return Map of reference filename to VFS full path (never null, may be empty)
     * @throws SkillRepositoryException
     *             if an I/O error occurs
     */
    Map<String, String> findReferences(String skillName);

    /**
     * Finds all asset files for a skill.
     *
     * <p>
     * Scans the assets/ directory for the specified skill.
     *
     * @param skillName
     *            The skill name (must not be null)
     * @return Map of asset filename to VFS full path (never null, may be empty)
     * @throws SkillRepositoryException
     *             if an I/O error occurs
     */
    Map<String, String> findAssets(String skillName);

    /**
     * Resolves the base directory of a skill.
     *
     * <p>
     * Directory-backed repositories return the storage path of the skill's directory so that callers can anchor the
     * skill's own relative file references (for example via the {@code ${AIMON_SKILL_DIR}} render variable). The
     * default implementation returns empty for repositories that have no addressable directory (such as classpath
     * repositories that have not been materialized to a filesystem).
     *
     * @param skillName
     *            The skill name (must not be null)
     * @return The skill's base directory, or empty if not applicable
     * @throws SkillRepositoryException
     *             if an I/O error occurs
     */
    default Optional<String> resolveBaseDir(String skillName) {
        return Optional.empty();
    }

    /**
     * Finds every bundled file for a skill.
     *
     * <p>
     * Recursively scans the skill's directory for all files (excluding {@code SKILL.md}), keyed by the file's path
     * relative to the skill directory. Unlike {@link #findScripts(String)}, {@link #findReferences(String)}, and
     * {@link #findAssets(String)}, this also covers files in arbitrary sub-directories such as {@code templates/}. The
     * default implementation returns an empty map for repositories that cannot enumerate a directory tree.
     *
     * @param skillName
     *            The skill name (must not be null)
     * @return Map of relative path to VFS full path (never null, may be empty)
     * @throws SkillRepositoryException
     *             if an I/O error occurs
     */
    default Map<String, String> findAllFiles(String skillName) {
        return Map.of();
    }
}
