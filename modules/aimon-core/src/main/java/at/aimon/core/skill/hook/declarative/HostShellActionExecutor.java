package at.aimon.core.skill.hook.declarative;

import java.util.Map;
import java.util.Objects;

import at.aimon.core.hook.execution.HookContext;
import at.aimon.core.shell.VirtualShell;
import at.aimon.core.skill.hook.action.ShellAction;

/**
 * {@link ShellActionExecutor} that runs every action in one fixed {@link VirtualShell} (typically a
 * {@code LocalShell}), whatever the firing context says.
 *
 * <p>
 * This is for operator-authored {@code hooks.json} only. That file is the one place that can declare events firing
 * outside any execution ({@code onSessionStart}, {@code onSessionEnd}, {@code onConfigReload}), which have no
 * execution environment to run in, so its shell actions stay on a shell the assembly opens for them.
 *
 * <p>
 * <strong>Do not hand this to a skill parser.</strong> A skill file is not operator configuration; a skill's hook
 * command must run where that skill's tools run, which is what {@link DefaultShellActionExecutor} does. Wiring this
 * executor into {@code SkillHookSetParser} gives every skill author the host shell.
 *
 * <p>
 * Thread-safe as long as the supplied {@link VirtualShell} is thread-safe (the in-tree {@code LocalShell} is).
 */
public final class HostShellActionExecutor implements ShellActionExecutor {

    private final VirtualShell shell;

    /**
     * Creates a new executor backed by the given shell.
     *
     * @param shell
     *            The shell used to execute commands (must not be null). Typically a long-lived shared instance — the
     *            executor does not own its lifecycle.
     * @throws NullPointerException
     *             if shell is null
     */
    public HostShellActionExecutor(VirtualShell shell) {
        this.shell = Objects.requireNonNull(shell, "Shell cannot be null");
    }

    @Override
    public boolean isShellSupported() {
        return true;
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
        return ShellActionRunner.run(shell, action, environmentOverrides, stdinPayload,
                context.getExecutionCancellation());
    }
}
