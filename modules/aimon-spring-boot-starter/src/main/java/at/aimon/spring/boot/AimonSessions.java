package at.aimon.spring.boot;

import java.util.concurrent.Flow;

import at.aimon.core.agent.AgentExecutionResult;
import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.agent.session.LiveSessionOptions;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.TurnId;
import at.aimon.core.agent.stream.AgentExecutionEvent;
import at.aimon.session.routing.SessionRouter;
import at.aimon.session.routing.SubmitDisposition;
import at.aimon.session.routing.SubmitRequest;

/**
 * The one bean a Spring Boot application is expected to inject to talk to its agent.
 *
 * <p>
 * Everything below it — {@code AimonStack}, {@code SessionRouter}, {@code LiveSession}, the agent runtime
 * registry — is published too and remains usable, but a host that only wants "send a message, get an answer"
 * should never have to learn that vocabulary. This facade exists so the common case is two lines and the
 * uncommon case is still reachable, rather than the common case being the uncommon one with defaults filled in
 * by hand at every call site.
 *
 * <p>
 * <b>What it fills in.</b> {@link SessionRouter#submit(SubmitRequest)} requires an {@code agentRef} and an
 * {@code initiator}, and silently accepts {@link LiveSessionOptions#defaults()} — whose budget is
 * <em>unlimited</em>. A host calling the router directly with a hand-built request therefore gets an unbounded
 * turn unless it remembers to attach the configured budget itself, which is the failure mode
 * {@code aimon.budget.*} is supposed to prevent. This facade attaches the configured agent, the configured
 * default budget and a system initiator to every request that does not carry its own.
 *
 * <p>
 * <b>The escape hatch is not a hole.</b> {@link #newRequest(SessionId, String)} returns a builder that is
 * already pre-filled with those same defaults, so a caller who needs one extra field (an idempotency key, a
 * priority, a real end-user {@code Principal}) overrides that one field instead of starting from an empty
 * builder and losing the rest. {@link #submitAsync(SubmitRequest)} then takes the fully-formed request
 * verbatim — it is the single primitive every other method here delegates to.
 *
 * <p>
 * Sessions are addressed by {@link SessionId} and are durable in the sense the record store makes them: with
 * the default in-memory store they live as long as the JVM. Submitting twice with the same id continues one
 * conversation; submitting with a fresh id starts a new one.
 */
public interface AimonSessions {

    /**
     * Runs one turn on the configured agent and blocks until it finishes.
     *
     * @param sessionId
     *            the session to continue (or start, if it is new) — must not be null
     * @param input
     *            the user input for this turn (must not be null)
     * @return the completed turn result
     */
    AgentExecutionResult submit(SessionId sessionId, String input);

    /**
     * Runs one turn on a named agent with explicit options and blocks until it finishes.
     *
     * @param sessionId
     *            the session to continue (must not be null)
     * @param agentRef
     *            the agent to route to; null falls back to the configured default agent
     * @param input
     *            the user input for this turn (must not be null)
     * @param options
     *            per-session options; null falls back to the configured default budget
     * @return the completed turn result
     */
    AgentExecutionResult submit(SessionId sessionId, String agentRef, String input, LiveSessionOptions options);

    /**
     * Submits one turn without waiting for it.
     *
     * <p>
     * The returned disposition tells the caller whether the turn is running on this node or was queued for the
     * node that currently holds the session, and carries the future to await either way.
     *
     * @param sessionId
     *            the session to continue (must not be null)
     * @param input
     *            the user input for this turn (must not be null)
     * @return where the turn went and how to await it
     */
    SubmitDisposition submitAsync(SessionId sessionId, String input);

    /**
     * Submits a fully-formed request verbatim. The primitive every other submit method delegates to.
     *
     * @param request
     *            the request (must not be null)
     * @return where the turn went and how to await it
     */
    SubmitDisposition submitAsync(SubmitRequest request);

    /**
     * Returns a request builder pre-filled with the configured defaults — agent, budget and initiator.
     *
     * <p>
     * Use this instead of {@link SubmitRequest#builder()} when one field needs overriding: starting from the
     * bare builder drops the configured budget back to unlimited and forces the caller to restate the agent and
     * the initiator, both of which are mandatory on the underlying request.
     *
     * @param sessionId
     *            the session to continue (must not be null)
     * @param input
     *            the user input for this turn (must not be null)
     * @return a builder that is already valid to {@code build()}
     */
    SubmitRequest.Builder newRequest(SessionId sessionId, String input);

    /**
     * Streams progress events for a session — assistant text, tool calls, budget updates, terminal frames.
     *
     * @param sessionId
     *            the session to observe (must not be null)
     * @return a publisher that may be subscribed to by several observers at once
     */
    Flow.Publisher<AgentExecutionEvent> events(SessionId sessionId);

    /**
     * Stops one specific in-flight turn.
     *
     * <p>
     * Addressed at the turn rather than the session on purpose: by the time a user's cancel reaches the agent
     * their turn may already have finished and the next one started, and an unaddressed stop would kill that
     * one instead. The id comes from {@link SubmitDisposition#getTurnId()}. Mismatch is a silent no-op.
     *
     * @param sessionId
     *            the session (must not be null)
     * @param turnId
     *            the turn to stop (must not be null)
     * @param reason
     *            why it is being stopped (must not be null)
     */
    void interrupt(SessionId sessionId, TurnId turnId, InterruptReason reason);

    /**
     * Drops the node-local handle for a session while keeping its stored history.
     *
     * <p>
     * Submitting the same id again resumes the conversation. This is not a delete — for that, reach through to
     * {@link SessionRouter#deleteSession(SessionId)}, which takes the session lock first.
     *
     * @param sessionId
     *            the session to release (must not be null)
     */
    void release(SessionId sessionId);
}
