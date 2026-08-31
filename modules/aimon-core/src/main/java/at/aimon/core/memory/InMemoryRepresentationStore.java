package at.aimon.core.memory;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default in-memory implementation of {@link RepresentationStore}.
 *
 * <p>
 * Backed by a {@link CopyOnWriteArrayList} so that {@code findLatest*} can scan
 * snapshots without external synchronization. Insert frequency is expected to
 * be much lower than read frequency, so the copy-on-write cost is acceptable
 * for the in-memory backend. Production backends should use indexed storage.
 */
public class InMemoryRepresentationStore implements RepresentationStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryRepresentationStore.class);

    private final List<Representation> storage = new CopyOnWriteArrayList<>();

    @Override
    public Representation save(Representation representation) {
        Objects.requireNonNull(representation, "representation cannot be null");
        storage.add(representation);
        log.info("Saved representation: subject={}, scope={}, generatedAt={}", representation.getSubject(),
                representation.isGlobal() ? "GLOBAL" : "LOCAL", representation.getGeneratedAt());
        return representation;
    }

    @Override
    public Optional<Representation> findLatestGlobal(PeerView subject) {
        Objects.requireNonNull(subject, "subject cannot be null");
        return storage.stream().filter(Representation::isGlobal).filter(r -> r.getSubject().equals(subject))
                .max(Comparator.comparing(Representation::getGeneratedAt));
    }

    @Override
    public Optional<Representation> findLatestLocal(PeerView subject, PeerView observer, String sessionId) {
        Objects.requireNonNull(subject, "subject cannot be null");
        Objects.requireNonNull(observer, "observer cannot be null");
        return storage.stream().filter(Representation::isLocal).filter(r -> r.getSubject().equals(subject))
                .filter(r -> r.getObserver().map(observer::equals).orElse(false))
                .filter(r -> Objects.equals(r.getSessionId().orElse(null), sessionId))
                .max(Comparator.comparing(Representation::getGeneratedAt));
    }

    @Override
    public void deleteOlderThan(Workspace workspace, Instant cutoff) {
        Objects.requireNonNull(workspace, "workspace cannot be null");
        Objects.requireNonNull(cutoff, "cutoff cannot be null");
        final int sizeBefore = storage.size();
        storage.removeIf(r -> r.getSubject().getWorkspace().equals(workspace) && r.getGeneratedAt().isBefore(cutoff));
        final int removed = sizeBefore - storage.size();
        if (removed > 0) {
            log.info("Pruned {} representations older than {} in workspace={}", removed, cutoff, workspace);
        } else {
            log.debug("Prune no-op: workspace={}, cutoff={}", workspace, cutoff);
        }
    }

    /** Test helper: number of representations currently stored. */
    public int size() {
        return storage.size();
    }
}
