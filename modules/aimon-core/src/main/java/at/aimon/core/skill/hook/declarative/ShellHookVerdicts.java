package at.aimon.core.skill.hook.declarative;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one place that decides what a hook action <em>that gave no answer</em> means on an event with a decision
 * channel: a shell command that produced no exit status, or an {@code http} / {@code mcp} call that produced no
 * verdict ({@link ActionCallOutcome}).
 *
 * <p>
 * The rule is fail-closed: a guard that could not decide blocks. The cause does not matter — no execution
 * environment, an unavailable one, an executor without shell support, a timeout, a shell failure and a command the
 * shell could not start (exit 126 / 127, see {@link ShellHookOutcome#asGuardAnswer()}) are the same event from the
 * guard's side ("no answer"), and letting any one of them through would make it the way to switch the guard off. A
 * hook opts out per declaration with {@code failOpen: true}, for the audit hooks that share the guard events without
 * being guards.
 *
 * <p>
 * One cause is outside that opt-out: {@link ShellHookOutcome.Unrun#CANCELLED}. A hook stopped because its execution
 * was interrupted blocks with or without {@code failOpen} &mdash; the same answer the hook executor gives when the
 * thread waiting on a hook is interrupted, for the same reason: no verdict is no permission to proceed, and the
 * path is reachable only when the execution is ending anyway.
 *
 * <p>
 * Shared by {@link DeclarativePreToolHook} and {@link AbstractDeclarativeShellHook} so the two cannot word or decide
 * this differently. Every other exit status is not a block here: 2 is the caller's veto, and the rest are allowed.
 */
final class ShellHookVerdicts {

    private static final Logger log = LoggerFactory.getLogger(ShellHookVerdicts.class);

    /** What a shell action could not do. */
    static final String COMMAND = "run its command";

    /** What an {@code http} action could not do. */
    static final String HTTP_CALL = "get a verdict from its http call";

    /** What an {@code mcp} action could not do. */
    static final String MCP_CALL = "get a verdict from its mcp call";

    private ShellHookVerdicts() {
        throw new AssertionError("This class should not be instantiated");
    }

    /**
     * Returns the reason to block with, or empty when the operation may proceed.
     *
     * <p>
     * Only call this for an event that can act on a block; an advisory event has nothing to decide.
     *
     * @param reported
     *            what the executor reported (never null)
     * @param failOpen
     *            whether the hook declared {@code failOpen}
     * @param skillName
     *            the declaring skill, or the synthetic {@code hooks.json} name (never null)
     * @param eventName
     *            the AIMON event name (never null)
     * @return the deny reason handed to the model or user, or empty when the outcome is not a block
     */
    static Optional<String> guard(ShellHookOutcome reported, boolean failOpen, String skillName, String eventName) {
        return guard(reported, failOpen, skillName, eventName, COMMAND);
    }

    /**
     * {@link #guard(ShellHookOutcome, boolean, String, String)} for an action that is not a shell command: the same
     * decision, worded for what the hook was trying to do.
     *
     * @param reported
     *            what the action came to, as a not-run outcome (never null)
     * @param failOpen
     *            whether the hook declared {@code failOpen}
     * @param skillName
     *            the declaring skill, or the synthetic {@code hooks.json} name (never null)
     * @param eventName
     *            the AIMON event name (never null)
     * @param attempted
     *            what could not be done, completing "could not &hellip;" ({@link #COMMAND}, {@link #HTTP_CALL},
     *            {@link #MCP_CALL})
     * @return the deny reason handed to the model or user, or empty when the outcome is not a block
     */
    static Optional<String> guard(ShellHookOutcome reported, boolean failOpen, String skillName, String eventName,
            String attempted) {
        // Exit 126 / 127 is the shell saying it never started the command: no answer either.
        final ShellHookOutcome outcome = reported.asGuardAnswer();
        if (outcome.getUnrunCause().isEmpty()) {
            return Optional.empty();
        }
        if (outcome.getUnrunCause().get() == ShellHookOutcome.Unrun.CANCELLED) {
            // Not a guard that failed to decide: the execution it guards is being cancelled. failOpen opens "the
            // hook could not run", and that is not what happened; letting the operation go on would have an
            // interrupted execution take one more step.
            log.warn("Hook '{}' ({}) was stopped because its execution was interrupted; blocking", skillName,
                    eventName);
            return Optional.of("Blocked: hook '" + skillName + "' (" + eventName
                    + ") was stopped — execution cancelled. An interrupted execution does not proceed.");
        }
        if (failOpen) {
            log.warn("Hook '{}' ({}) could not {} ({}); it declares failOpen, so the operation proceeds", skillName,
                    eventName, attempted, outcome.unrunReason());
            return Optional.empty();
        }
        // How to opt out goes to the log only. The deny reason is read by the party the guard constrains.
        log.warn(
                "Hook '{}' ({}) could not {} ({}); blocking (fail-closed). Declare failOpen: true on the"
                        + " hook if it only observes and should not block when it cannot get an answer",
                skillName, eventName, attempted, outcome.unrunReason());
        return Optional.of("Blocked: guard hook '" + skillName + "' (" + eventName + ") could not " + attempted + " — "
                + outcome.unrunReason() + ". A guard that cannot decide blocks (fail-closed).");
    }
}
