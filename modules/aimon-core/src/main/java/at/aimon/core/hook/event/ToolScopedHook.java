package at.aimon.core.hook.event;

/**
 * A tool hook ({@link PreToolHook}, {@link PermissionRequestHook}) that can say, from its configuration alone, which
 * tools it may ever act on.
 *
 * <p>
 * A hook that does not implement this is taken to apply to every tool. The answer must be conservative: {@code true}
 * whenever some call of the tool could make the hook fire. The shell engine of {@code ExecuteCode} asks it at offer
 * time ({@code docs/design/tool/programmatic-tool-calling-shell.md} §4): code it runs does what {@code Bash} does
 * without passing through {@code Bash}'s hooks, so it is offered only when no hook narrows {@code Bash}.
 */
public interface ToolScopedHook {

    /**
     * Returns whether this hook may fire for some call of {@code toolName}.
     *
     * @param toolName
     *            the tool's name (must not be null)
     * @return {@code false} only when no call of the tool can make the hook fire
     */
    boolean mayApplyTo(String toolName);
}
