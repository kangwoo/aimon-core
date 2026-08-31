/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.memory.file.internal;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.base.exception.AimonException;

/**
 * Append-only JSON Lines log used by the file-backed memory stores.
 *
 * <p>
 * Each line is a single self-contained JSON object that records one mutation
 * (a {@code save}, {@code delete}, {@code merge}, etc.). Replaying the log in
 * order rebuilds the store's state. The log is opened lazily per-operation:
 * we do <em>not</em> hold an open writer between calls, so a JVM crash mid-
 * write at worst leaves a torn final line — replay simply ignores it.
 *
 * <p>
 * <b>Single-process exclusion.</b> The constructor takes an exclusive OS
 * {@link FileLock} on a sidecar {@code <log>.lock} file and holds it for the
 * log's lifetime. A second store (in this JVM or another process) opening the
 * same log fails fast with an {@link AimonException} instead of silently
 * interleaving writes and corrupting the log. The lock releases on
 * {@link #close()} or on JVM exit.
 *
 * <p>
 * <b>Compaction.</b> {@link #rewrite(List)} atomically replaces the whole log
 * with a new minimal line set (temp file → fsync → {@code ATOMIC_MOVE}), so a
 * crash during compaction leaves either the old or the new complete log, never
 * a partial one.
 */
public final class JsonLineLog implements AutoCloseable {

    private static final String LOCK_SUFFIX = ".lock";
    private static final String COMPACTING_SUFFIX = ".compacting";

    private static final Logger log = LoggerFactory.getLogger(JsonLineLog.class);

    private final Path file;
    private final boolean fsyncOnAppend;

    private FileChannel lockChannel;
    private FileLock fileLock;

    /**
     * @param file
     *            log file. Will be created on first append if missing; its
     *            parent directories are created as needed.
     * @param fsyncOnAppend
     *            if true, every append calls {@code FileChannel.force(true)}
     *            (D in ACID at the cost of latency). Tests typically pass
     *            {@code false} for speed.
     * @throws AimonException
     *             if the exclusive single-process lock cannot be acquired
     *             (another process/store already holds this log).
     */
    public JsonLineLog(Path file, boolean fsyncOnAppend) {
        this.file = Objects.requireNonNull(file, "file cannot be null");
        this.fsyncOnAppend = fsyncOnAppend;
        ensureParentDir();
        cleanStaleCompactionTmp();
        acquireExclusiveLock();
    }

    public Path file() {
        return file;
    }

    /**
     * Replays the log line by line, invoking {@code lineHandler} for every
     * non-blank line. Trailing partial (torn) lines are silently skipped — we
     * only commit a line when it is fully written, but a JVM crash between
     * write and fsync may leave bytes without a terminating newline. Such a
     * line will be the very last in the file; we only deliver lines we have
     * read in full.
     *
     * <p>
     * If the file does not exist this is a no-op.
     *
     * @param lineHandler
     *            invoked once per complete line; an exception it throws
     *            propagates and aborts replay.
     * @throws AimonException
     *             if the log cannot be read
     */
    public void replay(Consumer<String> lineHandler) {
        Objects.requireNonNull(lineHandler, "lineHandler cannot be null");
        if (!Files.exists(file)) {
            return;
        }
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                lineHandler.accept(line);
            }
        } catch (IOException e) {
            throw new AimonException("Failed to replay JSONL log: " + file, e);
        }
    }

    /**
     * Appends a single JSON line to the log, terminated by {@code '\n'}.
     * The string must not contain a newline.
     */
    public void append(String jsonLine) {
        Objects.requireNonNull(jsonLine, "jsonLine cannot be null");
        if (jsonLine.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("JSONL line cannot contain a newline");
        }
        ensureParentDir();
        OpenOption[] options = fsyncOnAppend
                ? new OpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND,
                        StandardOpenOption.SYNC}
                : new OpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND};
        byte[] payload = (jsonLine + '\n').getBytes(StandardCharsets.UTF_8);
        try {
            Files.write(file, payload, options);
        } catch (IOException e) {
            throw new AimonException("Failed to append to JSONL log: " + file, e);
        }
    }

    /**
     * Atomically replaces the entire log with {@code lines} (compaction). The
     * new content is written to a temp file (with the same fsync policy as
     * appends), then {@code ATOMIC_MOVE}d over the log, so a crash leaves either
     * the old log or the fully-written new log intact — never a partial file.
     *
     * @param lines
     *            the complete minimal line set representing current state; each
     *            must be a single JSON object with no embedded newline.
     * @throws AimonException
     *             if the rewrite fails
     */
    public void rewrite(List<String> lines) {
        Objects.requireNonNull(lines, "lines cannot be null");
        ensureParentDir();
        final Path tmp = file.resolveSibling(file.getFileName().toString() + COMPACTING_SUFFIX);
        final StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            if (line.indexOf('\n') >= 0) {
                throw new IllegalArgumentException("JSONL line cannot contain a newline");
            }
            sb.append(line).append('\n');
        }
        final byte[] payload = sb.toString().getBytes(StandardCharsets.UTF_8);
        final OpenOption[] options = fsyncOnAppend
                ? new OpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.SYNC}
                : new OpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING};
        try {
            Files.write(tmp, payload, options);
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw new AimonException("Failed to compact JSONL log: " + file, e);
        }
    }

    /** Releases the exclusive lock and closes the lock channel. Idempotent. */
    @Override
    public synchronized void close() {
        try {
            if (fileLock != null) {
                fileLock.release();
                fileLock = null;
            }
        } catch (IOException e) {
            log.warn("Failed to release memory log lock for {}: {}", file, e.getMessage());
        } finally {
            try {
                if (lockChannel != null) {
                    lockChannel.close();
                    lockChannel = null;
                }
            } catch (IOException e) {
                log.warn("Failed to close lock channel for {}: {}", file, e.getMessage());
            }
        }
    }

    private void acquireExclusiveLock() {
        final Path lockFile = file.resolveSibling(file.getFileName().toString() + LOCK_SUFFIX);
        FileChannel channel = null;
        try {
            channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            final FileLock acquired;
            try {
                acquired = channel.tryLock();
            } catch (OverlappingFileLockException e) {
                channel.close();
                throw new AimonException("Another store in this JVM already holds the memory log lock: " + lockFile, e);
            }
            if (acquired == null) {
                channel.close();
                throw new AimonException("Another process holds the memory log lock: " + lockFile
                        + " — the file backend is single-process; use aimon-memory-postgres for multi-instance.");
            }
            this.lockChannel = channel;
            this.fileLock = acquired;
        } catch (IOException e) {
            closeQuietly(channel);
            throw new AimonException("Failed to acquire memory log lock: " + lockFile, e);
        }
    }

    private void cleanStaleCompactionTmp() {
        final Path tmp = file.resolveSibling(file.getFileName().toString() + COMPACTING_SUFFIX);
        try {
            Files.deleteIfExists(tmp);
        } catch (IOException e) {
            log.warn("Could not remove stale compaction temp file {}: {}", tmp, e.getMessage());
        }
    }

    private void ensureParentDir() {
        Path parent = file.getParent();
        if (parent == null || Files.isDirectory(parent)) {
            return;
        }
        try {
            Files.createDirectories(parent);
        } catch (IOException e) {
            throw new AimonException("Failed to create parent dir for log: " + file, e);
        }
    }

    private static void closeQuietly(FileChannel channel) {
        if (channel == null) {
            return;
        }
        try {
            channel.close();
        } catch (IOException ignore) {
            // best effort
        }
    }
}
