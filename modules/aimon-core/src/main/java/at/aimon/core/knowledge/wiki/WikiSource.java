package at.aimon.core.knowledge.wiki;

import java.util.Objects;

import at.aimon.core.filesystem.VirtualFileSystem;

/**
 * An immutable reference to the source of raw documents for wiki ingestion.
 *
 * <p>
 * Pairs a {@link VirtualFileSystem} with a directory path, representing a specific location from which raw documents
 * should be read and integrated into the wiki. This decouples the storage backend from the wiki knowledge base.
 *
 * <pre>{@code
 * WikiSource source = WikiSource.builder()
 *         .fileSystem(fileSystem)
 *         .directory("/raw/articles")
 *         .build();
 * wiki.ingest(scope, source, IngestOptions.defaults());
 * }</pre>
 *
 * @see WikiKnowledgeBase#ingest(WikiScope, WikiSource, IngestOptions)
 */
public final class WikiSource {

    /**
     * Creates a new builder instance.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    private final VirtualFileSystem fileSystem;
    private final String directory;

    /**
     * Creates a wiki source.
     *
     * @param fileSystem
     *            the virtual file system to read documents from (must not be null)
     * @param directory
     *            the VFS directory path containing raw source documents (must not be null or empty)
     */
    public WikiSource(VirtualFileSystem fileSystem, String directory) {
        this.fileSystem = Objects.requireNonNull(fileSystem, "fileSystem must not be null");
        this.directory = Objects.requireNonNull(directory, "directory must not be null");
        if (directory.isEmpty()) {
            throw new IllegalArgumentException("directory must not be empty");
        }
    }

    private WikiSource(Builder builder) {
        this(builder.fileSystem, builder.directory);
    }

    /**
     * Returns the virtual file system.
     *
     * @return the file system (never null)
     */
    public VirtualFileSystem getFileSystem() {
        return fileSystem;
    }

    /**
     * Returns the directory path.
     *
     * @return the directory (never null or empty)
     */
    public String getDirectory() {
        return directory;
    }

    @Override
    public String toString() {
        return "WikiSource{directory='" + directory + "'}";
    }

    /** Builder for {@link WikiSource}. */
    public static final class Builder {

        private VirtualFileSystem fileSystem;
        private String directory;

        private Builder() {
        }

        /**
         * Sets the virtual file system to read documents from.
         *
         * @param fileSystem
         *            the file system (must not be null)
         * @return this builder
         */
        public Builder fileSystem(VirtualFileSystem fileSystem) {
            this.fileSystem = fileSystem;
            return this;
        }

        /**
         * Sets the VFS directory path containing raw source documents.
         *
         * @param directory
         *            the directory path (must not be null or empty)
         * @return this builder
         */
        public Builder directory(String directory) {
            this.directory = directory;
            return this;
        }

        /**
         * Builds the wiki source.
         *
         * @return a new {@link WikiSource} instance
         * @throws NullPointerException
         *             if fileSystem or directory is null
         * @throws IllegalArgumentException
         *             if directory is empty
         */
        public WikiSource build() {
            return new WikiSource(this);
        }
    }
}
