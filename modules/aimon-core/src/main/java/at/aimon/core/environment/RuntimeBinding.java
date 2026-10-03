package at.aimon.core.environment;

/**
 * What an {@link ExecutionEnvironmentProvider} holds for one {@code AgentRuntime}, as a handle the assembly closes when
 * that runtime goes away ({@link ExecutionEnvironmentProvider#bindRuntime}).
 *
 * <p>
 * A handle rather than an "evicted(id)" callback because two runtimes of one id can be alive at once: an invalidated
 * runtime is closed when its last holder lets go, and a request arriving in between builds its successor. A callback
 * keyed by id would let the old runtime's late close release what the new one is using. Each binding releases only
 * its own share.
 */
@FunctionalInterface
public interface RuntimeBinding extends AutoCloseable {

    /** The binding of a provider that keeps nothing per runtime. */
    RuntimeBinding NONE = () -> {
    };

    /**
     * Releases what the provider held for this binding alone. What another binding of the same runtime id still uses
     * stays. A command still running in a shell handed out for this runtime is <b>not</b> stopped — what cannot be
     * released while it runs is released when it ends, or when the provider itself is closed. Idempotent; never
     * throws.
     */
    @Override
    void close();
}
