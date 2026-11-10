package at.aimon.session.routing.exception;

import java.util.Objects;

import at.aimon.core.agent.session.SessionId;

/**
 * {@link at.aimon.session.routing.SessionRouter#submit submit} was refused because this node is draining and no peer
 * can be relied on to collect the turn — thrown synchronously, before the input was queued or run anywhere, so the
 * caller may submit it again to another node.
 *
 * <p>
 * Only a {@code SINGLE_NODE} router throws it. A {@code DISTRIBUTED} router that is draining delivers the submission to
 * the shared inbox instead and returns a {@code FORWARDED} disposition, because a peer that is still serving traffic
 * will collect it. Design: {@code docs/design/session/routing.md} §8.4.
 *
 * <p>
 * Extends {@link IllegalStateException}, like the refusal a closing router throws, so callers that already catch that
 * keep working.
 */
public class NodeDrainingException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    /** The draining node. */
    private final String nodeId;

    /** The session whose submission was refused; not serialized. */
    private final transient SessionId sessionId;

    /**
     * Creates the exception.
     *
     * @param nodeId
     *            the draining node (must not be null)
     * @param sessionId
     *            the session whose submission was refused (must not be null)
     */
    public NodeDrainingException(String nodeId, SessionId sessionId) {
        super("node " + Objects.requireNonNull(nodeId, "nodeId must not be null")
                + " is draining and refused a new turn" + " for session "
                + Objects.requireNonNull(sessionId, "sessionId must not be null").value()
                + "; submit it to another node");
        this.nodeId = nodeId;
        this.sessionId = sessionId;
    }

    /**
     * @return the draining node
     */
    public String getNodeId() {
        return nodeId;
    }

    /**
     * @return the session whose submission was refused
     */
    public SessionId getSessionId() {
        return sessionId;
    }
}
