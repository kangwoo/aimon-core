package at.aimon.core.subagent.task;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory reference implementation of {@link TaskOutputStore}.
 *
 * <p>
 * Keeps each task's output in a per-node {@link StringBuilder} guarded by a per-task lock. This is the default,
 * single-node implementation; a scale-out deployment supplies a shared/persistent implementation
 * ({@code VfsTaskOutputStore} over GridFS/S3, ...) so output written on one node is readable on another.
 *
 * <p>
 * Thread-safe: appends for a task are serialized on the task's buffer, and reads take the same lock to observe a
 * consistent snapshot of the character stream.
 */
public final class InMemoryTaskOutputStore implements TaskOutputStore {

    private final Map<String, StringBuilder> buffers = new ConcurrentHashMap<>();

    @Override
    public void append(String taskId, String chunk) {
        Objects.requireNonNull(taskId, "taskId cannot be null");
        if (chunk == null || chunk.isEmpty()) {
            return;
        }
        final StringBuilder buffer = buffers.computeIfAbsent(taskId, k -> new StringBuilder());
        synchronized (buffer) {
            buffer.append(chunk);
        }
    }

    @Override
    public OutputSlice read(String taskId, long fromOffset, int maxChars) {
        Objects.requireNonNull(taskId, "taskId cannot be null");
        final long start = Math.max(0, fromOffset);
        final int budget = Math.max(1, maxChars);

        final StringBuilder buffer = buffers.get(taskId);
        if (buffer == null) {
            return OutputSlice.empty(start);
        }
        synchronized (buffer) {
            final int total = buffer.length();
            if (start >= total) {
                return OutputSlice.of("", start, start > 0, false);
            }
            final int from = (int) start;
            final int end = (int) Math.min((long) from + budget, total);
            final String text = buffer.substring(from, end);
            final long nextOffset = end;
            return OutputSlice.of(text, nextOffset, start > 0, nextOffset < total);
        }
    }

    @Override
    public long length(String taskId) {
        Objects.requireNonNull(taskId, "taskId cannot be null");
        final StringBuilder buffer = buffers.get(taskId);
        if (buffer == null) {
            return 0L;
        }
        synchronized (buffer) {
            return buffer.length();
        }
    }

    @Override
    public void evict(String taskId) {
        Objects.requireNonNull(taskId, "taskId cannot be null");
        buffers.remove(taskId);
    }
}
