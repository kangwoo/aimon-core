package at.aimon.core.skill.hook.declarative;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.shell.ExecutionOptions;
import at.aimon.core.shell.ShellCommandResult;
import at.aimon.core.shell.VirtualShell;
import at.aimon.core.shell.exception.ShellExecutionException;
import at.aimon.core.shell.exception.ShellTimeoutException;
import at.aimon.core.skill.hook.action.ShellAction;

/**
 * Default {@link ShellActionExecutor} (AIMON extension, SK-13) that delegates command execution to a
 * {@link VirtualShell} (typically a {@code LocalShell}).
 *
 * <p>
 * Implements the fail-soft contract of the interface: timeouts and unexpected exceptions are logged at WARN level and
 * swallowed so that the calling hook can stay non-blocking. Such a run reports {@link ShellHookOutcome#notObserved()}
 * — no exit status was produced, so nothing can be inferred from it.
 *
 * <p>
 * When the command does run to completion its exit code is reported back through {@link ShellHookOutcome}. Only
 * {@code preTool} hooks act on it (exit {@value ShellHookOutcome#DENY_EXIT_CODE} vetoes the dispatch); every other
 * event stays fire-and-forget regardless of what the command returned.
 *
 * <p>
 * Thread-safe as long as the supplied {@link VirtualShell} is thread-safe (the in-tree {@code LocalShell} is).
 */
public final class DefaultShellActionExecutor implements ShellActionExecutor {

    private static final Logger log = LoggerFactory.getLogger(DefaultShellActionExecutor.class);

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
    public DefaultShellActionExecutor(VirtualShell shell) {
        this.shell = Objects.requireNonNull(shell, "Shell cannot be null");
    }

    @Override
    public boolean isShellSupported() {
        return true;
    }

    @Override
    public void run(ShellAction action, Map<String, String> environmentOverrides) {
        run(action, environmentOverrides, null);
    }

    @Override
    public ShellHookOutcome run(ShellAction action, Map<String, String> environmentOverrides, String stdinPayload) {
        Objects.requireNonNull(action, "Action cannot be null");
        Objects.requireNonNull(environmentOverrides, "Environment overrides cannot be null");

        final ExecutionOptions options = ExecutionOptions.builder().timeout(action.getTimeout())
                .environment(new HashMap<>(environmentOverrides)).stdin(stdinPayload).build();

        try {
            final ShellCommandResult result = shell.execute(action::getCommand, options);
            if (result.isFailure()) {
                log.warn("Skill hook shell action exited with code {} (command={}, stderr={})", result.exitCode(),
                        action.getCommand(), summarise(result.stderr()));
            } else {
                log.debug("Skill hook shell action ok (command={}, duration={}ms)", action.getCommand(),
                        result.duration().toMillis());
            }
            return ShellHookOutcome.of(result.exitCode(), result.stdout(), result.stderr());
        } catch (ShellTimeoutException e) {
            log.warn("Skill hook shell action timed out after {} (command={})", action.getTimeout(),
                    action.getCommand());
        } catch (ShellExecutionException e) {
            log.warn("Skill hook shell action failed (command={}): {}", action.getCommand(), e.getMessage());
        } catch (RuntimeException e) {
            log.warn("Skill hook shell action threw unexpected error (command={}): {}", action.getCommand(),
                    e.getMessage(), e);
        }
        // A command that never produced an exit status cannot be read as a veto — fail soft and let the tool run.
        return ShellHookOutcome.notObserved();
    }

    private static String summarise(String text) {
        if (text == null || text.isEmpty()) {
            return "(empty)";
        }
        final String trimmed = text.strip();
        if (trimmed.length() <= 200) {
            return trimmed;
        }
        return trimmed.substring(0, 200) + "...";
    }
}
