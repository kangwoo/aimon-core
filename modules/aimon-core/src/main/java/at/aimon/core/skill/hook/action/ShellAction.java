package at.aimon.core.skill.hook.action;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Declarative action (AIMON extension, SK-13) that runs a shell command when the enclosing hook fires.
 *
 * <p>
 * The exit code is a deliberately narrow control channel, matching Claude Code: exit <strong>2</strong> vetoes the
 * firing chain and the command's stderr becomes the deny reason. Any <em>other</em> non-zero exit, a timeout, or an
 * executor exception is logged at WARN level and the hook still returns
 * {@link at.aimon.core.hook.execution.HookResult#success()} — a broken script must not become a silent gatekeeper.
 * The veto only takes effect on the events that own a decision channel — {@code preTool} and {@code preCompact}
 * block, {@code permissionRequest} denies; on every other event it is logged at WARN and the event proceeds. An
 * unconditional veto is still better expressed as a {@link DenyAction}.
 *
 * <p>
 * Each invocation receives the action's command verbatim — the command is <strong>never</strong> passed through
 * {@code TemplateRenderer}, so untrusted tool input never reaches the command line. The firing context is handed over
 * out-of-band instead: a JSON document on standard input (see {@code ShellHookPayload}) plus a set of {@code AIMON_*}
 * environment variables. See {@code Declarative*Hook} for the exact env-var contract per event type.
 *
 * <p>
 * {@link #getTimeout()} is enforced by the executor that runs the command, and is republished through
 * {@link #getExecutionBudget()} so a {@code timeoutMs} longer than the hook policy's own timeout widens the outer net
 * instead of being truncated by it.
 *
 * <p>
 * Immutable and thread-safe.
 */
public final class ShellAction implements HookAction {

    /** Default timeout when the YAML omits {@code timeoutMs}. Matches the typical hook-script budget. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private final String command;
    private final Duration timeout;
    private final HookScriptDirectory scriptDirectory;

    /**
     * Creates a new shell action.
     *
     * @param command
     *            The shell command to execute (must not be null or blank)
     * @param timeout
     *            The execution timeout; {@code null} resolves to {@link #DEFAULT_TIMEOUT}. Must be positive when set.
     * @throws NullPointerException
     *             if command is null
     * @throws IllegalArgumentException
     *             if command is blank or timeout is non-positive
     */
    public ShellAction(String command, Duration timeout) {
        this(command, timeout, null);
    }

    /**
     * Creates a new shell action that carries the {@code hooks/} directory of the {@code hooks.json} layer it was
     * declared in, for {@code AIMON_HOOK_DIR}.
     *
     * <p>
     * Only {@code HookRegistryApplier} sets one, and only when the command names {@code AIMON_HOOK_DIR}; a
     * skill-declared action never carries one.
     *
     * @param command
     *            The shell command to execute (must not be null or blank)
     * @param timeout
     *            The execution timeout; {@code null} resolves to {@link #DEFAULT_TIMEOUT}. Must be positive when set.
     * @param scriptDirectory
     *            The layer's script directory, or {@code null} for none
     * @throws NullPointerException
     *             if command is null
     * @throws IllegalArgumentException
     *             if command is blank or timeout is non-positive
     */
    public ShellAction(String command, Duration timeout, HookScriptDirectory scriptDirectory) {
        Objects.requireNonNull(command, "Command cannot be null");
        if (command.isBlank()) {
            throw new IllegalArgumentException("Command cannot be blank");
        }
        if (timeout != null && (timeout.isZero() || timeout.isNegative())) {
            throw new IllegalArgumentException("Timeout must be positive, but was: " + timeout);
        }
        this.command = command;
        this.timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
        this.scriptDirectory = scriptDirectory;
    }

    /**
     * Returns the shell command string (verbatim, no substitution).
     *
     * @return The command (never null, never blank)
     */
    public String getCommand() {
        return command;
    }

    /**
     * Returns the execution timeout.
     *
     * @return The timeout (never null; {@link #DEFAULT_TIMEOUT} when not explicitly set)
     */
    public Duration getTimeout() {
        return timeout;
    }

    /**
     * Returns the script directory whose path the running executor exports as {@code AIMON_HOOK_DIR}.
     *
     * @return the directory, or empty when the command does not name the variable or the action was not declared in
     *         a {@code hooks.json} layer
     */
    public Optional<HookScriptDirectory> getScriptDirectory() {
        return Optional.ofNullable(scriptDirectory);
    }

    @Override
    public Optional<Duration> getExecutionBudget() {
        return Optional.of(timeout);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ShellAction that)) {
            return false;
        }
        return command.equals(that.command) && timeout.equals(that.timeout)
                && Objects.equals(scriptDirectory, that.scriptDirectory);
    }

    @Override
    public int hashCode() {
        return Objects.hash(command, timeout, scriptDirectory);
    }

    @Override
    public String toString() {
        return "ShellAction{command='" + command + "', timeout=" + timeout + '}';
    }
}
