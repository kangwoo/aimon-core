package at.aimon.core.skill.hook.declarative;

import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.environment.exception.ExecutionEnvironmentUnavailableException;
import at.aimon.core.shell.ExecutionOptions;
import at.aimon.core.shell.ShellCommandResult;
import at.aimon.core.shell.VirtualShell;
import at.aimon.core.shell.exception.ShellExecutionException;
import at.aimon.core.shell.exception.ShellTimeoutException;
import at.aimon.core.skill.hook.action.ShellAction;

/**
 * The execution ladder shared by the {@link ShellActionExecutor} implementations: assemble the options, run the
 * command in the shell the executor picked, log what happened, and swallow every failure.
 *
 * <p>
 * Kept in one place so the contract — never throw, and report a command that produced no exit status as
 * {@link ShellHookOutcome#notRun} with its cause — cannot drift between the executor that takes its shell from the
 * execution environment and the one that holds a fixed shell.
 */
final class ShellActionRunner {

    private static final Logger log = LoggerFactory.getLogger(ShellActionRunner.class);

    private static final int SUMMARY_LIMIT = 200;

    private ShellActionRunner() {
        throw new AssertionError("This class should not be instantiated");
    }

    /**
     * Runs the action in the given shell. Never throws.
     *
     * @param shell
     *            the shell to run in (never null)
     * @param action
     *            the action to execute (never null)
     * @param environmentOverrides
     *            extra environment variables (never null)
     * @param stdinPayload
     *            JSON document for standard input, or null
     * @return what the command did (never null)
     */
    static ShellHookOutcome run(VirtualShell shell, ShellAction action, Map<String, String> environmentOverrides,
            String stdinPayload) {
        final ExecutionOptions options = ExecutionOptions.builder().timeout(action.getTimeout())
                .environment(new HashMap<>(environmentOverrides)).stdin(stdinPayload).build();

        try {
            final ShellCommandResult result = shell.execute(action::getCommand, options);
            if (result.isFailure()) {
                log.warn("Hook shell action exited with code {} (command={}, stderr={})", result.exitCode(),
                        action.getCommand(), summarise(result.stderr()));
            } else {
                log.debug("Hook shell action ok (command={}, duration={}ms)", action.getCommand(),
                        result.duration().toMillis());
            }
            return ShellHookOutcome.of(result.exitCode(), result.stdout(), result.stderr());
        } catch (ShellTimeoutException e) {
            log.warn("Hook shell action timed out after {} (command={})", action.getTimeout(), action.getCommand());
            return ShellHookOutcome.notRun(ShellHookOutcome.Unrun.TIMEOUT,
                    "no exit status within " + action.getTimeout().toMillis() + "ms");
        } catch (ExecutionEnvironmentUnavailableException e) {
            // The environment is there but cannot be used. Not a reason to reach for another shell: the command is
            // skipped, exactly as a tool call in the same execution would fail.
            log.warn("Hook shell action not run: the execution environment is unavailable (command={}): {}",
                    action.getCommand(), e.getMessage());
            return ShellHookOutcome.notRun(ShellHookOutcome.Unrun.ENVIRONMENT_UNAVAILABLE, e.getMessage());
        } catch (ShellExecutionException e) {
            // The message goes to the log only: a shell's failure message routinely quotes the command, and the
            // detail becomes a deny reason the constrained party reads.
            log.warn("Hook shell action failed (command={}): {}", action.getCommand(), e.getMessage(), e);
            return ShellHookOutcome.notRun(ShellHookOutcome.Unrun.EXECUTION_FAILED, failureDetail(e));
        } catch (RuntimeException e) {
            log.warn("Hook shell action threw unexpected error (command={}): {}", action.getCommand(), e.getMessage(),
                    e);
            return ShellHookOutcome.notRun(ShellHookOutcome.Unrun.EXECUTION_FAILED, failureDetail(e));
        }
    }

    /**
     * The model-facing detail of a failure: the exception's type, never its message, which may carry the command
     * or the shell's internals.
     *
     * @param failure
     *            what was thrown (never null)
     * @return the detail for {@link ShellHookOutcome#notRun} (never null)
     */
    static String failureDetail(Throwable failure) {
        return failure.getClass().getSimpleName();
    }

    private static String summarise(String text) {
        if (text == null || text.isEmpty()) {
            return "(empty)";
        }
        final String trimmed = text.strip();
        if (trimmed.length() <= SUMMARY_LIMIT) {
            return trimmed;
        }
        return trimmed.substring(0, SUMMARY_LIMIT) + "...";
    }
}
