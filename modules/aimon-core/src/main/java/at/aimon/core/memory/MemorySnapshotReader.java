package at.aimon.core.memory;

import java.util.Optional;

/**
 * {@link MemoryCapability#SNAPSHOT} — reads the current snapshot of a peer.
 *
 * <p>
 * Two consumers sit on this tier: the provider that injects a memory part into every execution's system prompt, and
 * the recall tool the model calls on demand. Both want the same thing at the same altitude — "what do we know about
 * this peer, within this budget" — which is why the tier exists rather than each of them reaching for a store.
 *
 * <p>
 * Implementations must be thread-safe: one instance is application-scoped and is called concurrently by every session
 * in the process.
 */
@FunctionalInterface
public interface MemorySnapshotReader {

    /**
     * Reads the snapshot {@code query} asks for.
     *
     * @param query
     *            what to read and how much of it (must not be null)
     * @return the snapshot, or {@link Optional#empty()} when the backend holds nothing for that subject and scope —
     *         which is an answer, not a failure
     * @throws NullPointerException
     *             if {@code query} is null
     */
    Optional<MemorySnapshot> read(MemorySnapshotQuery query);
}
