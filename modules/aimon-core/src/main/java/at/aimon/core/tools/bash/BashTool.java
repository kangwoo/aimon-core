package at.aimon.core.tools.bash;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.interrupt.CancellationSignal;
import at.aimon.core.agent.interrupt.InterruptBehavior;
import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.InterruptAccess;
import at.aimon.core.agent.tool.ToolCategories;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.agent.tool.permission.PermissionSubject;
import at.aimon.core.agent.tool.permission.ToolPattern;
import at.aimon.core.agent.tool.permission.ToolPermissionSubjectAware;
import at.aimon.core.shell.ExecutionOptions;
import at.aimon.core.shell.ShellCommandResult;
import at.aimon.core.shell.VirtualShell;
import at.aimon.core.shell.exception.ShellExecutionException;
import at.aimon.core.shell.exception.ShellTimeoutException;

/**
 * Tool for executing bash commands in a shell environment.
 *
 * <p>
 * This tools executes shell commands with timeout control and output management. It supports various terminal
 * operations including git, build tools, package managers, and system commands.
 *
 * <p>
 * Key features:
 *
 * <ul>
 * <li>Command execution with configurable timeout
 * <li>Output truncation at 30,000 characters
 * <li>Exit code reporting
 * <li>Stderr and stdout capture
 * </ul>
 *
 * <p>
 * <strong>CRITICAL</strong>: This tools is for terminal operations ONLY. DO NOT use it for file operations - use
 * specialized tools instead:
 *
 * <ul>
 * <li>Use Glob for finding files (not find/ls)
 * <li>Use Grep for searching content (not grep/rg)
 * <li>Use Read for reading files (not cat/head/tail)
 * <li>Use Edit for editing files (not sed/awk)
 * <li>Use Write for writing files (not echo/cat)
 * </ul>
 *
 * <p>
 * Thread-safe: {@link VirtualShell} implementations are required to be thread-safe, and this tool keeps no
 * per-execution
 * state of its own.
 *
 * <p>
 * <b>Process teardown belongs to the shell.</b> This tool does not wrap the foreground call in a future — it blocks
 * directly in {@link VirtualShell#execute(at.aimon.core.shell.ShellCommand, ExecutionOptions)}, which honours the
 * timeout in {@link ExecutionOptions} and kills the process (and its descendants) when the deadline passes or the
 * calling thread is interrupted. That is what makes {@link InterruptBehavior#THREAD_INTERRUPT} mean something here: the
 * interrupt no longer only wakes a waiter while the command keeps running.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     VirtualShell shell = // supplied by the assembly, e.g. OrcaToolProviderContext.getShell()
 *     BashTool bashTool = new BashTool(shell);
 *     ToolContext context = ToolContext.empty();
 *
 *     // Execute git command
 *     ToolInput gitInput = ToolInput.of(Map.of("command", "git status", "description", "Check git status"));
 *     ToolResult result = bashTool.execute(gitInput, context);
 *
 *     // Execute build command with custom timeout
 *     ToolInput buildInput = ToolInput
 *             .of(Map.of("command", "./gradlew build", "description", "Build project with Gradle", "timeout", 300000));
 *     ToolResult buildResult = bashTool.execute(buildInput, context);
 * }
 * </pre>
 */
public class BashTool extends AbstractTool implements ToolPermissionSubjectAware, AutoCloseable {
    public static final String TOOL_NAME = "Bash";
    private static final Logger log = LoggerFactory.getLogger(BashTool.class);
    private static final int DEFAULT_TIMEOUT_MS = 120_000; // 2 minutes
    private static final int MAX_TIMEOUT_MS = 600_000; // 10 minutes
    private static final int MAX_OUTPUT_LENGTH = 30_000; // 30,000 characters
    private static final int MAX_COMMAND_LENGTH = 32_768; // 32 KB

    /**
     * Lower bound on the effective timeout.
     *
     * <p>
     * Required, not cosmetic. The shell reads a zero or negative {@link Duration} as "wait forever"
     * ({@code LocalShell} falls through to a bare {@code waitFor()}), so clamping only at the top would let
     * {@code timeout: 0} — which the old thread wrapper turned into an immediate abort — become an unkillable command.
     * The schema advertises the same floor as a {@code minimum}: the clamp is the defence, the schema is the
     * information the model gets.
     */
    private static final int MIN_TIMEOUT_MS = 1_000;

    /**
     * Ceiling for background commands, deliberately far above {@link #MAX_TIMEOUT_MS}.
     *
     * <p>
     * Background execution exists for work that outlives a foreground turn, so it cannot share the 10-minute cap. It
     * cannot be unbounded either: passing no timeout would reintroduce the very leak this tool is being changed to
     * fix, and there is no kill tool in this codebase for a background task. A day is effectively unlimited while
     * still guaranteeing the shell eventually reaps the process.
     */
    private static final long BACKGROUND_TIMEOUT_MS = Duration.ofHours(24).toMillis();

    /** Cap on bytes the shell captures per stream, mirroring the 30k-character output cap this tool applies. */
    private static final long MAX_CAPTURE_BYTES = 1_000_000L;

    /**
     * Told to the model when the shell stopped capturing at {@link #MAX_CAPTURE_BYTES}.
     *
     * <p>
     * Distinct from the 30,000-character cut in {@link #renderOutput(String)}, and both can appear on one result: the
     * cut says the model is not being shown everything the shell captured, this says the shell never captured
     * everything the command printed. Only the second is unrecoverable, which is why it is worded as it is rather than
     * as a byte count the model cannot act on.
     *
     * <p>
     * Shared with {@link BashOutputTool} on purpose — the same condition should read the same way whether the command
     * ran in the foreground or was collected later. This tool owns the wording because this tool sets the cap, for
     * both paths.
     */
    static final String CAPTURE_TRUNCATION_NOTICE = "[Output truncated: the shell reached its capture limit, "
            + "so some output was discarded and is not recoverable]";

    private final VirtualShell shell;
    private final ExecutorService executorService;
    private final BackgroundBashManager backgroundManager;

    /**
     * Creates a new BashTool without background execution support.
     *
     * <p>
     * The tools is configured with the following schema:
     *
     * <ul>
     * <li>Name: "Bash"
     * <li>Required parameter: "command" (string) - The bash command to execute
     * <li>Optional parameters: description, timeout, run_in_background
     * </ul>
     *
     * @param shell
     *            The shell to run commands through (must not be null)
     * @throws NullPointerException
     *             if shell is null
     */
    public BashTool(VirtualShell shell) {
        this(shell, null);
    }

    /**
     * Creates a new BashTool with optional background execution support.
     *
     * <p>
     * The shell is <b>borrowed</b>: this tool never closes it. It is typically shared with other tools and with the
     * skill hooks, and closing it here would tear it out from under them.
     *
     * @param shell
     *            The shell to run commands through (must not be null)
     * @param backgroundManager
     *            The manager for background tasks, or null to disable background execution
     * @throws NullPointerException
     *             if shell is null
     */
    public BashTool(VirtualShell shell, BackgroundBashManager backgroundManager) {
        super(TOOL_NAME, "Executes bash commands in a shell environment. "
                + "CRITICAL: This tools is for terminal operations like git, npm, docker, etc. "
                + "DO NOT use it for file operations. "
                + "Use Glob for finding files, Grep for searching content, Read for reading files, "
                + "Edit for editing files, and Write for writing files. "
                + "Provides timeout control (default 120s, max 600s) and output truncation at 30,000 characters.",
                ToolCategories.EXECUTION, createInputSchema());
        this.shell = Objects.requireNonNull(shell, "Shell cannot be null");
        this.backgroundManager = backgroundManager;
        // Carries background tasks only. The foreground path no longer submits anything here: it blocks in the shell,
        // which owns the timeout and the process teardown.
        //
        // Daemon threads: the context-scoped ToolRegistry does not close AutoCloseable tools on agent teardown, so a
        // non-daemon pool here would keep its threads alive and could block JVM shutdown. Daemon threads never do.
        executorService = Executors.newCachedThreadPool(r -> {
            final Thread t = new Thread(r, "bash-tool");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Creates the JSON Schema for bash tools input.
     *
     * @return The input schema map
     */
    private static Map<String, Object> createInputSchema() {
        return Map.ofEntries(Map.entry("type", "object"), Map.entry("additionalProperties", false), Map.entry(
                "properties",
                Map.ofEntries(Map.entry("command", Map.of("type", "string", "description", "The command to execute")),
                        Map.entry("description", Map.of("type", "string", "description",
                                "Clear, concise description of what this command does in 5-10 words, in active voice")),
                        Map.entry("timeout",
                                Map.of("type", "number", "description",
                                        "Optional timeout in milliseconds (min 1000, max 600000)", "minimum",
                                        MIN_TIMEOUT_MS, "maximum", MAX_TIMEOUT_MS)),
                        Map.entry("run_in_background",
                                Map.of("type", "boolean", "description",
                                        "Set to true to run this command in the background. "
                                                + "Use BashOutput to read the output later.")))),
                Map.entry("required", List.of("command")));
    }

    /**
     * Executes a bash command with timeout control.
     *
     * <p>
     * The method performs the following operations:
     *
     * <ol>
     * <li>Validates the command parameter
     * <li>Extracts timeout (default 120s, max 600s)
     * <li>Executes the command with timeout
     * <li>Captures stdout and stderr
     * <li>Truncates output if longer than 30,000 characters
     * <li>Returns formatted result with exit code information
     * </ol>
     *
     * @param input
     *            The input parameters containing command and optional parameters
     * @param context
     *            The execution context (currently unused)
     * @return A success result with command output if successful, or an error result if the command fails or parameters
     *         are invalid
     * @throws NullPointerException
     *             if input or context is null
     */
    @Override
    public ToolResult execute(ToolInput input, ToolContext context) {
        Objects.requireNonNull(input, "Input cannot be null");
        Objects.requireNonNull(context, "Context cannot be null");

        try {
            // Extract command parameter
            final String command = input.getRequiredString("command");

            // Validate command
            if (command.trim().isEmpty()) {
                return ToolResult.error("Command cannot be empty");
            }
            if (command.length() > MAX_COMMAND_LENGTH) {
                return ToolResult.error("Command exceeds maximum length of " + MAX_COMMAND_LENGTH + " characters");
            }

            log.debug("Executing bash command: {}", command);

            // Extract optional parameters. The floor matters as much as the ceiling now that the shell owns the
            // waiting: it reads a non-positive timeout as "wait forever" (see MIN_TIMEOUT_MS).
            final int rawTimeout = input.getInteger("timeout", DEFAULT_TIMEOUT_MS);
            final int timeout = Math.min(Math.max(rawTimeout, MIN_TIMEOUT_MS), MAX_TIMEOUT_MS);
            final boolean runInBackground = input.getBoolean("run_in_background", false);

            // Handle background execution
            if (runInBackground) {
                if (backgroundManager == null) {
                    return ToolResult
                            .error("Background execution is not supported. BackgroundBashManager was not provided.");
                }
                return executeInBackground(command);
            }

            // Execute the command. No future wrapper: the shell enforces the timeout and destroys the process tree,
            // so the tool thread simply blocks here. The executor's pre-registered Thread.interrupt() terminator
            // (see getInterruptBehavior below) unblocks the shell's waitFor, which then kills the process on its way
            // out — under the old wrapper an interrupt woke this thread but left the command running.
            final ShellCommandResult result;
            try {
                result = shell.execute(() -> command, foregroundOptions(timeout));
            } catch (ShellTimeoutException e) {
                log.warn("Bash command timed out after {}ms", timeout);
                // Whatever the command printed before the shell killed it is the only diagnostic there will ever be,
                // and the exception already carries it. The adapter this tool replaced threw it away, which made a
                // build that hung on step 9 of 10 indistinguishable from one that never started.
                return ToolResult.error(renderBody(mergeStreams(e.stdout(), e.stderr()),
                        "[timed out after " + timeout + "ms]", e.outputTruncated()));
            } catch (ShellExecutionException e) {
                if (e.getCause() instanceof InterruptedException) {
                    // The shell restores the interrupt flag before throwing; report the reason from the signal.
                    log.warn("Bash command execution was interrupted");
                    return interruptedResult(InterruptAccess.signalOf(context));
                }
                log.warn("Bash command failed to execute: {}", e.getMessage());
                // The one outcome with no exit status to report: the command never ran, so there is no output to
                // carry either. Everything else goes through renderBody.
                return ToolResult.error("Command failed: " + e.getMessage());
            }

            // An exit code is a value, not an exception. The shell reports every code — including 0 — the same way,
            // and this is the only place that decides what a code means to the model.
            final String body = renderBody(mergeStreams(result.stdout(), result.stderr()),
                    result.isSuccess() ? null : "[exit code: " + result.exitCode() + "]", result.outputTruncated());

            if (result.isFailure()) {
                log.warn("Bash command exited with code {}", result.exitCode());
                return ToolResult.error(body);
            }

            log.debug("Bash command completed successfully");
            return ToolResult.success(body);

        } catch (IllegalArgumentException e) {
            log.warn("Invalid parameter: {}", e.getMessage());
            return ToolResult.error("Invalid parameter: " + e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error executing bash command", e);
            return ToolResult.error("Unexpected error: " + e.getMessage());
        }
    }

    /**
     * Executes a command in the background.
     *
     * @param command
     *            The command to execute
     * @return A ToolResult with the task ID
     */
    private ToolResult executeInBackground(String command) {
        // Generate task ID
        final String taskId = "bash_" + UUID.randomUUID().toString().substring(0, 8);

        // Create future for background execution
        final CompletableFuture<ShellCommandResult> future = CompletableFuture.supplyAsync(() -> {
            try {
                return shell.execute(() -> command, backgroundOptions());
            } catch (ShellExecutionException e) {
                throw new CompletionException(e);
            }
        }, executorService);

        // Register task
        backgroundManager.registerTask(taskId, command, future);

        // Return task ID
        return ToolResult.success("Background task started with ID: " + taskId + '\n' + "Command: " + command + "\n\n"
                + "Use BashOutput(taskId=\"" + taskId + "\") to check progress and retrieve output.");
    }

    /**
     * Builds the options for one foreground call.
     *
     * <p>
     * Built per call, never cached in a field: holding a single instance is what silently dropped the caller's
     * {@code timeout} before, since the value is only known once the input is read.
     *
     * @param timeoutMs
     *            the already-clamped timeout in milliseconds
     * @return the execution options
     */
    private static ExecutionOptions foregroundOptions(long timeoutMs) {
        return ExecutionOptions.builder().timeout(Duration.ofMillis(timeoutMs)).maxCaptureBytes(MAX_CAPTURE_BYTES)
                .redirectErrorStream(true).charset(StandardCharsets.UTF_8).build();
    }

    /**
     * Builds the options for one background call.
     *
     * <p>
     * Identical to {@link #foregroundOptions(long)} except for the timeout, which is the whole point: a background
     * command must not inherit the foreground ceiling, and must not run without one either.
     *
     * @return the execution options
     */
    private static ExecutionOptions backgroundOptions() {
        return foregroundOptions(BACKGROUND_TIMEOUT_MS);
    }

    /**
     * Merges stdout and stderr into the single stream the model sees.
     *
     * <p>
     * With {@code redirectErrorStream(true)} the shell already folds stderr into stdout and leaves
     * {@link ShellCommandResult#stderr()} empty, so the append below is a fallback for shells that do not honour the
     * flag rather than the normal path.
     *
     * <p>
     * Takes the two streams rather than a {@link ShellCommandResult} because the timeout path has no result — it has a
     * {@link ShellTimeoutException} carrying the same two strings, and it must render them the same way.
     *
     * @param stdout
     *            the captured standard output
     * @param stderr
     *            the captured standard error
     * @return the merged output
     */
    private static String mergeStreams(String stdout, String stderr) {
        if (stderr.isEmpty()) {
            return stdout;
        }
        return stdout.isEmpty() ? stderr : stdout + '\n' + stderr;
    }

    /**
     * Assembles the one text body the model sees for a command that reached the shell.
     *
     * <p>
     * Every such outcome funnels through here — success, non-zero exit, timeout. Letting each call site build its own
     * string is what produced {@code "Command failed: Command failed with exit code 3: ..."} and what left truncation
     * applying to successful commands only.
     *
     * <p>
     * <b>Order is load-bearing.</b> The 30,000-character cut happens first and the markers are appended after it.
     * Appending first would place {@code [exit code: N]} inside the region the cut removes, so precisely the failures
     * with the most output — the ones whose status matters most — would lose it.
     *
     * @param output
     *            the merged output, possibly partial
     * @param marker
     *            the bracketed status marker to append, or null when the command succeeded
     * @param captureTruncated
     *            whether the shell stopped capturing at its byte cap
     * @return the assembled body
     */
    private static String renderBody(String output, String marker, boolean captureTruncated) {
        final StringBuilder body = new StringBuilder(renderOutput(output));
        if (marker != null) {
            appendOnItsOwnLine(body, marker);
        }
        if (captureTruncated) {
            appendOnItsOwnLine(body, CAPTURE_TRUNCATION_NOTICE);
        }
        return body.toString();
    }

    /**
     * Appends a marker so that it starts a line, without inventing blank lines.
     *
     * <p>
     * Command output usually already ends in a newline and sometimes there is no output at all — {@code grep} with no
     * match being the everyday case. Appending {@code "\n" + marker} unconditionally would answer that command with a
     * body that opens on an empty line.
     *
     * @param body
     *            the body being assembled
     * @param marker
     *            the marker to append
     */
    private static void appendOnItsOwnLine(StringBuilder body, String marker) {
        if (body.length() > 0 && body.charAt(body.length() - 1) != '\n') {
            body.append('\n');
        }
        body.append(marker);
    }

    /**
     * Truncates output to the maximum length, appending a notice when anything was dropped.
     *
     * @param output
     *            The output to render
     * @return The output, truncated and annotated if it exceeded the cap
     */
    private static String renderOutput(String output) {
        if (output.length() <= MAX_OUTPUT_LENGTH) {
            return output;
        }
        return output.substring(0, MAX_OUTPUT_LENGTH) + "\n\n[Output truncated at 30,000 characters]";
    }

    /**
     * Declares {@link InterruptBehavior#THREAD_INTERRUPT}: the tool thread blocks inside the shell's wait on the
     * process, which is interruptible. The coordinator's pre-registered {@code Thread.interrupt()} terminator is the
     * whole mechanism — the shell responds by destroying the process (and its descendants) and throwing, so waking the
     * waiter and killing the command are now the same event.
     *
     * <p>
     * There is no longer a second, registrar-bound terminator. The {@code future.cancel(true)} handle that used to be
     * registered here belonged to the foreground future wrapper; with the wrapper gone there is nothing for it to
     * cancel, and registering one would only claim a teardown path that does not exist. This is a simplification, not
     * a lost capability: cancelling the future never killed the process either.
     */
    @Override
    public InterruptBehavior getInterruptBehavior() {
        return InterruptBehavior.THREAD_INTERRUPT;
    }

    private ToolResult interruptedResult(CancellationSignal signal) {
        final String reason = signal.getReason().map(InterruptReason::name).orElse("UNKNOWN");
        return ToolResult.error("Bash command interrupted: " + reason);
    }

    /**
     * Names the {@code command} argument as the value a {@code Bash(...)} pattern is matched against.
     *
     * <p>
     * <b>Example allowed patterns:</b>
     *
     * <ul>
     * <li>{@code Bash(git:*)} - Allow all git commands
     * <li>{@code Bash(./gradlew:*)} - Allow all Gradle commands
     * <li>{@code Bash(npm install)} - Allow exactly that command
     * </ul>
     *
     * <p>
     * The subject is a {@link PermissionSubject.Kind#COMMAND}, so {@link ToolPattern} judges it — {@code prefix:*} or
     * an exact match, with shell metacharacters rejected outright so that {@code git log; rm -rf /} cannot ride in on
     * a {@code Bash(git:*)} grant.
     *
     * <p>
     * This replaces the {@code BashToolPermissionRule} that used to do the same job: with the subject named here, the
     * matching itself is the framework's, and this tool no longer has to reach into the permission package to state
     * something as simple as "judge me on my command".
     *
     * @return the command to judge, or empty when the call carries none
     */
    @Override
    public Optional<PermissionSubject> permissionSubject(ToolInput input, ToolContext context) {
        Objects.requireNonNull(input, "Input cannot be null");
        Objects.requireNonNull(context, "Context cannot be null");

        // Read the raw value: ToolInput#getStringOrNull throws on a type mismatch, and this runs before execute(),
        // where nothing would catch it. A non-string command cannot be judged — same answer as a missing one.
        if (!(input.get("command") instanceof String command) || command.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(PermissionSubject.command(command));
    }

    /**
     * Closes this tool by shutting down the executor service.
     *
     * <p>
     * This method implements {@link AutoCloseable} to enable try-with-resources usage. Equivalent to calling
     * {@link #shutdown()}.
     */
    @Override
    public void close() {
        shutdown();
    }

    /**
     * Shuts down the executor service. Call this when the tools is no longer needed to release resources.
     */
    public void shutdown() {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
