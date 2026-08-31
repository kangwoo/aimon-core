package at.aimon.core.knowledge;

import java.util.Objects;

import at.aimon.core.filesystem.VirtualFileSystem;

/**
 * An immutable reference to the source of knowledge documents for indexing.
 *
 * <p>
 * Pairs a {@link VirtualFileSystem} with a directory path, representing a specific location from which documents should
 * be read, chunked, and indexed. This decouples the storage backend from the knowledge store, allowing the same store
 * instance to index documents from different file systems and directories.
 *
 * <pre>{@code
 * KnowledgeSource source = new KnowledgeSource(fileSystem, "/knowledge/runbooks");
 * store.index(scope, source, IndexOptions.defaults());
 * }</pre>
 *
 * @see KnowledgeStore#index(KnowledgeScope, KnowledgeSource, IndexOptions)
 */
public final class KnowledgeSource {

    private final VirtualFileSystem fileSystem;
    private final String directory;

    /**
     * Creates a knowledge source.
     *
     * @param fileSystem
     *            the virtual file system to read documents from (must not be null)
     * @param directory
     *            the VFS directory path containing documents (must not be null or empty)
     */
    public KnowledgeSource(VirtualFileSystem fileSystem, String directory) {
        this.fileSystem = Objects.requireNonNull(fileSystem, "fileSystem must not be null");
        this.directory = Objects.requireNonNull(directory, "directory must not be null");
        if (directory.isEmpty()) {
            throw new IllegalArgumentException("directory must not be empty");
        }
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
        return "KnowledgeSource{directory='" + directory + "'}";
    }
}
