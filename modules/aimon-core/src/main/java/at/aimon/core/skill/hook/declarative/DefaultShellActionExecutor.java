package at.aimon.core.skill.hook.declarative;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.environment.exception.ExecutionEnvironmentUnavailableException;
import at.aimon.core.hook.execution.HookContext;
import at.aimon.core.shell.VirtualShell;
import at.aimon.core.skill.hook.action.ShellAction;

/**
 * Default {@link ShellActionExecutor} (AIMON extension, SK-13): runs the command in the shell of the firing
 * execution's {@linkplain HookContext#getExecutionEnvironment() execution environment}.
 *
 * <p>
 * The executor holds no shell. It is handed to the skill parser when a skill is <em>parsed</em>, long before any
 * execution exists and shared across all of them, so a shell bound here would be the wrong one — the host's. Taking
 * it from the context at fire time is what makes a skill's hook command run where that execution's tools run, forked
 * skills included (the fork's own environment, not the spawner's).
 *
 * <p>
 * When the context carries no environment the command is <b>not run</b>: there is no host fallback
 * (execution-environment design §15). The skip is logged at WARN and reported as
 * {@link ShellHookOutcome#notRun} with {@link ShellHookOutcome.Unrun#NO_ENVIRONMENT}; an environment that is present
 * but unavailable reports {@link ShellHookOutcome.Unrun#ENVIRONMENT_UNAVAILABLE}. The executor does not decide what
 * that means — on a blocking chain the hook reads it as a block unless it declared {@code failOpen}.
 *
 * <p>
 * Otherwise implements the never-throw contract of the interface: timeouts and unexpected exceptions are logged at
 * WARN level and reported as {@code notRun} too. When the command does run to completion its exit code is reported
 * back through {@link ShellHookOutcome}. Only the blocking chains act on either (exit
 * {@value ShellHookOutcome#DENY_EXIT_CODE} vetoes, no exit status blocks); every other event stays fire-and-forget
 * regardless of what the command returned.
 *
 * <p>
 * Stateless and thread-safe.
 */
public final class DefaultShellActionExecutor implements ShellActionExecutor {

    private static final Logger log = LoggerFactory.getLogger(DefaultShellActionExecutor.class);

    /** Creates an executor that runs each action in the firing context's execution environment. */
    public DefaultShellActionExecutor() {
    }

    @Override
    public boolean isShellSupported() {
        return true;
    }

    @Override
    public boolean requiresExecutionEnvironment() {
        return true;
    }

    @Override
    public ShellHookOutcome run(ShellAction action, HookContext context, Map<String, String> environmentOverrides,
            String stdinPayload) {
        Objects.requireNonNull(action, "Action cannot be null");
        Objects.requireNonNull(context, "Context cannot be null");
        Objects.requireNonNull(environmentOverrides, "Environment overrides cannot be null");

        final Optional<ExecutionEnvironment> executionEnvironment = context.getExecutionEnvironment();
        if (executionEnvironment.isEmpty()) {
            log.warn(
                    "Skill hook shell action not run: no execution environment in hook context (skill={}, event={},"
                            + " command={})",
                    environmentOverrides.get(SkillHookEnv.AIMON_SKILL_NAME),
                    environmentOverrides.get(SkillHookEnv.AIMON_HOOK_EVENT), action.getCommand());
            return ShellHookOutcome.notRun(ShellHookOutcome.Unrun.NO_ENVIRONMENT,
                    "the hook context carries no execution environment");
        }
        final VirtualShell shell;
        try {
            shell = executionEnvironment.get().shell();
        } catch (ExecutionEnvironmentUnavailableException e) {
            log.warn("Skill hook shell action not run: the execution environment is unavailable (command={}): {}",
                    action.getCommand(), e.getMessage());
            return ShellHookOutcome.notRun(ShellHookOutcome.Unrun.ENVIRONMENT_UNAVAILABLE, e.getMessage());
        } catch (RuntimeException e) {
            log.warn("Skill hook shell action not run: the execution environment gave no shell (command={}): {}",
                    action.getCommand(), e.getMessage(), e);
            // The type only: the message is the provider's, and the detail becomes a deny reason the model reads.
            return ShellHookOutcome.notRun(ShellHookOutcome.Unrun.ENVIRONMENT_UNAVAILABLE,
                    "the environment gave no shell (" + ShellActionRunner.failureDetail(e) + ")");
        }
        return ShellActionRunner.run(shell, action, environmentOverrides, stdinPayload,
                context.getExecutionCancellation());
    }
}
