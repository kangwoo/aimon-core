package at.aimon.core.subagent;

import java.util.List;
import java.util.Optional;

import at.aimon.core.base.Reloadable;

/**
 * Registry interface for managing subagents.
 *
 * <p>
 * Provides abstraction for subagent storage and retrieval, supporting:
 *
 * <ul>
 * <li>Subagent lookup by name
 * <li>Listing all available subagents
 * <li>Hot-reloading of subagent definitions
 * </ul>
 *
 * <p>
 * Implementations may load subagents from different sources:
 *
 * <ul>
 * <li>File-based (agents/*.md files)
 * <li>Remote registry
 * <li>In-memory cache
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
 *     SubagentRegistry registry = new DefaultSubagentRegistry(repository, parser);
 *
 *     Optional<Subagent> codeReviewer = registry.getSubagent("code-reviewer");
 *     List<Subagent> all = registry.getAllSubagents();
 *     registry.reloadSubagent("code-reviewer");
 * }
 * </pre>
 */
public interface SubagentRegistry extends Reloadable {
    /**
     * Gets a subagent by name.
     *
     * @param subagentName
     *            The subagent name (must not be null)
     * @return An Optional containing the subagent, or empty if not found
     * @throws NullPointerException
     *             if subagentName is null
     */
    Optional<Subagent> getSubagent(String subagentName);

    /**
     * Gets all available subagents.
     *
     * @return A list of all subagents (never null, may be empty)
     */
    List<Subagent> getAllSubagents();

    /**
     * Reloads a specific subagent definition.
     *
     * <p>
     * If the subagent no longer exists, it is removed from the registry.
     *
     * @param subagentName
     *            The subagent name to reload (must not be null)
     * @throws NullPointerException
     *             if subagentName is null
     */
    void reloadSubagent(String subagentName);

}
