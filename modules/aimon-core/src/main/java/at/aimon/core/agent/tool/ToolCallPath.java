package at.aimon.core.agent.tool;

import java.util.Locale;

/**
 * The path a tool call arrived by, carried on {@link ToolCallOrigin}.
 *
 * <p>
 * Orthogonal to {@link at.aimon.core.agent.InvokerType}: the invoker type says <em>who</em> is calling (the main agent
 * or a subagent), the path says <em>by which route</em> the call reached the gates. A hook can therefore write a policy
 * such as "a {@code Bash} that came from code is refused" without caring which agent ran the code.
 *
 * <p>
 * Shell hooks receive the lower-case name of the constant in {@code AIMON_TOOL_PATH} ({@link #wireName()}).
 */
public enum ToolCallPath {

    /** The model asked for the call in an assistant message, on the main or a fork's ReAct loop. */
    DIRECT,

    /** A program the model wrote called the tool (programmatic tool calling). */
    CODE,

    /** The ReAct loop of a skill the user invoked as a slash command asked for the call. */
    SKILL,

    /** A rewake replay re-fired a hook for a call that was recorded earlier. */
    REPLAY;

    /**
     * Returns the spelling exported to shell hooks: the constant's name in lower case.
     *
     * @return {@code "direct"}, {@code "code"}, {@code "skill"} or {@code "replay"} (never null)
     */
    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
