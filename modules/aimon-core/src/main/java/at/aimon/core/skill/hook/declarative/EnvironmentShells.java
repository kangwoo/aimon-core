package at.aimon.core.skill.hook.declarative;

import java.util.Objects;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.environment.exception.ExecutionEnvironmentUnavailableException;
import at.aimon.core.shell.ShellFeature;
import at.aimon.core.shell.VirtualShell;

/**
 * Asks an execution environment for its shell, the way every environment-bound {@link ShellActionExecutor} does: an
 * unavailable environment, or one whose {@code shell()} throws, is a command that is not run, never a reason to reach
 * for another shell.
 *
 * <p>
 * Shared by {@link DefaultShellActionExecutor} (skill hooks) and {@link IsolatedShellActionExecutor}
 * ({@code hooks.json}) so the two cannot report the same failure differently. It hands the shell to the caller's
 * body and keeps nothing: a hook class holds no shell.
 */
final class EnvironmentShells {

    private static final Logger log = LoggerFactory.getLogger(EnvironmentShells.class);

    private EnvironmentShells() {
        throw new AssertionError("This class should not be instantiated");
    }

    /**
     * Runs {@code body} with the environment's shell, or returns why there is none. Never throws on the
     * environment's account.
     *
     * @param environment
     *            the environment to ask (never null)
     * @param command
     *            the command about to run, for the log only (never null)
     * @param what
     *            what is being run, as the log names it ({@code "Skill hook"}, {@code "Hook"}) (never null)
     * @param body
     *            what to do with the shell (never null)
     * @return the body's outcome, or the not-run outcome when the environment gave no shell (never null)
     */
    static ShellHookOutcome withShell(ExecutionEnvironment environment, String command, String what,
            Function<VirtualShell, ShellHookOutcome> body) {
        Objects.requireNonNull(environment, "environment cannot be null");
        Objects.requireNonNull(body, "body cannot be null");
        final VirtualShell shell;
        try {
            shell = environment.shell();
        } catch (ExecutionEnvironmentUnavailableException e) {
            // The message is passed on: it is what a tool call in this environment already returns to the model.
            log.warn("{} shell action not run: the execution environment is unavailable (command={}): {}", what,
                    command, e.getMessage());
            return ShellHookOutcome.notRun(ShellHookOutcome.Unrun.ENVIRONMENT_UNAVAILABLE, e.getMessage());
        } catch (RuntimeException e) {
            log.warn("{} shell action not run: the execution environment gave no shell (command={}): {}", what, command,
                    e.getMessage(), e);
            // The type only: the message is the provider's, and the detail becomes a deny reason the model reads.
            return ShellHookOutcome.notRun(ShellHookOutcome.Unrun.ENVIRONMENT_UNAVAILABLE,
                    "the environment gave no shell (" + ShellActionRunner.failureDetail(e) + ")");
        }
        return body.apply(shell);
    }

    /**
     * Whether the shell declares a feature. A {@code supports} that throws &mdash; a shell compiled against an older
     * {@link ShellFeature} with an exhaustive {@code switch} &mdash; is read as {@code false}, with a WARN.
     *
     * @param shell
     *            the shell to ask (never null)
     * @param feature
     *            the feature (never null)
     * @return {@code true} only when the shell answered that it supports the feature
     */
    static boolean declares(VirtualShell shell, ShellFeature feature) {
        try {
            return shell.supports(feature);
        } catch (RuntimeException | LinkageError e) {
            log.warn("The environment's shell ({}) could not say whether it supports {}: {}",
                    shell.getClass().getName(), feature, e.toString());
            return false;
        }
    }
}
