package at.aimon.core.skill.hook.declarative;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.environment.exception.StagingException;
import at.aimon.core.shell.RunDirectory;
import at.aimon.core.skill.hook.action.HookScriptDirectory;
import at.aimon.core.skill.hook.action.ShellAction;

/**
 * Gives a {@code hooks.json} command {@link SkillHookEnv#AIMON_HOOK_DIR} (aimon-core #273): the host path of the
 * declaring layer's {@code hooks/} directory when the command runs on the host, or a run directory holding its bytes
 * when it runs in an execution's environment (EE-109).
 *
 * <p>
 * <b>Called by the executor that runs the command, never by the hook.</b> Where a {@code hooks.json} command runs is
 * decided per firing ({@link ExecutionOrHostShellActionExecutor}), so only the executor that ends up running it knows
 * which value is right. {@link HostShellActionExecutor} and {@link IsolatedShellActionExecutor} call this, after any
 * routing; the router itself does not.
 *
 * <p>
 * <b>Read at every firing into the run directory; nothing is staged.</b> The directory is scanned and its bytes read
 * each time a handler that names the variable fires, so an operator's edit is picked up at the next firing, and the
 * shell places exactly those bytes in a fresh directory for that run ({@link HookRunFiles}). No copy of the layer
 * lands in the workspace, where the agent's shell could rewrite it between a check and the run. A handler that does
 * not name the variable carries no directory and pays nothing.
 *
 * <p>
 * <b>When the files cannot be read the command is not run</b> ({@link ShellHookOutcome.Unrun#HOOK_DIR_STAGING_FAILED}),
 * as for {@link SkillHookDirectory}: a command written against {@code "$AIMON_HOOK_DIR/guard.sh"} must not run as
 * {@code "/guard.sh"}.
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
     * Reads the action's directory into the run directory the shell places for {@code AIMON_HOOK_DIR}. Never throws.
     * The caller has checked that the action carries a directory and that the shell declares
     * {@code ShellFeature.RUN_DIRECTORY}.
     *
     * @param env
     *            the environment being assembled, for the log (never null)
     * @param directory
     *            the action's directory (never null)
     * @return the run directory, or the outcome to report <em>instead of</em> running the command
     */
    static Prepared runDirectory(Map<String, String> env, HookScriptDirectory directory) {
        try {
            return Prepared.of(HookRunFiles.forHookDirectory(directory));
        } catch (StagingException e) {
            // HookRunFiles' own message (over the limit, changing on disk, an unplaceable file name): fixed text plus
            // sizes and the escaped path inside the directory, no host path.
            return refuse(env, e.getMessage(), null);
        } catch (RuntimeException | LinkageError e) {
            // The type only: a scan failure's message names host paths, and this becomes a deny reason.
            return refuse(env, ShellActionRunner.failureDetail(e), e);
        }
    }

    private static Prepared refuse(Map<String, String> env, String detail, Throwable unexpected) {
        log.warn(
                "Hook shell action not run: the hooks directory could not be read for AIMON_HOOK_DIR"
                        + " (hook={}, event={}): {}",
                env.get(SkillHookEnv.AIMON_SKILL_NAME), env.get(SkillHookEnv.AIMON_HOOK_EVENT), detail, unexpected);
        return Prepared.refused(ShellHookOutcome.notRun(ShellHookOutcome.Unrun.HOOK_DIR_STAGING_FAILED, detail));
    }

    /** Either the run directory to hand the shell, or why the command is not run. */
    static final class Prepared {

        private final RunDirectory runDirectory;
        private final ShellHookOutcome refusal;

        private Prepared(RunDirectory runDirectory, ShellHookOutcome refusal) {
            this.runDirectory = runDirectory;
            this.refusal = refusal;
        }

        static Prepared of(RunDirectory runDirectory) {
            return new Prepared(Objects.requireNonNull(runDirectory, "runDirectory cannot be null"), null);
        }

        static Prepared refused(ShellHookOutcome refusal) {
            return new Prepared(null, Objects.requireNonNull(refusal, "refusal cannot be null"));
        }

        /** @return the run directory, or null when refused */
        RunDirectory runDirectory() {
            return runDirectory;
        }

        /** @return the outcome to report instead of running, or empty when there is a run directory */
        Optional<ShellHookOutcome> refusal() {
            return Optional.ofNullable(refusal);
        }
    }
}
