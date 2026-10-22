package at.aimon.core.tools.monitor;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.environment.UnavailableExecutionEnvironment;
import at.aimon.core.shell.ExecutionOptions;
import at.aimon.core.shell.ShellCancellationSource;
import at.aimon.core.shell.ShellCommandResult;
import at.aimon.core.shell.ShellFeature;
import at.aimon.core.shell.VirtualShell;
import at.aimon.core.shell.exception.ShellCancelledException;
import at.aimon.core.shell.exception.ShellExecutionException;
import at.aimon.core.shell.exception.ShellTimeoutException;
import at.aimon.core.tools.ExecutionEnvironmentAccess;
import at.aimon.core.tools.monitor.exception.MonitorRequestException;

/**
 * The {@code shell} source: a command run in the calling execution's shell, each line of its standard output one
 * event.
 *
 * <p>
 * The shell must declare both {@link ShellFeature#STREAMING} — or no line arrives before the command ends, which for
 * {@code tail -f} is never — and {@link ShellFeature#CANCELLATION}, or {@code MonitorStop} could not end the command
 * and it would run to its timeout. Which shell that is depends on the execution, so it is checked here, per call; the
 * tool cannot leave {@code command} out of its schema for a shell that lacks them.
 *
 * <p>
 * The source keeps the execution environment it was created with, the way a background {@code Bash} command keeps its
 * shell (execution-environment design §5.3), and reads the shell from it when it starts. It holds the environment and
 * not the shell because nothing under {@code at.aimon.core.tools} may hold a shell
 * ({@code PackageDependencyArchitectureTest}).
 *
 * <p>
 * The command runs on a daemon thread of its own for the monitor's lifetime; the manager's caps bound how many there
 * are. Exit status 0 completes the monitor; any other status fails it, with the last lines of standard error.
 */
public final class ShellMonitorSourceFactory implements MonitorSourceFactory {

    /** The kind this factory serves. */
    public static final String KIND = "shell";

    /** The parameter holding the command. */
    public static final String COMMAND_PARAM = "command";

    /** Longest command accepted, the same as {@code Bash}'s. */
    static final int MAX_COMMAND_LENGTH = 32_768;

    private static final Logger log = LoggerFactory.getLogger(ShellMonitorSourceFactory.class);

    /** How much stderr is captured: only its last lines are ever shown. */
    private static final long MAX_STDERR_BYTES = 64 * 1024;
    private static final int STDERR_TAIL_LINES = 5;

    /** Past the monitor's own timeout, so the manager's timer — not the shell's — ends the monitor and says so. */
    private static final Duration SHELL_TIMEOUT_MARGIN = Duration.ofMinutes(1);

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public String describe() {
        return "shell — runs `command` in your shell; each line it prints to stdout is one event";
    }

    @Override
    public MonitorSource create(MonitorRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        final String command = request.getStringParam(COMMAND_PARAM).filter(c -> !c.isBlank())
                .orElseThrow(() -> new MonitorRequestException("The shell source needs a non-empty command."));
        if (command.length() > MAX_COMMAND_LENGTH) {
            throw new MonitorRequestException(
                    "The command exceeds the maximum length of " + MAX_COMMAND_LENGTH + " characters.");
        }
        final ExecutionEnvironment environment;
        try {
            environment = ExecutionEnvironmentAccess.require(request.getContext());
        } catch (IllegalStateException e) {
            throw new MonitorRequestException(e.getMessage(), e);
        }
        if (environment instanceof UnavailableExecutionEnvironment unavailable) {
            throw new MonitorRequestException(unavailable.message());
        }
        final VirtualShell shell = environment.shell();
        if (!shell.supports(ShellFeature.STREAMING) || !shell.supports(ShellFeature.CANCELLATION)) {
            throw new MonitorRequestException("This environment's shell cannot stream a running command's output"
                    + " and stop it, so it cannot run a shell monitor. Use Bash with run_in_background for a command"
                    + " that ends on its own.");
        }
        // The manager hands over the timeout it settled on; the shell's own limit sits past it, so the manager's timer
        // ends the monitor and says "timed out" rather than the shell's kill reporting a failure.
        final Duration timeout = request.getTimeout().orElse(MonitorManager.MAX_TIMEOUT).plus(SHELL_TIMEOUT_MARGIN);
        return new ShellMonitorSource(environment, command, timeout);
    }

    /** One command, not yet started. */
    private static final class ShellMonitorSource implements MonitorSource {

        private final ExecutionEnvironment environment;
        private final String command;
        private final Duration timeout;

        ShellMonitorSource(ExecutionEnvironment environment, String command, Duration timeout) {
            this.environment = environment;
            this.command = command;
            this.timeout = timeout;
        }

        @Override
        public MonitorHandle start(MonitorEventSink sink) {
            final ShellCancellationSource stop = ShellCancellationSource.create();
            final ExecutionOptions options = ExecutionOptions.builder().timeout(timeout).background(true)
                    .maxCaptureBytes(MAX_STDERR_BYTES).charset(StandardCharsets.UTF_8).cancellation(stop.token())
                    .build();
            final Thread thread = new Thread(() -> run(options, sink), "monitor-shell");
            thread.setDaemon(true);
            thread.start();
            return stop::cancel;
        }

        private void run(ExecutionOptions options, MonitorEventSink sink) {
            try {
                final ShellCommandResult result = environment.shell().execute(() -> command, options, sink::emit);
                if (result.exitCode() == 0) {
                    sink.complete("exit code 0");
                } else {
                    sink.fail("exit code " + result.exitCode() + stderrTail(result.stderr()));
                }
            } catch (ShellCancelledException e) {
                // MonitorStop or the manager's timeout: the monitor has already ended, so this is not reported.
                sink.complete("stopped");
            } catch (ShellTimeoutException e) {
                sink.fail("the shell stopped the command at its time limit");
            } catch (ShellExecutionException e) {
                sink.fail("the command could not run: " + e.getMessage());
            } catch (RuntimeException e) {
                log.warn("Shell monitor command failed unexpectedly", e);
                sink.fail("the command could not run: " + e.getMessage());
            }
        }

        private static String stderrTail(String stderr) {
            if (stderr == null || stderr.isBlank()) {
                return "";
            }
            final List<String> lines = Arrays.asList(stderr.strip().split("\\R"));
            final List<String> tail = lines.subList(Math.max(0, lines.size() - STDERR_TAIL_LINES), lines.size());
            return "; stderr: " + String.join(" | ", tail);
        }
    }
}
