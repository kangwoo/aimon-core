package at.aimon.core.memory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.memory.index.InMemoryObservationIndex;
import at.aimon.core.memory.index.ObservationIndex;

/**
 * Default in-memory implementation of {@link ObservationStore}.
 *
 * <p>
 * Holds the metadata side of the store/index split (design doc §5.2): relations,
 * confidence, audit. The search side is delegated to an
 * {@link ObservationIndex} so the same store can run against an in-memory
 * substring index for tests, a vector index over {@code KnowledgeStore} in
 * production, or a keyword fallback when embeddings are unavailable.
 *
 * <p>
 * {@link #merge(ObservationId, ObservationId, Observation)} and
 * {@link #softDelete(ObservationId)} soft-delete the retired observation into an
 * in-memory audit map (design doc §5.2); {@link #purgeSoftDeletedBefore} removes
 * audit entries past the retention window. The audit map is non-durable like the
 * rest of this store.
 *
 * <p>
 * Thread-safe but not durable. Development and tests only.
 */
public class InMemoryObservationStore implements ObservationStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryObservationStore.class);

    private final Map<ObservationId, Observation> storage = new ConcurrentHashMap<>();
    /** Soft-deleted observations retained for the audit window, keyed by id. */
    private final Map<ObservationId, Tombstone> audit = new ConcurrentHashMap<>();
    /** Serialises the cross-structure write paths (merge/softDelete/purge) so an observer never sees a torn move. */
    private final Object writeLock = new Object();
    private final ObservationIndex index;

    /** Creates a store backed by a fresh {@link InMemoryObservationIndex}. */
    public InMemoryObservationStore() {
        this(new InMemoryObservationIndex());
    }

    /**
     * Creates a store that delegates {@link #semanticSearch} to {@code index}.
     * The injected index also receives every {@link #save}, {@link #delete}, and
     * {@link #merge} call so its view stays consistent with the metadata store.
     */
    public InMemoryObservationStore(ObservationIndex index) {
        this.index = Objects.requireNonNull(index, "index cannot be null");
    }

    @Override
    public Observation save(Observation observation) {
        Objects.requireNonNull(observation, "observation cannot be null");
        final boolean replacing = storage.put(observation.getId(), observation) != null;
        index.index(observation);
        if (replacing) {
            log.debug("Replaced observation: id={}, subject={}, confidence={}", observation.getId(),
                    observation.getSubject(), observation.getConfidence());
        } else {
            log.info("Saved observation: id={}, subject={}, type={}, confidence={}", observation.getId(),
                    observation.getSubject(), observation.getType(), observation.getConfidence());
        }
        return observation;
    }

    @Override
    public Optional<Observation> findById(ObservationId id) {
        Objects.requireNonNull(id, "id cannot be null");
        return Optional.ofNullable(storage.get(id));
    }

    @Override
    public List<Observation> findBySubject(PeerView subject, int limit) {
        Objects.requireNonNull(subject, "subject cannot be null");
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be >= 1, got " + limit);
        }
        return storage.values().stream().filter(o -> o.getSubject().equals(subject))
                .sorted(Comparator.comparing(Observation::getCreatedAt).reversed()).limit(limit)
                .collect(Collectors.toUnmodifiableList());
    }

    @Override
    public long count(PeerView subject) {
        Objects.requireNonNull(subject, "subject cannot be null");
        return storage.values().stream().filter(o -> o.getSubject().equals(subject)).count();
    }

    @Override
    public List<Observation> semanticSearch(PeerView subject, String query, int topK) {
        Objects.requireNonNull(subject, "subject cannot be null");
        Objects.requireNonNull(query, "query cannot be null");
        if (topK < 1) {
            throw new IllegalArgumentException("topK must be >= 1, got " + topK);
        }
        log.debug("semanticSearch: subject={}, topK={}, queryLen={}", subject, topK, query.length());
        List<ObservationId> ids = index.search(subject, query, topK);
        if (ids.isEmpty()) {
            log.debug("semanticSearch returned 0 hits: subject={}", subject);
            return List.of();
        }
        List<Observation> hydrated = new ArrayList<>(ids.size());
        for (ObservationId id : ids) {
            Observation obs = storage.get(id);
            if (obs != null) {
                hydrated.add(obs);
            }
        }
        log.debug("semanticSearch hydrated {}/{} hits: subject={}", hydrated.size(), ids.size(), subject);
        return List.copyOf(hydrated);
    }

    @Override
    public List<Observation> findByConfidenceBelow(PeerView subject, double threshold, int limit) {
        Objects.requireNonNull(subject, "subject cannot be null");
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be >= 1, got " + limit);
        }
        return storage.values().stream().filter(o -> o.getSubject().equals(subject))
                .filter(o -> o.getConfidence() < threshold)
                .sorted(Comparator.comparingDouble(Observation::getConfidence)).limit(limit)
                .collect(Collectors.toUnmodifiableList());
    }

    @Override
    public List<PeerView> findSubjects(Workspace workspace, int limit) {
        Objects.requireNonNull(workspace, "workspace cannot be null");
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be >= 1, got " + limit);
        }
        return storage.values().stream().filter(o -> o.getSubject().getWorkspace().equals(workspace))
                .map(Observation::getSubject).distinct().limit(limit).collect(Collectors.toUnmodifiableList());
    }

    @Override
    public void delete(ObservationId id) {
        Objects.requireNonNull(id, "id cannot be null");
        final boolean existed = storage.remove(id) != null;
        index.delete(id);
        if (existed) {
            log.info("Deleted observation: id={}", id);
        } else {
            log.debug("Delete no-op (id absent): id={}", id);
        }
    }

    @Override
    public Observation merge(ObservationId winner, ObservationId loser, Observation merged) {
        Objects.requireNonNull(winner, "winner cannot be null");
        Objects.requireNonNull(loser, "loser cannot be null");
        Objects.requireNonNull(merged, "merged cannot be null");
        if (winner.equals(loser)) {
            throw new IllegalArgumentException("winner and loser must differ: " + winner);
        }
        if (!merged.getId().equals(winner)) {
            throw new IllegalArgumentException(
                    "merged observation id (" + merged.getId() + ") must equal winner (" + winner + ")");
        }
        if (!winner.getWorkspaceId().equals(loser.getWorkspaceId())) {
            throw new IllegalArgumentException("winner workspace (" + winner.getWorkspaceId()
                    + ") must equal loser workspace (" + loser.getWorkspaceId() + ")");
        }
        synchronized (writeLock) {
            // Soft-delete the loser into the audit map (recoverable until purge), then publish the winner.
            tombstone(loser);
            storage.put(winner, merged);
            index.index(merged);
        }
        log.info("Merged observations: winner={}, loser={}, confidence={}", winner, loser, merged.getConfidence());
        return merged;
    }

    @Override
    public void softDelete(ObservationId id) {
        Objects.requireNonNull(id, "id cannot be null");
        synchronized (writeLock) {
            if (tombstone(id)) {
                log.info("Soft-deleted observation: id={}", id);
            } else {
                log.debug("Soft-delete no-op (id absent): id={}", id);
            }
        }
    }

    @Override
    public int purgeSoftDeletedBefore(Workspace workspace, Instant cutoff) {
        Objects.requireNonNull(workspace, "workspace cannot be null");
        Objects.requireNonNull(cutoff, "cutoff cannot be null");
        synchronized (writeLock) {
            int purged = 0;
            Iterator<Map.Entry<ObservationId, Tombstone>> it = audit.entrySet().iterator();
            while (it.hasNext()) {
                Tombstone t = it.next().getValue();
                if (t.observation.getSubject().getWorkspace().equals(workspace) && t.deletedAt.isBefore(cutoff)) {
                    it.remove();
                    purged++;
                }
            }
            if (purged > 0) {
                log.info("Purged {} soft-deleted observations older than {} in workspace {}", purged, cutoff,
                        workspace.getId());
            }
            return purged;
        }
    }

    /**
     * Moves {@code id} from live storage into the audit map and drops it from the index. Caller must hold
     * {@link #writeLock}. Returns {@code true} if a live observation was tombstoned.
     */
    private boolean tombstone(ObservationId id) {
        Observation removed = storage.remove(id);
        if (removed == null) {
            return false;
        }
        index.delete(id);
        audit.put(id, new Tombstone(removed, Instant.now()));
        return true;
    }

    /** Test helper: number of observations currently stored (live, excluding soft-deleted). */
    public int size() {
        return storage.size();
    }

    /** Test helper: number of soft-deleted observations retained in the audit window. */
    public int auditSize() {
        return audit.size();
    }

    /** A soft-deleted observation plus the instant it was retired. */
    private static final class Tombstone {
        private final Observation observation;
        private final Instant deletedAt;

        Tombstone(Observation observation, Instant deletedAt) {
            this.observation = observation;
            this.deletedAt = deletedAt;
        }
    }
}
