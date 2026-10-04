package at.aimon.core.skill.hook.declarative;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one place that decides what a shell hook command <em>that produced no exit status</em> means on an event with a
 * decision channel.
 *
 * <p>
 * The rule is fail-closed: a guard that could not decide blocks. The cause does not matter — no execution
 * environment, an unavailable one, an executor without shell support, a timeout and a shell failure are the same
 * event from the guard's side ("no answer"), and letting any one of them through would make it the way to switch the
 * guard off. A hook opts out per declaration with {@code failOpen: true}, for the audit hooks that share the guard
 * events without being guards.
 *
 * <p>
 * Shared by {@link DeclarativePreToolHook} and {@link AbstractDeclarativeShellHook} so the two cannot word or decide
 * this differently. Commands that did report an exit status never come through here.
 */
final class ShellHookVerdicts {

    private static final Logger log = LoggerFactory.getLogger(ShellHookVerdicts.class);

    private ShellHookVerdicts() {
        throw new AssertionError("This class should not be instantiated");
    }

    /**
     * Returns the reason to block with, or empty when the operation may proceed.
     *
     * <p>
     * Only call this for an event that can act on a block; an advisory event has nothing to decide.
     *
     * @param outcome
     *            what the executor reported (never null)
     * @param failOpen
     *            whether the hook declared {@code failOpen}
     * @param skillName
     *            the declaring skill, or the synthetic {@code hooks.json} name (never null)
     * @param eventName
     *            the AIMON event name (never null)
     * @return the deny reason handed to the model or user, or empty when the outcome is not a block
     */
    static Optional<String> guard(ShellHookOutcome outcome, boolean failOpen, String skillName, String eventName) {
        if (outcome.getUnrunCause().isEmpty()) {
            return Optional.empty();
        }
        if (failOpen) {
            log.warn("Hook '{}' ({}) could not run its command ({}); it declares failOpen, so the operation proceeds",
                    skillName, eventName, outcome.unrunReason());
            return Optional.empty();
        }
        // How to opt out goes to the log only. The deny reason is read by the party the guard constrains.
        log.warn(
                "Hook '{}' ({}) could not run its command ({}); blocking (fail-closed). Declare failOpen: true on the"
                        + " hook if it only observes and should not block when it cannot run",
                skillName, eventName, outcome.unrunReason());
        return Optional.of("Blocked: guard hook '" + skillName + "' (" + eventName + ") could not run its command — "
                + outcome.unrunReason() + ". A guard that cannot decide blocks (fail-closed).");
    }
}
