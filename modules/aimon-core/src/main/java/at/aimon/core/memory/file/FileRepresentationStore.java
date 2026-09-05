/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.core.memory.file;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import at.aimon.core.memory.PeerView;
import at.aimon.core.memory.Representation;
import at.aimon.core.memory.RepresentationStore;
import at.aimon.core.memory.Workspace;
import at.aimon.core.memory.file.internal.JsonLineLog;
import at.aimon.core.memory.file.internal.MemoryJsonCodec;

/**
 * File-backed {@link RepresentationStore}. Append-only JSON Lines log;
 * {@link #deleteOlderThan(Workspace, Instant)} is recorded as a tombstone and
 * applied to the in-memory mirror in the same order on replay.
 *
 * <p>
 * Single JVM only. The log is {@link #compact() compacted} (rewritten to one
 * {@code save} line per surviving snapshot) on demand, automatically once it
 * grows past a threshold, and at open time if replay found it bloated — so disk
 * usage and restart cost track surviving snapshots, not total mutation history.
 * {@link #close()} releases the underlying single-process file lock.
 */
public class FileRepresentationStore implements RepresentationStore, Compactable, AutoCloseable {

    private static final String OP_SAVE = "save";
    private static final String OP_DELETE_OLDER_THAN = "deleteOlderThan";

    /** Auto-compact once the journal exceeds {@code max(MIN_LINES, live * GROWTH_FACTOR)} lines. */
    private static final long AUTO_COMPACT_MIN_LINES = 10_000L;
    private static final int AUTO_COMPACT_GROWTH_FACTOR = 3;

    private static final Logger log = LoggerFactory.getLogger(FileRepresentationStore.class);

    private final JsonLineLog journal;
    private final MemoryJsonCodec codec;
    private final List<Representation> storage = new ArrayList<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    /** Total lines currently in the journal; drives auto-compaction. Guarded by the write lock. */
    private long journalLines;

    public FileRepresentationStore(Path logFile) {
        this(logFile, true);
    }

    public FileRepresentationStore(Path logFile, boolean fsyncOnAppend) {
        Objects.requireNonNull(logFile, "logFile cannot be null");
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
                        Representation rep = codec.representationFromJson(node.get("representation"));
                        storage.add(rep);
                    }
                    case OP_DELETE_OLDER_THAN -> {
                        String workspaceId = node.get("workspaceId").asText();
                        Instant cutoff = Instant.parse(node.get("cutoff").asText());
                        storage.removeIf(r -> r.getSubject().getWorkspace().getId().equals(workspaceId)
                                && r.getGeneratedAt().isBefore(cutoff));
                    }
                    default -> log.warn("Skipping unknown representation log op '{}' on line {}", op, counter[0]);
                }
            } catch (RuntimeException e) {
                log.warn("Skipping malformed representation log line {}: {}", counter[0], e.getMessage());
            }
        });
        journalLines = counter[0];
        log.debug("Replayed {} representation log lines from {}", counter[0], journal.file());
        if (exceedsCompactionThreshold()) {
            compact();
        }
    }

    @Override
    public Representation save(Representation representation) {
        Objects.requireNonNull(representation, "representation cannot be null");
        lock.writeLock().lock();
        try {
            // Journal first, then mutate the in-memory mirror so a failed append cannot desync.
            ObjectNode event = codec.newObject();
            event.put("op", OP_SAVE);
            event.set("representation", codec.representationToJson(representation));
            journal.append(codec.writeAsString(event));
            storage.add(representation);
            recordAppend();
            return representation;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public Optional<Representation> findLatestGlobal(PeerView subject) {
        Objects.requireNonNull(subject, "subject cannot be null");
        lock.readLock().lock();
        try {
            return storage.stream().filter(Representation::isGlobal).filter(r -> r.getSubject().equals(subject))
                    .max(Comparator.comparing(Representation::getGeneratedAt));
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Optional<Representation> findLatestLocal(PeerView subject, PeerView observer, String sessionId) {
        Objects.requireNonNull(subject, "subject cannot be null");
        Objects.requireNonNull(observer, "observer cannot be null");
        lock.readLock().lock();
        try {
            return storage.stream().filter(Representation::isLocal).filter(r -> r.getSubject().equals(subject))
                    .filter(r -> r.getObserver().map(observer::equals).orElse(false))
                    .filter(r -> Objects.equals(r.getSessionId().orElse(null), sessionId))
                    .max(Comparator.comparing(Representation::getGeneratedAt));
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void deleteOlderThan(Workspace workspace, Instant cutoff) {
        Objects.requireNonNull(workspace, "workspace cannot be null");
        Objects.requireNonNull(cutoff, "cutoff cannot be null");
        lock.writeLock().lock();
        try {
            boolean matches = storage.stream().anyMatch(
                    r -> r.getSubject().getWorkspace().equals(workspace) && r.getGeneratedAt().isBefore(cutoff));
            if (!matches) {
                return;
            }
            // Journal the tombstone first, then apply it to the in-memory mirror.
            ObjectNode event = codec.newObject();
            event.put("op", OP_DELETE_OLDER_THAN);
            event.put("workspaceId", workspace.getId());
            event.put("cutoff", cutoff.toString());
            journal.append(codec.writeAsString(event));
            storage.removeIf(
                    r -> r.getSubject().getWorkspace().equals(workspace) && r.getGeneratedAt().isBefore(cutoff));
            recordAppend();
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void compact() {
        lock.writeLock().lock();
        try {
            List<String> lines = new ArrayList<>(storage.size());
            for (Representation rep : storage) {
                ObjectNode event = codec.newObject();
                event.put("op", OP_SAVE);
                event.set("representation", codec.representationToJson(rep));
                lines.add(codec.writeAsString(event));
            }
            journal.rewrite(lines);
            journalLines = lines.size();
            log.debug("Compacted representation log: {} snapshots -> {} lines", storage.size(), lines.size());
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Releases the underlying single-process file lock. The store must not be used after closing. */
    @Override
    public void close() {
        journal.close();
    }

    private void recordAppend() {
        journalLines++;
        if (exceedsCompactionThreshold()) {
            compact();
        }
    }

    private boolean exceedsCompactionThreshold() {
        return journalLines > Math.max(AUTO_COMPACT_MIN_LINES, (long) storage.size() * AUTO_COMPACT_GROWTH_FACTOR);
    }

    /** Test helper: number of representations currently in memory. */
    public int size() {
        lock.readLock().lock();
        try {
            return storage.size();
        } finally {
            lock.readLock().unlock();
        }
    }
}
