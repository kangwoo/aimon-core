package at.aimon.core.skill.hook.declarative;

import java.util.Objects;

/**
 * Observable result of a declarative shell hook command (AIMON extension).
 *
 * <p>
 * Most hook events are fire-and-forget: the executor runs the command, logs failures, and the hook returns success
 * regardless. {@code preTool} is the exception — it is the one event whose hook may veto the dispatch, so it needs to
 * see what the command actually did. This value object is that channel.
 *
 * <p>
 * <b>Exit-code contract</b> (Claude Code parity):
 * <ul>
 * <li><b>0</b> — success; the hook allows the tool.
 * <li><b>{@value #DENY_EXIT_CODE}</b> — deny; the tool is blocked and {@link #getStderr() stderr} is fed back to the
 * model as the reason.
 * <li><b>anything else</b> — a hook script malfunction. It is logged at WARN and treated as allow: a broken audit
 * script must not silently start blocking every tool call.
 * </ul>
 *
 * <p>
 * {@link #notObserved()} models an executor that ran the command without reporting a status (or refused to run it at
 * all). It never denies — an executor that cannot report an exit code cannot be assumed to have approved or rejected
 * anything, and allow is the fail-soft default for declarative hooks.
 *
 * <p>
 * Immutable; thread-safe.
 */
public final class ShellHookOutcome {

    /** Exit code a shell hook uses to veto the tool dispatch. */
    public static final int DENY_EXIT_CODE = 2;

    /**
     * Upper bound on the number of characters of {@link #denyReason()} handed back to the model.
     *
     * <p>
     * The deny reason is concatenated verbatim into the tool result the model sees, so an unbounded stderr is an
     * unbounded injection into the conversation context: a hook script that exits {@value #DENY_EXIT_CODE} after
     * dumping a stack trace (or a whole log file) to stderr would otherwise push out the rest of the turn. The first
     * {@value #MAX_DENY_REASON_LENGTH} characters carry the actionable part of virtually every real deny message.
     */
    public static final int MAX_DENY_REASON_LENGTH = 4000;

    private static final ShellHookOutcome NOT_OBSERVED = new ShellHookOutcome(false, 0, "", "");

    private final boolean observed;
    private final int exitCode;
    private final String stdout;
    private final String stderr;

    private ShellHookOutcome(boolean observed, int exitCode, String stdout, String stderr) {
        this.observed = observed;
        this.exitCode = exitCode;
        this.stdout = stdout;
        this.stderr = stderr;
    }

    /**
     * Returns the shared "the executor did not report a status" outcome.
     *
     * @return the not-observed outcome (never null)
     */
    public static ShellHookOutcome notObserved() {
        return NOT_OBSERVED;
    }

    /**
     * Creates an observed outcome.
     *
     * @param exitCode
     *            the command's exit code
     * @param stdout
     *            captured standard output (must not be null; may be empty)
     * @param stderr
     *            captured standard error (must not be null; may be empty)
     * @return the outcome (never null)
     * @throws NullPointerException
     *             if stdout or stderr is null
     */
    public static ShellHookOutcome of(int exitCode, String stdout, String stderr) {
        return new ShellHookOutcome(true, exitCode, Objects.requireNonNull(stdout, "stdout cannot be null"),
                Objects.requireNonNull(stderr, "stderr cannot be null"));
    }

    /**
     * @return true when the executor actually reported an exit status for the command
     */
    public boolean isObserved() {
        return observed;
    }

    /**
     * @return the command's exit code; meaningless unless {@link #isObserved()}
     */
    public int getExitCode() {
        return exitCode;
    }

    /**
     * @return captured standard output (never null; empty when not observed)
     */
    public String getStdout() {
        return stdout;
    }

    /**
     * @return captured standard error (never null; empty when not observed)
     */
    public String getStderr() {
        return stderr;
    }

    /**
     * Returns whether the command vetoed the tool dispatch, i.e. exited with {@value #DENY_EXIT_CODE}.
     *
     * @return true when the hook should block the tool
     */
    public boolean isDenied() {
        return observed && exitCode == DENY_EXIT_CODE;
    }

    /**
     * Returns the deny reason to hand back to the model — the command's stderr, or a generic fallback when it wrote
     * nothing there.
     *
     * <p>
     * The reason is capped at {@value #MAX_DENY_REASON_LENGTH} characters. When stderr is longer it is cut at the cap
     * and a {@code "... [truncated, N chars total]"} marker is appended, so the model (and anyone reading the
     * transcript) can tell the reason is partial rather than silently losing the tail. {@link #getStderr()} still
     * exposes the full text for logging.
     *
     * @return a non-blank reason string (never null)
     */
    public String denyReason() {
        final String trimmed = stderr.strip();
        if (trimmed.isEmpty()) {
            return "Blocked by a shell hook (exit code " + DENY_EXIT_CODE + ", no stderr output)";
        }
        if (trimmed.length() <= MAX_DENY_REASON_LENGTH) {
            return trimmed;
        }
        return trimmed.substring(0, MAX_DENY_REASON_LENGTH) + "... [truncated, " + trimmed.length() + " chars total]";
    }

    @Override
    public String toString() {
        return observed ? "ShellHookOutcome{exitCode=" + exitCode + '}' : "ShellHookOutcome{notObserved}";
    }
}
