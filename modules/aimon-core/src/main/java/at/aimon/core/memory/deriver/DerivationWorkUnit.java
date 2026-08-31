package at.aimon.core.memory.deriver;

import java.util.Objects;

import at.aimon.core.base.Principal;
import at.aimon.core.memory.PeerView;
import at.aimon.core.memory.Workspace;

/**
 * Claim key for derivation work: identical work units are processed serially,
 * different work units run in parallel.
 *
 * <p>
 * The composite key is {@code (workspaceId, sessionId, observerType, observerId)}
 * — same shape as the future {@code mem_active_work_unit} row used by the
 * persistent backend (design doc §6.1.1). Carrying {@code observerType} alongside
 * {@code observerId} prevents collisions between principals of different types
 * that share an id.
 *
 * <p>
 * Immutable value object. Built via {@link #of(Workspace, String, PeerView)} or
 * the explicit factory {@link #of(String, String, Principal.Type, String)}.
 */
public final class DerivationWorkUnit {

    private final String workspaceId;
    private final String sessionId;
    private final Principal.Type observerType;
    private final String observerId;

    private DerivationWorkUnit(String workspaceId, String sessionId, Principal.Type observerType, String observerId) {
        this.workspaceId = Objects.requireNonNull(workspaceId, "workspaceId cannot be null");
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId cannot be null");
        this.observerType = Objects.requireNonNull(observerType, "observerType cannot be null");
        this.observerId = Objects.requireNonNull(observerId, "observerId cannot be null");
        if (workspaceId.isBlank()) {
            throw new IllegalArgumentException("workspaceId cannot be blank");
        }
        if (sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId cannot be blank");
        }
        if (observerId.isBlank()) {
            throw new IllegalArgumentException("observerId cannot be blank");
        }
    }

    public static DerivationWorkUnit of(String workspaceId, String sessionId, Principal.Type observerType,
            String observerId) {
        return new DerivationWorkUnit(workspaceId, sessionId, observerType, observerId);
    }

    public static DerivationWorkUnit of(Workspace workspace, String sessionId, PeerView observer) {
        Objects.requireNonNull(workspace, "workspace cannot be null");
        Objects.requireNonNull(observer, "observer cannot be null");
        Principal principal = observer.getPrincipal();
        return new DerivationWorkUnit(workspace.getId(), sessionId, principal.getType(), principal.getId());
    }

    public String getWorkspaceId() {
        return workspaceId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public Principal.Type getObserverType() {
        return observerType;
    }

    public String getObserverId() {
        return observerId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        DerivationWorkUnit that = (DerivationWorkUnit) o;
        return workspaceId.equals(that.workspaceId) && sessionId.equals(that.sessionId)
                && observerType == that.observerType && observerId.equals(that.observerId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(workspaceId, sessionId, observerType, observerId);
    }

    @Override
    public String toString() {
        return "DerivationWorkUnit{" + workspaceId + ":" + sessionId + ":" + observerType + ":" + observerId + "}";
    }
}
