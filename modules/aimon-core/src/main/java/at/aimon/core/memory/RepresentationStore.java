package at.aimon.core.memory;

import java.time.Instant;
import java.util.Optional;

/**
 * Storage abstraction for {@link Representation} snapshots.
 *
 * <p>
 * Representations are append-only by convention: callers persist a new snapshot
 * instead of mutating an existing one. {@code findLatest*} returns the most
 * recently generated snapshot for the requested scope.
 *
 * <p>
 * The {@code sessionId} parameter is a plain {@code String} and may be
 * {@code null} for cross-session representations. The session itself
 * ({@code LiveSession}) is looked up separately by id at use time —
 * representations never hold live session references.
 */
public interface RepresentationStore {

    Representation save(Representation representation);

    /** Latest global representation about {@code subject}, if any. */
    Optional<Representation> findLatestGlobal(PeerView subject);

    /**
     * Latest local representation of {@code subject} as seen by {@code observer}
     * within the given session. Pass {@code sessionId == null} for cross-session
     * scope.
     */
    Optional<Representation> findLatestLocal(PeerView subject, PeerView observer, String sessionId);

    /** Deletes representations within {@code workspace} generated before {@code cutoff}. */
    void deleteOlderThan(Workspace workspace, Instant cutoff);
}
