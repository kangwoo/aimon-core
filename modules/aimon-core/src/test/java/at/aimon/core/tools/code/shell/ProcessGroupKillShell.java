package at.aimon.core.tools.code.shell;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import at.aimon.core.shell.ExecutionOptions;
import at.aimon.core.shell.ShellCancellation;
import at.aimon.core.shell.ShellCancellationSource;
import at.aimon.core.shell.ShellCommand;
import at.aimon.core.shell.ShellCommandResult;
import at.aimon.core.shell.ShellFeature;
import at.aimon.core.shell.ShellLineListener;
import at.aimon.core.shell.VirtualShell;
import at.aimon.core.shell.exception.ShellCancelledException;
import at.aimon.core.shell.exception.ShellExecutionException;
import at.aimon.core.shell.exception.ShellTimeoutException;
import at.aimon.core.shell.impl.local.LocalShell;

/**
 * A shell that stops a streamed command the way aimon-sandbox's provider does (Q-C4): the command runs in a process
 * group of its own under a wrapper that is not the runtime, and a timeout or cancellation kills that group and nothing
 * else ({@code kill -TERM -- -pgid}, then {@code -KILL} after 1.5 s). {@link LocalShell} kills by walking the process
 * tree instead, which reaches a runtime that left the group — the reason core's own tests never saw the defect.
 *
 * <p>
 * The wrapper is {@code bash -c CMD\nexit $?}: the trailing {@code exit} keeps bash from {@code exec}ing the last
 * command, so the runtime is never the group's leader itself.
 */
final class ProcessGroupKillShell implements VirtualShell {

    private static final String WRAPPER = "import os, sys\n" + "os.setpgid(0, 0)\n"
            + "with open(sys.argv[1], 'w') as f:\n" + "    f.write(str(os.getpid()))\n"
            + "os.execvp('bash', ['bash', '-c', sys.argv[2] + '\\nexit $?'])\n";
    private static final Duration TERM_GRACE = Duration.ofMillis(1500);
    private static final Duration PIDFILE_WAIT = Duration.ofSeconds(2);
    private static final Duration END_WAIT = Duration.ofSeconds(10);

    private final LocalShell delegate;
    private final ExecutorService helper = Executors.newCachedThreadPool(runnable -> {
        final Thread thread = new Thread(runnable, "process-group-kill-shell");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicInteger groupKills = new AtomicInteger();
    private final AtomicInteger fallbackKills = new AtomicInteger();

    ProcessGroupKillShell(LocalShell delegate) {
        this.delegate = delegate;
    }

    /** How many times a timeout or cancellation ended the command by killing its process group. */
    int groupKills() {
        return groupKills.get();
    }

    /** How many times the group was not there yet and the delegate's own kill was used instead. */
    int fallbackKills() {
        return fallbackKills.get();
    }

    @Override
    public ShellCommandResult execute(ShellCommand command) throws ShellExecutionException {
        return delegate.execute(command);
    }

    @Override
    public ShellCommandResult execute(ShellCommand command, ExecutionOptions options) throws ShellExecutionException {
        return delegate.execute(command, options);
    }

    @Override
    public ShellCommandResult execute(ShellCommand command, ExecutionOptions options, ShellLineListener listener)
            throws ShellExecutionException {
        final ShellCancellation token = options.getCancellation();
        if (token.isCancelled()) {
            throw new ShellCancelledException("cancelled before the command started");
        }
        final Path pidFile;
        try {
            pidFile = Files.createTempDirectory("aimon-pgk-").resolve("pgid");
        } catch (IOException e) {
            throw new ShellExecutionException("no pid file: " + e, e);
        }
        final ShellCancellationSource own = ShellCancellationSource.create();
        final Duration timeout = options.getTimeout();
        final ExecutionOptions inner = options.toBuilder().cancellation(own.token())
                .timeout(timeout == null ? null : timeout.plusSeconds(60)).build();
        final String wrapped = "python3 -c " + quote(WRAPPER) + " " + quote(pidFile.toString()) + " "
                + quote(command.asString());
        final Future<ShellCommandResult> running = helper
                .submit(() -> delegate.execute(() -> wrapped, inner, listener));
        final long deadline = timeout == null || timeout.isZero() || timeout.isNegative()
                ? Long.MAX_VALUE
                : System.nanoTime() + timeout.toNanos();
        try {
            while (true) {
                try {
                    return running.get(10, TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    // still running
                }
                final boolean cancelled = token.isCancelled();
                if (cancelled || System.nanoTime() > deadline) {
                    kill(pidFile, own);
                    awaitEnd(running, own);
                    if (cancelled) {
                        throw new ShellCancelledException("cancelled; the process group was killed");
                    }
                    throw new ShellTimeoutException("timed out; the process group was killed", timeout, "", "");
                }
            }
        } catch (ExecutionException e) {
            if (e.getCause() instanceof ShellExecutionException shell) {
                throw shell;
            }
            throw new ShellExecutionException("the delegate failed: " + e.getCause(), e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            own.cancel();
            throw new ShellCancelledException("interrupted");
        } finally {
            try {
                Files.deleteIfExists(pidFile);
                Files.deleteIfExists(pidFile.getParent());
            } catch (IOException e) {
                // a temp file
            }
        }
    }

    private void kill(Path pidFile, ShellCancellationSource own) throws InterruptedException {
        final long until = System.nanoTime() + PIDFILE_WAIT.toNanos();
        String pgid = null;
        while (System.nanoTime() < until) {
            pgid = readPid(pidFile);
            if (pgid != null) {
                break;
            }
            Thread.sleep(10);
        }
        if (pgid == null) {
            fallbackKills.incrementAndGet();
            own.cancel();
            return;
        }
        groupKills.incrementAndGet();
        signal("-TERM", pgid);
        final long graceEnd = System.nanoTime() + TERM_GRACE.toNanos();
        while (System.nanoTime() < graceEnd && signal("-0", pgid) == 0) {
            Thread.sleep(20);
        }
        signal("-KILL", pgid);
    }

    private static String readPid(Path pidFile) {
        try {
            final String text = Files.readString(pidFile, StandardCharsets.US_ASCII).strip();
            return text.isEmpty() ? null : text;
        } catch (IOException e) {
            return null;
        }
    }

    private static int signal(String signal, String pgid) throws InterruptedException {
        try {
            final Process kill = new ProcessBuilder("kill", signal, "--", "-" + pgid).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            return kill.waitFor(5, TimeUnit.SECONDS) ? kill.exitValue() : -1;
        } catch (IOException e) {
            return -1;
        }
    }

    private static void awaitEnd(Future<ShellCommandResult> running, ShellCancellationSource own)
            throws InterruptedException {
        try {
            running.get(END_WAIT.toMillis(), TimeUnit.MILLISECONDS);
            return;
        } catch (ExecutionException e) {
            return;
        } catch (TimeoutException e) {
            // A safety net only: LocalShell captures through files, so a survivor does not hold the call open.
            own.cancel();
        }
        try {
            running.get(END_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException | TimeoutException e) {
            // given up
        }
    }

    private static String quote(String text) {
        return "'" + text.replace("'", "'\\''") + "'";
    }

    @Override
    public String getWorkingDirectory() {
        return delegate.getWorkingDirectory();
    }

    @Override
    public boolean supports(ShellFeature feature) {
        return delegate.supports(feature);
    }

    @Override
    public void close() {
        helper.shutdownNow();
        delegate.close();
    }
}
