package at.aimon.core.hook.rewake;

import java.util.List;
import java.util.Map;

/**
 * Application-scoped service that schedules deferred re-fires of hooks.
 *
 * <p>
 * The service is the single entry point hook-execution callers use to register a {@link RewakeEnvelope} for later
 * dispatch. Each envelope carries one {@link RewakeTrigger} — exactly one of:
 *
 * <ul>
 * <li>{@link RewakeTriggerDelay} — fire once at {@code now + delay}.
 * <li>{@link RewakeTriggerCron} — recurring fires until the spec-level timeout elapses.
 * <li>{@link RewakeTriggerEvent} — fire when a matching {@code (eventType, eventKey)} pair is delivered to
 * {@link #resolve(String, String, Map)}.
 * </ul>
 *
 * <p>
 * Implementations decide how the scheduling is realised — the in-memory default uses a
 * {@link java.util.concurrent.ScheduledExecutorService}; production deployments use the Quartz-backed implementation
 * (see {@code aimon-scheduling-quartz}).
 *
 * <p>
 * The framework wires a {@link RewakeFireListener} into the service at bootstrap. When a scheduled envelope fires the
 * service hands it to the listener, which is responsible for rehydrating the originating
 * {@link at.aimon.core.agent.AgentRuntime AgentRuntime} and re-dispatching the hook.
 *
 * <p>
 * Implementations must be thread-safe. The service is application-scoped — its lifetime spans the entire JVM, not a
 * single session or {@link at.aimon.core.agent.AgentRuntime AgentRuntime}. See
 * {@code .claude/rules/scheduling.md}.
 */
public interface RewakeService {

    /**
     * No-op service used as a safe default when async-rewake wiring has not been bootstrapped yet. Calls to
     * {@link #schedule(RewakeEnvelope)} log a warning and drop the envelope; the other methods return empty results.
     *
     * <p>
     * Production deployments must replace this with a real {@link RewakeService} (in-memory
     * {@code DefaultRewakeService} for stand-alone, or the Quartz-backed impl for clustered setups). The constant
     * exists only so existing call sites keep compiling while the wiring (CLI bootstrap, agent-setup factories) is
     * incrementally upgraded.
     */
    RewakeService NOOP = new NoopRewakeService();

    /**
     * Registers an envelope for later fire.
     *
     * <p>
     * Idempotent on {@link RewakeEnvelope#getEnvelopeId() envelopeId}: scheduling the same id twice replaces the
     * previous registration (the prior fire is cancelled before the new one is scheduled).
     *
     * @param envelope
     *            the envelope to schedule (must not be null)
     * @throws NullPointerException
     *             if {@code envelope} is null
     * @throws UnsupportedOperationException
     *             if the envelope's trigger shape is not supported by this implementation
     */
    void schedule(RewakeEnvelope envelope);

    /**
     * Cancels a previously-scheduled envelope. No-op if {@code envelopeId} is unknown.
     *
     * @param envelopeId
     *            the id to cancel (must not be null)
     * @return {@code true} if an envelope was cancelled, {@code false} if the id was unknown
     * @throws NullPointerException
     *             if {@code envelopeId} is null
     */
    boolean cancel(String envelopeId);

    /**
     * Cancels every envelope whose {@link RewakeEnvelope#getOriginatingHookId() originatingHookId} matches the given
     * id.
     *
     * <p>
     * Used by the config hot-reload path: when the originating hook is removed from the registry, every pending fire
     * for that hook is invalidated. Best-effort — race-condition fires that have already been handed to the listener
     * are not undone.
     *
     * @param originatingHookId
     *            the hook id to invalidate (must not be null or blank)
     * @return the number of envelopes cancelled (always &gt;= 0)
     */
    int cancelByOriginatingHookId(String originatingHookId);

    /**
     * Notifies the service of an external event. Every envelope whose trigger is a {@link RewakeTriggerEvent}
     * matching the {@code (eventType, eventKey)} pair fires immediately; others are unaffected.
     *
     * <p>
     * The {@code payload} is merged into the envelope's existing payload at fire time (event payload wins on
     * conflicting keys) so the listener can observe data from the trigger source. Pass an empty map when no payload
     * is available.
     *
     * @param eventType
     *            event-type discriminator (must not be null or blank)
     * @param eventKey
     *            opaque tenant-defined key (must not be null or blank)
     * @param payload
     *            additional payload entries (must not be null; values must not be null)
     * @return the number of envelopes fired (always &gt;= 0)
     */
    int resolve(String eventType, String eventKey, Map<String, String> payload);

    /**
     * Snapshots the currently-pending envelopes. Useful for debugging and CLI {@code /status} surfaces.
     *
     * @return immutable list of pending envelopes (never null; possibly empty; ordering is implementation-defined)
     */
    List<RewakeEnvelope> listPending();
}
