package at.aimon.core.memory;

/**
 * The executor's write seam into memory — the counterpart of {@link MemoryContextProvider}.
 *
 * <p>
 * The provider is consulted while a prompt is assembled; this is fed when the execution ends. Symmetry is the point:
 * without a seam here, AIMON's memory is written once per process (the CLI's shutdown hook) or never at all (the
 * starter), and a backend built around a message stream reads an empty memory forever.
 *
 * <h2>Fire-and-forget</h2>
 *
 * <p>
 * An implementation must not let an ingest failure fail the execution, and must not block it for long. The same
 * judgement the tool contract makes: memory is an enrichment, and a memory backend being down is not a reason for the
 * agent's answer to be lost.
 *
 * <p>
 * Implementations must be thread-safe: one instance is agent-scoped and is called concurrently by every session.
 */
@FunctionalInterface
public interface ExecutionMemorySink {

    /**
     * Offers what an execution added.
     *
     * @param update
     *            the execution's identity and its new messages (must not be null)
     */
    void afterExecution(ExecutionMemoryUpdate update);
}
