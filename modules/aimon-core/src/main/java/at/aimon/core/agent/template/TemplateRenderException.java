package at.aimon.core.agent.template;

/**
 * Exception thrown when template rendering fails.
 */
public class TemplateRenderException extends RuntimeException {
    /** TemplateRenderException을 생성한다. */
    public TemplateRenderException(String message) {
        super(message);
    }

    /** TemplateRenderException을 생성한다. */
    public TemplateRenderException(String message, Throwable cause) {
        super(message, cause);
    }
}
