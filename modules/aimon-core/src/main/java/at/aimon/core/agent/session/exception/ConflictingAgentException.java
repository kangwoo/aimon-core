package at.aimon.core.agent.session.exception;

import java.io.Serial;
import java.util.Objects;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.base.exception.AimonException;

/**
 * Thrown when a request targets a session already bound to a different agent.
 *
 * <p>
 * Surfaces the {@code SessionRouter} sole-binding rule (design §3.6): a {@link SessionId} is bound to
 * exactly one {@code agentRef} for the lifetime of the session. Attempting to submit input under a conflicting
 * {@code agentRef} fails fast with this exception.
 *
 * <p>
 * Carries both the requested and the existing {@code agentRef} so callers can render an actionable error message and,
 * if appropriate, retry against the correct agent.
 */
public class ConflictingAgentException extends AimonException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final SessionId sessionId;
    private final String requestedAgent;
    private final String existingAgent;

    /**
     * Constructs a new exception describing a binding conflict.
     *
     * @param sessionId
     *            the session whose binding was conflicted (must not be null)
     * @param requestedAgent
     *            the {@code agentRef} carried by the rejected request (must not be null)
     * @param existingAgent
     *            the {@code agentRef} already bound to the session (must not be null)
     * @throws NullPointerException
     *             if any argument is null
     */
    public ConflictingAgentException(SessionId sessionId, String requestedAgent, String existingAgent) {
        super(buildMessage(sessionId, requestedAgent, existingAgent));
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
        this.requestedAgent = Objects.requireNonNull(requestedAgent, "requestedAgent must not be null");
        this.existingAgent = Objects.requireNonNull(existingAgent, "existingAgent must not be null");
    }

    private static String buildMessage(SessionId sessionId, String requestedAgent, String existingAgent) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(requestedAgent, "requestedAgent must not be null");
        Objects.requireNonNull(existingAgent, "existingAgent must not be null");
        return "Session " + sessionId.value() + " is already bound to agent '" + existingAgent
                + "' but request targeted '" + requestedAgent + "'";
    }

    /**
     * Returns the session whose binding was conflicted.
     *
     * @return the session id (never null)
     */
    public SessionId getSessionId() {
        return sessionId;
    }

    /**
     * Returns the {@code agentRef} carried by the rejected request.
     *
     * @return the requested agent reference (never null)
     */
    public String getRequestedAgent() {
        return requestedAgent;
    }

    /**
     * Returns the {@code agentRef} already bound to the session.
     *
     * @return the existing agent reference (never null)
     */
    public String getExistingAgent() {
        return existingAgent;
    }
}
