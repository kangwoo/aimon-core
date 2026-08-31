/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.memory.file;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import at.aimon.core.memory.Observation;
import at.aimon.core.memory.ObservationId;
import at.aimon.core.memory.ObservationStore;
import at.aimon.core.memory.PeerView;
import at.aimon.core.memory.Workspace;
import at.aimon.core.memory.index.InMemoryObservationIndex;
import at.aimon.core.memory.index.ObservationIndex;
import at.aimon.memory.file.internal.JsonLineLog;
import at.aimon.memory.file.internal.MemoryJsonCodec;

/**
 * File-backed {@link ObservationStore}. Persists every mutation to a JSON
 * Lines log and keeps an in-memory mirror plus an {@link ObservationIndex} for
 * search.
 *
 * <p>
 * Single JVM only. The injected {@link ObservationIndex} (defaults to
 * {@link InMemoryObservationIndex}) mirrors the metadata store; semantic
 * search delegates to the index exactly like the in-memory implementation.
 *
 * <p>
 * Merge losers and {@link #softDelete(ObservationId)} targets are retained in an
 * in-memory audit map (design doc §5.2, 30-day soft-delete) and removed by
 * {@link #purgeSoftDeletedBefore(Workspace, java.time.Instant)}; the append log
 * records each soft-delete / purge so the audit state is rebuilt on replay.
 *
 * <p>
 * The append log is {@link #compact() compacted} (rewritten to the minimal
 * live + audit line set) on demand, automatically when it grows past a
 * threshold, and once at open time if replay found it bloated — so disk usage
 * and restart-replay cost track live state, not total mutation history.
 * {@link #close()} releases the underlying single-process file lock.
 */
public class FileObservationStore implements ObservationStore, Compactable, AutoCloseable {

    private static final String OP_SAVE = "save";
    private static final String OP_DELETE = "delete";
    private static final String OP_MERGE = "merge";
    private static final String OP_SOFT_DELETE = "softDelete";
    private static final String OP_PURGE = "purge";

    /** Auto-compact once the journal exceeds {@code max(MIN_LINES, live * GROWTH_FACTOR)} lines. */
    private static final long AUTO_COMPACT_MIN_LINES = 10_000L;
    private static final int AUTO_COMPACT_GROWTH_FACTOR = 3;

    private static final Logger log = LoggerFactory.getLogger(FileObservationStore.class);

    private final JsonLineLog journal;
    private final MemoryJsonCodec codec;
    private final Map<ObservationId, Observation> storage = new LinkedHashMap<>();
    /** Soft-deleted observations retained for the audit window, keyed by id. */
    private final Map<ObservationId, Tombstone> audit = new LinkedHashMap<>();
    private final ObservationIndex index;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    /** Total lines currently in the journal; drives auto-compaction. Guarded by the write lock. */
    private long journalLines;

    public FileObservationStore(Path logFile) {
        this(logFile, new InMemoryObservationIndex(), true);
    }

    public FileObservationStore(Path logFile, ObservationIndex index, boolean fsyncOnAppend) {
        Objects.requireNonNull(logFile, "logFile cannot be null");
        this.index = Objects.requireNonNull(index, "index cannot be null");
        this.journal = new JsonLineLog(logFile, fsyncOnAppend);
        this.codec = new MemoryJsonCodec();
        replay();
    }

    private void replay() {
        long[] counter = {0L};
        journal.replay(line -> {
            counter[0]++;
            try {
                JsonNode node = codec.readTree(line);
                String op = node.get("op").asText();
                switch (op) {
                    case OP_SAVE -> {
                        Observation obs = codec.observationFromJson(node.get("observation"));
                        storage.put(obs.getId(), obs);
                        index.index(obs);
                    }
                    case OP_DELETE -> {
                        ObservationId id = codec.observationIdFromJson(node.get("id"));
                        storage.remove(id);
                        index.delete(id);
                    }
                    case OP_MERGE -> {
                        ObservationId winner = codec.observationIdFromJson(node.get("winnerId"));
                        ObservationId loser = codec.observationIdFromJson(node.get("loserId"));
                        Observation merged = codec.observationFromJson(node.get("merged"));
                        Instant deletedAt = node.has("loserDeletedAt")
                                ? Instant.parse(node.get("loserDeletedAt").asText())
                                : merged.getCreatedAt();
                        tombstone(loser, deletedAt);
                        storage.put(winner, merged);
                        index.index(merged);
                    }
                    case OP_SOFT_DELETE -> {
                        Instant deletedAt = node.has("deletedAt")
                                ? Instant.parse(node.get("deletedAt").asText())
                                : Instant.EPOCH;
                        if (node.has("observation")) {
                            // Compacted form (and current live form): the tombstone carries its observation,
                            // so the audit entry is reconstructable without a preceding SAVE.
                            Observation obs = codec.observationFromJson(node.get("observation"));
                            storage.remove(obs.getId());
                            index.delete(obs.getId());
                            audit.put(obs.getId(), new Tombstone(obs, deletedAt));
                        } else {
                            // Legacy form: only the id was journaled; reconstruct from the live mirror.
                            tombstone(codec.observationIdFromJson(node.get("id")), deletedAt);
                        }
                    }
                    case OP_PURGE -> {
                        String workspaceId = node.get("workspaceId").asText();
                        Instant cutoff = Instant.parse(node.get("cutoff").asText());
                        applyPurge(workspaceId, cutoff);
                    }
                    default -> log.warn("Skipping unknown observation log op '{}' on line {}", op, counter[0]);
                }
            } catch (RuntimeException e) {
                log.warn("Skipping malformed observation log line {}: {}", counter[0], e.getMessage());
            }
        });
        journalLines = counter[0];
        log.debug("Replayed {} observation log lines from {}", counter[0], journal.file());
        // Cold-start compaction: if replay rebuilt a small live state from a bloated log, shrink it now so
        // the next restart is fast and disk usage tracks live state.
        if (exceedsCompactionThreshold()) {
            compact();
        }
    }

    @Override
    public Observation save(Observation observation) {
        Objects.requireNonNull(observation, "observation cannot be null");
        lock.writeLock().lock();
        try {
            // Append to the durable journal first; only mutate the in-memory mirror/index after the
            // write succeeds, so a failed append cannot leave memory inconsistent with disk.
            ObjectNode event = codec.newObject();
            event.put("op", OP_SAVE);
            event.set("observation", codec.observationToJson(observation));
            journal.append(codec.writeAsString(event));
            storage.put(observation.getId(), observation);
            index.index(observation);
            recordAppend();
            return observation;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public Optional<Observation> findById(ObservationId id) {
        Objects.requireNonNull(id, "id cannot be null");
        lock.readLock().lock();
        try {
            return Optional.ofNullable(storage.get(id));
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public List<Observation> findBySubject(PeerView subject, int limit) {
        Objects.requireNonNull(subject, "subject cannot be null");
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be >= 1, got " + limit);
        }
        lock.readLock().lock();
        try {
            return storage.values().stream().filter(o -> o.getSubject().equals(subject))
                    .sorted(Comparator.comparing(Observation::getCreatedAt).reversed()).limit(limit)
                    .collect(Collectors.toUnmodifiableList());
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public long count(PeerView subject) {
        Objects.requireNonNull(subject, "subject cannot be null");
        lock.readLock().lock();
        try {
            return storage.values().stream().filter(o -> o.getSubject().equals(subject)).count();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public List<Observation> semanticSearch(PeerView subject, String query, int topK) {
        Objects.requireNonNull(subject, "subject cannot be null");
        Objects.requireNonNull(query, "query cannot be null");
        if (topK < 1) {
            throw new IllegalArgumentException("topK must be >= 1, got " + topK);
        }
        lock.readLock().lock();
        try {
            List<ObservationId> ids = index.search(subject, query, topK);
            if (ids.isEmpty()) {
                return List.of();
            }
            List<Observation> hydrated = new ArrayList<>(ids.size());
            for (ObservationId id : ids) {
                Observation obs = storage.get(id);
                if (obs != null) {
                    hydrated.add(obs);
                }
            }
            return List.copyOf(hydrated);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public List<Observation> findByConfidenceBelow(PeerView subject, double threshold, int limit) {
        Objects.requireNonNull(subject, "subject cannot be null");
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be >= 1, got " + limit);
        }
        lock.readLock().lock();
        try {
            return storage.values().stream().filter(o -> o.getSubject().equals(subject))
                    .filter(o -> o.getConfidence() < threshold)
                    .sorted(Comparator.comparingDouble(Observation::getConfidence)).limit(limit)
                    .collect(Collectors.toUnmodifiableList());
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public List<PeerView> findSubjects(Workspace workspace, int limit) {
        Objects.requireNonNull(workspace, "workspace cannot be null");
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be >= 1, got " + limit);
        }
        lock.readLock().lock();
        try {
            return storage.values().stream().filter(o -> o.getSubject().getWorkspace().equals(workspace))
                    .map(Observation::getSubject).distinct().limit(limit).collect(Collectors.toUnmodifiableList());
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void delete(ObservationId id) {
        Objects.requireNonNull(id, "id cannot be null");
        lock.writeLock().lock();
        try {
            if (!storage.containsKey(id)) {
                return;
            }
            // Journal first, then mutate the in-memory mirror/index.
            ObjectNode event = codec.newObject();
            event.put("op", OP_DELETE);
            event.set("id", codec.observationIdToJson(id));
            journal.append(codec.writeAsString(event));
            storage.remove(id);
            index.delete(id);
            recordAppend();
        } finally {
            lock.writeLock().unlock();
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
        lock.writeLock().lock();
        try {
            // Journal first, then mutate the in-memory mirror/index. The loser is soft-deleted into
            // the audit map (recoverable until purge), not discarded.
            Instant deletedAt = Instant.now();
            ObjectNode event = codec.newObject();
            event.put("op", OP_MERGE);
            event.set("winnerId", codec.observationIdToJson(winner));
            event.set("loserId", codec.observationIdToJson(loser));
            event.set("merged", codec.observationToJson(merged));
            event.put("loserDeletedAt", deletedAt.toString());
            journal.append(codec.writeAsString(event));
            tombstone(loser, deletedAt);
            storage.put(winner, merged);
            index.index(merged);
            recordAppend();
            return merged;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void softDelete(ObservationId id) {
        Objects.requireNonNull(id, "id cannot be null");
        lock.writeLock().lock();
        try {
            Observation target = storage.get(id);
            if (target == null) {
                return;
            }
            Instant deletedAt = Instant.now();
            // Carry the full observation so the audit entry survives compaction (which drops the SAVE).
            ObjectNode event = codec.newObject();
            event.put("op", OP_SOFT_DELETE);
            event.set("observation", codec.observationToJson(target));
            event.put("deletedAt", deletedAt.toString());
            journal.append(codec.writeAsString(event));
            tombstone(id, deletedAt);
            recordAppend();
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public int purgeSoftDeletedBefore(Workspace workspace, Instant cutoff) {
        Objects.requireNonNull(workspace, "workspace cannot be null");
        Objects.requireNonNull(cutoff, "cutoff cannot be null");
        lock.writeLock().lock();
        try {
            boolean any = audit.values().stream().anyMatch(
                    t -> t.observation.getSubject().getWorkspace().equals(workspace) && t.deletedAt.isBefore(cutoff));
            if (!any) {
                return 0;
            }
            // Journal the purge tombstone first, then apply it to the in-memory audit map.
            ObjectNode event = codec.newObject();
            event.put("op", OP_PURGE);
            event.put("workspaceId", workspace.getId());
            event.put("cutoff", cutoff.toString());
            journal.append(codec.writeAsString(event));
            int purged = applyPurge(workspace.getId(), cutoff);
            recordAppend();
            return purged;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Moves {@code id} from live storage into the audit map (and drops it from the index). Shared by the
     * live soft-delete/merge paths and by replay. No-op if {@code id} is not live.
     */
    private void tombstone(ObservationId id, Instant when) {
        Observation removed = storage.remove(id);
        index.delete(id);
        if (removed != null) {
            audit.put(id, new Tombstone(removed, when));
        }
    }

    /**
     * Removes audit entries for {@code workspaceId} whose deletion time is before {@code cutoff}; returns the count.
     */
    private int applyPurge(String workspaceId, Instant cutoff) {
        int purged = 0;
        Iterator<Map.Entry<ObservationId, Tombstone>> it = audit.entrySet().iterator();
        while (it.hasNext()) {
            Tombstone t = it.next().getValue();
            if (t.observation.getSubject().getWorkspace().getId().equals(workspaceId) && t.deletedAt.isBefore(cutoff)) {
                it.remove();
                purged++;
            }
        }
        return purged;
    }

    @Override
    public void compact() {
        lock.writeLock().lock();
        try {
            List<String> lines = new ArrayList<>(storage.size() + audit.size());
            for (Observation o : storage.values()) {
                ObjectNode e = codec.newObject();
                e.put("op", OP_SAVE);
                e.set("observation", codec.observationToJson(o));
                lines.add(codec.writeAsString(e));
            }
            for (Map.Entry<ObservationId, Tombstone> entry : audit.entrySet()) {
                ObjectNode e = codec.newObject();
                e.put("op", OP_SOFT_DELETE);
                e.set("observation", codec.observationToJson(entry.getValue().observation));
                e.put("deletedAt", entry.getValue().deletedAt.toString());
                lines.add(codec.writeAsString(e));
            }
            journal.rewrite(lines);
            journalLines = lines.size();
            log.debug("Compacted observation log: {} live + {} tombstones -> {} lines", storage.size(), audit.size(),
                    lines.size());
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Releases the underlying single-process file lock. The store must not be used after closing. */
    @Override
    public void close() {
        journal.close();
    }

    /** Records that one line was appended and compacts if the journal has grown past the threshold. */
    private void recordAppend() {
        journalLines++;
        if (exceedsCompactionThreshold()) {
            compact();
        }
    }

    private boolean exceedsCompactionThreshold() {
        long live = (long) storage.size() + audit.size();
        return journalLines > Math.max(AUTO_COMPACT_MIN_LINES, live * AUTO_COMPACT_GROWTH_FACTOR);
    }

    /** Test helper: number of observations currently stored (live, excluding soft-deleted). */
    public int size() {
        lock.readLock().lock();
        try {
            return storage.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Test helper: number of soft-deleted observations retained in the audit window. */
    public int auditSize() {
        lock.readLock().lock();
        try {
            return audit.size();
        } finally {
            lock.readLock().unlock();
        }
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
