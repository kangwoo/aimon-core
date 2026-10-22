/**
 * Background monitors — sources whose events reach the model as messages while it keeps working.
 *
 * <h2>Sources produce, the manager decides</h2>
 *
 * <p>
 * A {@link at.aimon.core.tools.monitor.MonitorSource} reports events and how it ended, nothing else. The
 * {@link at.aimon.core.tools.monitor.MonitorManager} applies everything else — batching, the rate limit, the timeout,
 * the caps, delivery — so the built-in {@code shell} source and a source the host registers through a
 * {@link at.aimon.core.tools.monitor.MonitorSourceFactory} live under the same rules. A host source is how a monitor
 * works without a shell: the model names the kind and its parameters, and the connection details stay in the host's
 * code.
 *
 * <h2>Delivery</h2>
 *
 * <p>
 * Events become {@code NEXT} entries of the stack's message queue addressed to the owner's session
 * ({@link at.aimon.core.agent.queue.QueuedInput#getSessionId()}). A running turn reads them at its next tool-using
 * iteration and an idle session at the start of its next turn; nothing wakes an idle session. Each monitor keeps at
 * most one entry waiting in the queue and buffers the rest.
 *
 * <p>
 * The design, and what was left out on purpose, is {@code docs/design/tool/monitor.md}.
 */
package at.aimon.core.tools.monitor;
