package at.aimon.core.subagent.task.codec;

import at.aimon.core.subagent.task.TaskResult;

/**
 * Serialization boundary between a {@link at.aimon.core.subagent.task.TaskResult} and the bytes a persistent
 * {@link at.aimon.core.subagent.task.TaskResultStore} writes.
 *
 * <p>
 * Same split as its sibling {@link SessionSnapshotCodec}: a store decides <em>where</em> a task's result lives (a VFS
 * file today, a document or a row later) while the codec decides <em>how</em> it is written, so a new backend does not
 * restate the encoding and every backend produces the same document for the same result.
 *
 * <p>
 * The task id and the owning agent are the store's key material and stay outside the encoded payload — this codec
 * carries only what the task produced.
 *
 * <p>
 * Implementations must be thread-safe and stateless.
 */
public interface TaskResultCodec {

    /**
     * Encodes a task result into its serialized form.
     *
     * @param result
     *            the result to encode (must not be null)
     * @return the encoded representation (never null)
     * @throws TaskResultCodecException
     *             if encoding fails
     */
    String encode(TaskResult result);

    /**
     * Decodes a task result from its serialized form.
     *
     * @param encoded
     *            the encoded representation (must not be null)
     * @return the decoded result (never null)
     * @throws TaskResultCodecException
     *             if the payload is malformed or its format version is unsupported
     */
    TaskResult decode(String encoded);
}
