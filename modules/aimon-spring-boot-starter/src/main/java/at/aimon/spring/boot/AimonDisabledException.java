package at.aimon.spring.boot;

import at.aimon.core.base.exception.AimonException;

/**
 * Thrown when the application calls into AIMON while {@code aimon.enabled=false}.
 *
 * <p>
 * The kill switch exists so a profile can turn the agent off without the application being rebuilt — a test
 * profile, a staging environment that must not reach an LLM. Backing every slice off entirely would break that
 * use: a host bean injecting {@link AimonSessions} would fail to start, so the switch meant to disable the agent
 * would instead disable the application. So the disabled branch still publishes an {@code AimonSessions}, and
 * this exception is what it answers with — naming the property that produced the state, because the alternative
 * (a silent no-op returning an empty result) reads exactly like a working agent that had nothing to say.
 */
public class AimonDisabledException extends AimonException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception with the given message.
     *
     * @param message
     *            the detail message
     */
    public AimonDisabledException(String message) {
        super(message);
    }
}
