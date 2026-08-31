package at.aimon.core.hook.rewake;

/**
 * Callback invoked by a {@link RewakeService} when a previously-scheduled envelope fires.
 *
 * <p>
 * The listener is the bridge between scheduling (delay / cron / external event) and the actual hook re-dispatch logic.
 * The contract is intentionally minimal — the listener receives the envelope and is responsible for
 * resolving the originating {@link at.aimon.core.agent.AgentRuntime AgentRuntime}, rebuilding the
 * appropriate hook context, and re-invoking the originating hook.
 * {@code DefaultRewakeFireListener} performs that orchestration.
 *
 * <p>
 * Implementations must be thread-safe — multiple fires may be delivered concurrently.
 *
 * <p>
 * The default {@link #NOOP} simply drops the envelope after logging at debug level. It exists so the service can be
 * constructed and exercised in tests / bootstraps that do not yet wire a real listener.
 */
@FunctionalInterface
public interface RewakeFireListener {

    /**
     * No-op listener used as a safe default when no real handler is wired.
     */
    RewakeFireListener NOOP = envelope -> {
    };

    /**
     * Invoked when a scheduled envelope fires.
     *
     * <p>
     * The listener owns whatever the fire means — typically rehydrating the agent context and re-dispatching the hook.
     * Exceptions thrown by the listener must not propagate out of the service: the service is expected to catch and
     * log, then continue scheduling the next fire (for cron triggers).
     *
     * @param envelope
     *            the fired envelope (never null)
     */
    void onFire(RewakeEnvelope envelope);
}
