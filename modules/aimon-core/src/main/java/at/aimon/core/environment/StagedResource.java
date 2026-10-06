package at.aimon.core.environment;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.filesystem.VirtualFileSystem;

/**
 * A control-plane file set that an {@link ExecutionEnvironment} can {@link ExecutionEnvironment#stage stage} into its
 * own filesystem (design §4.4) — a skill directory, typically.
 *
 * <p>
 * <b>The file-set rule is fixed here, once.</b> {@link #scan} lists {@code sourceDir} recursively, drops paths
 * ignored by {@code sourceDir/.stageignore} ({@link StageIgnore}), fails on any remaining entry it cannot read (a
 * copy without it would be missing a file under {@code ${AIMON_SKILL_DIR}} with nothing reporting it), and records
 * exactly the relative paths it hashed in {@link #getFiles()}. The {@link #getContentKey() content key} is the SHA-256
 * (16 hex characters)
 * of the sorted {@code relPath + '\0' + bytes + '\0'} entries. A provider's {@code stage()} copies <b>exactly</b>
 * {@link #getFiles()} and nothing else; if one of those files cannot be read at copy time, or the bytes it copies no
 * longer hash to the content key, it fails without writing the {@code .staged} marker, so an incomplete or mislabelled
 * copy is never served.
 *
 * <p>
 * A source needs only the read side of {@link VirtualFileSystem}: {@code exists}, {@code isDirectory},
 * {@code listRecursive} and {@code read}/{@code openInputStream}. {@link #sizeOf} additionally asks for
 * {@code getMetadata}, and its callers do without it when the source has none.
 *
 * <p>
 * The key is computed when the registry loads the resource, not on every {@code stage()} call, so staging never
 * re-reads the whole directory just to decide whether a copy exists.
 *
 * <p>
 * <b>Scanned or assembled.</b> A resource {@link #scan} produced {@linkplain #isScanned() says so}: its file list
 * <em>is</em> the rule above applied to its directory, so a provider that finds the source changed may apply the rule
 * again and stage what is there now. A resource assembled through {@link #builder()} carries a file list its author
 * chose — possibly a deliberate subset of a directory that holds more — and no provider may widen it: it is staged as
 * recorded or refused. The builder cannot claim the first kind.
 */
public final class StagedResource {

    private static final Logger log = LoggerFactory.getLogger(StagedResource.class);

    /** Name of the gitignore-syntax file that excludes paths from staging. */
    public static final String STAGE_IGNORE_FILE = ".stageignore";

    /** Length of a {@link #getContentKey() content key}: 16 lowercase hex characters. */
    public static final int CONTENT_KEY_HEX_LENGTH = 16;

    private final VirtualFileSystem sourceFileSystem;
    private final String sourceDir;
    private final String contentKey;
    private final String name;
    private final long totalBytes;
    private final List<String> files;
    private final boolean scanned;

    private StagedResource(Builder builder) {
        this.sourceFileSystem = Objects.requireNonNull(builder.sourceFileSystem, "sourceFileSystem must not be null");
        this.sourceDir = Objects.requireNonNull(builder.sourceDir, "sourceDir must not be null");
        this.contentKey = Objects.requireNonNull(builder.contentKey, "contentKey must not be null");
        this.name = Objects.requireNonNull(builder.name, "name must not be null");
        this.totalBytes = builder.totalBytes;
        this.files = List.copyOf(builder.files);
        this.scanned = builder.scanned;
    }

    /**
     * Scans a directory into a staged resource: lists it, applies {@code .stageignore}, hashes every file left and
     * records exactly that file list.
     *
     * @param fileSystem
     *            the source filesystem (must not be null)
     * @param directory
     *            the directory on it (must not be null)
     * @param name
     *            the resource name, used as a path segment of the staged copy (must not be null)
     * @return the resource
     * @throws UncheckedIOException
     *             if a listed file that {@code .stageignore} does not exclude cannot be read
     * @throws RuntimeException
     *             whatever the source filesystem throws when the directory cannot be listed
     */
    public static StagedResource scan(VirtualFileSystem fileSystem, String directory, String name) {
        Objects.requireNonNull(fileSystem, "fileSystem must not be null");
        Objects.requireNonNull(directory, "directory must not be null");
        Objects.requireNonNull(name, "name must not be null");
        final String dir = trimTrailingSlash(directory);
        final List<String> relPaths = fileSet(fileSystem, dir);

        final ContentKeyBuilder hasher = new ContentKeyBuilder();
        final List<String> hashed = new ArrayList<>();
        long total = 0;
        for (String rel : relPaths) {
            final byte[] bytes;
            try (InputStream in = fileSystem.read(join(dir, rel))) {
                bytes = in.readAllBytes();
            } catch (IOException e) {
                throw new UncheckedIOException(unreadable(rel, name, e), e);
            } catch (RuntimeException e) {
                throw new UncheckedIOException(new IOException(unreadable(rel, name, e), e));
            }
            hasher.add(rel, bytes);
            hashed.add(rel);
            total += bytes.length;
        }
        final Builder builder = builder().sourceFileSystem(fileSystem).sourceDir(dir).name(name)
                .contentKey(hasher.build()).totalBytes(total).files(hashed);
        // Not a builder method: only this scan can say that the file list is the directory's.
        builder.scanned = true;
        return builder.build();
    }

    /**
     * The size {@link #scan} would record for a directory as it is now, taken from
     * {@link VirtualFileSystem#getMetadata file metadata}: the same file set — the listing less what
     * {@code .stageignore} excludes — and no file content read apart from {@code .stageignore} itself. It is how a
     * provider can tell that a resource is still too large to stage without reading it again.
     *
     * <p>
     * Unlike {@code scan}, this needs {@code getMetadata} of the source. A source that has only the read side throws
     * from here, and the caller falls back to scanning.
     *
     * @param fileSystem
     *            the source filesystem (must not be null)
     * @param directory
     *            the directory on it (must not be null)
     * @return the sum of the sizes of the files {@code scan} would hash; 0 for a missing directory
     * @throws RuntimeException
     *             whatever the source filesystem throws when the directory cannot be listed or a file's metadata
     *             cannot be read
     */
    public static long sizeOf(VirtualFileSystem fileSystem, String directory) {
        Objects.requireNonNull(fileSystem, "fileSystem must not be null");
        Objects.requireNonNull(directory, "directory must not be null");
        final String dir = trimTrailingSlash(directory);
        long total = 0;
        for (String rel : fileSet(fileSystem, dir)) {
            total += fileSystem.getMetadata(join(dir, rel)).getSize();
        }
        return total;
    }

    /** The file-set rule: the recursive listing, less what {@code .stageignore} excludes, sorted. */
    private static List<String> fileSet(VirtualFileSystem fileSystem, String dir) {
        final List<String> relPaths = new ArrayList<>();
        if (fileSystem.exists(dir) && fileSystem.isDirectory(dir)) {
            for (String path : fileSystem.listRecursive(dir)) {
                relPaths.add(relativize(dir, path));
            }
        }
        final StageIgnore ignore = readStageIgnore(fileSystem, dir);
        relPaths.removeIf(ignore::ignored);
        relPaths.sort(null);
        return relPaths;
    }

    private static String unreadable(String rel, String name, Exception e) {
        return "Cannot read '" + rel + "' of '" + name + "' while scanning for staging: " + e.getMessage()
                + "; exclude it with " + STAGE_IGNORE_FILE + " if it should not be staged";
    }

    /**
     * Reads the {@code .stageignore} of a directory, or returns a matcher that ignores nothing.
     *
     * @param fileSystem
     *            the source filesystem
     * @param directory
     *            the resource directory
     * @return the matcher (never null)
     */
    public static StageIgnore readStageIgnore(VirtualFileSystem fileSystem, String directory) {
        final String path = join(trimTrailingSlash(directory), STAGE_IGNORE_FILE);
        try {
            if (!fileSystem.exists(path)) {
                return StageIgnore.none();
            }
            try (InputStream in = fileSystem.read(path)) {
                return StageIgnore.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Could not read {}: {}; nothing is excluded", path, e.getMessage());
            return StageIgnore.none();
        }
    }

    /** @return the filesystem the resource is read from (read side only) */
    public VirtualFileSystem getSourceFileSystem() {
        return sourceFileSystem;
    }

    /** @return the resource's directory on {@link #getSourceFileSystem()} */
    public String getSourceDir() {
        return sourceDir;
    }

    /** @return the content hash, a path segment of the staged copy */
    public String getContentKey() {
        return contentKey;
    }

    /** @return the resource name, a path segment of the staged copy */
    public String getName() {
        return name;
    }

    /** @return the total size of {@link #getFiles()} in bytes */
    public long getTotalBytes() {
        return totalBytes;
    }

    /** @return the relative paths that were hashed — exactly what {@code stage()} copies */
    public List<String> getFiles() {
        return files;
    }

    /**
     * Whether {@link #scan} produced this resource, so that {@link #getFiles()} is everything under
     * {@link #getSourceDir()} that {@code .stageignore} does not exclude. Scanning the same directory again then
     * yields the same kind of resource, which is what lets a provider follow a source that changed since it was
     * loaded. {@code false} for a resource assembled through {@link #builder()}, whose file list is its author's
     * choice: nothing may be staged for it that it does not list.
     *
     * @return {@code true} only for a resource returned by {@link #scan}
     */
    public boolean isScanned() {
        return scanned;
    }

    /**
     * Returns the source path of one of {@link #getFiles()}.
     *
     * @param relPath
     *            a path relative to the resource directory
     * @return the path on {@link #getSourceFileSystem()}
     */
    public String sourcePath(String relPath) {
        return join(sourceDir, relPath);
    }

    /** @return a new builder */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String toString() {
        return "StagedResource{name='" + name + "', contentKey='" + contentKey + "', files=" + files.size()
                + ", totalBytes=" + totalBytes + ", scanned=" + scanned + '}';
    }

    private static String relativize(String dir, String path) {
        final String normalized = path.replace('\\', '/');
        if (dir.isEmpty() || ".".equals(dir)) {
            return stripLeadingSlashes(normalized);
        }
        final String prefix = stripLeadingSlashes(dir) + "/";
        final String candidate = stripLeadingSlashes(normalized);
        return candidate.startsWith(prefix) ? candidate.substring(prefix.length()) : candidate;
    }

    private static String join(String dir, String rel) {
        if (dir.isEmpty() || ".".equals(dir)) {
            return rel;
        }
        return dir + "/" + rel;
    }

    private static String trimTrailingSlash(String dir) {
        String d = dir;
        while (d.length() > 1 && d.endsWith("/")) {
            d = d.substring(0, d.length() - 1);
        }
        return d;
    }

    private static String stripLeadingSlashes(String s) {
        int i = 0;
        while (i < s.length() && s.charAt(i) == '/') {
            i++;
        }
        return s.substring(i);
    }

    /**
     * Computes a content key over {@code (relPath, bytes)} entries added in sorted path order. {@link #scan} and every
     * provider's copy verification use this one algorithm, so the hash of what was scanned and the hash of what was
     * copied can be compared.
     */
    public static final class ContentKeyBuilder {
        private final MessageDigest digest;

        /** Creates an empty hasher. */
        public ContentKeyBuilder() {
            try {
                this.digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 is not available", e);
            }
        }

        /**
         * Adds one entry. Entries must be added in ascending path order.
         *
         * @param relPath
         *            the path relative to the resource directory
         * @param bytes
         *            the file content
         * @return this hasher
         */
        public ContentKeyBuilder add(String relPath, byte[] bytes) {
            digest.update(relPath.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(bytes);
            digest.update((byte) 0);
            return this;
        }

        /** @return the content key (16 lowercase hex characters) */
        public String build() {
            return HexFormat.of().formatHex(digest.digest()).substring(0, CONTENT_KEY_HEX_LENGTH);
        }
    }

    /**
     * Builder for {@link StagedResource}. What it builds is never {@linkplain StagedResource#isScanned() scanned}:
     * there is no method to say so.
     */
    public static final class Builder {
        private VirtualFileSystem sourceFileSystem;
        private String sourceDir;
        private String contentKey;
        private String name;
        private long totalBytes;
        private List<String> files = List.of();
        /** Set by {@link StagedResource#scan} alone. */
        private boolean scanned;

        private Builder() {
        }

        /**
         * @param sourceFileSystem
         *            the source filesystem (required)
         * @return this builder
         */
        public Builder sourceFileSystem(VirtualFileSystem sourceFileSystem) {
            this.sourceFileSystem = sourceFileSystem;
            return this;
        }

        /**
         * @param sourceDir
         *            the source directory (required)
         * @return this builder
         */
        public Builder sourceDir(String sourceDir) {
            this.sourceDir = sourceDir;
            return this;
        }

        /**
         * @param contentKey
         *            the content key (required)
         * @return this builder
         */
        public Builder contentKey(String contentKey) {
            this.contentKey = contentKey;
            return this;
        }

        /**
         * @param name
         *            the resource name (required)
         * @return this builder
         */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /**
         * @param totalBytes
         *            the total size of the files
         * @return this builder
         */
        public Builder totalBytes(long totalBytes) {
            this.totalBytes = totalBytes;
            return this;
        }

        /**
         * @param files
         *            the relative paths to copy
         * @return this builder
         */
        public Builder files(List<String> files) {
            this.files = Objects.requireNonNull(files, "files must not be null");
            return this;
        }

        /** @return the resource */
        public StagedResource build() {
            return new StagedResource(this);
        }
    }
}
