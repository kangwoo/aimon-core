package at.aimon.core.base;

/**
 * Common interface for registries that support reloading all entries.
 *
 * <p>
 * Provides a uniform contract for hot-reloading registry contents (e.g., commands, skills, subagents) without
 * restarting the application.
 *
 * <p>
 * Implementations should clear cached entries and re-scan their underlying source (file system, classpath, remote
 * registry, etc.).
 */
public interface Reloadable {

    /**
     * Reloads all entries from the underlying source.
     *
     * <p>
     * Clears the registry and re-scans all available entries. Implementations should handle errors gracefully, logging
     * failures for individual entries without aborting the entire reload.
     */
    void reloadAll();
}
