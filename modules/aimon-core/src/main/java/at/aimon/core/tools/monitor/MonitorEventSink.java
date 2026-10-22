package at.aimon.core.tools.monitor;

/**
 * Where a {@link MonitorSource} puts its events. Implemented by the {@link MonitorManager}; thread-safe, and every
 * call after the monitor has ended is ignored.
 */
public interface MonitorEventSink {

    /**
     * Reports one event. An event may span several lines; it is still one event.
     *
     * @param event
     *            the event text (never null)
     */
    void emit(String event);

    /**
     * Reports that the source ended on its own, normally — a command that exited with status 0, a socket the server
     * closed.
     *
     * @param detail
     *            a short phrase the model is shown, such as {@code "exit code 0"} (never null)
     */
    void complete(String detail);

    /**
     * Reports that the source ended because something went wrong.
     *
     * @param reason
     *            what went wrong, shown to the model (never null)
     */
    void fail(String reason);
}
