package at.aimon.core.config.hook;

import at.aimon.core.base.exception.AimonException;

/**
 * Raised when {@link JacksonHookConfigParser} cannot turn the supplied JSON into a valid
 * {@link HookConfigDocument}.
 */
public class HookConfigParseException extends AimonException {

    private static final long serialVersionUID = 1L;

    /**
     * @param message
     *            human-readable error detail
     */
    public HookConfigParseException(String message) {
        super(message);
    }

    /**
     * @param message
     *            human-readable error detail
     * @param cause
     *            the underlying cause (typically a Jackson exception)
     */
    public HookConfigParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
