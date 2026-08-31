/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.memory.file;

import java.nio.file.Path;
import java.util.ArrayList;
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

import at.aimon.core.base.Principal;
import at.aimon.core.memory.Workspace;
import at.aimon.core.memory.WorkspaceAccessPolicy;
import at.aimon.core.memory.WorkspaceStore;
import at.aimon.memory.file.internal.JsonLineLog;
import at.aimon.memory.file.internal.MemoryJsonCodec;

/**
 * File-backed {@link WorkspaceStore}. Holds the canonical state in memory and
 * appends every mutation to a JSON Lines log so it can be replayed on next
 * open.
 *
 * <p>
 * Single JVM only. Multi-instance deployments must use the Postgres backend.
 *
 * <p>
 * Concurrency model: a single {@link ReentrantReadWriteLock} protects the
 * in-memory mirror; reads take the read lock, mutations take the write lock
 * (and synchronously append to the log before releasing). The log is opened
 * lazily for each append to avoid keeping a writer between calls.
 *
 * <p>
 * {@link #findAll(Principal)} is filtered by a {@link WorkspaceAccessPolicy}
 * (default {@link at.aimon.core.memory.DefaultWorkspaceAccessPolicy}) so a
 * requester only lists workspaces it is entitled to. The log is
 * {@link #compact() compacted} on demand / past a threshold / at open, and
 * {@link #close()} releases the single-process file lock.
 */
public class FileWorkspaceStore implements WorkspaceStore, Compactable, AutoCloseable {

    private static final String OP_CREATE = "create";
    private static final String OP_DELETE = "delete";

    /** Auto-compact once the journal exceeds {@code max(MIN_LINES, live * GROWTH_FACTOR)} lines. */
    private static final long AUTO_COMPACT_MIN_LINES = 10_000L;
    private static final int AUTO_COMPACT_GROWTH_FACTOR = 3;

    private static final Logger log = LoggerFactory.getLogger(FileWorkspaceStore.class);

    private final JsonLineLog journal;
    private final MemoryJsonCodec codec;
    private final Map<String, Workspace> storage = new LinkedHashMap<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final WorkspaceAccessPolicy accessPolicy;

    /** Total lines currently in the journal; drives auto-compaction. Guarded by the write lock. */
    private long journalLines;

    /** Creates a store backed by {@code logFile}; replays it immediately. */
    public FileWorkspaceStore(Path logFile) {
        this(logFile, true);
    }

    /**
     * @param logFile
     *            JSONL log file. Created on first append if missing.
     * @param fsyncOnAppend
     *            if true, every mutation is flushed to disk before returning.
     */
    public FileWorkspaceStore(Path logFile, boolean fsyncOnAppend) {
        this(logFile, fsyncOnAppend, WorkspaceAccessPolicy.defaults());
    }

    /**
     * @param logFile
     *            JSONL log file. Created on first append if missing.
     * @param fsyncOnAppend
     *            if true, every mutation is flushed to disk before returning.
     * @param accessPolicy
     *            policy applied by {@link #findAll(Principal)} (must not be null)
     */
    public FileWorkspaceStore(Path logFile, boolean fsyncOnAppend, WorkspaceAccessPolicy accessPolicy) {
        Objects.requireNonNull(logFile, "logFile cannot be null");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy cannot be null");
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
                    case OP_CREATE :
                        Workspace ws = codec.workspaceFromJson(node.get("workspace"));
                        storage.put(ws.getId(), ws);
                        break;
                    case OP_DELETE :
                        storage.remove(node.get("id").asText());
                        break;
                    default :
                        log.warn("Skipping unknown workspace log op '{}' on line {}", op, counter[0]);
                }
            } catch (RuntimeException e) {
                log.warn("Skipping malformed workspace log line {}: {}", counter[0], e.getMessage());
            }
        });
        journalLines = counter[0];
        log.debug("Replayed {} workspace log lines from {}", counter[0], journal.file());
        if (exceedsCompactionThreshold()) {
            compact();
        }
    }

    @Override
    public Workspace create(Workspace workspace) {
        Objects.requireNonNull(workspace, "workspace cannot be null");
        lock.writeLock().lock();
        try {
            if (storage.containsKey(workspace.getId())) {
                throw new IllegalStateException("Workspace already exists: " + workspace.getId());
            }
            // Journal first, then mutate the in-memory mirror so a failed append cannot desync.
            ObjectNode event = codec.newObject();
            event.put("op", OP_CREATE);
            event.set("workspace", codec.workspaceToJson(workspace));
            journal.append(codec.writeAsString(event));
            storage.put(workspace.getId(), workspace);
            recordAppend();
            return workspace;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public Optional<Workspace> findById(String id) {
        Objects.requireNonNull(id, "id cannot be null");
        lock.readLock().lock();
        try {
            return Optional.ofNullable(storage.get(id));
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public List<Workspace> findAll(Principal requester) {
        Objects.requireNonNull(requester, "requester cannot be null");
        lock.readLock().lock();
        try {
            return storage.values().stream().filter(ws -> accessPolicy.canAccess(requester, ws))
                    .collect(Collectors.toUnmodifiableList());
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void delete(Workspace workspace) {
        Objects.requireNonNull(workspace, "workspace cannot be null");
        lock.writeLock().lock();
        try {
            if (!storage.containsKey(workspace.getId())) {
                return;
            }
            // Journal first, then mutate the in-memory mirror.
            ObjectNode event = codec.newObject();
            event.put("op", OP_DELETE);
            event.put("id", workspace.getId());
            journal.append(codec.writeAsString(event));
            storage.remove(workspace.getId());
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
            for (Workspace ws : storage.values()) {
                ObjectNode event = codec.newObject();
                event.put("op", OP_CREATE);
                event.set("workspace", codec.workspaceToJson(ws));
                lines.add(codec.writeAsString(event));
            }
            journal.rewrite(lines);
            journalLines = lines.size();
            log.debug("Compacted workspace log: {} workspaces -> {} lines", storage.size(), lines.size());
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

    /** Test helper: number of workspaces currently in memory. */
    public int size() {
        lock.readLock().lock();
        try {
            return storage.size();
        } finally {
            lock.readLock().unlock();
        }
    }
}
