package at.aimon.core.filesystem;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

import at.aimon.core.filesystem.impl.PathRuleVirtualFileSystem;
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

    /**
     * Wraps a filesystem so {@link PathRule}s apply to it by root-anchored prefix (execution-environment design §4.4,
     * §9.2). This is how any provider — not only the local one — keeps the staging area read-only and the control
     * store hidden from the model's file tools, sharing one implementation of path normalisation and case/Unicode
     * folding instead of each provider writing its own.
     *
     * <p>
     * Paths are resolved against the delegate's working directory <b>at the time of this call</b>; the first rule that
     * covers a path decides. The result <b>borrows</b> the delegate: its {@code initialize()} and {@code close()} do
     * nothing, so the caller keeps closing the delegate. It is a guard against accidental edits, not a security
     * boundary — a shell in the same environment still reaches the files.
     *
     * @param delegate
     *            the filesystem to guard (must not be null; borrowed)
     * @param rules
     *            the rules (must not be null; may be empty)
     * @return the guarded filesystem
     */
    public static VirtualFileSystem withPathRules(VirtualFileSystem delegate, List<PathRule> rules) {
        return new PathRuleVirtualFileSystem(delegate, rules);
    }
}
