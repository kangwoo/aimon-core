package at.aimon.core.filesystem.impl.local;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileSystemLoopException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
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
 * <b>Symbolic links (design §4.4, the link rule).</b> {@link #listRecursive} and {@link #read} follow a link —
 * including a linked start directory, such as a skill directory installed as {@code skills/foo -> ../shared/foo} —
 * only when its real path lies inside the root or inside one of the operator's allowed link roots. A link that
 * resolves anywhere else fails the call with {@link InvalidPathException} instead of being skipped, so a staged copy
 * is never silently missing the files behind it. Every visited entry's real path is checked, so a link anywhere on
 * the way (a linked subdirectory, a linked file) is covered. A link that loops back to an ancestor is skipped with a
 * WARN; a dangling link is not a regular file and is not listed.
 */
public final class ReadOnlyLocalFileSystem implements VirtualFileSystem {

    private static final Logger log = LoggerFactory.getLogger(ReadOnlyLocalFileSystem.class);

    private static final String READ_ONLY = "read-only file system";

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
     * Fails unless {@code path}'s real path lies inside the root or an allowed link root. Real paths are taken on
     * both sides, so a root reached through a link of its own (macOS {@code /var -> /private/var}) still matches.
     */
    private void requireConfined(Path path) throws IOException {
        final Path real = path.toRealPath();
        if (real.startsWith(realOf(root))) {
            return;
        }
        for (Path allowed : allowedLinkRoots) {
            if (real.startsWith(realOf(allowed))) {
                return;
            }
        }
        throw new InvalidPathException(relative(path),
                "symbolic link resolves to " + real + ", outside the root " + root
                        + (allowedLinkRoots.isEmpty() ? "" : " and the allowed link roots " + allowedLinkRoots)
                        + "; add that directory to the allowed link roots to stage it");
    }

    private static Path realOf(Path directory) {
        try {
            return directory.toRealPath();
        } catch (IOException e) {
            return directory;
        }
    }

    @Override
    public InputStream read(String path) {
        final Path file = resolve(path);
        if (!Files.isRegularFile(file)) {
            throw new FileNotFoundException(path);
        }
        try {
            requireConfined(file);
            return Files.newInputStream(file);
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
        return Files.exists(resolve(path));
    }

    @Override
    public boolean isDirectory(String path) {
        return Files.isDirectory(resolve(path));
    }

    @Override
    public FileMetadata getMetadata(String path) {
        final Path file = resolve(path);
        if (!Files.exists(file)) {
            throw new FileNotFoundException(path);
        }
        try {
            final BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
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
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.map(this::relative).sorted().toList();
        } catch (IOException e) {
            throw new BackendConnectionException(BackendType.LOCAL, "Failed to list " + directory, e);
        }
    }

    @Override
    public List<String> listRecursive(String directory) {
        final Path dir = resolve(directory);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        // Files.walk without FOLLOW_LINKS visits a linked start directory as one non-regular entry, and a linked
        // subdirectory the same way, so their files would silently vanish from the list. Follow links and confine
        // every entry instead (the link rule in the class comment).
        final List<String> files = new ArrayList<>();
        try {
            Files.walkFileTree(dir, EnumSet.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE,
                    new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attributes)
                                throws IOException {
                            requireConfined(d);
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                            if (attributes.isRegularFile()) {
                                requireConfined(file);
                                files.add(relative(file));
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException e) throws IOException {
                            if (e instanceof FileSystemLoopException) {
                                log.warn("Skipping '{}' while listing {}: the link loops back to an ancestor",
                                        relative(file), directory);
                                return FileVisitResult.CONTINUE;
                            }
                            throw e;
                        }
                    });
        } catch (IOException e) {
            throw new BackendConnectionException(BackendType.LOCAL, "Failed to list " + directory, e);
        }
        files.sort(null);
        return files;
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
