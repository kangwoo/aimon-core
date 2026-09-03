package at.aimon.core.memory;

import org.junit.jupiter.api.DisplayName;

import at.aimon.core.memory.redaction.DefaultRedactionPolicy;

/**
 * The decorator every assembled deployment actually holds, run through the same contract as the backend underneath.
 *
 * <p>
 * {@code MemoryAssembly} wraps every backend in {@link RedactingPeerMemory} and hands on only the wrapper, so this —
 * not {@link StoreBackedPeerMemory} — is the {@link PeerMemory} a running stack calls. It is also not a pass-through:
 * it re-implements {@link MemorySearcher#search} by rebuilding the query, re-implements
 * {@link ObservationRecorder#observe} by rewriting the draft, and re-exposes three capability signals from the tier
 * beneath it. Each of those is a place a field or a flag can be dropped without any test noticing, because
 * {@code RedactingPeerMemoryTest} asks what the decorator adds rather than whether the contract survives it.
 *
 * <p>
 * Extending the store-backed suite rather than restating it is the point: the assertion is that wrapping changes
 * <em>nothing</em> a caller can observe about the contract, and the only way to assert "nothing" is to run the
 * identical cases. The seed hooks stay the parent's — they write to the stores directly, which is below the decorator
 * and therefore unredacted, so what the read tiers find is what the parent's cases expect.
 */
@DisplayName("RedactingPeerMemory — PeerMemory contract")
class RedactingPeerMemoryContractTest extends StoreBackedPeerMemoryContractTest {

    @Override
    protected PeerMemory newBackend() {
        return new RedactingPeerMemory(super.newBackend(), new DefaultRedactionPolicy());
    }
}
