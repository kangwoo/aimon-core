package at.aimon.core.memory;

import at.aimon.core.base.Principal;

/**
 * Decides whether a {@link Principal} may see a {@link Workspace}, used by
 * {@link WorkspaceStore#findAll(Principal)} to enforce multi-tenant isolation
 * (design doc §12, §13). Stores apply this filter so a requester only lists the
 * workspaces it is entitled to.
 *
 * <p>
 * Implementations must be thread-safe and side-effect-free.
 *
 * @see DefaultWorkspaceAccessPolicy
 */
@FunctionalInterface
public interface WorkspaceAccessPolicy {

    /** Returns {@code true} if {@code requester} may access {@code workspace}. */
    boolean canAccess(Principal requester, Workspace workspace);

    /** A policy that grants every requester access to every workspace (single-tenant / trusted). */
    static WorkspaceAccessPolicy allowAll() {
        return (requester, workspace) -> true;
    }

    /** The {@link DefaultWorkspaceAccessPolicy}: infrastructure principals see all; others see owned/unowned. */
    static WorkspaceAccessPolicy defaults() {
        return new DefaultWorkspaceAccessPolicy();
    }
}
