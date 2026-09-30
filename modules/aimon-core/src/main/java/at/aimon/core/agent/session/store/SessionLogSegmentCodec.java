package at.aimon.core.agent.session.store;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

import at.aimon.core.agent.session.transcript.SessionLogEntry;
import at.aimon.core.subagent.task.codec.JsonSessionSnapshotCodec;

/**
 * The payload encoding of a {@link SessionLogSegment}, and its content hash.
 *
 * <p>
 * The entries are encoded with the same message encoding as the record's transcript ({@link JsonSessionSnapshotCodec})
 * so that a sealed entry and a carried one are the same bytes apart from where they sit. Backends store the payload as
 * an opaque string, for the reasons {@link SessionRecordCodec} gives for the transcript.
 *
 * <p>
 * The hash is taken over the payload string as written, not over re-encoded entries: a round trip through the
 * message types is lossless but is not promised to be byte-identical, and a hash that could disagree with itself
 * would report healthy segments as gaps.
 *
 * <p>
 * Stateless and thread-safe.
 */
public final class SessionLogSegmentCodec {

    /** Prefix of every content hash, naming the algorithm so a later one can be told apart. */
    public static final String HASH_PREFIX = "sha256:";

    private static final JsonSessionSnapshotCodec CODEC = new JsonSessionSnapshotCodec();

    private SessionLogSegmentCodec() {
    }

    /**
     * @param entries
     *            the entries to seal (must not be null)
     * @return the payload (never null)
     */
    public static String encode(List<SessionLogEntry> entries) {
        return CODEC.encodeEntries(Objects.requireNonNull(entries, "entries cannot be null"));
    }

    /**
     * @param payload
     *            a payload {@link #encode(List)} wrote (must not be null)
     * @return the entries (never null)
     * @throws at.aimon.core.subagent.task.codec.SessionSnapshotCodecException
     *             if the payload cannot be read
     */
    public static List<SessionLogEntry> decode(String payload) {
        return CODEC.decodeEntries(Objects.requireNonNull(payload, "payload cannot be null"));
    }

    /**
     * @param payload
     *            the payload (must not be null)
     * @return {@code sha256:<hex>} of its UTF-8 bytes (never null)
     */
    public static String contentHash(String payload) {
        Objects.requireNonNull(payload, "payload cannot be null");
        try {
            final byte[] digest = MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8));
            return HASH_PREFIX + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // Every Java platform is required to provide SHA-256.
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
