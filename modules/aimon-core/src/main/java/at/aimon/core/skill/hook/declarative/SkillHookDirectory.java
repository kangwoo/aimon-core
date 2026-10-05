package at.aimon.core.skill.hook.declarative;

import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.environment.StagedResource;
import at.aimon.core.environment.exception.ExecutionEnvironmentUnavailableException;
import at.aimon.core.environment.exception.StagingException;
import at.aimon.core.hook.execution.ExecutionHook;
import at.aimon.core.hook.execution.HookContext;
import at.aimon.core.skill.hook.SkillScopedHookRegistry;

/**
 * Gives a skill-declared shell hook {@link SkillHookEnv#AIMON_SKILL_DIR}: stages the declaring skill's directory into
 * the environment the command is about to run in, and exports the path that environment returned.
 *
 * <p>
 * Shared by the three places that assemble a shell hook's environment ({@link AbstractDeclarativeShellHook},
 * {@link DeclarativePreToolHook}, {@link DeclarativePostToolHook}) so they cannot decide differently when to export
 * the variable or what a failure means.
 *
 * <p>
 * <b>Staged at fire time, into the firing execution's environment.</b> A skill's hooks fire in the skill's fork and in
 * the executions that fork starts, each of which resolves an environment of its own. The copy the {@code Skill} tool
 * staged to render the body lives in the <em>invoking</em> execution's environment and may not exist where the hook
 * runs, so no path is carried over from activation: the resource is, and {@code stage()} is asked again here. For an
 * environment that already holds the copy that is one marker lookup.
 *
 * <p>
 * <b>When the variable is left out</b> (absent, never empty):
 * <ul>
 * <li>the hook was not declared by a skill &mdash; a {@code hooks.json} hook has no skill directory;
 * <li>the declaring skill has no {@link StagedResource} (assembled by hand);
 * <li>the executor does not run the command in the context's environment (a host executor): a path staged into the
 * context's environment says nothing about that shell;
 * <li>the context carries no environment &mdash; the executor then refuses the command anyway.
 * </ul>
 *
 * <p>
 * <b>When staging fails the command is not run</b>, on any event. Running it with the variable unset would turn
 * {@code bash "$AIMON_SKILL_DIR/scripts/guard.sh"} into {@code bash "/scripts/guard.sh"} &mdash; a different command,
 * whose exit 127 a guard event reads as "allow". The refusal is an ordinary
 * {@link ShellHookOutcome#notRun(ShellHookOutcome.Unrun, String) not-run outcome}, so the existing rule decides the
 * rest: a guard event blocks unless the hook declared {@code failOpen}, an advisory event only logs.
 */
final class SkillHookDirectory {

    private static final Logger log = LoggerFactory.getLogger(SkillHookDirectory.class);

    private SkillHookDirectory() {
        throw new AssertionError("This class should not be instantiated");
    }

    /**
     * Adds {@link SkillHookEnv#AIMON_SKILL_DIR} to the environment being assembled, when there is a value for it.
     * Never throws.
     *
     * <p>
     * The path is exported exactly as the environment returned it. It is not passed through
     * {@link SkillHookEnv#truncateValue(String)}: that cap is for model- and user-authored text, and a path cut short
     * is another path.
     *
     * @param env
     *            the mutable environment being assembled (never null)
     * @param context
     *            the firing context (never null)
     * @param hook
     *            the hook that is firing, as it was declared &mdash; it is looked up by identity (never null)
     * @param executor
     *            the executor that will run the command (never null)
     * @return empty when the command may run (with or without the variable); otherwise the outcome to report
     *         <em>instead of</em> running it
     */
    static Optional<ShellHookOutcome> export(Map<String, String> env, HookContext context, ExecutionHook<?> hook,
            ShellActionExecutor executor) {
        try {
            if (!executor.requiresExecutionEnvironment()) {
                return Optional.empty();
            }
            final Optional<StagedResource> resource = SkillScopedHookRegistry
                    .stagedResourceOf(context.getHookRegistry(), hook);
            final Optional<ExecutionEnvironment> environment = context.getExecutionEnvironment();
            if (resource.isEmpty() || environment.isEmpty()) {
                return Optional.empty();
            }
            final String stagedDir = environment.get().stage(resource.get());
            if (stagedDir == null || stagedDir.isBlank()) {
                // A provider defect, and the one this variable must never paper over: an empty value is exactly the
                // "/scripts/guard.sh" case.
                return refuse(env, ShellHookOutcome.Unrun.STAGING_FAILED, "the environment returned no path", null);
            }
            env.put(SkillHookEnv.AIMON_SKILL_DIR, stagedDir);
            return Optional.empty();
        } catch (StagingException e) {
            // The message is the staging layer's own (over the limit, changed since scanned) and is what the Skill
            // tool already reports to the model for the same failure.
            return refuse(env, ShellHookOutcome.Unrun.STAGING_FAILED, e.getMessage(), null);
        } catch (ExecutionEnvironmentUnavailableException e) {
            // Likewise the text every tool call in this environment already fails with.
            return refuse(env, ShellHookOutcome.Unrun.ENVIRONMENT_UNAVAILABLE, e.getMessage(), null);
        } catch (RuntimeException | LinkageError e) {
            // The type only, as for a shell failure: an unexpected message may carry the provider's internals.
            return refuse(env, ShellHookOutcome.Unrun.STAGING_FAILED, ShellActionRunner.failureDetail(e), e);
        }
    }

    private static Optional<ShellHookOutcome> refuse(Map<String, String> env, ShellHookOutcome.Unrun cause,
            String detail, Throwable unexpected) {
        log.warn(
                "Skill hook shell action not run: the skill directory could not be staged for AIMON_SKILL_DIR"
                        + " (skill={}, event={}): {}",
                env.get(SkillHookEnv.AIMON_SKILL_NAME), env.get(SkillHookEnv.AIMON_HOOK_EVENT), detail, unexpected);
        return Optional.of(ShellHookOutcome.notRun(cause, detail));
    }
}
