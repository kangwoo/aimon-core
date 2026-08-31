package at.aimon.bootstrap.exception;

import at.aimon.core.base.exception.AimonException;

/**
 * Thrown when a runtime id names an agent the stack was never told about.
 *
 * <p>
 * The two axes of a runtime id are answered differently, and this exception is what keeps the difference
 * enforceable. A <b>tenant</b> that has not been seen before is normal — there is no list of tenants, so the first
 * request for one creates it. An <b>agent</b> that has not been seen before is not: agents are enumerated by
 * configuration and stood up at startup precisely so that a misspelled or undeployed one fails there rather than
 * on a user's first message. Creating one on demand here would give that guarantee away silently.
 *
 * <p>
 * So this is a caller error, not a capacity problem: unlike
 * {@link AgentRuntimeExhaustedException}, retrying the same call will never succeed.
 */
public class UnknownAgentRuntimeException extends AimonException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception with the given message.
     *
     * @param message
     *            the detail message
     */
    public UnknownAgentRuntimeException(String message) {
        super(message);
    }
}
