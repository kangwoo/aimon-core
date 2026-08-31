package at.aimon.core.memory;

import java.util.Objects;

/**
 * Workspace-bound identifier for an {@link Observation}.
 *
 * <p>
 * Carrying the {@code workspaceId} alongside the local id forces every store
 * lookup to remain inside its tenant — the compiler refuses to accept a bare
 * {@code String} where an {@code ObservationId} is required.
 *
 * <p>
 * Canonical string form is {@code workspaceId:localId} (used by {@link #toString()}).
 */
public final class ObservationId {

    private final String workspaceId;
    private final String localId;

    private ObservationId(String workspaceId, String localId) {
        this.workspaceId = Objects.requireNonNull(workspaceId, "workspaceId cannot be null");
        this.localId = Objects.requireNonNull(localId, "localId cannot be null");
        if (workspaceId.isBlank()) {
            throw new IllegalArgumentException("workspaceId cannot be blank");
        }
        if (localId.isBlank()) {
            throw new IllegalArgumentException("localId cannot be blank");
        }
    }

    /**
     * Creates a new id from raw workspace and local components.
     */
    public static ObservationId of(String workspaceId, String localId) {
        return new ObservationId(workspaceId, localId);
    }

    /**
     * Convenience factory that derives the workspace id from a {@link Workspace} instance.
     */
    public static ObservationId of(Workspace workspace, String localId) {
        Objects.requireNonNull(workspace, "workspace cannot be null");
        return new ObservationId(workspace.getId(), localId);
    }

    public String getWorkspaceId() {
        return workspaceId;
    }

    public String getLocalId() {
        return localId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        ObservationId that = (ObservationId) o;
        return workspaceId.equals(that.workspaceId) && localId.equals(that.localId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(workspaceId, localId);
    }

    @Override
    public String toString() {
        return workspaceId + ":" + localId;
    }
}
