package at.aimon.core.skill.hook.declarative;

import java.util.Map;
import java.util.Objects;

import at.aimon.core.skill.hook.action.ShellAction;

/**
 * No-op {@link ShellActionExecutor} (AIMON extension, SK-13) used as the default for parser wirings that have not
 * opted in to shell hooks.
 *
 * <p>
 * {@link #isShellSupported()} returns {@code false}, which causes {@code SkillHookSetParser} to reject any
 * frontmatter that declares a {@code type: shell} action with a clear error. {@link #run} is therefore unreachable
 * along the normal parse path; if it is reached anyway (e.g. because a hook was constructed manually) it logs nothing
 * and silently does nothing — consistent with the "fail-soft, never throw" contract of the interface.
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
    public void run(ShellAction action, Map<String, String> environmentOverrides) {
        Objects.requireNonNull(action, "Action cannot be null");
        Objects.requireNonNull(environmentOverrides, "Environment overrides cannot be null");
        // Intentionally no-op. Shell actions should have been rejected at parse time.
    }
}
