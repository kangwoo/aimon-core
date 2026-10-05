package at.aimon.core.filesystem.impl.local;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileSystemLoopException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.filesystem.BackendStatus;
import at.aimon.core.filesystem.BackendType;
import at.aimon.core.filesystem.FileMetadata;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.filesystem.exception.BackendConnectionException;
import at.aimon.core.filesystem.exception.FileNotFoundException;
import at.aimon.core.filesystem.exception.InvalidPathException;

/**
 * A read-only {@link VirtualFileSystem} over a host directory, built on {@code java.nio} alone.
 *
 * <p>
 * It exists so that a skill directory on the host ({@code PathSkillRepository}) can be a staging source
 * (execution-environment design §4.4) without the side effects of {@link LocalFileSystem}: it never creates its root,
 * never checks that it is writable, and needs no initialisation, so a missing or read-only skills directory (a
 * ConfigMap volume, a {@code readOnlyRootFilesystem} image) works as it did before. A missing root simply has no
 * files. Every mutating method throws {@link UnsupportedOperationException}; the instance holds no resources.
 *
 * <p>
 * Paths are resolved against the root and normalised, and must stay under it.
 *
 * <p>
 * <b>Symbolic links (design §4.4, the link rule).</b> Every method that looks at a path — {@link #read},
 * {@link #exists}, {@link #isDirectory}, {@link #getMetadata}, {@link #list} and {@link #listRecursive} — follows a
 * link, including a linked start directory such as a skill directory installed as
 * {@code skills/foo -> ../shared/foo}, only when its real path lies inside the root or inside one of the operator's
 * allowed link roots. A link that resolves anywhere else fails the call with {@link InvalidPathException} instead of
 * being skipped or answered with {@code false}, so a staged copy is never silently missing the files behind it and a
 * caller that asks {@code exists} before listing cannot mistake a refused link for an absent one. The answer does not
 * depend on whether the link's target is there: a dangling link to the outside, and a missing path below a linked
 * outside directory, are refused the same way, so none of these methods tells a caller what exists beyond the allowed
 * roots. {@link #listRecursive} checks every visited entry's real path, so a link anywhere on the way (a linked
 * subdirectory, a linked file) is covered; {@link #list} checks the directory it lists and names that directory's
 * entries without following them. A link that loops back to an ancestor is skipped with a WARN; a dangling link
 * that stays inside the allowed roots is not a regular file and is not listed.
 *
 * <p>
 * {@link #read} opens the real path it checked, without following a link in its last segment, so a link swapped
 * after the check cannot redirect the read. What remains is the gap {@code java.nio} cannot close without
 * directory-relative opens: an <em>ancestor directory</em> of that real path replaced by a link between the check and
 * the open.
 */
public final class ReadOnlyLocalFileSystem implements VirtualFileSystem {

    private static final Logger log = LoggerFactory.getLogger(ReadOnlyLocalFileSystem.class);

    private static final String READ_ONLY = "read-only file system";

    /** How many dangling links {@link #realPath} follows before it calls the chain a loop. */
    private static final int MAX_LINK_HOPS = 40;

    private final Path root;
    private final List<Path> allowedLinkRoots;

    /**
     * Creates a filesystem whose links may resolve only inside {@code root}.
     *
     * @param root
     *            the directory to expose (must not be null; need not exist)
     */
    public ReadOnlyLocalFileSystem(Path root) {
        this(root, List.of());
    }

    /**
     * Creates a filesystem whose links may resolve inside {@code root} or inside any of {@code allowedLinkRoots}.
     *
     * @param root
     *            the directory to expose (must not be null; need not exist)
     * @param allowedLinkRoots
     *            further directories a symbolic link may resolve into (must not be null; may be empty)
     */
    public ReadOnlyLocalFileSystem(Path root, Collection<Path> allowedLinkRoots) {
        this.root = Objects.requireNonNull(root, "root must not be null").toAbsolutePath().normalize();
        Objects.requireNonNull(allowedLinkRoots, "allowedLinkRoots must not be null");
        this.allowedLinkRoots = allowedLinkRoots.stream()
                .map(p -> Objects.requireNonNull(p, "allowed link root must not be null").toAbsolutePath().normalize())
                .toList();
    }

    private Path resolve(String path) {
        Objects.requireNonNull(path, "path must not be null");
        if (path.indexOf('\\') >= 0) {
            throw new InvalidPathException(path, "backslash separators are not supported");
        }
        final Path candidate = Path.of(path).isAbsolute() ? Path.of(path).normalize() : root.resolve(path).normalize();
        if (!candidate.startsWith(root)) {
            throw new InvalidPathException(path, "outside the root " + root);
        }
        return candidate;
    }

    private String relative(Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }

    /**
     * Fails unless {@code path}'s real path lies inside the root or an allowed link root, and returns that real path.
     * Real paths are taken on both sides, so a root reached through a link of its own (macOS
     * {@code /var -> /private/var}) still matches. The path need not exist (see {@link #realPath}).
     */
    private Path requireConfined(Path path) throws IOException {
        final Path real = realPath(path, 0);
        if (real.startsWith(realOf(root))) {
            return real;
        }
        for (Path allowed : allowedLinkRoots) {
            if (real.startsWith(realOf(allowed))) {
                return real;
            }
        }
        throw new InvalidPathException(relative(path),
                "symbolic link resolves to " + real + ", outside the root " + root
                        + (allowedLinkRoots.isEmpty() ? "" : " and the allowed link roots " + allowedLinkRoots)
                        + "; add that directory to the allowed link roots to stage it");
    }

    private static Path realOf(Path directory) {
        try {
            return realPath(directory, 0);
        } catch (IOException e) {
            return directory;
        }
    }

    /**
     * {@link Path#toRealPath} for a path that may not exist: where the path would be if it did. A dangling link is
     * followed to the place it names and a missing tail is appended to its parent's real path, so the link rule
     * answers a missing target exactly as it answers a present one — otherwise "refused" against "absent" would
     * itself say whether a file outside the root exists.
     */
    private static Path realPath(Path path, int hops) throws IOException {
        try {
            return path.toRealPath();
        } catch (NoSuchFileException e) {
            final Path parent = path.getParent();
            if (parent == null) {
                return path;
            }
            final Path realParent = realPath(parent, hops);
            if (!Files.isSymbolicLink(path)) {
                return realParent.resolve(path.getFileName());
            }
            if (hops >= MAX_LINK_HOPS) {
                throw new FileSystemLoopException(path.toString());
            }
            return followTarget(realParent, Files.readSymbolicLink(path), hops + 1);
        }
    }

    /**
     * Where a link's target leads from the link's (real) directory, one segment at a time. Not
     * {@code resolve(target).normalize()}: that drops {@code x/..} as text, and when {@code x} is itself a link the
     * kernel goes to the parent of where {@code x} points, which is somewhere else.
     */
    private static Path followTarget(Path realParent, Path target, int hops) throws IOException {
        Path current = target.isAbsolute() ? target.getRoot() : realParent;
        for (Path segment : target) {
            final String name = segment.toString();
            if (name.equals("..")) {
                // `current` is a real path, so its parent is where `..` leads.
                current = current.getParent() == null ? current : current.getParent();
            } else if (!name.equals(".") && !name.isEmpty()) {
                current = realPath(current.resolve(name), hops);
            }
        }
        return current;
    }

    @Override
    public InputStream read(String path) {
        final Path file = resolve(path);
        try {
            // Confine first, then look: a link to the outside is refused whether or not its target is a file.
            final Path real = requireConfined(file);
            if (!Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) {
                throw new FileNotFoundException(path);
            }
            // Open what was checked, not the link that led to it (the class comment has what this leaves open).
            return Files.newInputStream(real, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException e) {
            throw new BackendConnectionException(BackendType.LOCAL, "Failed to read " + path, e);
        }
    }

    @Override
    public InputStream openInputStream(String path) {
        return read(path);
    }

    @Override
    public boolean exists(String path) {
        final Path real = confinedOrNull(resolve(path));
        return real != null && Files.exists(real, LinkOption.NOFOLLOW_LINKS);
    }

    @Override
    public boolean isDirectory(String path) {
        final Path real = confinedOrNull(resolve(path));
        return real != null && Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS);
    }

    /**
     * The confined real path for a yes/no question, or null when the path cannot be resolved at all (a link loop, an
     * unreadable ancestor) — which {@code Files.exists} has always answered with {@code false}. A link that resolves
     * outside the allowed roots is not such a case: it throws.
     */
    private Path confinedOrNull(Path path) {
        try {
            return requireConfined(path);
        } catch (IOException e) {
            log.debug("Treating '{}' as absent: {}", relative(path), e.toString());
            return null;
        }
    }

    @Override
    public FileMetadata getMetadata(String path) {
        final Path file = resolve(path);
        try {
            final Path real = requireConfined(file);
            if (!Files.exists(real, LinkOption.NOFOLLOW_LINKS)) {
                throw new FileNotFoundException(path);
            }
            final BasicFileAttributes attributes = Files.readAttributes(real, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            final var modified = attributes.lastModifiedTime().toInstant();
            final var created = attributes.creationTime().toInstant();
            return FileMetadata.builder().path(relative(file)).size(attributes.isDirectory() ? 0 : attributes.size())
                    .directory(attributes.isDirectory()).createdAt(created.isAfter(modified) ? modified : created)
                    .modifiedAt(modified).build();
        } catch (IOException e) {
            throw new BackendConnectionException(BackendType.LOCAL, "Failed to read metadata of " + path, e);
        }
    }

    @Override
    public List<String> list(String directory) {
        final Path dir = resolve(directory);
        final Path real = confinedOrNull(dir);
        if (real == null || !Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        // List the checked directory, and name its entries under the path the caller asked for.
        try (Stream<Path> stream = Files.list(real)) {
            return stream.map(entry -> relative(dir.resolve(entry.getFileName()))).sorted().toList();
        } catch (IOException e) {
            throw new BackendConnectionException(BackendType.LOCAL, "Failed to list " + directory, e);
        }
    }

    @Override
    public List<String> listRecursive(String directory) {
        final Path dir = resolve(directory);
        // Confined before it is looked at, as in list(): a start directory linked to the outside is refused whether
        // or not its target is there, not answered with an empty list when it is not.
        final Path realStart = confinedOrNull(dir);
        if (realStart == null || !Files.isDirectory(realStart, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        // Files.walk without FOLLOW_LINKS visits a linked start directory as one non-regular entry, and a linked
        // subdirectory the same way, so their files would silently vanish from the list. Follow links and confine
        // every entry instead (the link rule in the class comment).
        final List<String> files = new ArrayList<>();
        try {
            Files.walkFileTree(dir, EnumSet.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE,
                    new ConfinedListing(directory, files));
        } catch (IOException e) {
            throw new BackendConnectionException(BackendType.LOCAL, "Failed to list " + directory, e);
        }
        files.sort(null);
        return files;
    }

    /** The walk behind {@link #listRecursive}: every entry is confined, and regular files are collected. */
    private final class ConfinedListing extends SimpleFileVisitor<Path> {

        private final String directory;
        private final List<String> files;

        ConfinedListing(String directory, List<String> files) {
            this.directory = directory;
            this.files = files;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attributes) throws IOException {
            requireConfined(d);
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
            // The walk follows links, so a link seen as a link is dangling: confined like a file, so one that
            // names a place outside is refused as it would be with its target there, and never listed.
            if (attributes.isRegularFile() || attributes.isSymbolicLink()) {
                requireConfined(file);
                if (attributes.isRegularFile()) {
                    files.add(relative(file));
                }
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException e) throws IOException {
            if (e instanceof FileSystemLoopException) {
                log.warn("Skipping '{}' while listing {}: the link loops back to an ancestor", relative(file),
                        directory);
                return FileVisitResult.CONTINUE;
            }
            throw e;
        }
    }

    @Override
    public void write(String path, InputStream content, long contentLength) {
        throw new UnsupportedOperationException(READ_ONLY);
    }

    @Override
    public void delete(String path) {
        throw new UnsupportedOperationException(READ_ONLY);
    }

    @Override
    public void copy(String sourcePath, String destinationPath, boolean overwrite) {
        throw new UnsupportedOperationException(READ_ONLY);
    }

    @Override
    public void move(String sourcePath, String destinationPath, boolean overwrite) {
        throw new UnsupportedOperationException(READ_ONLY);
    }

    @Override
    public OutputStream openOutputStream(String path) {
        throw new UnsupportedOperationException(READ_ONLY);
    }

    @Override
    public void createDirectory(String path) {
        throw new UnsupportedOperationException(READ_ONLY);
    }

    @Override
    public void deleteRecursive(String path) {
        throw new UnsupportedOperationException(READ_ONLY);
    }

    @Override
    public String getWorkingDirectory() {
        return root.toString();
    }

    @Override
    public void initialize() {
        // Nothing to prepare: no directory is created and no writability is checked.
    }

    @Override
    public BackendStatus getStatus() {
        return BackendStatus.connected(BackendType.LOCAL);
    }

    @Override
    public void close() {
        // Holds no resources.
    }
}
