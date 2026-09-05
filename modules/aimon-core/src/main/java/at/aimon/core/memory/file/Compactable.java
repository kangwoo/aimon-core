/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.core.memory.file;

/**
 * A file-backed store whose append-only journal can be compacted — rewritten to
 * the minimal set of lines that represents its current state, reclaiming the
 * space taken by superseded {@code save}/{@code delete}/{@code merge} records.
 *
 * <p>
 * Compaction is safe to call at any time: the store rewrites the journal
 * atomically while holding its write lock, leaving the in-memory state and the
 * on-disk live state unchanged but the file size proportional to live entities
 * rather than to total mutation history. The file stores also self-compact when
 * the journal grows past a threshold; {@link FileMemoryMaintenanceScheduler}
 * drives it proactively alongside retention.
 */
public interface Compactable {

    /** Rewrites the journal to the minimal line set for the current state. */
    void compact();
}
