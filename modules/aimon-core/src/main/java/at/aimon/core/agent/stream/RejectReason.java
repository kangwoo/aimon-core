package at.aimon.core.agent.stream;

/**
 * Classifies why a queued / inbox-delivered input was rejected after binding evaluation.
 *
 * <p>
 * Carried by {@link RejectedAt} so subscribers can distinguish recoverable conflicts (the input was sent to a
 * session already bound to a different agent) from the rest. Kept intentionally narrow — only
 * {@link #CONFLICTING_AGENT} is defined today; future reasons are added when concrete needs arise.
 */
public enum RejectReason {
    /**
     * The session is already bound to an agent other than the one specified by the input. The web session
     * manager (routing design §3.6) refuses to switch agents mid-session.
     */
    CONFLICTING_AGENT
}
