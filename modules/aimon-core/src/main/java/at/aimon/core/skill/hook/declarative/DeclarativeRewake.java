package at.aimon.core.skill.hook.declarative;

import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.hook.rewake.RewakeSpec;

/**
 * Attaches a configured {@code asyncRewake} spec to the result a declarative hook is about to return.
 *
 * <p>
 * Shared by {@link AbstractDeclarativeShellHook} and {@link DeclarativePreToolHook}, which cannot share a superclass:
 * preTool carries a matcher and HTTP / MCP executors, the lifecycle hooks do not.
 *
 * <p>
 * Attachment is unconditional, and deliberately so: this method cannot see whether the hook is running on the live
 * turn (where the attached spec becomes the <em>initial</em> envelope) or on a rewake fire (where it becomes a
 * <em>follow-up</em>). Filtering here would suppress both, turning a cron {@code asyncRewake} into dead config.
 *
 * <p>
 * <b>What bounds the resulting chain differs per trigger, and is enforced downstream:</b>
 * <ul>
 * <li><b>One-shot triggers</b> ({@code delay} / {@code event}) — the envelope fires exactly once, so re-attaching the
 * spec on every fire is what creates the next link. The chain stays bounded because
 * {@code DefaultRewakeFireListener#chainFollowUps} drops follow-ups past {@link RewakeSpec#getMaxAttempts()}.
 * <li><b>Cron triggers</b> — the envelope is registered with the scheduler's native cron trigger, stays registered
 * across fires, and is bounded by {@code endAt(firstScheduledAt + timeout)} plus a per-fire attempt cap. It already
 * repeats on its own, so chaining a fresh envelope off each fire would double the live envelope count every time
 * (roughly {@code 2^(maxAttempts-1)}). {@code DefaultRewakeFireListener#chainFollowUps} therefore refuses to chain a
 * cron follow-up — the guard sits there because only the listener knows it is on the re-fire path.
 * </ul>
 *
 * <p>
 * Callers must only route <em>firing</em> results through here — a hook whose matcher rejected the invocation must
 * schedule nothing.
 *
 * <p>
 * Stateless and thread-safe.
 */
final class DeclarativeRewake {

    private DeclarativeRewake() {
        // utility class
    }

    /**
     * Returns {@code result} with {@code spec} appended to its rewake specs.
     *
     * @param result
     *            the result the hook produced (must not be null)
     * @param spec
     *            the spec to attach, or null when the hook has none configured
     * @return {@code result} itself when {@code spec} is null, otherwise an equivalent result carrying the spec
     */
    static HookResult attach(HookResult result, RewakeSpec spec) {
        if (spec == null) {
            return result;
        }
        final HookResult.Builder builder = HookResult.builder().decision(result.getDecision())
                .flowControl(result.getFlowControl()).feedback(result.getFeedback().orElse(null))
                .updatedInput(result.getUpdatedInput().orElse(null))
                .updatedOutput(result.getUpdatedOutput().orElse(null));
        for (RewakeSpec existing : result.getRewakeSpecs()) {
            builder.rewakeSpec(existing);
        }
        return builder.rewakeSpec(spec).build();
    }
}
