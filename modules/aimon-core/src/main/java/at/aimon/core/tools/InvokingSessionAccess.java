package at.aimon.core.tools;

import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.tool.ToolContext;

/**
 * Static accessors that tools and spawning code use to read the invoking session out of a {@link ToolContext}
 * without repeating the key lookup + guard boilerplate.
 *
 * <p>
 * Two questions look alike and are not the same one, which is why they get two methods:
 *
 * <ul>
 * <li>{@link #invokerOf(ToolContext)} &mdash; <b>readers</b> asking "which session am I acting for?". A plain
 * read of {@link ToolContextKeys#INVOKING_SESSION_ID}, empty on the main-agent path because the main agent is
 * the invoker rather than an invokee.
 * <li>{@link #idToPropagate(ToolContext)} &mdash; <b>spawners</b> asking "which id should the run I am about to start
 * inherit?". The id the spawner inherited when it was itself spawned, its own session when the spawner
 * <em>is</em> a session's turn, and nothing when it is neither.
 * </ul>
 *
 * <p>
 * The order in {@link #idToPropagate(ToolContext)} is what makes nesting work. A fork's own identity was never
 * granted anything &mdash; it is an {@link ToolContextKeys#EXECUTION_ID}, minted per fork and never shown to the user
 * &mdash; so an intermediate fork must hand down the id it inherited, never its own. Getting that backwards yields a
 * reach that works exactly one level deep and then silently stops, which is why the rule lives in one place instead of
 * at each spawn site.
 */
public final class InvokingSessionAccess {

    private InvokingSessionAccess() {
        throw new AssertionError("This class should not be instantiated");
    }

    /**
     * Returns the session whose turn spawned this run, if any. Empty on the main-agent path and for runs nobody
     * asked for (scheduled tasks, background workflows).
     *
     * @param context
     *            the tool context (must not be null)
     * @return the invoking session id, or {@link Optional#empty()} when the run has no invoker
     * @throws NullPointerException
     *             if {@code context} is null
     */
    public static Optional<SessionId> invokerOf(ToolContext context) {
        Objects.requireNonNull(context, "context must not be null");
        return context.get(ToolContextKeys.INVOKING_SESSION_ID);
    }

    /**
     * Returns the id a run spawned from this context should inherit as its invoking session &mdash; the session on
     * whose behalf the spawner is itself running.
     *
     * <p>
     * Two cases, in order. A spawner that was itself spawned relays the id it inherited. A spawner that <em>is</em> a
     * session's turn hands down its own session id: it is the origin of the reach rather than a relay of someone
     * else's, so reading {@link ToolContextKeys#SESSION_ID} here is not the two senses of a {@link SessionId} being
     * confused for each other. A run that is neither &mdash; a scheduled routine, a fork nobody asked for &mdash;
     * hands down nothing.
     *
     * <p>
     * That second read is guarded on {@link ToolContextKeys#EXECUTION_ID} being <em>absent</em>, and the guard is the
     * point: both id keys carry the same type, so while the fork paths still minted session ids of their own this
     * read could promote a fabricated id into an inherited one and give a nested fork a reach the user never granted.
     * A run that publishes an execution id has stated it has no session to offer, so the guard makes that promotion
     * structurally impossible instead of merely unreachable.
     *
     * @param context
     *            the tool context of the code doing the spawning (must not be null)
     * @return the id to hand to the new run, or {@link Optional#empty()} when the spawner has no session of any
     *         kind
     * @throws NullPointerException
     *             if {@code context} is null
     */
    public static Optional<SessionId> idToPropagate(ToolContext context) {
        Objects.requireNonNull(context, "context must not be null");
        final Optional<SessionId> inherited = context.get(ToolContextKeys.INVOKING_SESSION_ID);
        if (inherited.isPresent() || context.containsKey(ToolContextKeys.EXECUTION_ID)) {
            // Either the spawner relays what it inherited, or it is a session-less run with nothing to relay.
            return inherited;
        }
        return context.get(ToolContextKeys.SESSION_ID);
    }
}
