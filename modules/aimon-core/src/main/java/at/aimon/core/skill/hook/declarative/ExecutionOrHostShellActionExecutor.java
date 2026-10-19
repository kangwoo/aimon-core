package at.aimon.core.skill.hook.declarative;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.environment.StagedResource;
import at.aimon.core.hook.execution.HookContext;
import at.aimon.core.skill.hook.action.ShellAction;

/**
 * Routes a {@code hooks.json} command to one of two executors per firing (aimon-core #273, EE-48): to the execution
 * environment's executor when there is an execution behind the firing, to the host executor when there is none.
 *
 * <p>
 * This is what a {@code command} handler with no {@code "shell"} key on an in-execution event is built with. The rule
 * it implements is the decided principle &mdash; a hook runs in the environment of the execution it is about, and the
 * host is used only when there is no execution &mdash; read from {@link HookContext#hasExecution()}, which the firing
 * site answers. A context that has an execution but carries no environment is not routed to the host: it goes to the
 * environment executor and is not run there ({@code NO_ENVIRONMENT}), because quietly using the host for an execution
 * whose environment did not reach the hook is the fallback the execution-environment design rules out.
 *
 * <p>
 * The router exports nothing itself: the executor it picks gives {@code AIMON_HOOK_DIR} the value that belongs to its
 * shell.
 *
 * <p>
 * {@link #requiresExecutionEnvironment()} is {@code false} because a firing can always be run somewhere, so the
 * applier's declaration-time checks treat it as they treat the host executor.
 *
 * <p>
 * Thread-safe as long as both executors are.
 */
public final class ExecutionOrHostShellActionExecutor implements ShellActionExecutor {

    private final ShellActionExecutor environmentExecutor;
    private final ShellActionExecutor hostExecutor;

    /**
     * Creates a router.
     *
     * @param environmentExecutor
     *            the executor for a firing with an execution behind it (must not be null)
     * @param hostExecutor
     *            the executor for a firing without one (must not be null)
     */
    public ExecutionOrHostShellActionExecutor(ShellActionExecutor environmentExecutor,
            ShellActionExecutor hostExecutor) {
        this.environmentExecutor = Objects.requireNonNull(environmentExecutor, "environmentExecutor cannot be null");
        this.hostExecutor = Objects.requireNonNull(hostExecutor, "hostExecutor cannot be null");
    }

    @Override
    public boolean isShellSupported() {
        return environmentExecutor.isShellSupported() && hostExecutor.isShellSupported();
    }

    @Override
    public boolean requiresExecutionEnvironment() {
        return false;
    }

    @Override
    public ShellHookOutcome run(ShellAction action, HookContext context, Map<String, String> environmentOverrides,
            String stdinPayload) {
        Objects.requireNonNull(context, "Context cannot be null");
        final ShellActionExecutor chosen = context.hasExecution() ? environmentExecutor : hostExecutor;
        return chosen.run(action, context, environmentOverrides, stdinPayload);
    }

    @Override
    public ShellHookOutcome run(ShellAction action, HookContext context, Map<String, String> environmentOverrides,
            String stdinPayload, Optional<StagedResource> skillDirectory) {
        Objects.requireNonNull(context, "Context cannot be null");
        final ShellActionExecutor chosen = context.hasExecution() ? environmentExecutor : hostExecutor;
        return chosen.run(action, context, environmentOverrides, stdinPayload, skillDirectory);
    }

    /** @return the executor for a firing with an execution behind it (never null) */
    public ShellActionExecutor getEnvironmentExecutor() {
        return environmentExecutor;
    }

    /** @return the executor for a firing without one (never null) */
    public ShellActionExecutor getHostExecutor() {
        return hostExecutor;
    }
}
