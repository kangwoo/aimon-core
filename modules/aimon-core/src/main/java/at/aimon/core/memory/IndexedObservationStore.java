package at.aimon.core.memory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.memory.index.ObservationIndex;

/**
 * {@link ObservationStore} decorator that adds search to a metadata-only store.
 *
 * <p>
 * This is the reusable realization of the design doc §5.2 store/index split: an
 * {@code ObservationStore} keeps the metadata side (relations, confidence,
 * audit) while an {@link ObservationIndex} keeps only what is needed to answer
 * {@code topK} lookups. Some metadata stores deliberately do <em>not</em>
 * implement {@link #semanticSearch} and throw
 * {@link UnsupportedOperationException} instead. Wrapping such a store here
 * restores search without dragging vector concerns into the metadata layer:
 *
 * <pre>
 * {@code
 * ObservationStore metadata = ...;   // a store whose semanticSearch throws
 * ObservationIndex index = new KnowledgeStoreObservationIndex(knowledgeStore);
 * ObservationStore store = new IndexedObservationStore(metadata, index);
 * // store.semanticSearch(...) now works; metadata still persists where it did.
 * }
 * </pre>
 *
 * <p>
 * There is no such store in this repository today. The two that were —
 * {@code PostgresObservationStore} and {@code MongoObservationStore}, which
 * this javadoc used to name — were removed with their modules when distributed
 * memory became a separate service consumed through
 * {@link PeerMemory}. The decorator stays because the shape it serves does not
 * depend on those two: it is what an assembly reaches for whenever the store it
 * has and the index it wants are different things, and the contract it upholds
 * — a tier that is offered answers — is asserted by the shared backend contract
 * suite rather than by any one backend.
 *
 * <h2>Write-through indexing</h2>
 *
 * The decorator <em>owns indexing</em>: every {@link #save}, {@link #delete},
 * and {@link #merge} updates the {@link ObservationIndex} after the metadata
 * store call succeeds, so the index stays consistent with the store. This
 * mirrors the in-tree reference {@link InMemoryObservationStore}, which composes
 * the same two collaborators internally.
 *
 * <p>
 * <strong>One writer per index.</strong> Write-through is synchronous. If the
 * wrapped metadata store already feeds the same backing index out of band, do
 * <em>not</em> also point this decorator's index at it — pick one strategy per
 * index, or every observation is indexed twice. An out-of-band path can be
 * transactionally consistent with the metadata write; this decorator is
 * simpler, and the price is that an index failure after a successful metadata
 * write leaves the two briefly out of sync.
 *
 * <p>
 * Thread-safety follows the wrapped collaborators: this class adds no mutable
 * state of its own, so it is safe for concurrent use when both the delegate
 * store and the index are.
 */
public final class IndexedObservationStore implements ObservationStore {

    private static final Logger log = LoggerFactory.getLogger(IndexedObservationStore.class);

    private final ObservationStore delegate;
    private final ObservationIndex index;

    /**
     * Creates a decorator that delegates metadata operations to {@code delegate}
     * and search to {@code index}.
     *
     * @param delegate
     *            metadata store (must not be null); typically a persistent
     *            implementation whose {@link #semanticSearch} throws
     * @param index
     *            search index kept in sync on every write (must not be null)
     * @throws NullPointerException
     *             if either argument is null
     */
    public IndexedObservationStore(ObservationStore delegate, ObservationIndex index) {
        this.delegate = Objects.requireNonNull(delegate, "delegate cannot be null");
        this.index = Objects.requireNonNull(index, "index cannot be null");
    }

    @Override
    public Observation save(Observation observation) {
        Objects.requireNonNull(observation, "observation cannot be null");
        final Observation saved = delegate.save(observation);
        index.index(saved);
        log.debug("Indexed observation on save: id={}", saved.getId());
        return saved;
    }

    @Override
    public Optional<Observation> findById(ObservationId id) {
        return delegate.findById(id);
    }

    @Override
    public List<Observation> findBySubject(PeerView subject, int limit) {
        return delegate.findBySubject(subject, limit);
    }

    @Override
    public long count(PeerView subject) {
        return delegate.count(subject);
    }

    @Override
    public List<Observation> semanticSearch(PeerView subject, String query, int topK) {
        Objects.requireNonNull(subject, "subject cannot be null");
        Objects.requireNonNull(query, "query cannot be null");
        if (topK < 1) {
            throw new IllegalArgumentException("topK must be >= 1, got " + topK);
        }
        final List<ObservationId> ids = index.search(subject, query, topK);
        if (ids.isEmpty()) {
            log.debug("semanticSearch returned 0 hits: subject={}", subject);
            return List.of();
        }
        final List<Observation> hydrated = new ArrayList<>(ids.size());
        for (ObservationId id : ids) {
            delegate.findById(id).ifPresent(hydrated::add);
        }
        log.debug("semanticSearch hydrated {}/{} hits: subject={}", hydrated.size(), ids.size(), subject);
        return List.copyOf(hydrated);
    }

    @Override
    public List<Observation> findByConfidenceBelow(PeerView subject, double threshold, int limit) {
        return delegate.findByConfidenceBelow(subject, threshold, limit);
    }

    @Override
    public List<PeerView> findSubjects(Workspace workspace, int limit) {
        return delegate.findSubjects(workspace, limit);
    }

    @Override
    public void delete(ObservationId id) {
        Objects.requireNonNull(id, "id cannot be null");
        delegate.delete(id);
        index.delete(id);
        log.debug("Removed observation from index on delete: id={}", id);
    }

    @Override
    public Observation merge(ObservationId winner, ObservationId loser, Observation merged) {
        Objects.requireNonNull(winner, "winner cannot be null");
        Objects.requireNonNull(loser, "loser cannot be null");
        Objects.requireNonNull(merged, "merged cannot be null");
        final Observation result = delegate.merge(winner, loser, merged);
        index.delete(loser);
        index.index(result);
        log.debug("Reindexed observation on merge: winner={}, loser={}", winner, loser);
        return result;
    }

    @Override
    public void softDelete(ObservationId id) {
        Objects.requireNonNull(id, "id cannot be null");
        delegate.softDelete(id);
        // A soft-deleted observation must not surface in search; drop it from the index. Its metadata
        // is retained by the delegate until purgeSoftDeletedBefore removes it.
        index.delete(id);
        log.debug("Removed observation from index on soft-delete: id={}", id);
    }

    @Override
    public int purgeSoftDeletedBefore(Workspace workspace, Instant cutoff) {
        // The index entry was already removed at soft-delete time; purge only affects retained metadata.
        return delegate.purgeSoftDeletedBefore(workspace, cutoff);
    }
}
