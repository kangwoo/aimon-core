package at.aimon.core.memory;

/**
 * One of the five service-tier operations a {@link PeerMemory} backend can offer.
 *
 * <p>
 * The five are the operations every backend AIMON targets has a name for — the local store-backed default, and the
 * remote memory services a {@code PeerMemory} adapter can front. They are deliberately at <em>service</em> altitude
 * rather than storage altitude: a remote server exposes {@code context} / {@code recall} / {@code chat} /
 * {@code messages}, never a store.
 *
 * <p>
 * <b>A capability set is computed, never declared.</b> {@link MemoryCapabilities#of(PeerMemory)} derives it from the
 * tier accessors, and {@link PeerMemory} has no method that returns one — so a backend cannot claim a capability it
 * does not implement. See {@link MemoryCapabilities} for why that computation lives outside the interface.
 */
public enum MemoryCapability {

    /** Reads a peer snapshot for prompt injection and recall. Tier: {@link MemorySnapshotReader}. */
    SNAPSHOT,

    /** Searches a peer's observations by relevance. Tier: {@link MemorySearcher}. */
    SEARCH,

    /**
     * Answers natural-language questions about a peer. Tier: {@link at.aimon.core.memory.dialectic.DialecticEngine}.
     */
    CHAT,

    /** Records a single fact directly. Tier: {@link ObservationRecorder}. */
    OBSERVE,

    /** Feeds conversation messages in so the backend can derive from them. Tier: {@link MemoryIngestor}. */
    INGEST
}
