package at.aimon.core.tools.monitor;

/**
 * One background source of events, ready to start: a shell command, a socket, a subscription the host holds.
 *
 * <p>
 * A source only produces events. Batching, rate limits, the timeout and delivery to the model belong to the
 * {@link MonitorManager}, which calls {@link #start(MonitorEventSink)} once and stops the source through the returned
 * handle — so every source, built in or registered by the host, lives under the same limits.
 *
 * <p>
 * <b>Every way a source ends goes through the sink.</b> When the source ends on its own it calls
 * {@link MonitorEventSink#complete(String)} or {@link MonitorEventSink#fail(String)}, exactly one of them. A source
 * that dies without saying so looks exactly like one that is still running, and the model waits on it until the
 * timeout.
 */
public interface MonitorSource {

    /**
     * Starts producing events into {@code sink}. Must not block: a source that needs a thread starts its own.
     *
     * <p>
     * The sink may be called before this method returns, and from any thread.
     *
     * @param sink
     *            where the events go (never null)
     * @return the handle the manager stops the source with (never null)
     * @throws RuntimeException
     *             if the source cannot start; the monitor is then not started and the message goes to the model
     */
    MonitorHandle start(MonitorEventSink sink);
}
