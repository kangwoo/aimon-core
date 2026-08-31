package at.aimon.core.knowledge.wiki;

import java.util.Objects;

import at.aimon.core.filesystem.VirtualFileSystem;

/**
 * Immutable execution context passed to a {@link WikiSearchStrategy}.
 *
 * <p>
 * Carries the resolved VFS and the canonical paths for a scope's storage layout, so a strategy does not need to know
 * how paths are resolved. All directory fields are pre-normalized to end with {@code /}.
 *
 * <pre>{@code
 * WikiSearchContext ctx = WikiSearchContext.builder()
 *         .fileSystem(fs)
 *         .scope(scope)
 *         .scopeDirectory("/wiki/ops-agent/ctx-1/runbook/")
 *         .pagesDirectory("/wiki/ops-agent/ctx-1/runbook/pages/")
 *         .indexPath("/wiki/ops-agent/ctx-1/runbook/index.md")
 *         .build();
 * }</pre>
 *
 * @see WikiSearchStrategy
 */
public final class WikiSearchContext {

    /** Returns a new builder. */
    public static Builder builder() {
        return new Builder();
    }

    private final VirtualFileSystem fileSystem;
    private final WikiScope scope;
    private final String scopeDirectory;
    private final String pagesDirectory;
    private final String indexPath;

    private WikiSearchContext(Builder builder) {
        this.fileSystem = Objects.requireNonNull(builder.fileSystem, "fileSystem must not be null");
        this.scope = Objects.requireNonNull(builder.scope, "scope must not be null");
        this.scopeDirectory = Objects.requireNonNull(builder.scopeDirectory, "scopeDirectory must not be null");
        this.pagesDirectory = Objects.requireNonNull(builder.pagesDirectory, "pagesDirectory must not be null");
        this.indexPath = Objects.requireNonNull(builder.indexPath, "indexPath must not be null");
    }

    public VirtualFileSystem getFileSystem() {
        return fileSystem;
    }

    public WikiScope getScope() {
        return scope;
    }

    public String getScopeDirectory() {
        return scopeDirectory;
    }

    public String getPagesDirectory() {
        return pagesDirectory;
    }

    public String getIndexPath() {
        return indexPath;
    }

    /** Builder for {@link WikiSearchContext}. */
    public static final class Builder {

        private VirtualFileSystem fileSystem;
        private WikiScope scope;
        private String scopeDirectory;
        private String pagesDirectory;
        private String indexPath;

        private Builder() {
        }

        /** Sets the resolved filesystem for the scope. */
        public Builder fileSystem(VirtualFileSystem fileSystem) {
            this.fileSystem = fileSystem;
            return this;
        }

        /** Sets the wiki scope. */
        public Builder scope(WikiScope scope) {
            this.scope = scope;
            return this;
        }

        /** Sets the scope's root directory (should end with {@code /}). */
        public Builder scopeDirectory(String scopeDirectory) {
            this.scopeDirectory = scopeDirectory;
            return this;
        }

        /** Sets the scope's pages directory (should end with {@code /}). */
        public Builder pagesDirectory(String pagesDirectory) {
            this.pagesDirectory = pagesDirectory;
            return this;
        }

        /** Sets the absolute path to the scope's {@code index.md}. */
        public Builder indexPath(String indexPath) {
            this.indexPath = indexPath;
            return this;
        }

        /** Builds the context. */
        public WikiSearchContext build() {
            return new WikiSearchContext(this);
        }
    }
}
