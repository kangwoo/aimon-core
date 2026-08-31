package at.aimon.core.subagent.task;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.filesystem.VirtualFileSystem;

/**
 * {@link TaskOutputStore} backed by a {@link VirtualFileSystem} using an <b>append-only segment log</b> (design
 * §5).
 *
 * <p>
 * <b>Why segments.</b> The reference implementation appends to a single per-task file, but AIMON's object-store VFS
 * backends (S3, GridFS) have no native append — emulating it with read-modify-write is O(n²) and racy. Instead, each
 * {@link #append(String, String)} PUTs a brand-new immutable object (never overwriting), so the store works over any
 * backend and a task's output can be shared across nodes.
 *
 * <p>
 * <b>Layout.</b> A task's segments live under {@code <baseDir>/<taskId>/}, and each segment's filename is its
 * zero-padded <em>start character offset</em>:
 *
 * <pre>
 * .aimon/task-output/&lt;taskId&gt;/00000000000000000000.seg   # chars [0, len0)
 * .aimon/task-output/&lt;taskId&gt;/00000000000000000042.seg   # chars [42, 42+len1)
 * </pre>
 *
 * Because segments are contiguous (segment <i>i</i>'s end equals segment <i>i+1</i>'s start), the filename alone gives
 * an O(1) offset→segment mapping from a directory listing — no separate index object is needed, and a delta read only
 * fetches the segments overlapping the requested window.
 *
 * <p>
 * <b>Offsets are characters,</b> not bytes: chunks are written as UTF-8 text and decoded back to the identical
 * {@code String} on read, so a delta never splits a multi-byte code point.
 *
 * <p>
 * <b>Thread-safety.</b> A per-task cursor serializes the single writer's appends and supplies a monotonic start offset
 * (needed because a task's parallel tool-result callbacks may append concurrently). Reads reconstruct purely from the
 * directory listing, so a reader on another node (or after this node's cursor is evicted) still resolves the correct
 * delta. All backend interactions are best-effort — a failed segment PUT logs a warning and never throws, so streaming
 * failures can never abort the task whose progress is being recorded.
 */
public final class VfsTaskOutputStore implements TaskOutputStore {

    /** Default base directory for task output segment logs. */
    public static final String DEFAULT_BASE_DIR = ".aimon/task-output";

    private static final Logger log = LoggerFactory.getLogger(VfsTaskOutputStore.class);

    private static final String SEGMENT_SUFFIX = ".seg";
    /** 20 digits comfortably holds {@link Long#MAX_VALUE} (19 digits) so filenames sort lexicographically by offset. */
    private static final String OFFSET_FORMAT = "%020d";

    private final VirtualFileSystem fileSystem;
    private final String baseDir;
    private final Map<String, TaskCursor> cursors = new ConcurrentHashMap<>();

    /**
     * Creates a store rooted at {@link #DEFAULT_BASE_DIR}.
     *
     * @param fileSystem
     *            the backing virtual file system (must not be null)
     */
    public VfsTaskOutputStore(VirtualFileSystem fileSystem) {
        this(fileSystem, DEFAULT_BASE_DIR);
    }

    /**
     * Creates a store rooted at the given base directory.
     *
     * @param fileSystem
     *            the backing virtual file system (must not be null)
     * @param baseDir
     *            the base directory under which per-task segment logs are stored (must not be null/blank)
     */
    public VfsTaskOutputStore(VirtualFileSystem fileSystem, String baseDir) {
        this.fileSystem = Objects.requireNonNull(fileSystem, "fileSystem cannot be null");
        Objects.requireNonNull(baseDir, "baseDir cannot be null");
        if (baseDir.isBlank()) {
            throw new IllegalArgumentException("baseDir cannot be blank");
        }
        this.baseDir = stripTrailingSlash(baseDir);
    }

    @Override
    public void append(String taskId, String chunk) {
        Objects.requireNonNull(taskId, "taskId cannot be null");
        if (chunk == null || chunk.isEmpty()) {
            return;
        }
        final TaskCursor cursor = cursors.computeIfAbsent(taskId, k -> new TaskCursor());
        try {
            synchronized (cursor) {
                if (!cursor.initialized) {
                    cursor.length = reconstructLength(taskId);
                    cursor.initialized = true;
                }
                final long start = cursor.length;
                fileSystem.write(segmentPath(taskId, start), chunk);
                // Advance only after a successful PUT so a failed append leaves no offset gap and is retried at
                // `start`.
                cursor.length = start + chunk.length();
            }
        } catch (RuntimeException e) {
            log.warn("Failed to append task output segment for task {}: {}", taskId, e.getMessage());
        }
    }

    @Override
    public OutputSlice read(String taskId, long fromOffset, int maxChars) {
        Objects.requireNonNull(taskId, "taskId cannot be null");
        final long start = Math.max(0, fromOffset);
        final int budget = Math.max(1, maxChars);

        final List<Long> starts = listSegmentStarts(taskId);
        if (starts.isEmpty()) {
            return OutputSlice.empty(start);
        }

        final long windowEnd = start + budget;
        final StringBuilder sb = new StringBuilder();
        long cursor = start;
        int remaining = budget;
        long total = -1L;

        for (int i = 0; i < starts.size(); i++) {
            final long segStart = starts.get(i);
            final boolean last = i == starts.size() - 1;
            // Contiguity: a non-last segment ends where the next one starts, so we can skip whole segments that end at
            // or before the cursor without reading them.
            final long knownEnd = last ? Long.MAX_VALUE : starts.get(i + 1);
            if (knownEnd <= cursor) {
                continue;
            }
            if (segStart >= windowEnd) {
                break;
            }
            final String content = readSegment(taskId, segStart);
            final long segEnd = segStart + content.length();
            if (last) {
                total = segEnd;
            }
            if (segEnd <= cursor) {
                continue;
            }
            final int fromInSeg = (int) Math.max(0, cursor - segStart);
            if (fromInSeg >= content.length()) {
                continue;
            }
            final int take = (int) Math.min(content.length() - fromInSeg, remaining);
            sb.append(content, fromInSeg, fromInSeg + take);
            cursor += take;
            remaining -= take;
            if (remaining <= 0) {
                break;
            }
        }

        if (total < 0L) {
            // The window ended before the final segment, so read it once to learn the true total length.
            final long lastStart = starts.get(starts.size() - 1);
            total = lastStart + readSegment(taskId, lastStart).length();
        }

        return OutputSlice.of(sb.toString(), cursor, start > 0, cursor < total);
    }

    @Override
    public long length(String taskId) {
        Objects.requireNonNull(taskId, "taskId cannot be null");
        final TaskCursor cursor = cursors.get(taskId);
        if (cursor != null) {
            synchronized (cursor) {
                if (cursor.initialized) {
                    return cursor.length;
                }
            }
        }
        return reconstructLength(taskId);
    }

    @Override
    public void evict(String taskId) {
        Objects.requireNonNull(taskId, "taskId cannot be null");
        cursors.remove(taskId);
        final String dir = taskDir(taskId);
        try {
            if (!fileSystem.exists(dir)) {
                return;
            }
            try {
                fileSystem.deleteRecursive(dir);
                return;
            } catch (UnsupportedOperationException unsupported) {
                // Backend lacks recursive delete — fall back to per-segment deletion.
            }
            for (final Long segStart : listSegmentStarts(taskId)) {
                try {
                    fileSystem.delete(segmentPath(taskId, segStart));
                } catch (RuntimeException e) {
                    log.debug("Failed to delete task output segment {} for task {}: {}", segStart, taskId,
                            e.getMessage());
                }
            }
        } catch (RuntimeException e) {
            log.warn("Failed to evict task output for task {}: {}", taskId, e.getMessage());
        }
    }

    /**
     * Reconstructs the total character length from the on-disk segments (used when no in-memory cursor exists, e.g. on
     * a
     * reader node). Contiguity means the total equals the last segment's start plus its length.
     */
    private long reconstructLength(String taskId) {
        final List<Long> starts = listSegmentStarts(taskId);
        if (starts.isEmpty()) {
            return 0L;
        }
        final long lastStart = starts.get(starts.size() - 1);
        return lastStart + readSegment(taskId, lastStart).length();
    }

    /** Lists a task's segment start offsets in ascending order (empty when the task directory is absent). */
    private List<Long> listSegmentStarts(String taskId) {
        final String dir = taskDir(taskId);
        final List<String> entries;
        try {
            if (!fileSystem.exists(dir) || !fileSystem.isDirectory(dir)) {
                return List.of();
            }
            entries = fileSystem.list(dir);
        } catch (RuntimeException e) {
            return List.of();
        }
        final List<Long> starts = new ArrayList<>();
        for (final String entry : entries) {
            final String name = basename(entry);
            if (!name.endsWith(SEGMENT_SUFFIX)) {
                continue;
            }
            final String digits = name.substring(0, name.length() - SEGMENT_SUFFIX.length());
            try {
                starts.add(Long.parseLong(digits));
            } catch (NumberFormatException ignored) {
                // Not a segment file we wrote; skip it.
            }
        }
        Collections.sort(starts);
        return starts;
    }

    /** Reads a single segment's text, returning "" (and logging) on any backend error. */
    private String readSegment(String taskId, long segStart) {
        final String path = segmentPath(taskId, segStart);
        try (InputStream in = fileSystem.read(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            log.warn("Failed to read task output segment {} for task {}: {}", segStart, taskId, e.getMessage());
            return "";
        }
    }

    private String taskDir(String taskId) {
        return baseDir + "/" + taskId;
    }

    private String segmentPath(String taskId, long start) {
        return taskDir(taskId) + "/" + String.format(OFFSET_FORMAT, start) + SEGMENT_SUFFIX;
    }

    private static String basename(String path) {
        final int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    /** Per-task write cursor: the next segment's start offset plus lazy-init state. Guards the single writer. */
    private static final class TaskCursor {
        private long length;
        private boolean initialized;
    }
}
