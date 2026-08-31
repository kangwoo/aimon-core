package at.aimon.core.memory;

import java.util.List;
import java.util.Optional;

import at.aimon.core.base.Principal;

/**
 * Storage abstraction for {@link Workspace} entities — the root of multi-tenant
 * isolation in the memory layer.
 *
 * <p>
 * Other memory stores never accept a bare {@code String} id; they require a
 * {@link Workspace} or a workspace-bound id object (e.g. {@link ObservationId}).
 * The single bootstrap exception is {@link #findById(String)} on this interface,
 * which exists precisely so callers can obtain a {@code Workspace} object before
 * touching anything else. ArchUnit whitelists this method specifically.
 *
 * <p>
 * Implementations must be safe for concurrent use.
 */
public interface WorkspaceStore {

    /**
     * Persists a new workspace. Implementations must reject duplicate ids with
     * an {@link IllegalStateException}.
     */
    Workspace create(Workspace workspace);

    /**
     * Bootstrap-only lookup by raw id. Multi-tenant isolation is enforced by
     * having every other store API require {@code Workspace} or a workspace-bound
     * id object.
     */
    Optional<Workspace> findById(String id);

    /**
     * Lists workspaces visible to {@code requester}. Implementations are expected
     * to apply access control: a non-system principal may only see workspaces it
     * is authorized for. The default in-memory implementation returns all
     * workspaces (no ACLs in stage 1) — production backends must enforce real
     * checks.
     */
    List<Workspace> findAll(Principal requester);

    /**
     * Deletes a workspace by object identity. Cascading observation/representation
     * deletion is the caller's responsibility (or a separate cleanup job in
     * production backends).
     */
    void delete(Workspace workspace);
}
