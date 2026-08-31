package at.aimon.core.subagent.task;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.subagent.task.codec.JsonTaskResultCodec;
import at.aimon.core.subagent.task.codec.TaskResultCodec;

/**
 * {@link TaskResultStore} backed by a {@link VirtualFileSystem}, isomorphic with {@link VfsSessionSnapshotStore} and
 * {@link VfsTaskOutputStore}.
 *
 * <p>
 * This is what closes the cross-node hole. Because a {@link VirtualFileSystem} can be a GridFS or S3 backend, a task
 * that settles on one node has its result readable from another — the last piece of the background-task surface that
 * was still node-local — and the result survives a restart of the node that produced it.
 *
 * <p>
 * <b>Layout.</b> Each task's result is a single JSON object at {@code <baseDir>/<taskId>.json}, written by the
 * {@link TaskResultCodec}. Unlike {@link VfsSessionSnapshotStore} there is no envelope around the codec output: the
 * envelope there exists to carry the owner tag, and this store is deliberately untagged (see {@link TaskResultStore}),
 * which would leave an envelope holding nothing but a second version number. The codec's own
 * {@link JsonTaskResultCodec#FORMAT_VERSION} is the version of record.
 *
 * <p>
 * One object per task means a save is one PUT (atomic on object stores) and a load one GET — no listing or segment
 * reassembly, unlike the append-only {@link VfsTaskOutputStore}. {@link #save} deletes any existing object first, so
 * the
 * newest result wins regardless of whether the backend's {@code write} overwrites, rejects, or versions an existing
 * path.
 *
 * <p>
 * <b>Best-effort.</b> Every backend and codec interaction is guarded: a failed save logs a warning and never throws (it
 * must not change the result the task hands back to its caller), and a malformed, unsupported-version, or unreadable
 * object loads as {@link Optional#empty()}, exactly as an evicted entry would. Bounding is delegated to the backend or
 * an external retention policy rather than an LRU cap.
 *
 * <p>
 * Thread-safe: saves, loads, and evicts are independent single-object operations with no shared mutable state, so the
 * subagent thread saving a terminal result may safely race with a parent agent polling for it.
 */
public final class VfsTaskResultStore implements TaskResultStore {

    /** Default base directory for per-task result objects. */
    public static final String DEFAULT_BASE_DIR = ".aimon/task-result";

    private static final Logger log = LoggerFactory.getLogger(VfsTaskResultStore.class);

    private static final String RESULT_SUFFIX = ".json";

    private final VirtualFileSystem fileSystem;
    private final TaskResultCodec codec;
    private final String baseDir;

    /**
     * Creates a store rooted at {@link #DEFAULT_BASE_DIR} using the default {@link JsonTaskResultCodec}.
     *
     * @param fileSystem
     *            the backing virtual file system (must not be null)
     */
    public VfsTaskResultStore(VirtualFileSystem fileSystem) {
        this(fileSystem, new JsonTaskResultCodec(), DEFAULT_BASE_DIR);
    }

    /**
     * Creates a store with an explicit codec and base directory.
     *
     * @param fileSystem
     *            the backing virtual file system (must not be null)
     * @param codec
     *            the codec used to (de)serialize the {@link TaskResult} (must not be null)
     * @param baseDir
     *            the base directory under which per-task result objects are stored (must not be null/blank)
     */
    public VfsTaskResultStore(VirtualFileSystem fileSystem, TaskResultCodec codec, String baseDir) {
        this.fileSystem = Objects.requireNonNull(fileSystem, "fileSystem cannot be null");
        this.codec = Objects.requireNonNull(codec, "codec cannot be null");
        Objects.requireNonNull(baseDir, "baseDir cannot be null");
        if (baseDir.isBlank()) {
            throw new IllegalArgumentException("baseDir cannot be blank");
        }
        this.baseDir = stripTrailingSlash(baseDir);
    }

    @Override
    public void save(String taskId, TaskResult result) {
        Objects.requireNonNull(taskId, "taskId cannot be null");
        Objects.requireNonNull(result, "result cannot be null");
        final String path = resultPath(taskId);
        try {
            final String json = codec.encode(result);
            deleteQuietly(path);
            fileSystem.write(path, json);
        } catch (RuntimeException e) {
            log.warn("Failed to save task result for task {}: {}", taskId, e.getMessage());
        }
    }

    @Override
    public Optional<TaskResult> load(String taskId) {
        Objects.requireNonNull(taskId, "taskId cannot be null");
        final String path = resultPath(taskId);
        try {
            if (!fileSystem.exists(path) || fileSystem.isDirectory(path)) {
                return Optional.empty();
            }
            return Optional.of(codec.decode(readString(path)));
        } catch (RuntimeException | IOException e) {
            log.warn("Failed to load task result for task {}: {}", taskId, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void evict(String taskId) {
        Objects.requireNonNull(taskId, "taskId cannot be null");
        deleteQuietly(resultPath(taskId));
    }

    private void deleteQuietly(String path) {
        try {
            if (fileSystem.exists(path)) {
                fileSystem.delete(path);
            }
        } catch (RuntimeException e) {
            log.debug("Failed to delete task result {}: {}", path, e.getMessage());
        }
    }

    private String readString(String path) throws IOException {
        try (InputStream in = fileSystem.read(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String resultPath(String taskId) {
        return baseDir + "/" + taskId + RESULT_SUFFIX;
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
