package at.aimon.core.config.hook;

import at.aimon.core.base.exception.AimonException;

/**
 * Raised when {@link JacksonHookConfigParser} cannot turn the supplied JSON into a valid
 * {@link HookConfigDocument}, and when {@link HookConfigLoader} finds a {@code hooks.json} it cannot read at all (a
 * non-regular file, an I/O error). Both leave a layer's hooks unknown, so both are the same failure to a caller.
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
