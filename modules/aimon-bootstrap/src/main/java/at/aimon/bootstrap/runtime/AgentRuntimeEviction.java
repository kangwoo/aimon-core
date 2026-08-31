package at.aimon.bootstrap.runtime;

/**
 * What an {@link AgentRuntimeResolver} does with a tenant runtime nobody is using.
 *
 * <p>
 * Only tenant runtimes are ever considered. The agents enumerated at startup are the basis of the stack's
 * fail-fast guarantee, so closing one because it went quiet would mean the next request rebuilds it on a path
 * that never ran that check.
 */
public enum AgentRuntimeEviction {

    /**
     * Close a tenant runtime once it has had no holders for the configured idle TTL.
     *
     * <p>
     * The idle clock starts when the last holder releases and is reset by the next acquire, so a runtime in
     * continuous use is never a candidate however old it is. This is the default because the set of tenants is
     * unbounded: without it, a process that serves a thousand tenants over a week holds a thousand file systems
     * and tool registries open, and the only thing that ever releases them is a restart.
     */
    IDLE,

    /**
     * Keep every tenant runtime for the life of the process.
     *
     * <p>
     * Correct when the tenant set is small and known — a handful of business units rather than a customer per
     * row — where re-creating a runtime costs more than holding it. It is a capacity decision, not a correctness
     * one: {@code max-entries} still applies, so an unbounded tenant set configured this way fails with
     * {@link at.aimon.bootstrap.exception.AgentRuntimeExhaustedException} instead of reclaiming.
     */
    NEVER
}
