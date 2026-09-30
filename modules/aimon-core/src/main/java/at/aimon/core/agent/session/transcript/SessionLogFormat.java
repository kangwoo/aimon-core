package at.aimon.core.agent.session.transcript;

/**
 * The persisted format of a session's transcript.
 *
 * <p>
 * A version-1 document is a plain message list with an index-based rewind point. A version-2 document is the whole
 * {@link SessionLogState}: seq-addressed entries with their {@link LogOrigin}, {@code nextSeq} and {@code floorSeq},
 * and a seq-based rewind point. Every build that knows this type reads both.
 *
 * <p>
 * <b>Writing is switched on in two steps across a cluster.</b> First every node is deployed able to read version 2
 * while still writing version 1; only then is writing switched to version 2. A state read from a version-2 document
 * carries {@link #V2} and is always written back as version 2, whatever the node's own switch says — the upgrade is
 * sticky. Without that, a node still writing version 1 during the switch-over would rewrite a version-2 record as
 * version 1 and silently drop everything version 1 cannot express.
 */
public enum SessionLogFormat {

    /** A plain message list; seqs and origins are not persisted. The default. */
    V1(1),

    /** The whole {@link SessionLogState}. */
    V2(2);

    private final int wireVersion;

    SessionLogFormat(int wireVersion) {
        this.wireVersion = wireVersion;
    }

    /**
     * Returns the value written to the document's {@code version} field.
     *
     * @return the wire version
     */
    public int getWireVersion() {
        return wireVersion;
    }

    /**
     * Returns the later of this format and {@code other} — the format a state must be written in when one side asks
     * for {@code other}.
     *
     * @param other
     *            the other format (must not be null)
     * @return the later format (never null)
     */
    public SessionLogFormat atLeast(SessionLogFormat other) {
        return other.wireVersion > wireVersion ? other : this;
    }
}
