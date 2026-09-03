package at.aimon.core.memory;

/**
 * Which snapshot of a subject a {@link MemorySnapshotQuery} asks for, and which one answered.
 *
 * <p>
 * On the way in, {@link #LOCAL_THEN_GLOBAL} is the ordinary request: prefer what this observer saw in this session,
 * fall back to the system-wide portrait. On the way out, {@link MemorySnapshot#getResolvedScope()} carries the one
 * that actually answered — never {@link #LOCAL_THEN_GLOBAL}, which is a preference rather than an outcome.
 */
public enum MemorySnapshotScope {

    /** The subject as this observer saw them, within the session the query names. */
    LOCAL,

    /** The system-wide portrait of the subject, with no observer or session. */
    GLOBAL,

    /** {@link #LOCAL} if there is one, {@link #GLOBAL} otherwise. Request-only. */
    LOCAL_THEN_GLOBAL
}
