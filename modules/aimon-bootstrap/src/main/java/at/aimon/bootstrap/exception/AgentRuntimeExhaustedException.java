package at.aimon.bootstrap.exception;

import at.aimon.core.base.exception.AimonException;

/**
 * Thrown when a tenant runtime cannot be created because the cap is reached and nothing is idle.
 *
 * <p>
 * Tenant runtimes are created on first use and there is no list of them to size the cap against, so a cap that is
 * ever reached is reached with every entry in use. Refusing is the better of the two available answers: the
 * alternative is to keep creating, and each runtime holds a file system, a tool registry and possibly an MCP
 * connection, so the process degrades into swapping or an {@code OutOfMemoryError} — at which point every tenant
 * fails, not just the one that arrived last.
 *
 * <p>
 * A caller that maps this to a status code should use one that means "try again", not one that means "wrong
 * request": the same call succeeds as soon as any current holder releases.
 */
public class AgentRuntimeExhaustedException extends AimonException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception with the given message.
     *
     * @param message
     *            the detail message
     */
    public AgentRuntimeExhaustedException(String message) {
        super(message);
    }
}
