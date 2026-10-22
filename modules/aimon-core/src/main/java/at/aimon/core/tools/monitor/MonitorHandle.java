package at.aimon.core.tools.monitor;

/**
 * Stops a started {@link MonitorSource}.
 */
@FunctionalInterface
public interface MonitorHandle {

    /**
     * Stops the source and releases what it holds. Idempotent, must not block for long, and must not throw. A source
     * may still call its sink after this; the manager ignores those calls.
     */
    void stop();
}
