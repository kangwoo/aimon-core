package at.aimon.core.memory;

/**
 * {@link MemoryCapability#INGEST} — takes conversation messages in so the backend can derive knowledge from them.
 *
 * <p>
 * This is the tier without which a memory is read-only forever. Every backend AIMON targets is built around being fed
 * a message stream; a deployment that reads memory and never writes it looks configured and answers nothing, which is
 * why the assembly records the absence of this capability rather than leaving it to be discovered.
 *
 * <p>
 * Implementations must be thread-safe.
 */
@FunctionalInterface
public interface MemoryIngestor {

    /**
     * Offers {@code request}'s messages to the backend.
     *
     * @param request
     *            the messages and who they belong to, already redacted (must not be null)
     * @return what the backend did with them, never null
     * @throws NullPointerException
     *             if {@code request} is null
     */
    MemoryIngestReceipt ingest(MemoryIngestRequest request);
}
