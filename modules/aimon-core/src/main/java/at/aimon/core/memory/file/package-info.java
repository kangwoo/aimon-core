/*
 * Copyright 2025 the original author or authors.
 */

/**
 * File-backed implementations of the memory store SPIs
 * ({@link at.aimon.core.memory.WorkspaceStore},
 * {@link at.aimon.core.memory.ObservationStore},
 * {@link at.aimon.core.memory.RepresentationStore}).
 *
 * <p>
 * Persistence model: append-only JSON Lines log per store, replayed at open
 * time to rebuild an in-memory mirror, with configurable fsync. Logs are
 * {@linkplain at.aimon.core.memory.file.Compactable compacted} (rewritten to the
 * minimal live-state line set via an atomic temp-file swap) on demand,
 * automatically once they grow past a threshold, and at open time — so disk
 * usage and restart-replay cost track live state, not total mutation history.
 * {@link at.aimon.core.memory.file.FileMemoryMaintenanceScheduler} drives retention
 * (soft-delete purge + representation pruning) and compaction on a schedule,
 * independent of the Dreamer.
 *
 * <p>
 * Single-JVM only — these stores hold an exclusive OS file lock on a sidecar
 * {@code <log>.lock} file (fail-fast if another process/store opens the same
 * log) plus an in-process {@link
 * java.util.concurrent.locks.ReentrantReadWriteLock}. Each store is
 * {@link java.lang.AutoCloseable} and releases its lock on {@code close()} (or
 * JVM exit). Multi-instance / scale-out deployments do not replace these
 * stores; they replace the whole backend, by assembling a remote
 * {@link at.aimon.core.memory.PeerMemory} instead of
 * {@link at.aimon.core.memory.StoreBackedPeerMemory}.
 *
 * <p>
 * This package lived in a module of its own ({@code aimon-memory-file}, package
 * {@code at.aimon.memory.file}) until the distributed backends moved out of
 * this repository. It is here rather than under {@code .impl} for the reason
 * given in {@code docs/design/memory/pluggable-memory-backend.md} §4.2: the
 * memory domain has no {@code .impl} split yet, and its in-tree defaults
 * ({@code InMemory*Store}) sit beside the SPI in {@code at.aimon.core.memory}.
 * The storage format — one JSON Lines record per mutation — is frozen; a
 * reader of an existing log must keep working across this move.
 */
package at.aimon.core.memory.file;
