package at.aimon.memory.postgres.internal;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import at.aimon.core.filesystem.BackendStatus;
import at.aimon.core.filesystem.FileMetadata;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.filesystem.exception.FileNotFoundException;

/**
 * Internal in-memory {@link VirtualFileSystem} used by
 * {@code KnowledgeStoreOutboxRelay} to ferry outbox payloads into
 * {@link at.aimon.core.knowledge.KnowledgeStore#reindex} calls.
 *
 * <p>
 * This is a copy of the design implemented by
 * {@code at.aimon.core.memory.index.StagingFileSystem} (which is
 * package-private and therefore not reusable across modules). Only the subset
 * of operations actually invoked by
 * {@link at.aimon.core.knowledge.KeywordKnowledgeStore} during indexing is
 * implemented (write-string via {@link #put}, read, list, listRecursive,
 * isDirectory). Every other method throws
 * {@link UnsupportedOperationException} on purpose — this is not a
 * general-purpose VFS, it is private plumbing.
 *
 * <p>
 * Each relay uses a fresh instance per row so concurrent reindex calls do not
 * trample each other. The staging directory layout matches
 * {@code KnowledgeStoreObservationIndex}: {@code /observations/{subjectKey}/{localId}.txt}.
 */
public final class OutboxStagingFileSystem implements VirtualFileSystem {

    private final Map<String, byte[]> files = new ConcurrentHashMap<>();

    /**
     * Stages a UTF-8 encoded payload at the given path.
     *
     * @param path
     *            absolute VFS path (must not be null)
     * @param content
     *            payload (must not be null)
     */
    public void put(String path, String content) {
        Objects.requireNonNull(path, "path cannot be null");
        Objects.requireNonNull(content, "content cannot be null");
        files.put(path, content.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public InputStream read(String path) {
        byte[] content = files.get(path);
        if (content == null) {
            throw new FileNotFoundException("Not found: " + path);
        }
        return new ByteArrayInputStream(content);
    }

    @Override
    public boolean exists(String path) {
        return files.containsKey(path) || isDirectory(path);
    }

    @Override
    public boolean isDirectory(String path) {
        String prefix = path.endsWith("/") ? path : path + "/";
        for (String p : files.keySet()) {
            if (p.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public List<String> list(String directory) {
        String prefix = directory.endsWith("/") ? directory : directory + "/";
        List<String> result = new ArrayList<>();
        for (String p : files.keySet()) {
            if (p.startsWith(prefix) && p.indexOf('/', prefix.length()) < 0) {
                result.add(p);
            }
        }
        return result;
    }

    @Override
    public List<String> listRecursive(String directory) {
        String prefix = directory.endsWith("/") ? directory : directory + "/";
        List<String> result = new ArrayList<>();
        for (String p : files.keySet()) {
            if (p.startsWith(prefix)) {
                result.add(p);
            }
        }
        return result;
    }

    @Override
    public InputStream openInputStream(String path) {
        return read(path);
    }

    @Override
    public String getWorkingDirectory() {
        return "/";
    }

    @Override
    public void initialize() {
        // no-op
    }

    @Override
    public BackendStatus getStatus() {
        throw new UnsupportedOperationException("getStatus not supported by staging VFS");
    }

    @Override
    public void close() {
        files.clear();
    }

    // -- unsupported operations -------------------------------------------------

    @Override
    public void write(String path, InputStream content, long contentLength) {
        throw new UnsupportedOperationException("OutboxStagingFileSystem is internal-write only via put(...)");
    }

    @Override
    public void delete(String path) {
        throw new UnsupportedOperationException("OutboxStagingFileSystem does not support delete");
    }

    @Override
    public FileMetadata getMetadata(String path) {
        throw new UnsupportedOperationException("getMetadata not supported by staging VFS");
    }

    @Override
    public void copy(String sourcePath, String destinationPath, boolean overwrite) {
        throw new UnsupportedOperationException("copy not supported by staging VFS");
    }

    @Override
    public void move(String sourcePath, String destinationPath, boolean overwrite) {
        throw new UnsupportedOperationException("move not supported by staging VFS");
    }

    @Override
    public OutputStream openOutputStream(String path) {
        throw new UnsupportedOperationException("openOutputStream not supported by staging VFS");
    }
}
