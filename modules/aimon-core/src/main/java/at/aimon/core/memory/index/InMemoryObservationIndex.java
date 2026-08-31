package at.aimon.core.memory.index;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.memory.Observation;
import at.aimon.core.memory.ObservationId;
import at.aimon.core.memory.PeerView;

/**
 * In-memory {@link ObservationIndex} that ranks by case-insensitive substring
 * match against {@link Observation#getContent()}, then by descending confidence
 * and recency.
 *
 * <p>
 * This is the dev/test default. It owns no embedding state — the same logic
 * previously lived inside {@code InMemoryObservationStore.semanticSearch} and
 * has been lifted out unchanged so the metadata store can run against richer
 * indexes without modification (design doc §5.2).
 *
 * <p>
 * Thread-safe: backed by a {@link ConcurrentHashMap} of observation snapshots.
 */
public final class InMemoryObservationIndex implements ObservationIndex {

    private static final Logger log = LoggerFactory.getLogger(InMemoryObservationIndex.class);

    private final Map<ObservationId, Observation> entries = new ConcurrentHashMap<>();

    @Override
    public void index(Observation observation) {
        Objects.requireNonNull(observation, "observation cannot be null");
        entries.put(observation.getId(), observation);
        log.debug("Indexed observation: id={}, subject={}", observation.getId(), observation.getSubject());
    }

    @Override
    public void delete(ObservationId id) {
        Objects.requireNonNull(id, "id cannot be null");
        if (entries.remove(id) != null) {
            log.debug("Removed observation from index: id={}", id);
        }
    }

    @Override
    public List<ObservationId> search(PeerView subject, String query, int topK) {
        Objects.requireNonNull(subject, "subject cannot be null");
        Objects.requireNonNull(query, "query cannot be null");
        if (topK < 1) {
            throw new IllegalArgumentException("topK must be >= 1, got " + topK);
        }
        if (query.isBlank()) {
            log.debug("Substring search short-circuited (blank query): subject={}", subject);
            return List.of();
        }
        String needle = query.toLowerCase(Locale.ROOT);
        List<ObservationId> hits = entries.values().stream().filter(o -> o.getSubject().equals(subject))
                .filter(o -> o.getContent().toLowerCase(Locale.ROOT).contains(needle))
                .sorted(Comparator.comparing(Observation::getConfidence).reversed()
                        .thenComparing(Comparator.comparing(Observation::getCreatedAt).reversed()))
                .limit(topK).map(Observation::getId).collect(Collectors.toUnmodifiableList());
        log.debug("Substring search: subject={}, topK={}, queryLen={}, hits={}", subject, topK, query.length(),
                hits.size());
        return hits;
    }

    /** Test helper: number of indexed entries. */
    public int size() {
        return entries.size();
    }
}
