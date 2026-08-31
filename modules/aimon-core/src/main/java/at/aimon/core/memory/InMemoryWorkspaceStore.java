package at.aimon.core.memory;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.base.Principal;

/**
 * Default in-memory implementation of {@link WorkspaceStore}.
 *
 * <p>
 * Backed by a {@link ConcurrentHashMap}; thread-safe but not durable. Intended
 * for development and tests only — production assemblies must inject a
 * persistent implementation.
 */
public class InMemoryWorkspaceStore implements WorkspaceStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryWorkspaceStore.class);

    private final Map<String, Workspace> storage = new ConcurrentHashMap<>();

    @Override
    public Workspace create(Workspace workspace) {
        Objects.requireNonNull(workspace, "workspace cannot be null");
        Workspace previous = storage.putIfAbsent(workspace.getId(), workspace);
        if (previous != null) {
            throw new IllegalStateException("Workspace already exists: " + workspace.getId());
        }
        log.info("Created workspace: id={}", workspace.getId());
        return workspace;
    }

    @Override
    public Optional<Workspace> findById(String id) {
        Objects.requireNonNull(id, "id cannot be null");
        return Optional.ofNullable(storage.get(id));
    }

    @Override
    public List<Workspace> findAll(Principal requester) {
        Objects.requireNonNull(requester, "requester cannot be null");
        // Stage 1: no ACL — production backends must enforce real checks.
        return List.copyOf(storage.values());
    }

    @Override
    public void delete(Workspace workspace) {
        Objects.requireNonNull(workspace, "workspace cannot be null");
        final boolean existed = storage.remove(workspace.getId()) != null;
        if (existed) {
            log.info("Deleted workspace: id={}", workspace.getId());
        } else {
            log.debug("Delete no-op (workspace absent): id={}", workspace.getId());
        }
    }

    /** Test helper: number of workspaces currently stored. */
    public int size() {
        return storage.size();
    }
}
