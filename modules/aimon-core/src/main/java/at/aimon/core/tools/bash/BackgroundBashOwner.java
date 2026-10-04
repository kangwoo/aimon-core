package at.aimon.core.tools.bash;

import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.ExecutionId;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.tools.InvokingSessionAccess;
import at.aimon.core.tools.ToolContextKeys;

/**
 * Who a background command belongs to: the runtime that started it, and within that runtime the session it was
 * started for — or, for an execution that acts for no session, that execution.
 *
 * <p>
 * A task is found only by a caller whose owner {@linkplain #equals(Object) equals} the task's; every other caller is
 * told the id does not exist. {@link #of(ToolContext)} is the one place the owner of a call is worked out, so the call
 * that starts a command and the calls that read or stop it cannot disagree:
 *
 * <ol>
 * <li>the call's own session ({@code SESSION_ID}) — a session's turn;
 * <li>otherwise the session it was spawned for ({@code INVOKING_SESSION_ID}) — a fork. This is what lets a session's
 * turn read and stop what its forks started, and the reverse;
 * <li>otherwise the execution itself ({@code EXECUTION_ID}) — a scheduled routine, a fork nobody's turn asked for.
 * Nothing outlives such an execution to claim its tasks later; they end at the environment's background ceiling;
 * <li>otherwise the runtime alone — an assembly that publishes none of the three.
 * </ol>
 *
 * <p>
 * Immutable; thread-safe.
 */
public final class BackgroundBashOwner {

    private static final BackgroundBashOwner NONE = new BackgroundBashOwner(null, null, null);

    private final AgentRuntimeId runtimeId;
    private final SessionId sessionId;
    private final ExecutionId executionId;

    private BackgroundBashOwner(AgentRuntimeId runtimeId, SessionId sessionId, ExecutionId executionId) {
        this.runtimeId = runtimeId;
        this.sessionId = sessionId;
        // A session and an execution are never both the owner: the session wins, so that a context (or a stored
        // record) carrying both resolves the same way from every side instead of matching nobody.
        this.executionId = sessionId != null ? null : executionId;
    }

    /**
     * Returns the owner with no runtime, session or execution — a caller outside any runtime.
     *
     * @return the unscoped owner (never null)
     */
    public static BackgroundBashOwner none() {
        return NONE;
    }

    /**
     * Works out the owner of the call a tool context belongs to. Never throws for a context that carries an unexpected
     * combination of ids: it is called from inside a tool's {@code execute()}.
     *
     * @param context
     *            the calling tool context (must not be null)
     * @return the owner (never null)
     * @throws NullPointerException
     *             if context is null
     */
    public static BackgroundBashOwner of(ToolContext context) {
        Objects.requireNonNull(context, "Context cannot be null");
        final SessionId session = context.get(ToolContextKeys.SESSION_ID)
                .or(() -> InvokingSessionAccess.invokerOf(context)).orElse(null);
        return new BackgroundBashOwner(context.get(ToolContextKeys.AGENT_RUNTIME_ID).orElse(null), session,
                context.get(ToolContextKeys.EXECUTION_ID).orElse(null));
    }

    /**
     * Assembles an owner from its parts. When both a session and an execution are given the session wins and the
     * execution is dropped; nothing is rejected.
     *
     * @param runtimeId
     *            the runtime, or null for none
     * @param sessionId
     *            the session the command was started for, or null for none
     * @param executionId
     *            the execution that started the command, or null for none; ignored when a session is given
     * @return the owner (never null)
     */
    public static BackgroundBashOwner of(AgentRuntimeId runtimeId, SessionId sessionId, ExecutionId executionId) {
        return new BackgroundBashOwner(runtimeId, sessionId, executionId);
    }

    /**
     * @return the runtime whose execution started the command, or empty for a command started outside any runtime
     */
    public Optional<AgentRuntimeId> getRuntimeId() {
        return Optional.ofNullable(runtimeId);
    }

    /**
     * @return the session the command was started for — the starting turn's own session, or the session a fork was
     *         spawned for; empty when the starting execution acted for no session
     */
    public Optional<SessionId> getSessionId() {
        return Optional.ofNullable(sessionId);
    }

    /**
     * @return the execution that started the command, when it acted for no session; always empty when
     *         {@link #getSessionId()} is present
     */
    public Optional<ExecutionId> getExecutionId() {
        return Optional.ofNullable(executionId);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof BackgroundBashOwner that)) {
            return false;
        }
        return Objects.equals(runtimeId, that.runtimeId) && Objects.equals(sessionId, that.sessionId)
                && Objects.equals(executionId, that.executionId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(runtimeId, sessionId, executionId);
    }

    @Override
    public String toString() {
        return "BackgroundBashOwner{runtime=" + runtimeId + ", session=" + sessionId + ", execution=" + executionId
                + '}';
    }
}
