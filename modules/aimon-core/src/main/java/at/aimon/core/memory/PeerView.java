package at.aimon.core.memory;

import java.util.Objects;

import at.aimon.core.base.Principal;

/**
 * Adapter combining a {@link Workspace} with an existing {@link Principal} to form
 * the "peer" concept of the memory layer without modifying the {@code Principal} type.
 *
 * <p>
 * The composite key {@code workspaceId:type:id} prevents collisions between principals
 * of different types that happen to share the same id (e.g. user "alice" vs service "alice").
 *
 * <p>
 * Immutable value object. Created via {@link #of(Workspace, Principal)}.
 */
public final class PeerView {

    private final Workspace workspace;
    private final Principal principal;

    private PeerView(Workspace workspace, Principal principal) {
        this.workspace = Objects.requireNonNull(workspace, "workspace cannot be null");
        this.principal = Objects.requireNonNull(principal, "principal cannot be null");
    }

    public static PeerView of(Workspace workspace, Principal principal) {
        return new PeerView(workspace, principal);
    }

    public Workspace getWorkspace() {
        return workspace;
    }

    public Principal getPrincipal() {
        return principal;
    }

    /**
     * Storage-stable key combining the workspace id with the principal's type and id.
     *
     * <p>
     * Format: {@code workspaceId:TYPE:principalId}. The type segment prevents id
     * collisions across {@link Principal.Type} values.
     */
    public String key() {
        return workspace.getId() + ":" + principal.getType().name() + ":" + principal.getId();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        PeerView that = (PeerView) o;
        return workspace.equals(that.workspace) && principal.equals(that.principal);
    }

    @Override
    public int hashCode() {
        return Objects.hash(workspace, principal);
    }

    @Override
    public String toString() {
        return "PeerView{" + key() + "}";
    }
}
