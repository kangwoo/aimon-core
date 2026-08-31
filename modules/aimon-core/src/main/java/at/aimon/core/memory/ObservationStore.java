package at.aimon.core.memory;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Storage abstraction for {@link Observation} metadata, relationships, and
 * confidence scores.
 *
 * <p>
 * Vector embeddings are <em>not</em> handled here. As decided in design doc §5.2,
 * semantic search is delegated to the knowledge module so that the
 * memory layer does not build a parallel RAG stack. In stage 1 the in-memory
 * implementation provides a substring fallback; stage 4 swaps that for
 * delegation to {@code KnowledgeStore} on a dedicated {@code KnowledgeScope}.
 *
 * <p>
 * Every method takes a workspace-bound id object — never a bare {@code String} —
 * so multi-tenant isolation is enforced at compile time.
 */
public interface ObservationStore {

    /** Persists or updates an observation. Returns the stored instance. */
    Observation save(Observation observation);

    Optional<Observation> findById(ObservationId id);

    /**
     * Returns the most recent observations about {@code subject}, newest first,
     * up to {@code limit}. Limit must be {@code >= 1}.
     */
    List<Observation> findBySubject(PeerView subject, int limit);

    long count(PeerView subject);

    /**
     * Semantic search over observations about {@code subject}.
     *
     * <p>
     * In stage 1 the in-memory implementation matches by substring. Stage 4 will
     * replace this with a {@code KnowledgeStore.search(...)} call on the
     * {@code memory.observation} scope, hydrating results back to {@code Observation}
     * via {@link ObservationId}.
     */
    List<Observation> semanticSearch(PeerView subject, String query, int topK);

    /**
     * Returns observations about {@code subject} whose {@code confidence} is
     * strictly below {@code threshold}, ordered by ascending confidence.
     * Used by the dreamer to find consolidation candidates.
     */
    List<Observation> findByConfidenceBelow(PeerView subject, double threshold, int limit);

    /**
     * Lists distinct subjects (peers that have at least one observation) within
     * {@code workspace}. Used by the dreamer engine to walk every peer once per
     * cycle. Order is not guaranteed; the implementation should cap the result
     * at {@code limit} so a runaway tenant cannot stall a cycle.
     *
     * @param workspace
     *            tenant scope (must not be null)
     * @param limit
     *            maximum subjects to return; must be {@code >= 1}
     */
    List<PeerView> findSubjects(Workspace workspace, int limit);

    void delete(ObservationId id);

    /**
     * Merges two observations into one. {@code merged} is saved with
     * {@code winner}'s id and {@code loser} is <em>soft-deleted</em>: removed from
     * all normal query results but retained in an audit window so it can be
     * recovered until a {@link #purgeSoftDeletedBefore} pass permanently removes it
     * (design doc §5.2, 30-day retention). All backends honour this contract.
     *
     * @return the merged observation as stored
     */
    Observation merge(ObservationId winner, ObservationId loser, Observation merged);

    /**
     * Soft-deletes a single observation: it is removed from every normal query
     * result but retained in the audit window until {@link #purgeSoftDeletedBefore}
     * removes it. This is the recoverable retirement primitive used by
     * consolidation to drop observations absorbed into a merge winner without
     * losing the 30-day audit trail (design doc §5.2). A no-op if {@code id} is
     * absent.
     *
     * <p>
     * The default falls back to a hard {@link #delete}; audit-capable backends
     * override it. Implementations that override this <em>must</em> also override
     * {@link #purgeSoftDeletedBefore}.
     */
    default void softDelete(ObservationId id) {
        delete(id);
    }

    /**
     * Permanently removes soft-deleted observations in {@code workspace} whose
     * deletion time is strictly before {@code cutoff}, enforcing the audit
     * retention window (design doc §5.2). Live (non-deleted) observations are
     * never touched.
     *
     * <p>
     * The default returns {@code 0} (no audit state to purge). Audit-capable
     * backends override it.
     *
     * @param workspace
     *            tenant scope (must not be null)
     * @param cutoff
     *            soft-deleted observations with deletion time before this are
     *            removed (must not be null)
     * @return the number of observations permanently removed
     */
    default int purgeSoftDeletedBefore(Workspace workspace, Instant cutoff) {
        return 0;
    }
}
