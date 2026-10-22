package at.aimon.core.tools.monitor;

import at.aimon.core.tools.monitor.exception.MonitorRequestException;

/**
 * Builds {@link MonitorSource}s of one kind, named by {@link #kind()}.
 *
 * <p>
 * The {@link MonitorManager} holds one factory per kind. {@code shell} is registered by default; a host registers its
 * own kinds — an alarm stream, a message topic — through {@link MonitorManager.Builder#source(MonitorSourceFactory)},
 * and the model then starts them by name with parameters, never with a command or a URL. Connection details and
 * credentials stay in the factory, out of the model's reach.
 *
 * <p>
 * Implementations must be thread-safe: the manager may call {@link #create(MonitorRequest)} for several monitors at
 * once.
 */
public interface MonitorSourceFactory {

    /**
     * The kind this factory builds — the {@code source} a model names. Lower case letters, digits and {@code -}.
     *
     * @return the kind (never null or blank)
     */
    String kind();

    /**
     * One line shown to the model in the {@code Monitor} tool's description: what the kind watches and which
     * {@code params} it takes.
     *
     * @return the description (never null)
     */
    String describe();

    /**
     * Builds a source for one monitor. Checks everything that can be checked before anything starts; a refusal goes
     * to the model as the tool's error.
     *
     * @param request
     *            what was asked for (never null)
     * @return the source, not yet started (never null)
     * @throws MonitorRequestException
     *             if the request cannot be served — a missing parameter, an environment that cannot run it
     */
    MonitorSource create(MonitorRequest request);
}
