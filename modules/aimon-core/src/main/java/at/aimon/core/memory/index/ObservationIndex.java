package at.aimon.core.memory.index;

import java.util.List;

import at.aimon.core.memory.Observation;
import at.aimon.core.memory.ObservationId;
import at.aimon.core.memory.PeerView;

/**
 * Search-side projection of observation content.
 *
 * <p>
 * Splitting search out of {@link at.aimon.core.memory.ObservationStore} is the
 * concrete realization of design doc §5.2: the
 * {@code ObservationStore} keeps metadata (relations, confidence, audit) and
 * the index keeps only what is needed to answer
 * {@link #search(PeerView, String, int) topK lookups}. With this seam in place,
 * the same metadata store can run on top of an in-memory substring index for
 * tests, a vector index over {@code KnowledgeStore} in production, or a
 * keyword-only fallback when embeddings are unavailable — without dragging the
 * metadata store into the choice.
 *
 * <p>
 * Implementations must be thread-safe and idempotent for repeated index/delete
 * calls on the same id. Workspace and subject scoping must be honoured: a
 * {@code search} for {@code subject A} must never return observations whose
 * subject is {@code B}, and observations from one workspace must never leak
 * into another.
 *
 * <p>
 * The index returns {@link ObservationId} only. Callers hydrate full
 * {@link Observation}s via the metadata store; the index never owns observation
 * payload that the metadata store does not also own.
 */
public interface ObservationIndex {

    /**
     * Adds or replaces the index entry for {@code observation}.
     *
     * <p>
     * The implementation must overwrite any prior entry for the same
     * {@link ObservationId} so that updates to {@code content} or
     * {@code confidence} are reflected on the next search.
     */
    void index(Observation observation);

    /**
     * Removes the index entry for {@code id}. A no-op if no such entry exists.
     */
    void delete(ObservationId id);

    /**
     * Returns up to {@code topK} observation ids whose content matches
     * {@code query} for the given {@code subject}, ordered from most to least
     * relevant. Implementations rank by their underlying scoring function
     * (substring confidence, vector similarity, etc.).
     *
     * @throws IllegalArgumentException
     *             if {@code topK} is less than 1
     */
    List<ObservationId> search(PeerView subject, String query, int topK);
}
