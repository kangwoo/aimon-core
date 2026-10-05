package at.aimon.core.skill.hook.declarative;

import java.util.Objects;
import java.util.Optional;

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
 * <li><b>{@value #NOT_EXECUTABLE_EXIT_CODE} / {@value #NOT_FOUND_EXIT_CODE}</b> — the shell could not start the
 * command. On an event with a decision channel this is read as "not run" ({@link #asGuardAnswer()}), so the guard
 * blocks unless it declared {@code failOpen}; on an advisory event it is logged like any other failure.
 * <li><b>anything else</b> — a hook script malfunction. It is logged at WARN and treated as allow: a broken audit
 * script must not silently start blocking every tool call.
 * </ul>
 *
 * <p>
 * {@link #notRun(Unrun, String)} models a command that produced no exit status at all — the executor could not run it,
 * or it ran and never finished. It carries <em>why</em>, because the caller has to decide what the missing answer
 * means: on an event with a decision channel a guard that could not decide blocks (fail-closed) unless the hook
 * declared {@code failOpen}, and on an advisory event it is only logged. There is deliberately no cause-less factory —
 * a new "could not run" branch has to name its cause, so it cannot quietly come back as a pass.
 *
 * <p>
 * Immutable; thread-safe.
 */
public final class ShellHookOutcome {

    /** Exit code a shell hook uses to veto the tool dispatch. */
    public static final int DENY_EXIT_CODE = 2;

    /** Exit code a POSIX shell reports for a command it found but could not execute. */
    public static final int NOT_EXECUTABLE_EXIT_CODE = 126;

    /** Exit code a POSIX shell reports for a command it did not find. */
    public static final int NOT_FOUND_EXIT_CODE = 127;

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

    /** Why a command produced no exit status. */
    public enum Unrun {

        /** The firing context carried no execution environment, and the executor has no other shell. */
        NO_ENVIRONMENT("no execution environment"),

        /** The execution environment is there but could not be used (or gave no shell). */
        ENVIRONMENT_UNAVAILABLE("execution environment unavailable"),

        /**
         * The declaring skill's directory could not be staged into the environment the command runs in, so
         * {@code AIMON_SKILL_DIR} has no value. The command is not run without it: a command written against
         * {@code "$AIMON_SKILL_DIR/scripts/x.sh"} would otherwise run {@code /scripts/x.sh}.
         */
        STAGING_FAILED("skill directory could not be staged"),

        /** The executor does not run shell actions at all. */
        SHELL_UNSUPPORTED("shell actions not supported"),

        /** The command did not finish inside its timeout. */
        TIMEOUT("timed out"),

        /** The shell failed before the command could report an exit status. */
        EXECUTION_FAILED("shell execution failed"),

        /**
         * The shell found the command but could not execute it (exit
         * {@value ShellHookOutcome#NOT_EXECUTABLE_EXIT_CODE}:
         * no execute permission, or not an executable). Only a guard event reads the exit code this way — see
         * {@link ShellHookOutcome#asGuardAnswer()}.
         */
        COMMAND_NOT_EXECUTABLE("command not executable"),

        /**
         * The shell did not find the command (exit {@value ShellHookOutcome#NOT_FOUND_EXIT_CODE}). Only a guard event
         * reads the exit code this way — see {@link ShellHookOutcome#asGuardAnswer()}.
         */
        COMMAND_NOT_FOUND("command not found");

        private final String description;

        Unrun(String description) {
            this.description = description;
        }

        /**
         * @return the cause as it reads in a deny reason (never null)
         */
        public String description() {
            return description;
        }
    }

    private final boolean observed;
    private final int exitCode;
    private final String stdout;
    private final String stderr;
    private final Unrun unrunCause;
    private final String unrunDetail;

    private ShellHookOutcome(boolean observed, int exitCode, String stdout, String stderr, Unrun unrunCause,
            String unrunDetail) {
        this.observed = observed;
        this.exitCode = exitCode;
        this.stdout = stdout;
        this.stderr = stderr;
        this.unrunCause = unrunCause;
        this.unrunDetail = unrunDetail;
    }

    /**
     * Creates the outcome of a command that produced no exit status.
     *
     * @param cause
     *            why there is no exit status (must not be null)
     * @param detail
     *            what the failure said about itself, typically the exception message (may be null or blank)
     * @return the outcome (never null)
     * @throws NullPointerException
     *             if cause is null
     */
    public static ShellHookOutcome notRun(Unrun cause, String detail) {
        return new ShellHookOutcome(false, 0, "", "", Objects.requireNonNull(cause, "cause cannot be null"),
                detail == null ? "" : detail.strip());
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
                Objects.requireNonNull(stderr, "stderr cannot be null"), null, "");
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
     * @return why the command produced no exit status; empty when it {@linkplain #isObserved() did}
     */
    public Optional<Unrun> getUnrunCause() {
        return Optional.ofNullable(unrunCause);
    }

    /**
     * Returns why the command produced no exit status, as {@code "<cause>: <detail>"} (or just the cause when the
     * failure said nothing about itself).
     *
     * <p>
     * Capped the same way {@link #denyReason()} is, for the same reason: on a blocking chain this text is handed back
     * to the model. It never contains the command string, which may carry secrets.
     *
     * @return the reason; empty when the command {@linkplain #isObserved() was observed} (never null)
     */
    public String unrunReason() {
        if (unrunCause == null) {
            return "";
        }
        return unrunDetail.isEmpty() ? unrunCause.description() : cap(unrunCause.description() + ": " + unrunDetail);
    }

    /**
     * Returns this outcome as an event with a decision channel reads it: an exit code of
     * {@value #NOT_EXECUTABLE_EXIT_CODE} or {@value #NOT_FOUND_EXIT_CODE} becomes a command that was
     * {@linkplain #notRun(Unrun, String) not run}, and every other outcome is returned unchanged.
     *
     * <p>
     * Those two codes are the shell's own report that it never started the command — the guard script is missing
     * from the environment, or is not executable there — so the guard said nothing, exactly as when there is no exit
     * status at all. A script can also exit with either code itself; the two cannot be told apart, and a guard that
     * does so is read as one that could not run. Every other non-zero code keeps its meaning (a script malfunction,
     * allowed), and an advisory event never calls this: there an exit code is only logged.
     *
     * <p>
     * The detail is the exit code alone. The shell's stderr for these codes quotes the command line, which may carry
     * secrets, and the reason is read by the party the guard constrains.
     *
     * @return the outcome to judge on a guard event (never null)
     */
    public ShellHookOutcome asGuardAnswer() {
        if (!observed) {
            return this;
        }
        if (exitCode == NOT_FOUND_EXIT_CODE) {
            return notRun(Unrun.COMMAND_NOT_FOUND, "exit code " + exitCode);
        }
        if (exitCode == NOT_EXECUTABLE_EXIT_CODE) {
            return notRun(Unrun.COMMAND_NOT_EXECUTABLE, "exit code " + exitCode);
        }
        return this;
    }

    /**
     * Returns whether the command vetoed the tool dispatch, i.e. exited with {@value #DENY_EXIT_CODE}.
     *
     * <p>
     * A command that was {@linkplain #notRun(Unrun, String) not run} is never "denied" in this sense — it said
     * nothing. Whether its silence blocks is the caller's decision, not a property of the outcome.
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
        return cap(trimmed);
    }

    private static String cap(String text) {
        if (text.length() <= MAX_DENY_REASON_LENGTH) {
            return text;
        }
        return text.substring(0, MAX_DENY_REASON_LENGTH) + "... [truncated, " + text.length() + " chars total]";
    }

    @Override
    public String toString() {
        return observed ? "ShellHookOutcome{exitCode=" + exitCode + '}' : "ShellHookOutcome{notRun=" + unrunCause + '}';
    }
}
