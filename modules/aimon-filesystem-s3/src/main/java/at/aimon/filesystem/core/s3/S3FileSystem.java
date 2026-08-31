package at.aimon.filesystem.core.s3;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

import at.aimon.core.filesystem.BackendStatus;
import at.aimon.core.filesystem.BackendType;
import at.aimon.core.filesystem.FileMetadata;
import at.aimon.core.filesystem.FileSystemUsage;
import at.aimon.core.filesystem.SearchPatterns;
import at.aimon.core.filesystem.SizeLimitedOutputStream;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.filesystem.exception.BackendConnectionException;
import at.aimon.core.filesystem.exception.FileAlreadyExistsException;
import at.aimon.core.filesystem.exception.FileNotFoundException;
import at.aimon.core.filesystem.exception.InsufficientStorageException;
import at.aimon.core.filesystem.exception.InvalidPathException;
import at.aimon.core.filesystem.exception.VirtualFileSystemException;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * AWS S3 implementation of VirtualFileSystem interface. Stores files in S3-compatible object storage. Thread-safe for
 * concurrent operations.
 *
 * <h2>Maximum file size</h2>
 *
 * <p>
 * When {@link S3Config#getMaxFileSize()} is set, both write paths refuse content past the cap with
 * {@link InsufficientStorageException} and upload nothing, so the key keeps whatever it held before. Neither path
 * needs a remote rollback: {@link #write(String, InputStream, long)} rejects before issuing the request, and
 * {@link #openOutputStream(String)} buffers in memory and never puts an object it has already refused.
 */
public final class S3FileSystem implements VirtualFileSystem {
    private final S3Config config;
    private final Object lifecycleLock = new Object();
    private volatile S3Client s3Client;
    private volatile boolean initialized;
    private volatile boolean closed;

    /** S3FileSystem을 생성한다. */
    public S3FileSystem(S3Config config) {
        Objects.requireNonNull(config, "Config cannot be null");
        this.config = config;
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

            try {
                // Create S3 client
                S3ClientBuilder builder = S3Client.builder().region(Region.of(config.getRegion()))
                        .credentialsProvider(StaticCredentialsProvider
                                .create(AwsBasicCredentials.create(config.getAccessKey(), config.getSecretKey())));

                // Set custom endpoint if provided (for S3-compatible services)
                if (config.hasCustomEndpoint()) {
                    builder.endpointOverride(URI.create(config.getEndpoint()));
                    builder.serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build());
                }

                s3Client = builder.build();

                // Test connection by checking if bucket exists
                HeadBucketRequest headBucketRequest = HeadBucketRequest.builder().bucket(config.getBucketName())
                        .build();

                s3Client.headBucket(headBucketRequest);

                initialized = true;

            } catch (S3Exception e) {
                if (s3Client != null) {
                    s3Client.close();
                    s3Client = null;
                }
                throw new BackendConnectionException(BackendType.S3,
                        "Failed to initialize S3 filesystem: " + e.getMessage(), e);
            }
        }
    }

    @Override
    public void write(String path, InputStream content, long contentLength) {
        checkState();
        Objects.requireNonNull(path, "Path cannot be null");
        Objects.requireNonNull(content, "Content cannot be null");
        validatePath(path);

        // Checking the declared length is exact here rather than optimistic: the SDK needs a known content length for
        // a single-shot put and reads precisely that many bytes, so what would be stored is contentLength bytes —
        // never more, even if the stream has more to give. Rejecting before the request means nothing is uploaded.
        if (config.hasMaxFileSize() && contentLength > config.getMaxFileSize()) {
            throw new InsufficientStorageException("File size " + contentLength
                    + " bytes exceeds maximum allowed size of " + config.getMaxFileSize() + " bytes");
        }

        try {
            PutObjectRequest putObjectRequest = PutObjectRequest.builder().bucket(config.getBucketName()).key(path)
                    .build();

            s3Client.putObject(putObjectRequest, RequestBody.fromInputStream(content, contentLength));

        } catch (S3Exception e) {
            throw new BackendConnectionException(BackendType.S3, "Failed to write file: " + path, e);
        }
    }

    @Override
    public InputStream read(String path) {
        checkState();
        Objects.requireNonNull(path, "Path cannot be null");
        validatePath(path);

        try {
            GetObjectRequest getObjectRequest = GetObjectRequest.builder().bucket(config.getBucketName()).key(path)
                    .build();

            return s3Client.getObject(getObjectRequest, ResponseTransformer.toInputStream());

        } catch (NoSuchKeyException e) {
            throw new FileNotFoundException(path);
        } catch (S3Exception e) {
            throw new BackendConnectionException(BackendType.S3, "Failed to read file: " + path, e);
        }
    }

    @Override
    public void delete(String path) {
        checkState();
        Objects.requireNonNull(path, "Path cannot be null");
        validatePath(path);

        try {
            // Check if exists first
            if (!exists(path)) {
                throw new FileNotFoundException(path);
            }

            DeleteObjectRequest deleteObjectRequest = DeleteObjectRequest.builder().bucket(config.getBucketName())
                    .key(path).build();

            s3Client.deleteObject(deleteObjectRequest);

        } catch (FileNotFoundException e) {
            throw e;
        } catch (S3Exception e) {
            throw new BackendConnectionException(BackendType.S3, "Failed to delete file: " + path, e);
        }
    }

    @Override
    public boolean exists(String path) {
        checkState();
        Objects.requireNonNull(path, "Path cannot be null");
        validatePath(path);

        try {
            HeadObjectRequest headObjectRequest = HeadObjectRequest.builder().bucket(config.getBucketName()).key(path)
                    .build();

            s3Client.headObject(headObjectRequest);
            return true;

        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            throw new BackendConnectionException(BackendType.S3, "Failed to check existence: " + path, e);
        }
    }

    @Override
    public boolean isDirectory(String path) {
        checkState();
        Objects.requireNonNull(path, "Path cannot be null");
        validatePath(path);

        try {
            // S3 doesn't have real directories, only keys with path-like names
            // A path is considered a directory if:
            // 1. It's not an actual object
            // 2. There are objects that start with this path + "/"
            if (exists(path)) {
                return false;
            }
            return findFirstObjectInDirectory(path) != null;

        } catch (S3Exception e) {
            throw new BackendConnectionException(BackendType.S3, "Failed to check directory: " + path, e);
        }
    }

    @Override
    public FileMetadata getMetadata(String path) {
        checkState();
        Objects.requireNonNull(path, "Path cannot be null");
        validatePath(path);

        try {
            HeadObjectRequest headObjectRequest = HeadObjectRequest.builder().bucket(config.getBucketName()).key(path)
                    .build();

            HeadObjectResponse response = s3Client.headObject(headObjectRequest);

            return FileMetadata.builder().path(path).size(response.contentLength()).createdAt(response.lastModified())
                    .modifiedAt(response.lastModified()).mimeType(response.contentType()).build();

        } catch (NoSuchKeyException e) {
            // Check if this path is a virtual directory
            try {
                S3Object firstChild = findFirstObjectInDirectory(path);
                if (firstChild != null) {
                    Instant childTimestamp = firstChild.lastModified();
                    return FileMetadata.builder().path(path).size(0).directory(true).createdAt(childTimestamp)
                            .modifiedAt(childTimestamp).build();
                }
            } catch (S3Exception s3e) {
                throw new BackendConnectionException(BackendType.S3, "Failed to check directory: " + path, s3e);
            }
            throw new FileNotFoundException(path);
        } catch (S3Exception e) {
            throw new BackendConnectionException(BackendType.S3, "Failed to get metadata: " + path, e);
        }
    }

    @Override
    public List<String> list(String directory) {
        checkState();
        Objects.requireNonNull(directory, "Directory cannot be null");
        validateDirectoryPath(directory);

        try {
            String prefix = normalizeDirectory(directory);
            List<String> files = new ArrayList<>();

            ListObjectsV2Request listRequest = ListObjectsV2Request.builder().bucket(config.getBucketName())
                    .prefix(prefix).delimiter("/").build();

            forEachObject(listRequest, object -> {
                String key = object.key();
                // Skip directory markers
                if (!key.endsWith("/") && !key.equals(prefix)) {
                    files.add(key);
                }
                return true;
            });

            return files;

        } catch (S3Exception e) {
            throw new BackendConnectionException(BackendType.S3, "Failed to list directory: " + directory, e);
        }
    }

    @Override
    public List<String> listRecursive(String directory) {
        checkState();
        Objects.requireNonNull(directory, "Directory cannot be null");
        validateDirectoryPath(directory);

        try {
            String prefix = normalizeDirectory(directory);
            List<String> files = new ArrayList<>();

            ListObjectsV2Request listRequest = ListObjectsV2Request.builder().bucket(config.getBucketName())
                    .prefix(prefix).build();

            forEachObject(listRequest, object -> {
                String key = object.key();
                // Skip directory markers
                if (!key.endsWith("/") && !key.equals(prefix)) {
                    files.add(key);
                }
                return true;
            });

            return files;

        } catch (S3Exception e) {
            throw new BackendConnectionException(BackendType.S3, "Failed to list directory recursively: " + directory,
                    e);
        }
    }

    @Override
    public void copy(String sourcePath, String destinationPath, boolean overwrite) {
        checkState();
        Objects.requireNonNull(sourcePath, "Source path cannot be null");
        Objects.requireNonNull(destinationPath, "Destination path cannot be null");
        validatePath(sourcePath);
        validatePath(destinationPath);

        try {
            // Check source exists
            if (!exists(sourcePath)) {
                throw new FileNotFoundException(sourcePath);
            }

            // Check destination
            if (!overwrite && exists(destinationPath)) {
                throw new FileAlreadyExistsException(destinationPath);
            }

            // Copy object
            CopyObjectRequest copyObjectRequest = CopyObjectRequest.builder().sourceBucket(config.getBucketName())
                    .sourceKey(sourcePath).destinationBucket(config.getBucketName()).destinationKey(destinationPath)
                    .build();

            s3Client.copyObject(copyObjectRequest);

        } catch (FileNotFoundException | FileAlreadyExistsException e) {
            throw e;
        } catch (S3Exception e) {
            throw new BackendConnectionException(BackendType.S3,
                    "Failed to copy from " + sourcePath + " to " + destinationPath, e);
        }
    }

    @Override
    public void move(String sourcePath, String destinationPath, boolean overwrite) {
        checkState();
        Objects.requireNonNull(sourcePath, "Source path cannot be null");
        Objects.requireNonNull(destinationPath, "Destination path cannot be null");
        validatePath(sourcePath);
        validatePath(destinationPath);

        try {
            // Copy then delete source
            copy(sourcePath, destinationPath, overwrite);
            try {
                delete(sourcePath);
            } catch (Exception deleteEx) {
                // Compensate: remove the destination copy to avoid inconsistent state
                try {
                    deleteIfExists(destinationPath);
                } catch (Exception compensateEx) {
                    throw new VirtualFileSystemException(
                            "Move failed: source delete failed after copy, and compensation "
                                    + "also failed. State may be inconsistent for source=" + sourcePath
                                    + " destination=" + destinationPath,
                            compensateEx);
                }
                throw new BackendConnectionException(BackendType.S3,
                        "Move failed: could not delete source after copy: " + sourcePath, deleteEx);
            }

        } catch (VirtualFileSystemException e) {
            throw e;
        } catch (Exception e) {
            throw new BackendConnectionException(BackendType.S3,
                    "Failed to move from " + sourcePath + " to " + destinationPath, e);
        }
    }

    @Override
    public OutputStream openOutputStream(String path) {
        checkState();
        Objects.requireNonNull(path, "Path cannot be null");
        validatePath(path);

        // S3 doesn't support streaming uploads in the same way
        // Return a custom OutputStream that buffers and uploads on close
        return new S3OutputStream(path, new ByteArrayOutputStream(), config.getMaxFileSize());
    }

    @Override
    public InputStream openInputStream(String path) {
        checkState();
        Objects.requireNonNull(path, "Path cannot be null");
        validatePath(path);

        return read(path);
    }

    @Override
    public void createDirectory(String path) {
        checkState();
        Objects.requireNonNull(path, "Path cannot be null");
        validatePath(path);

        String normalizedPath = normalizeDirectory(path);
        if (normalizedPath.isEmpty()) {
            return; // Root directory — no-op
        }

        // Check if a file (not directory) already exists at this path
        if (exists(path)) {
            throw new FileAlreadyExistsException(path);
        }

        // Check if directory marker already exists — idempotent no-op
        if (exists(normalizedPath)) {
            return;
        }

        // Create zero-byte directory marker
        try {
            PutObjectRequest putRequest = PutObjectRequest.builder().bucket(config.getBucketName()).key(normalizedPath)
                    .contentLength(0L).build();
            s3Client.putObject(putRequest, RequestBody.fromBytes(new byte[0]));
        } catch (S3Exception e) {
            throw new BackendConnectionException(BackendType.S3, "Failed to create directory: " + path, e);
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

        List<String> keysToDelete = new ArrayList<>();

        // Check exact file match
        if (exists(normalizedPath)) {
            keysToDelete.add(normalizedPath);
        }

        // Check directory contents (prefix match)
        String prefix = normalizedPath + "/";
        try {
            ListObjectsV2Request listRequest = ListObjectsV2Request.builder().bucket(config.getBucketName())
                    .prefix(prefix).build();

            forEachObject(listRequest, object -> {
                keysToDelete.add(object.key());
                return true;
            });

        } catch (S3Exception e) {
            throw new BackendConnectionException(BackendType.S3, "Failed to list for recursive delete: " + path, e);
        }

        if (keysToDelete.isEmpty()) {
            throw new FileNotFoundException(path);
        }

        // Delete collected keys in batches (S3 supports up to 1000 per request)
        int failureCount = 0;
        try {
            for (int i = 0; i < keysToDelete.size(); i += 1000) {
                List<ObjectIdentifier> batch = new ArrayList<>();
                for (int j = i; j < Math.min(i + 1000, keysToDelete.size()); j++) {
                    batch.add(ObjectIdentifier.builder().key(keysToDelete.get(j)).build());
                }

                DeleteObjectsRequest deleteRequest = DeleteObjectsRequest.builder().bucket(config.getBucketName())
                        .delete(Delete.builder().objects(batch).quiet(true).build()).build();

                DeleteObjectsResponse deleteResponse = s3Client.deleteObjects(deleteRequest);
                failureCount += deleteResponse.errors().size();
            }
        } catch (S3Exception e) {
            throw new BackendConnectionException(BackendType.S3, "Failed to delete recursively: " + path, e);
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

        String prefix = normalizeDirectory(directory);

        // Check directory existence for non-root
        if (!prefix.isEmpty()) {
            try {
                ListObjectsV2Request checkRequest = ListObjectsV2Request.builder().bucket(config.getBucketName())
                        .prefix(prefix).maxKeys(1).build();
                ListObjectsV2Response checkResponse = s3Client.listObjectsV2(checkRequest);
                if (checkResponse.contents().isEmpty()) {
                    throw new FileNotFoundException(directory);
                }
            } catch (FileNotFoundException e) {
                throw e;
            } catch (S3Exception e) {
                throw new BackendConnectionException(BackendType.S3, "Failed to check directory: " + directory, e);
            }
        }

        String sanitized = SearchPatterns.sanitize(pattern);
        if (sanitized.isEmpty()) {
            return List.of();
        }

        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + sanitized);
        List<String> results = new ArrayList<>();

        try {
            ListObjectsV2Request listRequest = ListObjectsV2Request.builder().bucket(config.getBucketName())
                    .prefix(prefix).build();

            forEachObject(listRequest, object -> {
                String key = object.key();
                // Skip directory markers
                if (key.endsWith("/")) {
                    return true;
                }

                int lastSlash = key.lastIndexOf('/');
                String fileName = lastSlash >= 0 ? key.substring(lastSlash + 1) : key;

                if (matcher.matches(Paths.get(fileName))) {
                    results.add(key);
                    if (results.size() >= maxResults) {
                        return false; // stop early
                    }
                }
                return true;
            });

        } catch (S3Exception e) {
            throw new BackendConnectionException(BackendType.S3, "Failed to search directory: " + directory, e);
        }

        return results;
    }

    @Override
    public FileSystemUsage getUsageSummary() {
        checkState();

        long[] totalSize = {0};
        long[] fileCount = {0};
        Set<String> directories = new HashSet<>();

        try {
            ListObjectsV2Request listRequest = ListObjectsV2Request.builder().bucket(config.getBucketName()).build();

            forEachObject(listRequest, object -> {
                String key = object.key();
                // Skip directory markers
                if (key.endsWith("/")) {
                    return true;
                }

                fileCount[0]++;
                totalSize[0] += object.size();

                // Derive directories from path
                int slashIdx = key.indexOf('/');
                while (slashIdx >= 0) {
                    directories.add(key.substring(0, slashIdx + 1));
                    slashIdx = key.indexOf('/', slashIdx + 1);
                }
                return true;
            });

        } catch (S3Exception e) {
            throw new BackendConnectionException(BackendType.S3, "Failed to calculate usage summary", e);
        }

        return FileSystemUsage.builder().totalSize(totalSize[0]).fileCount(fileCount[0])
                .directoryCount(directories.size()).build();
    }

    @Override
    public String getWorkingDirectory() {
        return String.format("s3://%s", config.getBucketName());
    }

    @Override
    public BackendStatus getStatus() {
        if (closed) {
            return BackendStatus.disconnected(BackendType.S3, "VirtualFileSystem is closed");
        }
        if (!initialized) {
            return BackendStatus.unknown(BackendType.S3);
        }

        try {
            // Test connection
            HeadBucketRequest headBucketRequest = HeadBucketRequest.builder().bucket(config.getBucketName()).build();

            s3Client.headBucket(headBucketRequest);
            return BackendStatus.connected(BackendType.S3);

        } catch (Exception e) {
            return BackendStatus.disconnected(BackendType.S3, "Connection error: " + e.getMessage());
        }
    }

    @Override
    public void close() {
        synchronized (lifecycleLock) {
            if (!closed) {
                closed = true;
                if (s3Client != null) {
                    s3Client.close();
                    s3Client = null;
                }
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

    private void validateDirectoryPath(String directory) {
        if (directory.indexOf('\0') >= 0) {
            throw new InvalidPathException(directory, "path contains null byte");
        }
        if (directory.contains("../") || directory.contains("..\\")) {
            throw new InvalidPathException(directory, "path traversal is not allowed");
        }
    }

    /**
     * Finds the first S3 object whose key starts with the given path as a directory prefix. Used to detect virtual
     * directories in S3 where directories are implicit in object keys.
     *
     * @param path
     *            Directory path to check
     * @return First matching S3Object, or null if no objects exist under this path
     */
    private S3Object findFirstObjectInDirectory(String path) {
        String prefix = path.endsWith("/") ? path : path + "/";
        ListObjectsV2Request listRequest = ListObjectsV2Request.builder().bucket(config.getBucketName()).prefix(prefix)
                .maxKeys(1).build();
        ListObjectsV2Response response = s3Client.listObjectsV2(listRequest);
        List<S3Object> contents = response.contents();
        return contents.isEmpty() ? null : contents.get(0);
    }

    private void deleteIfExists(String path) {
        if (exists(path)) {
            DeleteObjectRequest request = DeleteObjectRequest.builder().bucket(config.getBucketName()).key(path)
                    .build();
            s3Client.deleteObject(request);
        }
    }

    private String normalizeDirectory(String directory) {
        if (directory == null || directory.isEmpty() || directory.equals(".")) {
            return "";
        }
        String normalized = directory.replace("\\", "/");
        if (!normalized.endsWith("/")) {
            normalized += "/";
        }
        return normalized;
    }

    /**
     * Iterates over S3 objects matching the given request, passing each to the processor. Handles pagination
     * automatically via continuation tokens.
     *
     * @param initialRequest
     *            Initial list request (may be paginated)
     * @param processor
     *            Receives each S3Object; returns true to continue, false to stop early
     */
    private void forEachObject(ListObjectsV2Request initialRequest, Predicate<S3Object> processor) {
        ListObjectsV2Response response;
        String continuationToken = null;

        do {
            ListObjectsV2Request request = continuationToken != null
                    ? initialRequest.toBuilder().continuationToken(continuationToken).build()
                    : initialRequest;

            response = s3Client.listObjectsV2(request);

            for (S3Object object : response.contents()) {
                if (!processor.test(object)) {
                    return;
                }
            }

            continuationToken = response.nextContinuationToken();
        } while (response.isTruncated());
    }

    /**
     * Custom OutputStream for S3 that buffers content and uploads on close.
     *
     * <p>
     * The configured {@code maxFileSize} is enforced against the buffer as it fills, so an over-sized write is
     * rejected at the offending {@code write} call rather than at {@code close()} — the caller learns which write went
     * too far, and the upload never happens. Rolling back costs nothing here because nothing has left the JVM yet.
     */
    private class S3OutputStream extends SizeLimitedOutputStream {
        private final String path;
        private final ByteArrayOutputStream buffer;
        private boolean closed;
        private boolean aborted;

        S3OutputStream(String path, ByteArrayOutputStream buffer, long maxFileSize) {
            super(buffer, maxFileSize);
            this.path = path;
            this.buffer = buffer;
        }

        @Override
        public void write(int b) throws IOException {
            ensureOpen();
            super.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            ensureOpen();
            super.write(b, off, len);
        }

        @Override
        protected void onLimitExceeded() {
            aborted = true;
            buffer.reset();
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            if (!aborted) {
                byte[] data = buffer.toByteArray();
                S3FileSystem.this.write(path, new ByteArrayInputStream(data), data.length);
            }
        }

        private void ensureOpen() throws IOException {
            if (closed) {
                throw new IOException("Stream already closed");
            }
        }
    }
}
