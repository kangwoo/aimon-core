package at.aimon.core.filesystem.impl;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

import at.aimon.core.filesystem.BackendStatus;
import at.aimon.core.filesystem.FileMetadata;
import at.aimon.core.filesystem.FileSystemUsage;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.filesystem.exception.FileNotFoundException;
import at.aimon.core.filesystem.exception.InvalidPathException;

/**
 * A {@link VirtualFileSystem} decorator that scopes every path under a fixed branch prefix
 * ({@code .worktrees/<branchKey>/}), giving one parallel branch an isolated filesystem view over a shared backend
 * (design §6.3, worktree isolation).
 *
 * <p>
 * Immutable and purely delegating — concurrency safety reduces to the backend's, which parallel subagents already rely
 * on. It <b>borrows</b> the delegate: {@link #close()} and {@link #initialize()} are no-ops on it.
 *
 * <h2>Path model (design §6.3)</h2>
 * <ul>
 * <li><b>Input scoping.</b> File tools forward the LLM's absolute path verbatim, anchored on the advertised base
 * working directory. {@link #scope(String)} therefore first strips the delegate's base working directory prefix from an
 * absolute input to obtain a branch-relative remainder (a path already branch-relative — e.g. a stripped list result a
 * tool re-feeds — is used as-is), then prepends the branch prefix, normalises, and rejects any {@code ../} that would
 * escape the prefix (the backend's own validator only guards the shared base, not the branch subtree). Paths
 * containing a backslash are rejected outright (never a supported separator in the VFS path model; passed through as
 * an opaque segment it could be re-interpreted as a separator by a Windows-hosted backend and defeat the escape
 * check), and an absolute input outside the base working directory is rejected rather than silently remapped into the
 * branch (behavioural parity with the unscoped backend, whose base-path guard rejects the same input).</li>
 * <li><b>Output stripping.</b> {@code list}/{@code listRecursive}/{@code search} return VFS-root-relative paths on
 * every
 * reference backend, so this decorator strips the branch prefix from their results <em>uniformly</em>, turning
 * root-relative delegate output back into branch-relative caller output. The round-trip invariant holds: any path this
 * decorator returns is directly usable through this same decorator (no double-prefix). {@code getMetadata} likewise
 * rebuilds the returned metadata with the branch-relative path.</li>
 * <li><b>{@code getWorkingDirectory()}</b> returns branch-relative {@code "."} (which {@link #scope(String)} maps to
 * the
 * branch root) — a deliberate deviation from the {@code LocalFileSystem} contract (which returns the absolute base
 * path) — so a tool that captures it as a default search root (e.g. {@code GrepTool}) round-trips instead of
 * double-prefixing.</li>
 * <li><b>{@code search} pattern</b> is a filename glob, not a path, so it is passed through <em>unscoped</em>.</li>
 * <li><b>{@code getUsageSummary()}</b> reports the branch subtree, by delegating to the path-scoped overload with the
 * branch prefix — "the whole filesystem", scoped, is the branch. A backend that cannot answer per subtree still falls
 * back to whole-backend usage through that overload's default.</li>
 * </ul>
 */
public final class ScopedVirtualFileSystem implements VirtualFileSystem {

    private final VirtualFileSystem delegate;
    private final String prefix;
    private final String baseWorkingDir;

    /**
     * @param delegate
     *            the shared backend filesystem to scope (must not be null; borrowed — never closed)
     * @param prefix
     *            the branch prefix (e.g. {@code .worktrees/<branchKey>}); normalised to a single root segment path
     *            (must
     *            not be null or blank)
     */
    public ScopedVirtualFileSystem(VirtualFileSystem delegate, String prefix) {
        this.delegate = Objects.requireNonNull(delegate, "delegate cannot be null");
        Objects.requireNonNull(prefix, "prefix cannot be null");
        final String normalized = normalizeRelative(prefix);
        if (normalized == null || normalized.isEmpty()) {
            throw new IllegalArgumentException("prefix must be a non-empty relative path, got: " + prefix);
        }
        this.prefix = normalized;
        this.baseWorkingDir = delegate.getWorkingDirectory();
    }

    // --- path scoping ---------------------------------------------------------------------------------------------

    /**
     * Maps a caller path (absolute-anchored-on-base or branch-relative) to the delegate-relative path under the branch
     * prefix.
     *
     * <p>
     * Backslashes are rejected up front, before any segment splitting: the splitters below only recognise {@code '/'},
     * so a backslash would ride through inside an opaque segment (e.g. {@code "..\..\x"} passes the branch-escape
     * self-check as a single segment) and a Windows-hosted backend would later re-interpret it as a separator,
     * escaping the branch while staying inside the shared base. The {@link VirtualFileSystem} contract requires
     * rejecting {@code ..\} sequences.
     *
     * @throws InvalidPathException
     *             if the path contains a backslash, is an absolute path outside the base working directory, or
     *             normalizes to a path that escapes the branch prefix
     */
    private String scope(String path) {
        if (path != null && path.indexOf('\\') >= 0) {
            throw new InvalidPathException(path, "backslash separators are not supported");
        }
        final String rel = toBranchRelative(path);
        final String normalizedRel = normalizeRelative(rel);
        if (normalizedRel == null) {
            throw new InvalidPathException(path, "escapes the worktree branch prefix '" + prefix + "'");
        }
        return normalizedRel.isEmpty() ? prefix : prefix + "/" + normalizedRel;
    }

    /**
     * Strips the delegate base working directory (if the input is absolute-anchored) and any leading separators. The
     * base prefix is stripped only on an exact match or a {@code '/'} boundary, so a sibling such as
     * {@code /data/vfs2/x} is never mistaken for a child of base {@code /data/vfs}. For a {@code '/'}-anchored base
     * (a local filesystem directory), an absolute input that is not under the base is rejected rather than treated as
     * branch-relative: the unscoped backend rejects the same input via its base-path guard, and silently remapping it
     * into the branch would let a later merge promote it to a canonical path nobody intended (behavioural parity,
     * class javadoc). A URI-shaped base ({@code gridfs://...}, {@code s3://...}) gets no such rejection — those
     * backends accept leading-slash paths as root-anchored, so the input is scoped into the branch like any other
     * (parity again, in the opposite direction).
     *
     * @throws InvalidPathException
     *             if the input is an absolute path outside a {@code '/'}-anchored base working directory
     */
    private String toBranchRelative(String path) {
        if (path == null) {
            return "";
        }
        String p = path;
        // Base-anchoring and the outside-base rejection apply only to '/'-anchored filesystem bases (e.g.
        // LocalFileSystem's absolute directory), where the unscoped backend's own base-path guard rejects the same
        // input. A URI-shaped base (gridfs://db/bucket, s3://bucket) can never prefix a '/'-leading input, and those
        // backends ACCEPT leading-slash paths as anchored on their root — so such inputs fall through to the
        // strip-leading-slashes logic below, exactly like the documented base == "/" case.
        if (baseWorkingDir.startsWith("/") && !baseWorkingDir.equals("/")) {
            if (p.equals(baseWorkingDir)) {
                p = "";
            } else if (p.startsWith(baseWorkingDir + "/")) {
                p = p.substring(baseWorkingDir.length() + 1);
            } else if (p.startsWith("/")) {
                throw new InvalidPathException(path,
                        "absolute path outside the base working directory '" + baseWorkingDir + "'");
            }
        }
        // Drop any leading separators so a base-anchored (or root-anchored, when the base IS the root) input becomes
        // relative to the branch prefix.
        int start = 0;
        while (start < p.length() && p.charAt(start) == '/') {
            start++;
        }
        return p.substring(start);
    }

    /** Strips the branch prefix from a delegate-returned (root-relative) path, back to branch-relative. */
    private String unscope(String path) {
        if (path == null) {
            return null;
        }
        final String normalized = stripLeadingSlashes(path);
        if (normalized.equals(prefix)) {
            return "";
        }
        final String withSlash = prefix + "/";
        if (normalized.startsWith(withSlash)) {
            return normalized.substring(withSlash.length());
        }
        return normalized;
    }

    private List<String> unscopeAll(List<String> paths) {
        final List<String> out = new ArrayList<>(paths.size());
        for (final String p : paths) {
            out.add(unscope(p));
        }
        return out;
    }

    private static String stripLeadingSlashes(String p) {
        int start = 0;
        while (start < p.length() && p.charAt(start) == '/') {
            start++;
        }
        return p.substring(start);
    }

    /**
     * Normalises a relative path by resolving {@code .} and {@code ..} segments.
     *
     * @return the normalised path (possibly empty for the root), or {@code null} if it escapes above its own root
     */
    private static String normalizeRelative(String rel) {
        final Deque<String> out = new ArrayDeque<>();
        for (final String segment : rel.split("/")) {
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                if (out.isEmpty()) {
                    return null; // escapes above the root
                }
                out.removeLast();
            } else {
                out.addLast(segment);
            }
        }
        return String.join("/", out);
    }

    // --- delegated, scoped operations -----------------------------------------------------------------------------

    @Override
    public void write(String path, InputStream content, long contentLength) {
        delegate.write(scope(path), content, contentLength);
    }

    @Override
    public InputStream read(String path) {
        return delegate.read(scope(path));
    }

    @Override
    public void delete(String path) {
        delegate.delete(scope(path));
    }

    @Override
    public boolean exists(String path) {
        return delegate.exists(scope(path));
    }

    @Override
    public boolean isDirectory(String path) {
        return delegate.isDirectory(scope(path));
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * The delegate stamps the metadata with the scoped path ({@code .worktrees/<branchKey>/...}); this override
     * rebuilds it with the branch-relative path so the round-trip invariant holds: re-feeding
     * {@link FileMetadata#getPath()} through this decorator must not double-prefix. Every other field is preserved as
     * returned by the delegate.
     */
    @Override
    public FileMetadata getMetadata(String path) {
        final FileMetadata scoped = delegate.getMetadata(scope(path));
        return FileMetadata.builder().path(unscope(scoped.getPath())).size(scoped.getSize())
                .directory(scoped.isDirectory()).createdAt(scoped.getCreatedAt()).modifiedAt(scoped.getModifiedAt())
                .mimeType(scoped.getMimeType().orElse(null)).customMetadata(scoped.getCustomMetadata()).build();
    }

    @Override
    public List<String> list(String directory) {
        return unscopeAll(delegate.list(scope(directory)));
    }

    @Override
    public List<String> listRecursive(String directory) {
        return unscopeAll(delegate.listRecursive(scope(directory)));
    }

    @Override
    public void copy(String sourcePath, String destinationPath, boolean overwrite) {
        delegate.copy(scope(sourcePath), scope(destinationPath), overwrite);
    }

    @Override
    public void move(String sourcePath, String destinationPath, boolean overwrite) {
        delegate.move(scope(sourcePath), scope(destinationPath), overwrite);
    }

    @Override
    public OutputStream openOutputStream(String path) {
        return delegate.openOutputStream(scope(path));
    }

    @Override
    public InputStream openInputStream(String path) {
        return delegate.openInputStream(scope(path));
    }

    @Override
    public void createDirectory(String path) {
        delegate.createDirectory(scope(path));
    }

    @Override
    public void deleteRecursive(String path) {
        delegate.deleteRecursive(scope(path));
    }

    @Override
    public List<String> search(String directory, String pattern, int maxResults) {
        // Scope the directory; pass the filename glob UNSCOPED (it is matched against file names only, and prepending
        // the prefix would be semantically wrong and stripped of its '/' by the backend's pattern sanitiser). Strip the
        // prefix from the root-relative results.
        return unscopeAll(delegate.search(scope(directory), pattern, maxResults));
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * Reports the branch subtree, not the shared backend: "the whole filesystem" from this decorator's point of view
     * is the branch prefix. It routes through the delegate's path-scoped overload, whose own default falls back to
     * whole-backend usage for a backend that cannot scope — over-reporting a quota is safe, under-reporting is not.
     */
    @Override
    public FileSystemUsage getUsageSummary() {
        try {
            return delegate.getUsageSummary(prefix);
        } catch (FileNotFoundException e) {
            // The branch subtree is this decorator's root, and the no-arg contract does not report a missing root:
            // a branch nobody has written to yet has zero usage. LocalFileSystem answers the same way for an absent
            // base path.
            return FileSystemUsage.builder().totalSize(0).fileCount(0).directoryCount(0).build();
        }
    }

    @Override
    public FileSystemUsage getUsageSummary(String path) {
        return delegate.getUsageSummary(scope(path));
    }

    // --- backend management (borrow-not-own) ----------------------------------------------------------------------

    @Override
    public String getWorkingDirectory() {
        return ".";
    }

    @Override
    public void initialize() {
        // No-op: the borrowed delegate was initialized by the bootstrap that created it.
    }

    @Override
    public BackendStatus getStatus() {
        return delegate.getStatus();
    }

    @Override
    public void close() {
        // No-op: the shared backend is closed only by the bootstrap that created it.
    }
}
