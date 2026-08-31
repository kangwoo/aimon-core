package at.aimon.core.agent.interrupt;

/**
 * Callback a tool registers with {@link TerminatorRegistrar} so the {@link InterruptCoordinator} can abort the tool's
 * in-flight work out-of-band.
 *
 * <p>
 * Used by tools whose {@link InterruptBehavior} is {@link InterruptBehavior#THREAD_INTERRUPT} or
 * {@link InterruptBehavior#EXTERNALLY_TERMINATED}. A canonical implementation cancels an internal
 * {@link java.util.concurrent.Future} or destroys a spawned {@link Process}.
 *
 * <h2>Contract</h2>
 * <ul>
 * <li>Must be idempotent — the coordinator invokes each terminator once on the first interrupt, and implementations
 * must tolerate additional invocations (for example when the tool itself also calls cancel).
 * <li>Must not throw. Implementations are responsible for logging and swallowing their own errors; exceptions
 * propagating out would short-circuit terminator iteration in the coordinator.
 * <li>Must return promptly (no blocking calls). The coordinator may invoke it from a signalling thread that must not
 * be held up.
 * </ul>
 */
@FunctionalInterface
public interface Terminator {

    /**
     * Aborts the associated in-flight work. Invoked at most once per registration by the coordinator; implementations
     * must remain safe if called more than once.
     */
    void terminate();
}
