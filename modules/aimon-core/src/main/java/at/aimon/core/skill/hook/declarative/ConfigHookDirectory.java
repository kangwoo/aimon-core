package at.aimon.core.skill.hook.declarative;

import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.environment.StagedResource;
import at.aimon.core.environment.exception.ExecutionEnvironmentUnavailableException;
import at.aimon.core.environment.exception.StagingException;
import at.aimon.core.skill.hook.action.HookScriptDirectory;
import at.aimon.core.skill.hook.action.ShellAction;

/**
 * Gives a {@code hooks.json} command {@link SkillHookEnv#AIMON_HOOK_DIR} (aimon-core #273): the host path of the
 * declaring layer's {@code hooks/} directory when the command runs on the host, or the path a fresh stage of it
 * returned when it runs in an execution's environment.
 *
 * <p>
 * <b>Called by the executor that runs the command, never by the hook.</b> Where a {@code hooks.json} command runs is
 * decided per firing ({@link ExecutionOrHostShellActionExecutor}), so only the executor that ends up running it knows
 * which path is right. {@link HostShellActionExecutor} and {@link IsolatedShellActionExecutor} call this, after any
 * routing; the router itself does not.
 *
 * <p>
 * <b>Scanned at every firing.</b> The staging layer keeps serving a copy it already holds for a content key, and only a
 * change to {@code hooks.json} reloads the hooks: a resource scanned once when the file was applied would go on
 * running an old {@code guard.sh} after the operator edited it. Re-scanning reads and hashes the directory each time,
 * which is what a handler that names the variable pays; one that does not name it carries no directory and pays
 * nothing. A copy of a superseded version stays in the staging area until the provider's next sweep.
 *
 * <p>
 * <b>When staging fails the command is not run</b> ({@link ShellHookOutcome.Unrun#HOOK_DIR_STAGING_FAILED},
 * {@link ShellHookOutcome.Unrun#ENVIRONMENT_UNAVAILABLE}), as for {@link SkillHookDirectory}: a command written
 * against {@code "$AIMON_HOOK_DIR/guard.sh"} must not run as {@code "/guard.sh"}.
 */
final class ConfigHookDirectory {

    private static final Logger log = LoggerFactory.getLogger(ConfigHookDirectory.class);

    private ConfigHookDirectory() {
        throw new AssertionError("This class should not be instantiated");
    }

    /**
     * Exports the host path, for a command that runs on the host. Does nothing when the action carries no
     * directory.
     *
     * @param env
     *            the mutable environment being assembled (never null)
     * @param action
     *            the action about to run (never null)
     */
    static void exportHost(Map<String, String> env, ShellAction action) {
        action.getScriptDirectory()
                .ifPresent(directory -> env.put(SkillHookEnv.AIMON_HOOK_DIR, directory.getHostDirectory().toString()));
    }

    /**
     * Stages the action's directory into {@code environment} and exports the path it returned. Never throws.
     *
     * @param env
     *            the mutable environment being assembled (never null)
     * @param action
     *            the action about to run (never null)
     * @param environment
     *            the environment whose shell will run the command (never null)
     * @return empty when the command may run; otherwise the outcome to report <em>instead of</em> running it
     */
    static Optional<ShellHookOutcome> exportStaged(Map<String, String> env, ShellAction action,
            ExecutionEnvironment environment) {
        final Optional<HookScriptDirectory> directory = action.getScriptDirectory();
        if (directory.isEmpty()) {
            return Optional.empty();
        }
        try {
            final StagedResource resource = StagedResource.scan(directory.get().getSourceFileSystem(),
                    directory.get().getSourceDirectory(), directory.get().getResourceName());
            final String stagedDir = environment.stage(resource);
            if (stagedDir == null || stagedDir.isBlank()) {
                return refuse(env, ShellHookOutcome.Unrun.HOOK_DIR_STAGING_FAILED, "the environment returned no path",
                        null);
            }
            env.put(SkillHookEnv.AIMON_HOOK_DIR, stagedDir);
            return Optional.empty();
        } catch (StagingException e) {
            // The staging layer's own message (over the limit, changed since scanned), as for a skill directory.
            return refuse(env, ShellHookOutcome.Unrun.HOOK_DIR_STAGING_FAILED, e.getMessage(), null);
        } catch (ExecutionEnvironmentUnavailableException e) {
            return refuse(env, ShellHookOutcome.Unrun.ENVIRONMENT_UNAVAILABLE, e.getMessage(), null);
        } catch (RuntimeException | LinkageError e) {
            // The type only: a scan failure's message names host paths, and this becomes a deny reason.
            return refuse(env, ShellHookOutcome.Unrun.HOOK_DIR_STAGING_FAILED, ShellActionRunner.failureDetail(e), e);
        }
    }

    private static Optional<ShellHookOutcome> refuse(Map<String, String> env, ShellHookOutcome.Unrun cause,
            String detail, Throwable unexpected) {
        log.warn(
                "Hook shell action not run: the hooks directory could not be staged for AIMON_HOOK_DIR"
                        + " (hook={}, event={}): {}",
                env.get(SkillHookEnv.AIMON_SKILL_NAME), env.get(SkillHookEnv.AIMON_HOOK_EVENT), detail, unexpected);
        return Optional.of(ShellHookOutcome.notRun(cause, detail));
    }
}
