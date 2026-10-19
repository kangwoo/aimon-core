package at.aimon.core.skill.hook.declarative;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.hook.execution.HookContext;
import at.aimon.core.shell.ExecutionOptions;
import at.aimon.core.shell.RunDirectory;
import at.aimon.core.shell.ShellFeature;
import at.aimon.core.shell.VirtualShell;
import at.aimon.core.skill.hook.action.HookScriptDirectory;
import at.aimon.core.skill.hook.action.ShellAction;

/**
 * {@link ShellActionExecutor} for operator-authored {@code hooks.json} commands placed in the firing execution's
 * environment (aimon-core #273, EE-48): runs the command in that environment's shell as an
 * {@linkplain ExecutionOptions#isIsolated() isolated} exec.
 *
 * <p>
 * <b>Isolated, because the command is policy.</b> A skill's hook is the skill's own code and may start from the
 * model's shell state ({@link DefaultShellActionExecutor}). A {@code hooks.json} command is the operator's guard over
 * the model, so nothing the model did to its shell &mdash; an {@code export} of {@code PATH} or {@code BASH_ENV}, a
 * {@code cd} &mdash; may steer it. It starts at the environment's root with the environment's base variables plus the
 * hook's own {@code AIMON_*} ones; core adds no {@code PATH}. A shell that does not declare
 * {@link ShellFeature#ISOLATED_EXECUTION} cannot promise that, and the command is <b>not run</b>
 * ({@link ShellHookOutcome.Unrun#ISOLATION_UNSUPPORTED}): a guard event blocks unless the handler declared
 * {@code failOpen}, an observer event only logs. A {@code supports} that throws &mdash; a shell compiled against an
 * older {@link ShellFeature} with an exhaustive {@code switch} &mdash; is read the same way.
 *
 * <p>
 * <b>On the base environment.</b> A workflow branch that declares its lineage through
 * {@link ExecutionEnvironment#isolatedFrom()} is a view of the environment it was isolated from. The command runs on
 * the shell at the end of that chain, at its root &mdash; under the local provider the workspace root, for a root
 * execution and a branch alike &mdash; and that same shell places {@code AIMON_HOOK_DIR}, so the path and the shell
 * always agree. An environment with no lineage is used as it is.
 *
 * <p>
 * <b>The hook's files travel with the command (EE-109).</b> A handler that names {@code AIMON_HOOK_DIR} gets the
 * layer's {@code hooks/} directory as a {@linkplain ExecutionOptions#getRunDirectory() run directory}: core reads and
 * hashes the bytes at the firing, and the shell places them in a fresh directory for that one run. Nothing is staged
 * into the workspace, where the agent's shell could rewrite a copy between a check and the run. A shell that does not
 * declare {@link ShellFeature#RUN_DIRECTORY} cannot do that, and such a handler is <b>not run</b>
 * ({@link ShellHookOutcome.Unrun#RUN_DIRECTORY_UNSUPPORTED}), on the rule {@code ISOLATION_UNSUPPORTED} follows. A
 * shell that found the files changed when it placed them reports
 * {@link ShellHookOutcome.Unrun#HOOK_FILES_TAMPERED}, which blocks a guard whatever {@code failOpen} says.
 *
 * <p>
 * When the context carries no environment the command is not run ({@link ShellHookOutcome.Unrun#NO_ENVIRONMENT});
 * there is no host fallback. A firing that has no execution behind it at all is routed to the host before it gets
 * here, by {@link ExecutionOrHostShellActionExecutor}.
 *
 * <p>
 * Stateless and thread-safe.
 */
public final class IsolatedShellActionExecutor implements ShellActionExecutor {

    private static final Logger log = LoggerFactory.getLogger(IsolatedShellActionExecutor.class);

    /** No real branch nests deeper than a handful; anything longer is a provider defect, not lineage. */
    private static final int MAX_LINEAGE_DEPTH = 32;

    /** Creates an executor that runs each action isolated in the firing context's execution environment. */
    public IsolatedShellActionExecutor() {
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
                    "Hook shell action not run: no execution environment in hook context (hook={}, event={},"
                            + " command={})",
                    environmentOverrides.get(SkillHookEnv.AIMON_SKILL_NAME),
                    environmentOverrides.get(SkillHookEnv.AIMON_HOOK_EVENT), action.getCommand());
            return ShellHookOutcome.notRun(ShellHookOutcome.Unrun.NO_ENVIRONMENT,
                    "the hook context carries no execution environment");
        }
        final ExecutionEnvironment base = baseOf(executionEnvironment.get());
        return EnvironmentShells.withShell(base, action.getCommand(), "Hook", shell -> {
            if (!supportsIsolation(shell, action)) {
                return ShellHookOutcome.notRun(ShellHookOutcome.Unrun.ISOLATION_UNSUPPORTED,
                        "the shell does not declare ISOLATED_EXECUTION");
            }
            final Map<String, String> env = new LinkedHashMap<>(environmentOverrides);
            RunDirectory runDirectory = null;
            final Optional<HookScriptDirectory> directory = action.getScriptDirectory();
            if (directory.isPresent()) {
                if (!EnvironmentShells.declares(shell, ShellFeature.RUN_DIRECTORY)) {
                    log.warn("Hook shell action not run: the environment's shell ({}) does not declare RUN_DIRECTORY,"
                            + " so the files under AIMON_HOOK_DIR cannot be handed to it for the run (command={})."
                            + " Upgrade the environment provider, or declare \"shell\": \"host\" on the handler",
                            shell.getClass().getName(), action.getCommand());
                    return ShellHookOutcome.notRun(ShellHookOutcome.Unrun.RUN_DIRECTORY_UNSUPPORTED,
                            "the shell does not declare RUN_DIRECTORY");
                }
                final ConfigHookDirectory.Prepared prepared = ConfigHookDirectory.runDirectory(env, directory.get());
                if (prepared.refusal().isPresent()) {
                    return prepared.refusal().get();
                }
                runDirectory = prepared.runDirectory();
            }
            return ShellActionRunner.run(shell, action, env, stdinPayload, context.getExecutionCancellation(), true,
                    runDirectory);
        });
    }

    /**
     * The environment at the end of the {@link ExecutionEnvironment#isolatedFrom()} chain: the one a branch is a view
     * of. A provider that answered with a cycle would loop; the chain is bounded to stop that.
     */
    static ExecutionEnvironment baseOf(ExecutionEnvironment environment) {
        ExecutionEnvironment current = environment;
        for (int depth = 0; depth < MAX_LINEAGE_DEPTH; depth++) {
            final Optional<ExecutionEnvironment> parent;
            try {
                parent = current.isolatedFrom();
            } catch (RuntimeException e) {
                // A lineage that cannot be read is no lineage: the environment is used as it is.
                log.warn("Hook shell action: could not read the lineage of {} ({}); using it as it is",
                        current.getClass().getName(), ShellActionRunner.failureDetail(e));
                return current;
            }
            if (parent.isEmpty() || parent.get() == current) {
                return current;
            }
            current = parent.get();
        }
        log.warn("Hook shell action: the isolatedFrom() chain is longer than {}; using its last environment",
                MAX_LINEAGE_DEPTH);
        return current;
    }

    private static boolean supportsIsolation(VirtualShell shell, ShellAction action) {
        final String shellClass = shell.getClass().getName();
        try {
            if (shell.supports(ShellFeature.ISOLATED_EXECUTION)) {
                return true;
            }
            log.warn("Hook shell action not run: the environment's shell ({}) does not declare ISOLATED_EXECUTION, so"
                    + " the command would start from the agent's shell state (command={}). Upgrade the environment"
                    + " provider, or declare \"shell\": \"host\" on the handler", shellClass, action.getCommand());
            return false;
        } catch (RuntimeException | LinkageError e) {
            log.warn("Hook shell action not run: the environment's shell ({}) could not say whether it supports"
                    + " ISOLATED_EXECUTION (command={}): {}", shellClass, action.getCommand(), e.toString());
            return false;
        }
    }
}
