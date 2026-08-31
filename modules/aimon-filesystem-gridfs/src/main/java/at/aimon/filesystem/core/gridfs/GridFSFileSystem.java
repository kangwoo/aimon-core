package at.aimon.filesystem.core.gridfs;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mongodb.MongoException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.gridfs.GridFSBucket;
import com.mongodb.client.gridfs.GridFSBuckets;
import com.mongodb.client.gridfs.GridFSUploadStream;
import com.mongodb.client.gridfs.model.GridFSFile;
import com.mongodb.client.gridfs.model.GridFSUploadOptions;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.Sorts;

import at.aimon.core.filesystem.BackendStatus;
import at.aimon.core.filesystem.BackendType;
import at.aimon.core.filesystem.FileMetadata;
import at.aimon.core.filesystem.FileSystemUsage;
import at.aimon.core.filesystem.SearchPatterns;
import at.aimon.core.filesystem.SizeLimitedOutputStream;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.filesystem.exception.BackendConnectionException;
import at.aimon.core.filesystem.exception.ConfigurationException;
import at.aimon.core.filesystem.exception.FileAlreadyExistsException;
import at.aimon.core.filesystem.exception.FileNotFoundException;
import at.aimon.core.filesystem.exception.InvalidPathException;
import at.aimon.core.filesystem.exception.VirtualFileSystemException;

/**
 * MongoDB GridFS implementation of VirtualFileSystem interface. Stores files in MongoDB using GridFS for efficient
 * large file storage. Thread-safe for concurrent operations.
 *
 * <h2>Directories</h2>
 *
 * <p>
 * GridFS stores a flat key space, so a directory has nothing to be unless something materializes it. This backend
 * writes a <b>directory marker</b>: a zero-length file whose name is the directory path with a trailing slash
 * ({@code "docs/"}) and whose metadata carries {@code type: "directory"}. A regular file's name never ends in a
 * slash, so a marker cannot collide with one, and file lookups filter markers out by that same shape.
 *
 * <p>
 * Markers exist so the {@link VirtualFileSystem} directory contract holds here as it does on a local disk — an empty
 * directory survives, {@link #exists(String)} answers for it, and {@link #list(String)} reports it. Directories
 * <em>implied</em> by a file's path need no marker and never get one: {@code docs} is a directory the moment
 * {@code docs/a.txt} exists, whether or not anyone created it. Both forms read identically from the outside, which is
 * why {@link #createDirectory(String)} stays idempotent and why data written by earlier versions of this class (which
 * had no markers at all) keeps working unchanged.
 *
 * <p>
 * <b>The marker shape is reserved.</b> Within a bucket this class manages, two things belong to AIMON and must not be
 * written by anything else: a {@code filename} ending in {@code /}, and {@code metadata.type} set to
 * {@value #DIRECTORY_MARKER_TYPE}. A foreign document of either shape is read back as a directory, so a real file
 * would become invisible to {@link #read(String)} while still occupying its name. This is a documented constraint, not
 * an enforced one — the alternatives (scanning the bucket at startup, or encoding markers in a separate collection)
 * both cost every deployment something to protect against a bucket AIMON was never meant to share. Point this
 * filesystem at its own bucket, which {@link GridFSConfig#getBucketName()} exists to make easy.
 *
 * <h2>Maximum file size</h2>
 *
 * <p>
 * When {@link GridFSConfig#getMaxFileSize()} is set, both write paths refuse content past the cap with
 * {@link at.aimon.core.filesystem.exception.InsufficientStorageException} and <b>abort the upload</b>, which reclaims
 * the chunks already sent and leaves the path holding its previous revision. Unlike the local backend — where a
 * rejected streaming write leaves the accepted prefix on disk — an over-sized write here stores nothing at all,
 * because GridFS can discard an incomplete upload as a unit.
 *
 * <h2>Connection ownership</h2>
 *
 * <p>
 * By default this class builds a {@code MongoClient} from {@link GridFSConfig#getConnectionString()} and closes it in
 * {@link #close()}. An application that already runs a client passes it to
 * {@link #GridFSFileSystem(GridFSConfig, MongoClient)}, and then owns it: {@link #close()} releases this filesystem's
 * own state and leaves the client — and its connection pool — alone. Whoever creates, closes.
 */
public final class GridFSFileSystem implements VirtualFileSystem {

    /** Value of {@code metadata.type} on the zero-length document that materializes a directory. */
    public static final String DIRECTORY_MARKER_TYPE = "directory";

    private static final Logger log = LoggerFactory.getLogger(GridFSFileSystem.class);

    /**
     * Newest revision first. GridFS allows several documents to share a filename, and a write leaves one behind for
     * as long as it takes to retire it, so "the file" has to be defined rather than picked arbitrarily. {@code _id}
     * breaks ties because {@code uploadDate} has millisecond granularity and two writes can land inside one.
     */
    private static final Bson NEWEST_REVISION_FIRST = Sorts.orderBy(Sorts.descending("uploadDate", "_id"));

    private final GridFSConfig config;
    private final Object lifecycleLock = new Object();
    private final boolean ownsClient;
    private volatile MongoClient mongoClient;
    private volatile GridFSBucket gridFSBucket;
    private volatile MongoCollection<Document> filesCollection;
    private volatile boolean initialized;
    private volatile boolean closed;

    /**
     * Creates a filesystem that opens — and closes — its own {@code MongoClient}.
     *
     * @param config
     *            configuration; its connection string is required on this path
     */
    public GridFSFileSystem(GridFSConfig config) {
        this.config = Objects.requireNonNull(config, "Config cannot be null");
        this.mongoClient = null;
        this.ownsClient = true;
        this.initialized = false;
        this.closed = false;
    }

    /**
     * Creates a filesystem over a {@code MongoClient} the caller owns. Nothing here closes that client, so several
     * filesystems (and the rest of the application) can share one connection pool. Pair it with
     * {@link GridFSConfig#forSharedClient(String)}, which drops the now-unused connection string.
     *
     * @param config
     *            configuration; only database, bucket and chunk size are read on this path
     * @param mongoClient
     *            an externally managed client, already configured and not closed for the lifetime of this filesystem
     */
    public GridFSFileSystem(GridFSConfig config, MongoClient mongoClient) {
        this.config = Objects.requireNonNull(config, "Config cannot be null");
        this.mongoClient = Objects.requireNonNull(mongoClient, "Mongo client cannot be null");
        this.ownsClient = false;
        this.initialized = false;
        this.closed = false;
    }

    @Override
    public void initialize() {
        synchronized (lifecycleLock) {
            if (initialized) {
                return;
            }
            if (closed) {
                throw new IllegalStateException("Cannot initialize closed VirtualFileSystem");
            }
            if (ownsClient && config.getConnectionString() == null) {
                throw new ConfigurationException("A connection string is required unless a MongoClient is supplied; "
                        + "use GridFSConfig.forSharedClient(...) with GridFSFileSystem(config, client)");
            }

            try {
                if (ownsClient) {
                    mongoClient = MongoClients.create(config.getConnectionString());
                }

                MongoDatabase database = mongoClient.getDatabase(config.getDatabaseName());

                gridFSBucket = GridFSBuckets.create(database, config.getBucketName());
                // The bucket's own index is on this collection; querying it directly is what lets listings project
                // filenames only instead of dragging every file document across the wire.
                filesCollection = database.getCollection(config.getBucketName() + ".files");

                // Test connection
                database.runCommand(new Document("ping", 1));

                initialized = true;
            } catch (MongoException e) {
                if (ownsClient && mongoClient != null) {
                    mongoClient.close();
                    mongoClient = null;
                }
                gridFSBucket = null;
                filesCollection = null;
                throw new BackendConnectionException(BackendType.GRIDFS,
                        "Failed to initialize GridFS filesystem: " + e.getMessage(), e);
            }
        }
    }

    @Override
    public void write(String path, InputStream content, long contentLength) {
        checkState();
        Objects.requireNonNull(path, "Path cannot be null");
        Objects.requireNonNull(content, "Content cannot be null");
        validateFilePath(path);

        try {
            rejectDirectoryCollision(path);

            Document metadata = new Document("path", path).append("uploadDate", Instant.now().toString());
            uploadReplacing(path, content, metadata);

        } catch (MongoException e) {
            throw new BackendConnectionException(BackendType.GRIDFS, "Failed to write file: " + path, e);
        }
    }

    @Override
    public InputStream read(String path) {
        checkState();
        Objects.requireNonNull(path, "Path cannot be null");
        validatePath(path);

        try {
            // Find file
            GridFSFile file = findFile(path);
            if (file == null) {
                throw notAFile(path);
            }

            // Download and return stream
            return gridFSBucket.openDownloadStream(file.getObjectId());

        } catch (VirtualFileSystemException e) {
            throw e;
        } catch (MongoException e) {
            throw new BackendConnectionException(BackendType.GRIDFS, "Failed to read file: " + path, e);
        }
    }

    @Override
    public void delete(String path) {
        checkState();
        Objects.requireNonNull(path, "Path cannot be null");
        validatePath(path);

        try {
            if (findFile(path) != null) {
                deleteAllRevisions(path);
                return;
            }

            // Not a file — it may still be an empty directory, which deletes, or a populated one, which does not.
            final String prefix = normalizeDirectory(path);
            if (prefix.isEmpty()) {
                throw new VirtualFileSystemException("Cannot delete VFS root directory");
            }
            final GridFSFile marker = findDirectoryMarker(prefix);
            final boolean populated = firstEntryUnder(prefix) != null;
            if (marker == null && !populated) {
                throw new FileNotFoundException(path);
            }
            if (populated) {
                throw new VirtualFileSystemException("Directory not empty: " + path);
            }
            gridFSBucket.delete(marker.getObjectId());

        } catch (VirtualFileSystemException e) {
            throw e;
        } catch (MongoException e) {
            throw new BackendConnectionException(BackendType.GRIDFS, "Failed to delete file: " + path, e);
        }
    }

    @Override
    public boolean exists(String path) {
        checkState();
        Objects.requireNonNull(path, "Path cannot be null");
        validatePath(path);

        try {
            return findFile(path) != null || isDirectoryPath(path);
        } catch (MongoException e) {
            throw new BackendConnectionException(BackendType.GRIDFS, "Failed to check existence: " + path, e);
        }
    }

    @Override
    public boolean isDirectory(String path) {
        checkState();
        Objects.requireNonNull(path, "Path cannot be null");
        validatePath(path);

        try {
            if (findFile(path) != null) {
                return false;
            }
            return isDirectoryPath(path);

        } catch (MongoException e) {
            throw new BackendConnectionException(BackendType.GRIDFS, "Failed to check directory: " + path, e);
        }
    }

    @Override
    public FileMetadata getMetadata(String path) {
        checkState();
        Objects.requireNonNull(path, "Path cannot be null");
        validatePath(path);

        try {
            GridFSFile file = findFile(path);
            if (file != null) {
                return FileMetadata.builder().path(path).size(file.getLength())
                        .createdAt(file.getUploadDate().toInstant()).modifiedAt(file.getUploadDate().toInstant())
                        .mimeType(detectMimeType(path)).build();
            }

            // A directory: created explicitly (a marker) or implied by something stored under it.
            final String prefix = normalizeDirectory(path);
            final GridFSFile marker = prefix.isEmpty() ? null : findDirectoryMarker(prefix);
            final GridFSFile firstChild = firstEntryUnder(prefix);
            if (marker == null && firstChild == null && !prefix.isEmpty()) {
                throw new FileNotFoundException(path);
            }
            final GridFSFile stampSource = marker != null ? marker : firstChild;
            final Instant stamp = stampSource != null ? stampSource.getUploadDate().toInstant() : Instant.EPOCH;
            return FileMetadata.builder().path(path).size(0).directory(true).createdAt(stamp).modifiedAt(stamp).build();

        } catch (FileNotFoundException e) {
            throw e;
        } catch (Exception e) {
            throw new BackendConnectionException(BackendType.GRIDFS, "Failed to get metadata: " + path, e);
        }
    }

    @Override
    public List<String> list(String directory) {
        checkState();
        Objects.requireNonNull(directory, "Directory cannot be null");
        validateDirectoryPath(directory);

        try {
            final String prefix = normalizeDirectory(directory);
            requireDirectory(directory, prefix);

            // A subdirectory can be reached two ways — its own marker, or a file deeper down implying it — and both
            // must produce the same single entry, hence the set.
            final Set<String> entries = new LinkedHashSet<>();
            for (String filename : filenamesUnder(prefix)) {
                if (filename.equals(prefix)) {
                    continue; // the marker of the directory being listed, not an entry in it
                }
                final String remainder = filename.substring(prefix.length());
                final int slash = remainder.indexOf('/');
                if (slash < 0) {
                    entries.add(filename);
                } else {
                    entries.add(prefix + remainder.substring(0, slash));
                }
            }

            return new ArrayList<>(entries);

        } catch (MongoException e) {
            throw new BackendConnectionException(BackendType.GRIDFS, "Failed to list directory: " + directory, e);
        }
    }

    @Override
    public List<String> listRecursive(String directory) {
        checkState();
        Objects.requireNonNull(directory, "Directory cannot be null");
        validateDirectoryPath(directory);

        try {
            final String prefix = normalizeDirectory(directory);
            requireDirectory(directory, prefix);

            final List<String> files = new ArrayList<>();
            for (String filename : filenamesUnder(prefix)) {
                if (!isMarkerName(filename)) {
                    files.add(filename);
                }
            }

            return files;

        } catch (MongoException e) {
            throw new BackendConnectionException(BackendType.GRIDFS,
                    "Failed to list directory recursively: " + directory, e);
        }
    }

    @Override
    public void copy(String sourcePath, String destinationPath, boolean overwrite) {
        checkState();
        Objects.requireNonNull(sourcePath, "Source path cannot be null");
        Objects.requireNonNull(destinationPath, "Destination path cannot be null");
        validatePath(sourcePath);
        validateFilePath(destinationPath);

        try {
            // Check source exists
            GridFSFile sourceFile = findFile(sourcePath);
            if (sourceFile == null) {
                throw new FileNotFoundException(sourcePath);
            }

            // Check destination
            if (!overwrite && findFile(destinationPath) != null) {
                throw new FileAlreadyExistsException(destinationPath);
            }
            rejectDirectoryCollision(destinationPath);

            // Copy by downloading and re-uploading. The destination's previous revisions are retired only after the
            // new one lands, so a copy that fails midway leaves the destination as it was.
            try (InputStream sourceStream = gridFSBucket.openDownloadStream(sourceFile.getObjectId())) {
                Document metadata = new Document("path", destinationPath).append("copiedFrom", sourcePath)
                        .append("uploadDate", Instant.now().toString());
                uploadReplacing(destinationPath, sourceStream, metadata);
            }

        } catch (VirtualFileSystemException e) {
            throw e;
        } catch (Exception e) {
            throw new BackendConnectionException(BackendType.GRIDFS,
                    "Failed to copy from " + sourcePath + " to " + destinationPath, e);
        }
    }

    @Override
    public void move(String sourcePath, String destinationPath, boolean overwrite) {
        checkState();
        Objects.requireNonNull(sourcePath, "Source path cannot be null");
        Objects.requireNonNull(destinationPath, "Destination path cannot be null");
        validatePath(sourcePath);
        validateFilePath(destinationPath);

        try {
            // Copy then delete source
            copy(sourcePath, destinationPath, overwrite);
            try {
                delete(sourcePath);
            } catch (Exception deleteEx) {
                // Compensate: remove the destination copy to avoid inconsistent state
                try {
                    deleteAllRevisions(destinationPath);
                } catch (Exception compensateEx) {
                    throw new VirtualFileSystemException(
                            "Move failed: source delete failed after copy, and compensation "
                                    + "also failed. State may be inconsistent for source=" + sourcePath
                                    + " destination=" + destinationPath,
                            compensateEx);
                }
                throw new BackendConnectionException(BackendType.GRIDFS,
                        "Move failed: could not delete source after copy: " + sourcePath, deleteEx);
            }

        } catch (VirtualFileSystemException e) {
            throw e;
        } catch (Exception e) {
            throw new BackendConnectionException(BackendType.GRIDFS,
                    "Failed to move from " + sourcePath + " to " + destinationPath, e);
        }
    }

    @Override
    public OutputStream openOutputStream(String path) {
        checkState();
        Objects.requireNonNull(path, "Path cannot be null");
        validateFilePath(path);

        try {
            rejectDirectoryCollision(path);

            return openUploadStream(path, new Document("path", path).append("uploadDate", Instant.now().toString()));

        } catch (MongoException e) {
            throw new BackendConnectionException(BackendType.GRIDFS, "Failed to open output stream: " + path, e);
        }
    }

    @Override
    public InputStream openInputStream(String path) {
        checkState();
        Objects.requireNonNull(path, "Path cannot be null");
        validatePath(path);

        try {
            GridFSFile file = findFile(path);
            if (file == null) {
                throw notAFile(path);
            }

            return gridFSBucket.openDownloadStream(file.getObjectId());

        } catch (VirtualFileSystemException e) {
            throw e;
        } catch (MongoException e) {
            throw new BackendConnectionException(BackendType.GRIDFS, "Failed to open input stream: " + path, e);
        }
    }

    @Override
    public void createDirectory(String path) {
        checkState();
        Objects.requireNonNull(path, "Path cannot be null");
        validatePath(path);

        final String normalized = normalizeDirectory(path);
        if (normalized.isEmpty()) {
            return; // Root directory — no-op
        }

        try {
            // mkdir -p: every level gets its own marker, so an intermediate directory is a directory in its own
            // right even when nothing is ever written into it.
            int slash = normalized.indexOf('/');
            while (slash >= 0) {
                final String levelMarker = normalized.substring(0, slash + 1);
                final String levelPath = normalized.substring(0, slash);
                if (findFile(levelPath) != null) {
                    throw new FileAlreadyExistsException(levelPath);
                }
                if (findDirectoryMarker(levelMarker) == null) {
                    writeDirectoryMarker(levelMarker);
                }
                slash = normalized.indexOf('/', slash + 1);
            }

        } catch (MongoException e) {
            throw new BackendConnectionException(BackendType.GRIDFS, "Failed to create directory: " + path, e);
        }
    }

    @Override
    public void deleteRecursive(String path) {
        checkState();
        Objects.requireNonNull(path, "Path cannot be null");
        validatePath(path);

        String normalizedPath = path.replace("\\", "/");
        while (normalizedPath.endsWith("/")) {
            normalizedPath = normalizedPath.substring(0, normalizedPath.length() - 1);
        }
        if (normalizedPath.isEmpty() || normalizedPath.equals(".")) {
            throw new VirtualFileSystemException("Cannot delete VFS root directory");
        }

        final List<ObjectId> toDelete = new ArrayList<>(revisionIds(normalizedPath));

        // The subtree, including this directory's own marker: the range starts at the prefix itself.
        try {
            for (GridFSFile file : gridFSBucket.find(underPrefix(normalizedPath + "/"))) {
                toDelete.add(file.getObjectId());
            }
        } catch (MongoException e) {
            throw new BackendConnectionException(BackendType.GRIDFS, "Failed to list for recursive delete: " + path, e);
        }

        if (toDelete.isEmpty()) {
            throw new FileNotFoundException(path);
        }

        int failureCount = 0;
        for (ObjectId id : toDelete) {
            try {
                gridFSBucket.delete(id);
            } catch (MongoException e) {
                failureCount++;
            }
        }

        if (failureCount > 0) {
            throw new VirtualFileSystemException(
                    "Recursive delete partially failed: " + failureCount + " items could not be deleted");
        }
    }

    @Override
    public List<String> search(String directory, String pattern, int maxResults) {
        checkState();
        Objects.requireNonNull(directory, "Directory cannot be null");
        Objects.requireNonNull(pattern, "Pattern cannot be null");
        validateDirectoryPath(directory);
        if (maxResults < 1) {
            throw new IllegalArgumentException("maxResults must be >= 1, got: " + maxResults);
        }

        final String prefix = normalizeDirectory(directory);

        String sanitized = SearchPatterns.sanitize(pattern);
        if (sanitized.isEmpty()) {
            return List.of();
        }

        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + sanitized);
        List<String> results = new ArrayList<>();

        try {
            requireDirectory(directory, prefix);

            for (String filename : filenamesUnder(prefix)) {
                if (isMarkerName(filename)) {
                    continue;
                }

                // Extract just the filename part
                int lastSlash = filename.lastIndexOf('/');
                String fileNameOnly = lastSlash >= 0 ? filename.substring(lastSlash + 1) : filename;

                if (matcher.matches(Paths.get(fileNameOnly))) {
                    results.add(filename);
                    if (results.size() >= maxResults) {
                        break;
                    }
                }
            }
        } catch (VirtualFileSystemException e) {
            throw e;
        } catch (MongoException e) {
            throw new BackendConnectionException(BackendType.GRIDFS, "Failed to search directory: " + directory, e);
        }

        return results;
    }

    @Override
    public FileSystemUsage getUsageSummary() {
        checkState();
        return usageUnder("");
    }

    @Override
    public FileSystemUsage getUsageSummary(String path) {
        checkState();
        Objects.requireNonNull(path, "Path cannot be null");
        validateDirectoryPath(path);

        final String prefix = normalizeDirectory(path);
        requireDirectory(path, prefix);
        return usageUnder(prefix);
    }

    @Override
    public String getWorkingDirectory() {
        return String.format("gridfs://%s/%s", config.getDatabaseName(), config.getBucketName());
    }

    @Override
    public BackendStatus getStatus() {
        if (closed) {
            return BackendStatus.disconnected(BackendType.GRIDFS, "VirtualFileSystem is closed");
        }
        if (!initialized) {
            return BackendStatus.unknown(BackendType.GRIDFS);
        }

        try {
            // Test connection with ping
            MongoDatabase database = mongoClient.getDatabase(config.getDatabaseName());
            database.runCommand(new Document("ping", 1));
            return BackendStatus.connected(BackendType.GRIDFS);
        } catch (Exception e) {
            return BackendStatus.disconnected(BackendType.GRIDFS, "Connection error: " + e.getMessage());
        }
    }

    @Override
    public void close() {
        synchronized (lifecycleLock) {
            if (!closed) {
                closed = true;
                // A borrowed client is left running: the application that opened it may still be using the pool.
                if (ownsClient && mongoClient != null) {
                    mongoClient.close();
                }
                mongoClient = null;
                gridFSBucket = null;
                filesCollection = null;
            }
        }
    }

    private void checkState() {
        if (closed) {
            throw new IllegalStateException("VirtualFileSystem is closed");
        }
        if (!initialized) {
            throw new IllegalStateException("VirtualFileSystem not initialized. Call initialize() first.");
        }
    }

    private void validatePath(String path) {
        if (path.isBlank()) {
            throw new InvalidPathException(path, "path cannot be empty or blank");
        }
        if (path.indexOf('\0') >= 0) {
            throw new InvalidPathException(path, "path contains null byte");
        }
        if (path.contains("../") || path.contains("..\\")) {
            throw new InvalidPathException(path, "path traversal is not allowed");
        }
    }

    /**
     * Path validation for operations that name a regular file. A trailing slash is the shape of a directory marker,
     * so accepting one here would let a write forge a directory.
     */
    private void validateFilePath(String path) {
        validatePath(path);
        if (path.endsWith("/") || path.endsWith("\\")) {
            throw new InvalidPathException(path, "file path cannot end with a separator");
        }
    }

    private void validateDirectoryPath(String directory) {
        if (directory.indexOf('\0') >= 0) {
            throw new InvalidPathException(directory, "path contains null byte");
        }
        if (directory.contains("../") || directory.contains("..\\")) {
            throw new InvalidPathException(directory, "path traversal is not allowed");
        }
    }

    private GridFSFile findFile(String path) {
        if (path.isEmpty() || isMarkerName(path)) {
            // Markers are not files. Filtering them out here is what keeps them invisible to read/delete/exists.
            return null;
        }
        return gridFSBucket.find(Filters.eq("filename", path)).sort(NEWEST_REVISION_FIRST).limit(1).first();
    }

    /**
     * The failure for a read of something that is not a regular file. A directory gets its own answer rather than
     * "not found", matching what a local filesystem reports and telling the caller which mistake it made.
     */
    private VirtualFileSystemException notAFile(String path) {
        if (isDirectoryPath(path)) {
            return new InvalidPathException(path, "Not a regular file");
        }
        return new FileNotFoundException(path);
    }

    private GridFSFile findDirectoryMarker(String prefix) {
        return gridFSBucket.find(Filters.eq("filename", prefix)).limit(1).first();
    }

    /**
     * First entry strictly below {@code prefix} — a file, or the marker of a subdirectory. The prefix's own marker is
     * excluded, which is what makes this "is the directory populated?" rather than "does it exist?".
     */
    private GridFSFile firstEntryUnder(String prefix) {
        final Bson filter = prefix.isEmpty()
                ? new Document()
                : Filters.and(underPrefix(prefix), Filters.ne("filename", prefix));
        return gridFSBucket.find(filter).limit(1).first();
    }

    private boolean isDirectoryPath(String path) {
        final String prefix = normalizeDirectory(path);
        if (prefix.isEmpty()) {
            return true; // the bucket root
        }
        return findDirectoryMarker(prefix) != null || firstEntryUnder(prefix) != null;
    }

    /**
     * Throws unless {@code prefix} names a directory that exists, mirroring what a local filesystem does when asked
     * to list something that is missing or is a regular file. Returning an empty list for either is the behavior this
     * replaces — it made a typo indistinguishable from an empty directory.
     */
    private void requireDirectory(String original, String prefix) {
        if (prefix.isEmpty()) {
            return; // the root always exists
        }
        if (findFile(prefix.substring(0, prefix.length() - 1)) != null) {
            throw new InvalidPathException(original, "Not a directory");
        }
        if (findDirectoryMarker(prefix) == null && firstEntryUnder(prefix) == null) {
            throw new FileNotFoundException(original);
        }
    }

    /**
     * Range predicate over the {@code filename} index, replacing an anchored regex so the query is a bounded index
     * scan rather than a scan of the whole bucket.
     *
     * <p>
     * The upper bound increments the prefix's last character. That is exact rather than approximate because a prefix
     * here always ends in {@code '/'} (0x2F), whose successor {@code '0'} (0x30) is a single ASCII byte: under
     * MongoDB's simple collation strings compare as UTF-8 bytes, so every name starting with the prefix sorts below
     * it and nothing else does. A sentinel like {@code "￿"} would not work — supplementary characters encode to
     * bytes above it.
     */
    private static Bson underPrefix(String prefix) {
        final char last = prefix.charAt(prefix.length() - 1);
        final String upperBound = prefix.substring(0, prefix.length() - 1) + (char) (last + 1);
        return Filters.and(Filters.gte("filename", prefix), Filters.lt("filename", upperBound));
    }

    /**
     * Filenames at or below {@code prefix}, projected so nothing but the name crosses the wire. An empty prefix means
     * the whole bucket.
     */
    private List<String> filenamesUnder(String prefix) {
        final Bson filter = prefix.isEmpty() ? new Document() : underPrefix(prefix);
        final List<String> names = new ArrayList<>();
        for (Document doc : filesCollection.find(filter).projection(Projections.include("filename"))) {
            final String filename = doc.getString("filename");
            if (filename != null) {
                names.add(filename);
            }
        }
        return names;
    }

    private FileSystemUsage usageUnder(String prefix) {
        try {
            // Size and count are folded server-side; markers are excluded because a directory is not a file. The
            // `$ne` also matches documents with no metadata at all, so files written before markers existed count.
            final Bson notMarker = Filters.ne("metadata.type", DIRECTORY_MARKER_TYPE);
            final Bson fileScope = prefix.isEmpty() ? notMarker : Filters.and(underPrefix(prefix), notMarker);
            final Document group = new Document("$group", new Document("_id", null)
                    .append("totalSize", new Document("$sum", "$length")).append("fileCount", new Document("$sum", 1)));
            final Document totals = filesCollection.aggregate(List.of(Aggregates.match(fileScope), group)).first();

            final long totalSize = totals == null ? 0L : ((Number) totals.get("totalSize")).longValue();
            final long fileCount = totals == null ? 0L : ((Number) totals.get("fileCount")).longValue();

            return FileSystemUsage.builder().totalSize(totalSize).fileCount(fileCount)
                    .directoryCount(countDirectories(prefix)).build();

        } catch (MongoException e) {
            throw new BackendConnectionException(BackendType.GRIDFS, "Failed to calculate usage summary", e);
        }
    }

    /**
     * Directories below {@code prefix}, counting the explicit and the implied as one set: {@code docs/a.txt} makes
     * {@code docs} a directory whether or not a marker for it was ever written. The prefix itself is not counted.
     */
    private long countDirectories(String prefix) {
        final Set<String> directories = new HashSet<>();
        for (String filename : filenamesUnder(prefix)) {
            if (filename.equals(prefix)) {
                continue;
            }
            final String remainder = filename.substring(prefix.length());
            int slash = remainder.indexOf('/');
            while (slash >= 0) {
                directories.add(remainder.substring(0, slash + 1));
                slash = remainder.indexOf('/', slash + 1);
            }
        }
        return directories.size();
    }

    /**
     * Uploads {@code path} and only then retires the revisions it replaces.
     *
     * <p>
     * The snapshot is taken <b>before</b> the upload on purpose: taken afterwards it would contain the revision just
     * written and delete it. Ordering it this way is the whole point — the previous delete-then-upload sequence
     * destroyed the original whenever the upload failed, which on a VFS backing an agent's workspace means a file
     * disappears because the network hiccuped.
     */
    private void uploadReplacing(String path, InputStream content, Document metadata) {
        final SupersedingUploadStream out = openUploadStream(path, metadata);
        try {
            content.transferTo(out);
            out.close();
        } catch (IOException e) {
            out.abortQuietly();
            throw new BackendConnectionException(BackendType.GRIDFS, "Failed to write file: " + path, e);
        } catch (RuntimeException e) {
            out.abortQuietly();
            throw e;
        }
    }

    /**
     * Opens the upload stream both write paths go through, so the size cap is measured in exactly one place and a
     * bulk write cannot disagree with a streaming one about where the limit is.
     *
     * <p>
     * This is why the bulk path copies by hand instead of calling {@code GridFSBucket.uploadFromStream}: that helper
     * catches whatever the copy throws and rethrows it wrapped in a {@code MongoGridFSException}, which would turn a
     * size rejection into a backend error rather than the {@code InsufficientStorageException} the contract promises.
     * Aborting on failure is kept — {@link #uploadReplacing} does it explicitly.
     */
    private SupersedingUploadStream openUploadStream(String path, Document metadata) {
        final List<ObjectId> superseded = revisionIds(path);
        final GridFSUploadOptions options = new GridFSUploadOptions().chunkSizeBytes(config.getChunkSizeBytes())
                .metadata(metadata);
        return new SupersedingUploadStream(path, gridFSBucket.openUploadStream(path, options), superseded,
                config.getMaxFileSize());
    }

    private List<ObjectId> revisionIds(String path) {
        final List<ObjectId> ids = new ArrayList<>();
        for (GridFSFile file : gridFSBucket.find(Filters.eq("filename", path))) {
            ids.add(file.getObjectId());
        }
        return ids;
    }

    private void deleteAllRevisions(String path) {
        for (ObjectId id : revisionIds(path)) {
            gridFSBucket.delete(id);
        }
    }

    /**
     * Removes superseded revisions without failing the write that superseded them. The new revision is already
     * durable and wins every lookup, so a stranded old one costs storage, not correctness.
     */
    private void retireQuietly(List<ObjectId> ids) {
        for (ObjectId id : ids) {
            try {
                gridFSBucket.delete(id);
            } catch (MongoException e) {
                log.warn("Failed to remove superseded GridFS revision {}: {}", id, e.getMessage());
            }
        }
    }

    private void writeDirectoryMarker(String markerName) {
        final Document metadata = new Document("type", DIRECTORY_MARKER_TYPE)
                .append("path", markerName.substring(0, markerName.length() - 1))
                .append("uploadDate", Instant.now().toString());
        final GridFSUploadOptions options = new GridFSUploadOptions().chunkSizeBytes(config.getChunkSizeBytes())
                .metadata(metadata);
        gridFSBucket.uploadFromStream(markerName, InputStream.nullInputStream(), options);
    }

    private void rejectDirectoryCollision(String path) {
        if (isDirectoryPath(path)) {
            throw new InvalidPathException(path, "path is a directory");
        }
    }

    private static boolean isMarkerName(String filename) {
        return filename.endsWith("/");
    }

    private String normalizeDirectory(String directory) {
        if (directory.isEmpty() || directory.equals(".")) {
            return "";
        }
        String normalized = directory.replace("\\", "/");
        if (!normalized.endsWith("/")) {
            normalized += "/";
        }
        return normalized;
    }

    private String detectMimeType(String path) {
        int lastDot = path.lastIndexOf('.');
        if (lastDot == -1 || lastDot == path.length() - 1) {
            return null;
        }

        String extension = path.substring(lastDot + 1).toLowerCase();
        return switch (extension) {
            case "txt" -> "text/plain";
            case "pdf" -> "application/pdf";
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "json" -> "application/json";
            case "xml" -> "application/xml";
            default -> null;
        };
    }

    /**
     * Defers retiring the revisions this upload replaces until the stream closes successfully, so an aborted or
     * failed stream leaves the previous content in place. Same upload-then-retire ordering as
     * {@link #uploadReplacing}, spread over the lifetime of a stream.
     *
     * <p>
     * It is also where the configured {@code maxFileSize} is enforced, by extending
     * {@link SizeLimitedOutputStream}. Wrapping the stream from outside would not do: the caller's
     * try-with-resources would still {@code close()} the upload after a rejection and finalize a truncated file.
     * Overflow instead {@linkplain #onLimitExceeded() aborts} the upload, which reclaims the chunks already sent and
     * skips retiring the superseded revisions — so an over-sized write leaves the path exactly as it was.
     */
    private final class SupersedingUploadStream extends SizeLimitedOutputStream {

        private final String path;
        private final GridFSUploadStream upload;
        private final List<ObjectId> superseded;

        /** Whether the upload has been finalized or abandoned; either way there is nothing left to close. */
        private boolean settled;

        SupersedingUploadStream(String path, GridFSUploadStream upload, List<ObjectId> superseded, long maxFileSize) {
            super(upload, maxFileSize);
            this.path = path;
            this.upload = upload;
            this.superseded = superseded;
        }

        @Override
        protected void onLimitExceeded() {
            abortQuietly();
        }

        /** Discards the upload, keeping the previous revisions. Safe to call after the stream is already settled. */
        void abortQuietly() {
            if (settled) {
                return;
            }
            settled = true;
            try {
                upload.abort();
            } catch (MongoException e) {
                // Same trade-off as retireQuietly: a stranded upload costs storage, not correctness, and the caller
                // is already being told why its write failed.
                log.warn("Failed to abort GridFS upload for {}: {}", path, e.getMessage());
            }
        }

        @Override
        public void close() throws IOException {
            if (settled) {
                return;
            }
            settled = true;
            upload.close();
            retireQuietly(superseded);
        }
    }
}
