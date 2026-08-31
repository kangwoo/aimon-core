package at.aimon.core.subagent.repository;

import java.util.List;
import java.util.Optional;

import at.aimon.core.subagent.exception.SubagentRepositoryException;

/**
 * Repository interface for subagent persistence.
 *
 * <p>
 * Provides abstraction for subagent storage operations. Implementations can use different storage backends (filesystem,
 * database, remote). Implementations should handle I/O errors and convert them to {@link SubagentRepositoryException}.
 *
 * <p>
 * Follows Repository pattern for clean separation of concerns:
 *
 * <ul>
 * <li>Handles all storage-level operations
 * <li>Returns raw content (unparsed)
 * <li>No caching responsibility
 * </ul>
 *
 * <p>
 * Thread-safe implementations are recommended for concurrent access.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     SubagentRepository repository = new VfsSubagentRepository(fileSystem, "agents");
 *
 *     Optional<String> content = repository.findByName("code-reviewer");
 *     List<String> allNames = repository.findAllNames();
 *     boolean exists = repository.exists("code-reviewer");
 * }
 * </pre>
 */
public interface SubagentRepository {
    /**
     * Finds a subagent by name.
     *
     * @param subagentName
     *            The subagent name (must not be null)
     * @return Optional containing raw content if found, empty otherwise
     * @throws NullPointerException
     *             if subagentName is null
     * @throws SubagentRepositoryException
     *             if an I/O error occurs
     */
    Optional<String> findByName(String subagentName);

    /**
     * Lists all available subagent names.
     *
     * @return A list of subagent names (never null, may be empty)
     * @throws SubagentRepositoryException
     *             if an I/O error occurs
     */
    List<String> findAllNames();

    /**
     * Checks if a subagent exists.
     *
     * @param subagentName
     *            The subagent name (must not be null)
     * @return true if exists, false otherwise
     * @throws NullPointerException
     *             if subagentName is null
     */
    boolean exists(String subagentName);
}
