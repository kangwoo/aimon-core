package at.aimon.core.subagent.task;

/**
 * Storage abstraction for the live output log of a background subagent task (design §5).
 *
 * <p>
 * This is the multi-instance seam for <b>progress visibility</b>: while a detached subagent runs, its executor appends
 * human-readable progress (iteration boundaries, tool starts/results, the final answer) here, and the
 * {@code AgentOutput}
 * tool tails it incrementally via {@link #read(String, long, int)} so the parent agent can watch a long-running task
 * without blocking.
 *
 * <p>
 * <b>Append-only, offset-addressed.</b> Output is an ever-growing character stream addressed by a monotonic character
 * offset. {@link #append(String, String)} adds to the tail; {@link #read(String, long, int)} returns the delta starting
 * at a caller-held cursor. Offsets are measured in <b>characters</b> (not bytes) so a delta never splits a multi-byte
 * UTF-8 code point — matching {@code BackgroundTask.outputOffset}'s contract.
 *
 * <p>
 * <b>Object-store friendly.</b> The default {@link InMemoryTaskOutputStore} keeps the log in a per-node buffer; the VFS
 * segment-log implementation ({@code VfsTaskOutputStore}) persists each append as an immutable segment object (no
 * overwrite, no native append) so it works over S3/GridFS backends that cannot append in place, and so a task's output
 * can be shared across nodes. Per the project's multi-instance rule, swapping the backend is an implementation change,
 * not a refactoring.
 *
 * <p>
 * Implementations must be safe for concurrent access: a single writer (the task's executor thread, possibly invoking
 * {@code append} from parallel tool-result callbacks) racing with any number of readers.
 */
public interface TaskOutputStore {

    /**
     * Appends a chunk to the tail of a task's output log.
     *
     * <p>
     * A null or empty chunk is a no-op. Appends for a given {@code taskId} are serialized so the resulting stream is
     * well-ordered even under concurrent callers. Implementations should treat streaming failures as best-effort and
     * must not throw from this method for routine backend errors — a failed append must never abort the task whose
     * progress it is recording.
     *
     * @param taskId
     *            the task identifier (must not be null)
     * @param chunk
     *            the text to append (null/empty ignored)
     */
    void append(String taskId, String chunk);

    /**
     * Reads up to {@code maxChars} characters of a task's output starting at {@code fromOffset}.
     *
     * <p>
     * Returns whatever is currently available in {@code [fromOffset, fromOffset + maxChars)}; it never waits for more
     * output to be produced. When {@code fromOffset} is at or past the current length, the returned slice's text is
     * empty and its {@code nextOffset} echoes {@code fromOffset}.
     *
     * @param taskId
     *            the task identifier (must not be null)
     * @param fromOffset
     *            the character offset to start reading from (clamped to {@code >= 0})
     * @param maxChars
     *            the maximum number of characters to return (must be {@code >= 1})
     * @return the delta window (never null)
     */
    OutputSlice read(String taskId, long fromOffset, int maxChars);

    /**
     * Returns the total number of characters currently recorded for a task.
     *
     * @param taskId
     *            the task identifier (must not be null)
     * @return the character length of the log, or {@code 0} if the task has no output
     */
    long length(String taskId);

    /**
     * Discards all output recorded for a task, releasing its storage.
     *
     * <p>
     * A no-op when the task is unknown. Best-effort: implementations must not throw for routine backend errors.
     *
     * @param taskId
     *            the task identifier (must not be null)
     */
    void evict(String taskId);
}
