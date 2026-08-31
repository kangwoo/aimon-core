package at.aimon.core.subagent.task.codec;

import at.aimon.core.agent.session.transcript.SessionSnapshot;

/**
 * Serializes a {@link SessionSnapshot} to a portable string form and back, losslessly.
 *
 * <p>
 * This is the prerequisite for any persistent or shared {@link at.aimon.core.subagent.task.SessionSnapshotStore}
 * (design §7): the core message types ({@link SessionSnapshot}, {@link at.aimon.core.llm.Message}, and the
 * polymorphic {@link at.aimon.core.llm.content.ContentBlock} hierarchy) are <em>not</em> Jackson-ready — they have
 * private constructors, derived fields, a polymorphic content-block interface, and binary blocks guarded by MIME
 * whitelists — so a snapshot cannot simply be reflected to/from JSON. A codec hand-maps every field of the snapshot's
 * type graph (roles, text/image/document blocks with their binary payloads, tool uses and their arbitrary JSON input,
 * tool results, and artifacts) so that {@code decode(encode(s)).equals(s)} holds for any snapshot a live session
 * can produce.
 *
 * <p>
 * <b>Owner tag is out of scope.</b> A codec serializes the {@link SessionSnapshot} value only. The subagent owner
 * tag that {@link at.aimon.core.subagent.task.SessionSnapshotStore} pairs with a snapshot (as
 * {@link at.aimon.core.subagent.task.ResumableSession}) is a store-level concern; the store composes it around the
 * codec's output.
 *
 * <p>
 * <b>Render payload is never persisted.</b> {@link at.aimon.core.llm.ToolUseResult#getRenderPayload()} is a sidecar for
 * hook consumers, contractually excluded from the conversation persistence path; implementations must not serialize it.
 * A snapshot round-tripped through a codec therefore carries tool results with a {@code null} render payload, by
 * design.
 *
 * <p>
 * Implementations must be thread-safe and stateless.
 */
public interface SessionSnapshotCodec {

    /**
     * Encodes a snapshot to its portable string form.
     *
     * @param snapshot
     *            the snapshot to encode (must not be null)
     * @return the encoded representation
     * @throws SessionSnapshotCodecException
     *             if the snapshot cannot be encoded
     */
    String encode(SessionSnapshot snapshot);

    /**
     * Decodes a snapshot previously produced by {@link #encode(SessionSnapshot)}.
     *
     * @param encoded
     *            the encoded representation (must not be null)
     * @return the reconstructed snapshot
     * @throws SessionSnapshotCodecException
     *             if the input is malformed, of an unsupported version, or otherwise cannot be reconstructed
     */
    SessionSnapshot decode(String encoded);
}
