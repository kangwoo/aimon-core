package at.aimon.core.memory.index;

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
import at.aimon.core.filesystem.BackendType;
import at.aimon.core.filesystem.FileMetadata;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.filesystem.exception.FileNotFoundException;

/**
 * Internal in-memory {@link VirtualFileSystem} that backs
 * {@link KnowledgeStoreObservationIndex} for ferrying observation content into
 * {@link at.aimon.core.knowledge.KnowledgeStore#reindex} calls.
 *
 * <p>
 * Only the subset of operations actually invoked by
 * {@link at.aimon.core.knowledge.KeywordKnowledgeStore} during indexing is
 * implemented (write-string, read, listRecursive, list+isDirectory). Every
 * other method throws {@link UnsupportedOperationException} on purpose — this
 * is not a general-purpose VFS, it is private plumbing.
 */
final class StagingFileSystem implements VirtualFileSystem {

    private static final BackendType STAGING_BACKEND_TYPE = BackendType.of("MEMORY_STAGING");

    private final Map<String, byte[]> files = new ConcurrentHashMap<>();

    void put(String path, String content) {
        Objects.requireNonNull(path, "path cannot be null");
        Objects.requireNonNull(content, "content cannot be null");
        files.put(path, content.getBytes(StandardCharsets.UTF_8));
    }

    void remove(String path) {
        Objects.requireNonNull(path, "path cannot be null");
        files.remove(path);
    }

    void clearDirectory(String directory) {
        Objects.requireNonNull(directory, "directory cannot be null");
        String prefix = directory.endsWith("/") ? directory : directory + "/";
        files.keySet().removeIf(p -> p.startsWith(prefix));
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
        return BackendStatus.connected(STAGING_BACKEND_TYPE);
    }

    @Override
    public void close() {
        files.clear();
    }

    // -- unsupported operations -------------------------------------------------

    @Override
    public void write(String path, InputStream content, long contentLength) {
        throw new UnsupportedOperationException("StagingFileSystem is internal-write only via put(...)");
    }

    @Override
    public void delete(String path) {
        throw new UnsupportedOperationException("StagingFileSystem deletes via remove(...)");
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
