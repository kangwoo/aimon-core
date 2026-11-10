package at.aimon.core.tools.code.shell;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import at.aimon.core.agent.interrupt.CancellationSignal;
import at.aimon.core.agent.tool.DataReach;
import at.aimon.core.agent.tool.DestructiveBehavior;
import at.aimon.core.agent.tool.SideEffectLevel;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.environment.EnvironmentDescriptor;
import at.aimon.core.environment.EnvironmentIsolation;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.environment.RuntimeFileSystem;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.programmatic.CodeExecutionEngine;
import at.aimon.core.programmatic.CodeExecutionLimits;
import at.aimon.core.programmatic.CodeExecutionOutcome;
import at.aimon.core.programmatic.CodeExecutionRequest;
import at.aimon.core.programmatic.CodeExecutionStatus;
import at.aimon.core.programmatic.EngineAvailability;
import at.aimon.core.programmatic.OfferFacts;
import at.aimon.core.programmatic.ToolCallBridge;
import at.aimon.core.programmatic.ToolGrade;
import at.aimon.core.shell.ExecutionOptions;
import at.aimon.core.shell.RunDirectory;
import at.aimon.core.shell.ShellCancellationSource;
import at.aimon.core.shell.ShellCommandResult;
import at.aimon.core.shell.ShellFeature;
import at.aimon.core.shell.ShellKillCause;
import at.aimon.core.shell.exception.RunDirectoryIntegrityException;
import at.aimon.core.shell.exception.ShellCancelledException;
import at.aimon.core.shell.exception.ShellExecutionException;
import at.aimon.core.shell.exception.ShellTimeoutException;
import at.aimon.core.tools.ExecutionEnvironmentAccess;
import at.aimon.core.tools.bash.BashTool;

/**
 * The shell engine of {@code ExecuteCode}: model-written Python run by {@code python3} inside a sandboxed execution
 * environment, whose tool calls come back to the host over an authenticated line protocol
 * ({@code docs/design/tool/programmatic-tool-calling-shell.md}).
 *
 * <p>
 * The engine knows no sandbox provider. It uses what the environment gives it: the shell (an isolated, streamed
 * command with a {@link RunDirectory} holding the runtime, the program, the key as a secret file and the reply
 * directory), the {@link RuntimeFileSystem} (the reply files, written with an atomic create-new), and the descriptor
 * ({@link EnvironmentDescriptor#isolation()}, the declared {@code python3} runtime).
 *
 * <p>
 * Its intrinsic grade is {@code Bash}'s — code it runs writes, deletes and starts processes without calling a tool —
 * and it is approved under {@code Bash}'s key (decisions 10, 11). It is offered only where the agent can use
 * {@code Bash} without constraint, and its callable set is bounded by {@code shell-engine-tool-scope}
 * ({@link #isToolScopeBound()}).
 *
 * <p>
 * Application-scoped and thread-safe. What it keeps across calls is the probe result per environment object, held
 * weakly ({@code docs/overview/scope-model.md}); {@link #close()} stops its reply-writer threads.
 */
public final class ShellCodeExecutionEngine implements CodeExecutionEngine {

    /** The environment variable naming the run directory. */
    static final String RUN_DIRECTORY_VARIABLE = "AIMON_RUN_DIR";

    /** The command that starts the runtime (§5). */
    static final String COMMAND = "python3 -I -u \"$" + RUN_DIRECTORY_VARIABLE + "/aimon_ptc.py\"";

    /** The directory, under the environment's working directory, that holds the reply directories (§5). */
    static final String PTC_DIRECTORY = ".aimon-ptc";

    /** The most a reply payload may hold (§7): 1 MiB. */
    static final int MAX_REPLY_BYTES = 1024 * 1024;

    private static final Logger log = LoggerFactory.getLogger(ShellCodeExecutionEngine.class);

    /** What an absolute path in the shell's stderr becomes in the model's {@code [logs]} (TC-31). */
    static final String HOST_PATH = "<host path>";

    /** A path segment with its {@code /}: no whitespace, slash, quote, parenthesis, colon, comma or semicolon. */
    private static final String PATH_SEGMENT = "(?:/[^\\s/'\"():,;]+)";

    /**
     * An absolute POSIX path: {@code /} and a segment, any number of times. Not after a word character, {@code :},
     * {@code /} or {@code .}, so a URL's {@code //host/p}, a relative {@code a/b} and a fraction stay as they are.
     */
    private static final Pattern ABSOLUTE_PATH = Pattern.compile("(?<![\\w:/.])" + PATH_SEGMENT + "+/?");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String RUNTIME_RESOURCE = "aimon_ptc.py";
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(30);
    /**
     * The least age at which a run's runtime sweeps an entry of {@code .aimon-ptc/} (§8): the default configuration's
     * twice-the-wall-clock, so a small configured limit cannot sweep a run directory another run has written but not
     * yet started on a slow sandbox.
     */
    static final Duration MIN_SWEEP_AGE = Duration.ofMinutes(20);

    private static final ToolGrade GRADE = ToolGrade.builder().sideEffectLevel(SideEffectLevel.MUTATING)
            .destructiveBehavior(DestructiveBehavior.DESTRUCTIVE).dataReach(DataReach.ENVIRONMENT)
            .approvalDelegated(false).approvalKey(BashTool.TOOL_NAME).build();

    private final byte[] runtime;
    /** Probe answers by environment object identity, held weakly (§4). */
    private final Map<ExecutionEnvironment, EngineAvailability> probes = Collections
            .synchronizedMap(new WeakHashMap<>());
    private final ExecutorService executor;

    private ShellCodeExecutionEngine() {
        this.runtime = loadRuntime();
        final AtomicInteger threads = new AtomicInteger();
        this.executor = Executors.newCachedThreadPool(runnable -> {
            final Thread thread = new Thread(runnable, "aimon-ptc-reply-" + threads.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Creates the engine.
     *
     * @return a new engine; the caller owns it and closes it at shutdown (never null)
     */
    public static ShellCodeExecutionEngine create() {
        return new ShellCodeExecutionEngine();
    }

    private static byte[] loadRuntime() {
        try (InputStream in = ShellCodeExecutionEngine.class.getResourceAsStream(RUNTIME_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("The shell engine's runtime " + RUNTIME_RESOURCE + " is missing");
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("The shell engine's runtime could not be read", e);
        }
    }

    @Override
    public String language() {
        return PYTHON;
    }

    @Override
    public EngineAvailability staticAvailability(OfferFacts facts) {
        Objects.requireNonNull(facts, "facts cannot be null");
        if (facts.getIsolation().orElse(null) != EnvironmentIsolation.SANDBOXED) {
            return EngineAvailability.unavailable("the execution environment is not sandboxed (the shell engine runs "
                    + "only inside a sandbox; isolation is " + facts.getIsolation().map(Enum::name).orElse("undeclared")
                    + ")");
        }
        final List<String> missing = new ArrayList<>();
        for (ShellFeature feature : List.of(ShellFeature.STREAMING, ShellFeature.RUN_DIRECTORY,
                ShellFeature.ISOLATED_EXECUTION)) {
            if (!facts.getShellFeatures().contains(feature)) {
                missing.add(feature.name());
            }
        }
        if (!missing.isEmpty()) {
            return EngineAvailability.unavailable("the environment's shell does not declare " + missing);
        }
        if (!facts.hasRuntimeFileSystem()) {
            return EngineAvailability.unavailable("the environment offers no runtime file system");
        }
        if (!facts.getRuntimes().contains(EnvironmentDescriptor.RUNTIME_PYTHON3)) {
            return EngineAvailability.unavailable("the environment does not declare the python3 runtime");
        }
        if (!facts.isBashUnconstrained()) {
            return EngineAvailability.unavailable("the agent cannot use Bash without constraint (Bash must be "
                    + "offered, named in allowed-tools without an argument pattern, and no PermissionRequest or "
                    + "PreTool hook may apply to it)");
        }
        return EngineAvailability.available();
    }

    /**
     * Checks, once per environment object, that {@code python3} 3.8 or later runs and sees a file written through the
     * runtime file system (§4). A definite answer is kept; a failure to ask (the shell or the file system failed) is
     * answered unavailable for this call only. Stale run directories are swept by each run's runtime, not here (§8).
     */
    @Override
    public EngineAvailability probe(ExecutionEnvironment environment) {
        if (environment == null) {
            return EngineAvailability.unavailable("the execution has no environment");
        }
        final EngineAvailability known = probes.get(environment);
        if (known != null) {
            return known;
        }
        final Optional<RuntimeFileSystem> files = environment.runtimeFileSystem();
        if (files.isEmpty()) {
            return remember(environment,
                    EngineAvailability.unavailable("the environment offers no runtime file system"));
        }
        final String root = ptcRoot(environment);
        final String probeFile = root + "/probe-" + randomHex(16);
        try {
            files.get().createDirectories(root);
            files.get().writeNew(probeFile, new byte[]{'1'});
        } catch (IOException e) {
            return EngineAvailability.unavailable("a file could not be written through the runtime file system: " + e);
        }
        try {
            final String command = "command -v python3 >/dev/null 2>&1 || exit 127\n"
                    + "python3 -I -c 'import sys; sys.exit(0 if sys.version_info >= (3, 8) else 3)' || exit 3\n"
                    + "test -f " + quote(probeFile) + " || exit 4\n" + "exit 0";
            final ShellCommandResult result = environment.shell().execute(() -> command,
                    ExecutionOptions.builder().isolated(true).timeout(PROBE_TIMEOUT).build());
            return switch (result.exitCode()) {
                case 0 -> remember(environment, EngineAvailability.available());
                case 127 -> remember(environment, EngineAvailability.unavailable("python3 is not installed"));
                case 3 -> remember(environment, EngineAvailability.unavailable("python3 is older than 3.8"));
                case 4 -> remember(environment, EngineAvailability
                        .unavailable("a file written through the runtime file system is not visible to the shell"));
                default -> EngineAvailability.unavailable("the probe failed with exit code " + result.exitCode());
            };
        } catch (ShellExecutionException e) {
            return EngineAvailability.unavailable("the probe could not run: " + e.getMessage());
        } finally {
            deleteQuietly(files.get(), probeFile);
        }
    }

    private EngineAvailability remember(ExecutionEnvironment environment, EngineAvailability availability) {
        probes.put(environment, availability);
        if (!availability.isAvailable()) {
            log.warn("ExecuteCode: the shell engine cannot run in this environment: {}",
                    availability.getReason().orElse(""));
        }
        return availability;
    }

    @Override
    public ToolGrade intrinsicGrade() {
        return GRADE;
    }

    @Override
    public boolean isToolScopeBound() {
        return true;
    }

    @Override
    public CodeExecutionOutcome execute(CodeExecutionRequest request, ToolCallBridge bridge, ToolContext context) {
        Objects.requireNonNull(request, "request cannot be null");
        Objects.requireNonNull(bridge, "bridge cannot be null");
        Objects.requireNonNull(context, "context cannot be null");
        final Optional<ExecutionEnvironment> found = ExecutionEnvironmentAccess.of(context);
        if (found.isEmpty()) {
            return error("the execution has no environment to run the program in");
        }
        final ExecutionEnvironment environment = found.get();
        final Optional<RuntimeFileSystem> runtimeFiles = environment.runtimeFileSystem();
        if (runtimeFiles.isEmpty()) {
            return error("the environment offers no runtime file system");
        }
        final RuntimeFileSystem files = runtimeFiles.get();
        final CodeExecutionLimits limits = request.getLimits();
        final byte[] key = new byte[32];
        RANDOM.nextBytes(key);
        final String runDirectory = ptcRoot(environment) + "/" + randomHex(16);
        final String replyDirectory = runDirectory + "/r";

        final RunDirectory placed;
        try {
            placed = RunDirectory.builder().variable(RUN_DIRECTORY_VARIABLE).file(RUNTIME_RESOURCE, runtime)
                    .file("main.py", request.getCode().getBytes(StandardCharsets.UTF_8))
                    .file("config.json", config(request)).secretFile("key", key)
                    .file("reply_dir", runDirectory.getBytes(StandardCharsets.UTF_8)).build();
        } catch (IllegalArgumentException e) {
            return error("the program is too large: " + e.getMessage());
        }
        try {
            files.createDirectories(replyDirectory);
        } catch (IOException e) {
            return error("the reply directory could not be created: " + e);
        }

        final ShellCancellationSource stop = ShellCancellationSource.create();
        final long deadline = System.nanoTime() + request.getWallClock().toNanos();
        final ShellProgramRun run = new ShellProgramRun(key, bridge, files, replyDirectory, limits, executor,
                ShellProgramRun.Control.of(stop::cancel, deadline));
        final CancellationSignal signal = request.getCancellation();
        // Stopping first, then the shell's kill: a CALL line still in flight after the cancellation is not forwarded.
        final CancellationSignal.Registration stopOnCancel = signal.onCancel(() -> {
            run.markStopping();
            stop.cancel();
        });
        final ExecutionOptions options = ExecutionOptions.builder().isolated(true).runDirectory(placed)
                .timeout(request.getWallClock()).cancellation(stop.token()).charset(StandardCharsets.UTF_8).build();
        ShellEnd end;
        try {
            end = ShellEnd.finished(environment.shell().execute(() -> COMMAND, options, run));
        } catch (ShellTimeoutException e) {
            end = ShellEnd.TIMED_OUT;
        } catch (ShellCancelledException e) {
            end = ShellEnd.CANCELLED;
        } catch (RunDirectoryIntegrityException e) {
            end = ShellEnd.failed(e.getMessage(), null);
        } catch (ShellExecutionException e) {
            end = ShellEnd.failed(null, e.getMessage());
        } catch (UnsupportedOperationException e) {
            end = ShellEnd.failed(null, "the environment's shell does not stream: " + e.getMessage());
        } finally {
            stopOnCancel.remove();
            // The run's reply writes close, and those already started finish, before the run directory goes: nothing
            // is written into the sandbox after this (§8). The bridge is closed later by its owner, ExecuteCode.
            run.close();
            deleteQuietly(files, runDirectory);
        }
        return classify(environment, bridge, run, end);
    }

    private CodeExecutionOutcome classify(ExecutionEnvironment environment, ToolCallBridge bridge, ShellProgramRun run,
            ShellEnd end) {
        final ShellCommandResult result = end.result;
        final String integrity = end.integrity;
        final String hostError = end.hostError;
        final String stdout = run.stdout();
        final String logs = run.logs();
        String violation = run.violation().orElse(null);
        if (violation == null && integrity != null) {
            violation = "the run directory was changed before the runtime started: " + integrity;
        }
        if (violation == null && result != null
                && result.killCause().orElse(null) == ShellKillCause.LINE_LIMIT_EXCEEDED) {
            violation = "the shell ended the runtime for a line over its limit";
        }
        if (violation == null && run.done().map(done -> "violation".equals(done.status())).orElse(false)) {
            violation = "the runtime reported a violation: "
                    + run.done().flatMap(ShellProgramRun.Done::error).orElse("");
        }
        if (violation != null) {
            // Decision 7: end now, not retryable, ERROR log (ExecuteCodeTool), no more shell engine on this object.
            bridge.markUnattributed();
            probes.put(environment, EngineAvailability.unavailable("a protocol violation was detected on this "
                    + "environment; the shell engine stays disabled for it"));
            return outcome(CodeExecutionStatus.PROTOCOL_VIOLATION, "", "", "protocol violation: " + violation);
        }
        if (bridge.limitExceeded().isPresent()) {
            return outcome(CodeExecutionStatus.LIMIT_EXCEEDED, stdout, logs, bridge.limitExceeded().get());
        }
        if (run.outputLimitExceeded()) {
            return outcome(CodeExecutionStatus.LIMIT_EXCEEDED, stdout, logs, "the program's output exceeded the limit");
        }
        if (run.hostFailure().isPresent()) {
            return outcome(CodeExecutionStatus.SCRIPT_ERROR, stdout, logs, run.hostFailure().get());
        }
        if (end.timedOut) {
            return outcome(CodeExecutionStatus.TIMED_OUT, stdout, logs, "the program ran out of wall clock");
        }
        if (end.cancelled) {
            return outcome(CodeExecutionStatus.CANCELLED, stdout, logs, "the execution was cancelled");
        }
        if (hostError != null) {
            return outcome(CodeExecutionStatus.SCRIPT_ERROR, stdout, logs, "the shell failed: " + hostError);
        }
        final Optional<ShellProgramRun.Done> done = run.done();
        if (done.isPresent()) {
            final String status = done.get().status();
            final String message = done.get().error().orElse(null);
            return switch (status) {
                case "completed" -> outcome(CodeExecutionStatus.COMPLETED, stdout, logs, null);
                case "error" -> outcome(CodeExecutionStatus.SCRIPT_ERROR, stdout, logs,
                        message == null ? "the program failed" : message);
                case "output_limit" -> outcome(CodeExecutionStatus.LIMIT_EXCEEDED, stdout, logs,
                        message == null ? "the program's output exceeded the limit" : message);
                default -> {
                    bridge.markUnattributed();
                    yield outcome(CodeExecutionStatus.PROTOCOL_VIOLATION, "", "",
                            "protocol violation: unknown DONE status");
                }
            };
        }
        final int exit = result == null ? -1 : result.exitCode();
        final String stderr = (run.shellStderr() + "\n" + (result == null ? "" : result.stderr())).strip();
        // TC-31: what the shell itself wrote — a traceback from before the runtime installed its printers, or
        // python3's own complaint — names host paths and runtime frames. The host log keeps all of it; the model gets
        // its last line with the paths taken out, which is the part that says what went wrong.
        if (logs.isEmpty() && !stderr.isEmpty()) {
            log.warn(
                    "ExecuteCode: the shell engine's runtime ended without finishing (exit code {}); shell stderr:\n{}",
                    exit, stderr);
        }
        final String modelLogs = logs.isEmpty() ? modelVisibleStderr(stderr, knownRoots(environment)) : logs;
        if (exit == 127) {
            probes.put(environment, EngineAvailability.unavailable("python3 is not installed"));
            return outcome(CodeExecutionStatus.SCRIPT_ERROR, stdout, modelLogs,
                    "python3 is not available in this environment");
        }
        return outcome(CodeExecutionStatus.SCRIPT_ERROR, stdout, modelLogs,
                "the runtime ended without finishing (exit code " + exit + ")");
    }

    /**
     * What the model sees of the shell's own stderr when the runtime ended without {@code DONE} and sent no logs
     * (TC-31): the last non-blank line, with every absolute path replaced by {@value #HOST_PATH}.
     *
     * <p>
     * A segment of {@link #ABSOLUTE_PATH} stops at whitespace, so a path under a directory with a space in its name
     * ({@code /Users/Jane Doe/...}) would be taken out only up to the space. The directories this engine knows the
     * runtime's files to be under — {@code knownRoots} — are therefore matched literally first, with whatever path
     * follows them, longest first; the pattern then takes the paths left.
     *
     * <p>
     * Unlike the runtime's {@code ERR} lines, this text is not the program's output — the runtime had not started it,
     * or died around it — so rewriting it loses no data the model wrote. The traceback above the last line is the
     * runtime's own frames, of no use to the model; the last line is the exception or {@code python3}'s message.
     *
     * @param stderr
     *            the shell's stderr tail (must not be null)
     * @param knownRoots
     *            absolute directories to take out even where they hold whitespace (must not be null); an entry that
     *            is not absolute, or is {@code /}, is skipped
     * @return the line to show, or {@code ""} when there is none
     */
    static String modelVisibleStderr(String stderr, List<String> knownRoots) {
        final String[] lines = stderr.split("\\R");
        for (int i = lines.length - 1; i >= 0; i--) {
            if (!lines[i].isBlank()) {
                String line = lines[i].strip();
                final Pattern known = knownRootPattern(knownRoots);
                if (known != null) {
                    line = known.matcher(line).replaceAll(Matcher.quoteReplacement(HOST_PATH));
                }
                return ABSOLUTE_PATH.matcher(line).replaceAll(HOST_PATH);
            }
        }
        return "";
    }

    /**
     * The known roots as one pattern: each root literally, ending at a segment boundary, then any segments under it.
     * Longest first, so a root under another root is taken out whole. {@code null} when no root is usable.
     */
    private static Pattern knownRootPattern(List<String> knownRoots) {
        final List<String> roots = new ArrayList<>();
        for (String root : knownRoots) {
            String trimmed = root == null ? "" : root.strip();
            while (trimmed.endsWith("/")) {
                trimmed = trimmed.substring(0, trimmed.length() - 1);
            }
            if (trimmed.startsWith("/") && !roots.contains(trimmed)) {
                roots.add(trimmed);
            }
        }
        if (roots.isEmpty()) {
            return null;
        }
        roots.sort(Comparator.comparingInt(String::length).reversed());
        final StringJoiner alternatives = new StringJoiner("|", "(?<![\\w:/.])(?:", ")");
        roots.forEach(root -> alternatives.add(Pattern.quote(root)));
        return Pattern.compile(alternatives + "(?![^\\s/'\"():,;])" + PATH_SEGMENT + "*/?");
    }

    /**
     * The directories the runtime's files are under, as far as this engine knows them: the environment's working
     * directory, which holds {@code .aimon-ptc/} (§5), and this JVM's temp root, under which {@code LocalShell} places
     * the run directory with {@code aimon_ptc.py}. A remote shell's run directory is not known here; its paths are
     * left to {@link #ABSOLUTE_PATH}.
     */
    private static List<String> knownRoots(ExecutionEnvironment environment) {
        final List<String> roots = new ArrayList<>();
        roots.add(environment.descriptor().workingDirectory());
        final String temp = System.getProperty("java.io.tmpdir");
        if (temp != null) {
            roots.add(temp);
        }
        return roots;
    }

    /** How the shell call ended: a result, or one of its exceptions. */
    private static final class ShellEnd {
        static final ShellEnd TIMED_OUT = new ShellEnd(null, true, false, null, null);
        static final ShellEnd CANCELLED = new ShellEnd(null, false, true, null, null);

        final ShellCommandResult result;
        final boolean timedOut;
        final boolean cancelled;
        /** The run directory's digest check failed: someone changed the runtime's files. */
        final String integrity;
        final String hostError;

        private ShellEnd(ShellCommandResult result, boolean timedOut, boolean cancelled, String integrity,
                String hostError) {
            this.result = result;
            this.timedOut = timedOut;
            this.cancelled = cancelled;
            this.integrity = integrity;
            this.hostError = hostError;
        }

        static ShellEnd finished(ShellCommandResult result) {
            return new ShellEnd(result, false, false, null, null);
        }

        static ShellEnd failed(String integrity, String hostError) {
            return new ShellEnd(null, false, false, integrity, hostError);
        }
    }

    private static CodeExecutionOutcome outcome(CodeExecutionStatus status, String stdout, String logs,
            String errorMessage) {
        return CodeExecutionOutcome.builder().status(status).stdout(stdout).logs(logs).errorMessage(errorMessage)
                .build();
    }

    private static CodeExecutionOutcome error(String message) {
        return outcome(CodeExecutionStatus.SCRIPT_ERROR, "", "", message);
    }

    private static byte[] config(CodeExecutionRequest request) {
        final List<String> tools = new ArrayList<>();
        for (ToolDefinition tool : request.getTools()) {
            tools.add(tool.getName());
        }
        final Map<String, Object> config = new LinkedHashMap<>();
        config.put("tools", tools);
        config.put("max_output_bytes", request.getLimits().getMaxScriptOutputBytes());
        config.put("max_reply_bytes", MAX_REPLY_BYTES + 128);
        config.put("max_log_chars", request.getLimits().getMaxLogChars());
        config.put("wall_clock_seconds", request.getWallClock().toMillis() / 1000.0);
        config.put("sweep_age_seconds", sweepAge(request.getLimits()).toSeconds());
        try {
            return JSON.writeValueAsBytes(config);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("The runtime's configuration could not be encoded", e);
        }
    }

    /**
     * The age past which this run's runtime sweeps an entry of {@code .aimon-ptc/} (§8):
     * {@code max(2 × maxWallClock, 20 min)}. A run's wall clock never exceeds {@code maxWallClock}, so no live run of
     * the same configuration is that old.
     */
    static Duration sweepAge(CodeExecutionLimits limits) {
        final Duration twice = limits.getMaxWallClock().multipliedBy(2);
        return twice.compareTo(MIN_SWEEP_AGE) > 0 ? twice : MIN_SWEEP_AGE;
    }

    private static String ptcRoot(ExecutionEnvironment environment) {
        String workingDirectory = environment.descriptor().workingDirectory();
        while (workingDirectory.length() > 1 && workingDirectory.endsWith("/")) {
            workingDirectory = workingDirectory.substring(0, workingDirectory.length() - 1);
        }
        return (workingDirectory.equals("/") ? "" : workingDirectory) + "/" + PTC_DIRECTORY;
    }

    private static String randomHex(int bytes) {
        final byte[] random = new byte[bytes];
        RANDOM.nextBytes(random);
        return HexFormat.of().formatHex(random);
    }

    private static String quote(String text) {
        return "'" + text.replace("'", "'\\''") + "'";
    }

    private static void deleteQuietly(RuntimeFileSystem files, String path) {
        try {
            files.delete(path);
        } catch (IOException e) {
            log.warn("ExecuteCode: {} could not be removed ({}); a later run sweeps it", path, e.toString());
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
