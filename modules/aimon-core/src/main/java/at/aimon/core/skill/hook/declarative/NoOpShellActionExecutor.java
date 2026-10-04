package at.aimon.core.skill.hook.declarative;

import java.util.Map;
import java.util.Objects;

import at.aimon.core.hook.execution.HookContext;
import at.aimon.core.skill.hook.action.ShellAction;

/**
 * No-op {@link ShellActionExecutor} (AIMON extension, SK-13) used as the default for parser wirings that have not
 * opted in to shell hooks.
 *
 * <p>
 * {@link #isShellSupported()} returns {@code false}, which causes {@code SkillHookSetParser} to reject any
 * frontmatter that declares a {@code type: shell} action with a clear error. {@link #run} is therefore unreachable
 * along the normal parse path — {@code HookRegistryApplier} likewise skips {@code command} handlers for such an
 * executor. If it is reached anyway (e.g. because a hook was constructed manually) it runs nothing and reports
 * {@link ShellHookOutcome.Unrun#SHELL_UNSUPPORTED}, which a guard event reads as a block: a guard wired to an executor
 * that cannot run it has not decided anything.
 */
public final class NoOpShellActionExecutor implements ShellActionExecutor {

    /** Singleton instance — the executor is stateless. */
    public static final NoOpShellActionExecutor INSTANCE = new NoOpShellActionExecutor();

    private NoOpShellActionExecutor() {
    }

    @Override
    public boolean isShellSupported() {
        return false;
    }

    @Override
    public boolean requiresExecutionEnvironment() {
        return false;
    }

    @Override
    public ShellHookOutcome run(ShellAction action, HookContext context, Map<String, String> environmentOverrides,
            String stdinPayload) {
        Objects.requireNonNull(action, "Action cannot be null");
        Objects.requireNonNull(context, "Context cannot be null");
        Objects.requireNonNull(environmentOverrides, "Environment overrides cannot be null");
        // Runs nothing. Shell actions should have been rejected at parse time.
        return ShellHookOutcome.notRun(ShellHookOutcome.Unrun.SHELL_UNSUPPORTED,
                "no shell executor is wired for declarative hooks");
    }
}
