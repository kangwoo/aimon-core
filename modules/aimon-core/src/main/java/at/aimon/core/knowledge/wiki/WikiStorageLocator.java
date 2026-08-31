package at.aimon.core.knowledge.wiki;

import at.aimon.core.filesystem.VirtualFileSystem;

/**
 * Strategy that resolves both the {@link VirtualFileSystem} and the directory under which a {@link WikiScope}'s data
 * is stored.
 *
 * <p>
 * A {@code WikiStorageLocator} unifies the two scope-keyed lookups required by {@link DefaultWikiKnowledgeBase}:
 * <ul>
 * <li>{@link #fileSystemFor(WikiScope)} — which VFS instance backs the scope. The standard pattern is to route to the
 * filesystem owned by the agent runtime registered for the scope's {@code contextId}, so wiki I/O lands in
 * the same VFS the agent itself is using; see {@link ContextResolvingWikiStorageLocator}. Custom implementations can
 * also partition by other dimensions (e.g., a different MongoDB GridFS database per tenant).
 * <li>{@link #directoryFor(WikiScope)} — the VFS-relative directory under which {@code pages/}, {@code index.md}, and
 * {@code log.md} live for that scope
 * </ul>
 *
 * <p>
 * Combining the two in a single abstraction makes it impossible to forget to update one when the other changes:
 * implementations must answer both questions consistently for the same scope.
 *
 * <p>
 * Implementations must:
 * <ul>
 * <li>be deterministic — equal scopes must yield equal {@code (VFS, directory)} pairs
 * <li>return non-null values from both methods (or throw a runtime exception when the scope cannot be served)
 * <li>return a non-empty directory path; a trailing slash is permitted but not required — the caller normalizes
 * <li>not transfer ownership of the returned VFS to the caller — the locator (or whoever provided it) owns the
 * lifecycle
 * </ul>
 *
 * <p>
 * This interface intentionally exposes no built-in factories. The standard concrete adapter for the common
 * "look up the VFS from an agent runtime by id" pattern is {@link ContextResolvingWikiStorageLocator};
 * callers needing fully custom routing should implement this interface directly.
 *
 * @see DefaultWikiKnowledgeBase
 * @see WikiScope
 */
public interface WikiStorageLocator {

    /**
     * Returns the {@link VirtualFileSystem} that backs the given scope.
     *
     * @param scope
     *            the wiki scope (must not be null)
     * @return the VFS instance for this scope (never null)
     */
    VirtualFileSystem fileSystemFor(WikiScope scope);

    /**
     * Returns the VFS-relative directory for the given scope. A trailing slash is permitted but not required; callers
     * (notably {@link DefaultWikiKnowledgeBase}) normalize.
     *
     * @param scope
     *            the wiki scope (must not be null)
     * @return the directory path (never null or empty)
     */
    String directoryFor(WikiScope scope);
}
