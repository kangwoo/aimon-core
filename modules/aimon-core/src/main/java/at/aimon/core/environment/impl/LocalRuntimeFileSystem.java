package at.aimon.core.environment.impl;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Objects;

import at.aimon.core.environment.RuntimeFileSystem;

/**
 * {@link RuntimeFileSystem} over a local directory: paths are host paths, confined to the root. What the engine
 * contract suite runs the shell engine on, with a local shell.
 *
 * <p>
 * {@link #writeNew} writes the bytes to a fresh temporary file created with {@code O_EXCL} ({@code CREATE_NEW}) in the
 * target's directory, then hard-links it to the target, which fails when the target exists, and removes the
 * temporary name. A reader therefore never sees a partly written target, and of two writers exactly one wins — what
 * {@code link(2)} gives the OpenSandbox provider too. Every path must lie in the root, after normalization and with
 * the parent's links resolved; the last segment's link is never followed.
 */
public final class LocalRuntimeFileSystem implements RuntimeFileSystem {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path root;

    /**
     * @param root
     *            the directory every path must lie in (must not be null)
     */
    public LocalRuntimeFileSystem(Path root) {
        this.root = Objects.requireNonNull(root, "root must not be null").toAbsolutePath().normalize();
    }

    @Override
    public void writeNew(String path, byte[] bytes) throws IOException {
        Objects.requireNonNull(bytes, "bytes must not be null");
        final Path target = confine(path);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(path);
        }
        final byte[] suffix = new byte[8];
        RANDOM.nextBytes(suffix);
        final Path temp = target
                .resolveSibling(".tmp-" + target.getFileName() + "-" + HexFormat.of().formatHex(suffix));
        try {
            try (SeekableByteChannel channel = Files.newByteChannel(temp, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE)) {
                final ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
            }
            Files.createLink(target, temp);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    @Override
    public byte[] read(String path, int maxBytes) throws IOException {
        if (maxBytes < 0) {
            throw new IllegalArgumentException("maxBytes must not be negative: " + maxBytes);
        }
        final Path file = confine(path);
        try (SeekableByteChannel channel = Files.newByteChannel(file, StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS)) {
            final BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile()) {
                throw new IOException("Not a regular file: " + path);
            }
            final int size = (int) Math.min(maxBytes, attributes.size());
            final ByteBuffer buffer = ByteBuffer.allocate(size);
            while (buffer.hasRemaining() && channel.read(buffer) > 0) {
                // read until full or end of file
            }
            final byte[] out = new byte[buffer.position()];
            buffer.flip().get(out);
            return out;
        }
    }

    @Override
    public void delete(String path) throws IOException {
        final Path target = confine(path);
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            Files.deleteIfExists(target);
            return;
        }
        // walkFileTree does not follow links unless asked to: a link inside the tree is deleted, not entered.
        Files.walkFileTree(target, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException e) throws IOException {
                if (e instanceof NoSuchFileException) {
                    return FileVisitResult.CONTINUE;
                }
                throw e;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
                if (e != null && !(e instanceof NoSuchFileException)) {
                    throw e;
                }
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    @Override
    public void createDirectories(String path) throws IOException {
        Files.createDirectories(confine(path));
    }

    /** The path as a host path inside the root; its parent's links are resolved, its last segment's are not. */
    private Path confine(String path) throws IOException {
        Objects.requireNonNull(path, "path must not be null");
        final Path candidate = Path.of(path);
        if (!candidate.isAbsolute()) {
            throw new IOException("Not an absolute path: " + path);
        }
        final Path normalized = candidate.normalize();
        if (!normalized.startsWith(root) || normalized.equals(root)) {
            throw new IOException("Outside the runtime file system's root " + root + ": " + path);
        }
        // The deepest existing ancestor, resolved: a link there must not lead out of the root.
        Path existing = normalized.getParent();
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        if (existing == null || !existing.toRealPath().startsWith(root.toRealPath())) {
            throw new IOException("Outside the runtime file system's root " + root + ": " + path);
        }
        return normalized;
    }
}
