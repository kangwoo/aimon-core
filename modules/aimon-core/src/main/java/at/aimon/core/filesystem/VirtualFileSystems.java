package at.aimon.core.filesystem;

import java.nio.file.Path;
import java.util.Collection;

import at.aimon.core.filesystem.impl.local.ReadOnlyLocalFileSystem;

/**
 * Factories for filesystems that code outside the {@code at.aimon.core.filesystem} tree may build without reaching
 * into {@code filesystem.impl}, which ArchUnit keeps to one assembly point per implementation.
 */
public final class VirtualFileSystems {

    private VirtualFileSystems() {
        throw new AssertionError("This class should not be instantiated");
    }

    /**
     * Returns a read-only filesystem over a host directory. It does no I/O when built: a missing root is not created
     * (it has no files) and a read-only root is fine. Every mutating method throws
     * {@link UnsupportedOperationException}. Nothing to close.
     *
     * @param root
     *            the directory to expose (must not be null; need not exist)
     * @return the filesystem
     */
    public static VirtualFileSystem readOnlyLocal(Path root) {
        return new ReadOnlyLocalFileSystem(root);
    }

    /**
     * Returns a read-only filesystem over a host directory whose symbolic links may also resolve into
     * {@code allowedLinkRoots} (design §4.4, the link rule). {@link #readOnlyLocal(Path)} is this with no allowed
     * link roots: a link must then resolve inside {@code root}, and one that does not fails the listing or the read.
     *
     * @param root
     *            the directory to expose (must not be null; need not exist)
     * @param allowedLinkRoots
     *            further directories a link may resolve into (must not be null; may be empty)
     * @return the filesystem
     */
    public static VirtualFileSystem readOnlyLocal(Path root, Collection<Path> allowedLinkRoots) {
        return new ReadOnlyLocalFileSystem(root, allowedLinkRoots);
    }
}
