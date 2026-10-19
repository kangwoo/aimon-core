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
import at.aimon.core.skill.hook.action.ShellAction;

/**
 * Decides whether a skill-declared shell hook gets {@link SkillHookEnv#AIMON_SKILL_DIR}, and holds the staging path
 * the executors fall back to.
 *
 * <p>
 * Shared by the three places that assemble a shell hook's environment ({@link AbstractDeclarativeShellHook},
 * {@link DeclarativePreToolHook}, {@link DeclarativePostToolHook}) so they cannot decide differently when there is a
 * skill directory. They run the command through {@link #run}, which hands what {@link #resourceFor} answers to
 * {@link ShellActionExecutor#run(ShellAction, HookContext, Map, String, Optional)},
 * and the executor gives the variable its value (EE-109): the bytes of the skill as a run directory where the shell
 * can place one ({@link HookRunFiles}), otherwise a staged copy ({@link #stageInto}).
 *
 * <p>
 * <b>Resolved at fire time, in the firing execution's environment.</b> A skill's hooks fire in the skill's fork and in
 * the executions that fork starts, each of which resolves an environment of its own. The copy the {@code Skill} tool
 * staged to render the body lives in the <em>invoking</em> execution's environment and may not exist where the hook
 * runs, so no path is carried over from activation: the resource is.
 *
 * <p>
 * <b>When the variable is left out</b> (absent, never empty):
 * <ul>
 * <li>the hook was not declared by a skill &mdash; a {@code hooks.json} hook has no skill directory;
 * <li>the declaring skill has no {@link StagedResource} (assembled by hand);
 * <li>the executor does not run the command in the context's environment (a host executor): a path in the context's
 * environment says nothing about that shell;
 * <li>the context carries no environment &mdash; the executor then refuses the command anyway.
 * </ul>
 *
 * <p>
 * <b>When the directory cannot be given the command is not run</b>, on any event. Running it with the variable unset
 * would turn {@code bash "$AIMON_SKILL_DIR/scripts/guard.sh"} into {@code bash "/scripts/guard.sh"} &mdash; a
 * different command, whose exit 127 a guard event reads as "allow". The refusal is an ordinary
 * {@link ShellHookOutcome#notRun(ShellHookOutcome.Unrun, String) not-run outcome}, so the existing rule decides the
 * rest: a guard event blocks unless the hook declared {@code failOpen}, an advisory event only logs.
 */
final class SkillHookDirectory {

    private static final Logger log = LoggerFactory.getLogger(SkillHookDirectory.class);

    private SkillHookDirectory() {
        throw new AssertionError("This class should not be instantiated");
    }

    /**
     * Returns the declaring skill's resource when the command is to get {@link SkillHookEnv#AIMON_SKILL_DIR}: the
     * executor runs it in the context's environment, the hook was declared by a skill with a resource, and the context
     * carries an environment.
     *
     * @param context
     *            the firing context (never null)
     * @param hook
     *            the hook that is firing, as it was declared &mdash; it is looked up by identity (never null)
     * @param executor
     *            the executor that will run the command (never null)
     * @return the resource, or empty when the variable is left out
     */
    static Optional<StagedResource> resourceFor(HookContext context, ExecutionHook<?> hook,
            ShellActionExecutor executor) {
        if (!executor.requiresExecutionEnvironment() || context.getExecutionEnvironment().isEmpty()) {
            return Optional.empty();
        }
        return SkillScopedHookRegistry.stagedResourceOf(context.getHookRegistry(), hook);
    }

    /**
     * Runs a declarative hook's shell action through its executor, with the skill directory when
     * {@link #resourceFor} finds one. A command that gets none goes through the four-argument
     * {@link ShellActionExecutor#run(ShellAction, HookContext, Map, String)}, exactly
     * as before EE-109, so an executor that never learned the skill-directory overload sees no change for it.
     *
     * @param executor
     *            the hook's executor (never null)
     * @param action
     *            the action (never null)
     * @param context
     *            the context handed to the executor (never null)
     * @param env
     *            the assembled environment (never null)
     * @param stdinPayload
     *            the stdin document, or null
     * @param hook
     *            the hook that is firing, as it was declared (never null)
     * @return what the executor reported
     */
    static ShellHookOutcome run(ShellActionExecutor executor, ShellAction action, HookContext context,
            Map<String, String> env, String stdinPayload, ExecutionHook<?> hook) {
        final Optional<StagedResource> resource = resourceFor(context, hook, executor);
        if (resource.isEmpty()) {
            return executor.run(action, context, env, stdinPayload);
        }
        return executor.run(action, context, env, stdinPayload, resource);
    }

    /**
     * Stages the skill into {@code environment} and adds the path it returned to the environment being assembled:
     * the path for a shell that cannot place a run directory. {@code stage()} checks the copy on every call, so what
     * is left is the moment between that check and the interpreter opening the files. Never throws.
     *
     * <p>
     * The path is exported exactly as the environment returned it. It is not passed through
     * {@link SkillHookEnv#truncateValue(String)}: that cap is for model- and user-authored text, and a path cut short
     * is another path.
     *
     * @param env
     *            the mutable environment being assembled (never null)
     * @param environment
     *            the firing context's environment (never null)
     * @param resource
     *            the declaring skill's resource (never null)
     * @return empty when the command may run; otherwise the outcome to report <em>instead of</em> running it
     */
    static Optional<ShellHookOutcome> stageInto(Map<String, String> env, ExecutionEnvironment environment,
            StagedResource resource) {
        try {
            final String stagedDir = environment.stage(resource);
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

    /**
     * The refusal for a skill directory that could not be given to the command, logged.
     *
     * @param env
     *            the environment being assembled, for the log (never null)
     * @param cause
     *            the cause to report (never null)
     * @param detail
     *            the model-safe detail (never null)
     * @param unexpected
     *            the throwable to log with its trace, or null
     * @return the outcome to report instead of running the command
     */
    static Optional<ShellHookOutcome> refuse(Map<String, String> env, ShellHookOutcome.Unrun cause, String detail,
            Throwable unexpected) {
        log.warn(
                "Skill hook shell action not run: the skill directory could not be given to AIMON_SKILL_DIR"
                        + " (skill={}, event={}): {}",
                env.get(SkillHookEnv.AIMON_SKILL_NAME), env.get(SkillHookEnv.AIMON_HOOK_EVENT), detail, unexpected);
        return Optional.of(ShellHookOutcome.notRun(cause, detail));
    }
}
