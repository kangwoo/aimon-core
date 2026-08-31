package at.aimon.core.shell.impl.local;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.shell.ExecutionOptions;
import at.aimon.core.shell.ShellCommand;
import at.aimon.core.shell.ShellCommandResult;
import at.aimon.core.shell.ShellFeature;
import at.aimon.core.shell.VirtualShell;
import at.aimon.core.shell.exception.ShellExecutionException;
import at.aimon.core.shell.exception.ShellTimeoutException;

/**
 * Local machine shell implementation.
 *
 * <p>
 * This implementation executes shell commands on the local machine through a Unix shell (bash by default). A
 * {@code cmd.exe} branch exists for Windows but that platform is out of scope — see the package javadoc. It provides:
 * <ul>
 * <li>File-backed stdout/stderr capture (the child writes to temp files rather than inherited pipes)
 * <li>Configurable timeout support
 * <li>Working directory and environment variable control
 * <li>Bounded in-memory capture with truncation reporting
 * </ul>
 *
 * <p>
 * <b>Why files, not pipes:</b> reading a child's stdout/stderr through inherited pipes requires a reader thread that
 * loops on {@code read()} until EOF, and EOF only arrives once <em>all</em> write-ends of the pipe are closed. A
 * command
 * that leaves a backgrounded child holding the write-end (e.g. {@code server & echo done}) therefore delays EOF
 * indefinitely, blocking (leaking) the reader thread and the pipe stream. Redirecting the child's output to temp files
 * via {@link ProcessBuilder} avoids inheriting the pipe fd entirely: the parent's output is fully flushed to the file
 * when it exits, so after {@code waitFor()} the result is read directly from the file — no reader thread, no
 * delayed-EOF
 * drain, and no thread/stream leak (issue #13, superseding the drain-based workaround from issue #10).
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {@code
 * try (LocalShell shell = new LocalShell()) {
 *     ShellCommand cmd = () -> "echo 'Hello, World!'";
 *     ShellCommandResult result = shell.execute(cmd);
 *     System.out.println(result.stdout());
 * }
 * }
 * </pre>
 */
public final class LocalShell implements VirtualShell {

    private static final Logger log = LoggerFactory.getLogger(LocalShell.class);

    private static final int DEFAULT_IO_THREADS = Runtime.getRuntime().availableProcessors();
    private static final String DEFAULT_UNIX_SHELL = "bash";
    private static final int STREAM_BUFFER_SIZE = 8192;
    private static final Duration PROCESS_DESTROY_TIMEOUT = Duration.ofMillis(200);
    private static final String TEMP_FILE_PREFIX = "aimon-shell-";

    /**
     * Default upper bound on how many bytes of each captured stream are read into memory. The child writes its full
     * output to a temp file regardless; this only bounds how much is decoded into the returned
     * {@link ShellCommandResult}
     * (16 MiB), so a runaway command cannot OOM the JVM. Callers can override via
     * {@link ExecutionOptions#getMaxCaptureBytes()}.
     */
    private static final long DEFAULT_MAX_CAPTURE_BYTES = 16L * 1024 * 1024;

    private final boolean isWindows;
    private final Path defaultWorkingDirectory;
    private final String defaultUnixShell;

    /**
     * Creates a new local shell with default settings.
     *
     * <p>
     * Uses no default working directory and "bash" as Unix shell.
     */
    public LocalShell() {
        this(DEFAULT_IO_THREADS, null, DEFAULT_UNIX_SHELL);
    }

    /**
     * Creates a new local shell with a default working directory.
     *
     * @param defaultWorkingDirectory
     *            the default working directory for commands
     */
    public LocalShell(Path defaultWorkingDirectory) {
        this(DEFAULT_IO_THREADS, defaultWorkingDirectory, DEFAULT_UNIX_SHELL);
    }

    /**
     * Creates a new local shell.
     *
     * @param ioThreads
     *            retained for source/binary compatibility and ignored — output is captured to files, not drained on I/O
     *            threads
     */
    public LocalShell(int ioThreads) {
        this(ioThreads, null, DEFAULT_UNIX_SHELL);
    }

    /**
     * Creates a new local shell with a default working directory.
     *
     * @param ioThreads
     *            retained for source/binary compatibility and ignored (see {@link #LocalShell(int)})
     * @param defaultWorkingDirectory
     *            the default working directory for commands
     */
    public LocalShell(int ioThreads, Path defaultWorkingDirectory) {
        this(ioThreads, defaultWorkingDirectory, DEFAULT_UNIX_SHELL);
    }

    /**
     * Creates a new local shell with full customization.
     *
     * @param ioThreads
     *            retained for source/binary compatibility and ignored (see {@link #LocalShell(int)})
     * @param defaultWorkingDirectory
     *            the default working directory for commands, or null for system default
     * @param defaultUnixShell
     *            the default Unix shell to use (e.g., "bash", "sh", "zsh"), or null for "bash"
     */
    public LocalShell(int ioThreads, Path defaultWorkingDirectory, String defaultUnixShell) {
        isWindows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        this.defaultWorkingDirectory = defaultWorkingDirectory;
        this.defaultUnixShell = defaultUnixShell != null ? defaultUnixShell : DEFAULT_UNIX_SHELL;
    }

    /**
     * Terminates a process <em>and its descendants</em>. Killing only the parent leaves backgrounded children
     * ({@code sleep 30 &}, a spawned server) running past the timeout that was supposed to stop them.
     *
     * <p>
     * The descendant snapshot is inherently racy: a grandchild born after the snapshot is not enumerated and survives.
     * Closing that gap needs a process group ({@code setsid} + {@code kill(-pgid)}), which is platform-specific and out
     * of {@link ProcessBuilder}'s reach.
     */
    private static void destroyForciblyQuietly(Process p) {
        // Order matters: kill the descendants first. Destroying the parent first re-parents its children to init,
        // after which descendants() no longer enumerates them at all.
        //
        // Snapshot the handles now, too. The forcible sweep below runs after p.destroy(), and calling descendants()
        // at that point may already return an empty stream.
        final List<ProcessHandle> descendants = snapshotDescendants(p);
        descendants.forEach(LocalShell::destroyQuietly);
        try {
            p.destroy();
            if (!p.waitFor(PROCESS_DESTROY_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                destroyForciblyAll(descendants, p);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            destroyForciblyAll(descendants, p);
        } catch (Exception ignored) {
            destroyForciblyAll(descendants, p);
        }
    }

    private static void destroyForciblyQuietly(ProcessHandle handle) {
        try {
            handle.destroyForcibly();
        } catch (Exception ignored) {
            // Best effort - the handle may already refer to a process that exited
        }
    }

    /**
     * Snapshots the process's descendants, degrading to an empty list (parent-only cleanup) if the platform refuses to
     * enumerate them.
     */
    private static List<ProcessHandle> snapshotDescendants(Process p) {
        try {
            return p.descendants().toList();
        } catch (Exception e) {
            log.debug("Failed to enumerate process descendants ({}); cleaning up the parent only.", e.toString());
            return List.of();
        }
    }

    private static void destroyForciblyAll(List<ProcessHandle> descendants, Process p) {
        descendants.forEach(LocalShell::destroyForciblyQuietly);
        try {
            p.destroyForcibly();
        } catch (Exception ignored) {
            // Best effort cleanup - no action needed
            // Process cleanup failed, but we can't do anything about it
        }
    }

    private static void destroyQuietly(ProcessHandle handle) {
        try {
            handle.destroy();
        } catch (Exception ignored) {
            // Best effort - the handle may already refer to a process that exited
        }
    }

    /**
     * Reads up to {@code maxBytes} of a captured output file and decodes it. If the file holds more than
     * {@code maxBytes}
     * the extra is dropped and the result is flagged truncated. An I/O error while reading degrades to an empty,
     * truncated capture rather than failing the whole execution (the exit code is still authoritative).
     */
    private static Capture readCapped(Path file, Charset charset, long maxBytes) {
        try (InputStream in = Files.newInputStream(file); ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            final byte[] buf = new byte[STREAM_BUFFER_SIZE];
            long total = 0;
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (total + n > maxBytes) {
                    final int room = (int) Math.max(0L, maxBytes - total);
                    baos.write(buf, 0, room);
                    return new Capture(baos.toString(charset), true);
                }
                baos.write(buf, 0, n);
                total += n;
            }
            return new Capture(baos.toString(charset), false);
        } catch (IOException e) {
            log.warn("Failed to read captured output from {} ({}); output may be incomplete.", file, e.toString());
            return new Capture("", true);
        }
    }

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            // On Unix this unlinks even if a lingering backgrounded child still holds the fd; the inode (and its disk
            // space) is reclaimed when that child finally exits, so no named file is left behind.
            Files.deleteIfExists(file);
        } catch (IOException e) {
            // On Windows a file still open by a surviving child cannot be deleted; register a JVM-exit backstop so it
            // is
            // not leaked. This path is rare (Unix deletes succeed), so deleteOnExit does not accumulate in normal use.
            log.debug("Failed to delete temp capture file {} ({}); scheduling deleteOnExit backstop", file,
                    e.toString());
            file.toFile().deleteOnExit();
        }
    }

    private static String safe(ShellCommand cmd) {
        try {
            return cmd.asString();
        } catch (Exception e) {
            return "<unprintable command>";
        }
    }

    @Override
    public ShellCommandResult execute(ShellCommand command) throws ShellExecutionException {
        return execute(command, ExecutionOptions.defaults());
    }

    @Override
    public ShellCommandResult execute(ShellCommand command, ExecutionOptions options) throws ShellExecutionException {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(options, "options");

        // Unix shell: use option first, then default
        final String shellToUse = options.getUnixShell() != null ? options.getUnixShell() : defaultUnixShell;
        final List<String> cmdline = buildPlatformCommandLine(command, shellToUse);
        final ProcessBuilder pb = new ProcessBuilder(cmdline);

        // Working directory: use option first, then default
        final Path workingDir;
        if (options.getWorkingDirectory() != null) {
            workingDir = Path.of(options.getWorkingDirectory());
        } else {
            workingDir = defaultWorkingDirectory;
        }
        if (workingDir != null) {
            pb.directory(workingDir.toFile());
        }

        // Environment
        if (options.getEnvironment() != null && !options.getEnvironment().isEmpty()) {
            pb.environment().putAll(options.getEnvironment());
        }

        final Charset charset = options.getCharset() != null ? options.getCharset() : StandardCharsets.UTF_8;
        final long maxCaptureBytes = options.getMaxCaptureBytes() != null
                ? options.getMaxCaptureBytes()
                : DEFAULT_MAX_CAPTURE_BYTES;
        final boolean mergeErr = options.isRedirectErrorStream();

        Path outFile = null;
        Path errFile = null;
        Path inFile = null;
        try {
            // stdin goes through a temp file for the same reason stdout does: a file redirect cannot block. Writing
            // to the child's pipe instead would deadlock whenever the payload exceeds the pipe buffer and the child
            // has not started reading yet.
            //
            // The redirect is unconditional: ProcessBuilder's default is a pipe that this implementation never writes
            // to and never closes, so a child that reads stdin to EOF (cat, read, jq, `while read line`) would block
            // until its timeout. Redirecting from an empty temp file gives it immediate EOF instead.
            inFile = Files.createTempFile(TEMP_FILE_PREFIX, ".in");
            Files.writeString(inFile, options.getStdin() != null ? options.getStdin() : "", charset);
            pb.redirectInput(inFile.toFile());
            outFile = Files.createTempFile(TEMP_FILE_PREFIX, ".out");
            pb.redirectOutput(outFile.toFile());
            if (mergeErr) {
                pb.redirectErrorStream(true);
            } else {
                errFile = Files.createTempFile(TEMP_FILE_PREFIX, ".err");
                pb.redirectError(errFile.toFile());
            }

            final Instant start = Instant.now();
            final Process p;
            try {
                p = pb.start();
            } catch (IOException e) {
                throw new ShellExecutionException("Failed to start process: " + safe(command), e);
            }

            final boolean finished;
            try {
                final Duration timeout = options.getTimeout();
                if (timeout == null || timeout.isZero() || timeout.isNegative()) {
                    p.waitFor();
                    finished = true;
                } else {
                    finished = p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
                }
            } catch (InterruptedException ie) {
                // Order matters: clean up *before* restoring the interrupt flag. destroyForciblyQuietly's SIGTERM
                // grace period is itself a waitFor(200ms), which throws immediately on an already-interrupted
                // thread — restoring the flag first would turn every interrupt into an instant SIGKILL.
                destroyForciblyQuietly(p);
                // The command a caller interrupted is usually the one whose output they most want to see, so carry
                // whatever was flushed before the kill instead of dropping it (same treatment as the timeout path).
                final Capture out = readCapped(outFile, charset, maxCaptureBytes);
                final Capture err = mergeErr ? Capture.EMPTY : readCapped(errFile, charset, maxCaptureBytes);
                Thread.currentThread().interrupt();
                throw new ShellExecutionException("Interrupted while waiting for process: " + safe(command), ie,
                        out.content(), err.content(), out.truncated() || err.truncated());
            }

            if (!finished) {
                destroyForciblyQuietly(p);
                // Capture whatever the (now killed) process had flushed to the files before termination.
                final Capture out = readCapped(outFile, charset, maxCaptureBytes);
                final Capture err = mergeErr ? Capture.EMPTY : readCapped(errFile, charset, maxCaptureBytes);
                throw new ShellTimeoutException(
                        "Process timed out after " + options.getTimeout() + ": " + safe(command), options.getTimeout(),
                        out.content(), err.content(), out.truncated() || err.truncated());
            }

            final int exit = p.exitValue();
            // The parent's output is fully flushed to the file(s) on exit; read it directly (bounded by
            // maxCaptureBytes).
            final Capture out = readCapped(outFile, charset, maxCaptureBytes);
            final Capture err = mergeErr ? Capture.EMPTY : readCapped(errFile, charset, maxCaptureBytes);
            final boolean truncated = out.truncated() || err.truncated();
            if (truncated) {
                log.warn("Captured stdout/stderr for [{}] exceeded the {}-byte capture limit; output was truncated. "
                        + "outputTruncated=true.", safe(command), maxCaptureBytes);
            }

            final Duration dur = Duration.between(start, Instant.now());
            return new ShellCommandResult(exit, out.content(), err.content(), dur, truncated);
        } catch (IOException e) {
            throw new ShellExecutionException("Failed to prepare process I/O redirection for: " + safe(command), e);
        } finally {
            deleteQuietly(outFile);
            deleteQuietly(errFile);
            deleteQuietly(inFile);
        }
    }

    @Override
    public boolean supports(ShellFeature feature) {
        // Local shell generally supports these, but "INTERACTIVE" is tricky without PTY handling.
        return switch (feature) {
            case PIPE, REDIRECTION -> true;
            case INTERACTIVE -> false;
        };
    }

    @Override
    public String getWorkingDirectory() {
        return defaultWorkingDirectory != null ? defaultWorkingDirectory.toString() : null;
    }

    @Override
    public void close() {
        // No long-lived resources: output is captured to per-execution temp files that are deleted in execute()'s
        // finally block, so there is nothing to release here. Retained to satisfy VirtualShell/AutoCloseable.
    }

    private List<String> buildPlatformCommandLine(ShellCommand command, String unixShell) {
        // If your ShellCommand is argv-based, you should NOT route through a platform shell.
        // But if it's "asString()", a platform shell is the most compatible way.
        final String script = command.asString();
        if (isWindows) {
            // cmd.exe /c "<script>"
            return List.of("cmd.exe", "/c", script);
        }
        // Unix shell (sh, bash, zsh, etc.)
        return List.of(unixShell, "-c", script);
    }

    /**
     * Decoded capture of one output stream: the (possibly capped) content plus whether more was dropped.
     */
    private static final class Capture {

        private static final Capture EMPTY = new Capture("", false);

        private final String content;
        private final boolean truncated;

        Capture(String content, boolean truncated) {
            this.content = content;
            this.truncated = truncated;
        }

        String content() {
            return content;
        }

        boolean truncated() {
            return truncated;
        }
    }
}
