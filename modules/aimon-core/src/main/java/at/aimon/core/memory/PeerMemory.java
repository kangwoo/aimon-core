package at.aimon.core.memory;

import java.util.Optional;

import at.aimon.core.base.ApplicationScoped;
import at.aimon.core.memory.dialectic.DialecticEngine;

/**
 * The memory backend one deployment uses, expressed as the tiers it can serve.
 *
 * <p>
 * This is the seam a memory backend is replaced at. It sits at <em>service</em> altitude — five operations that the
 * store-backed default and a remote memory service both have a name for — rather than at storage altitude, where half
 * of {@link ObservationStore} and {@link RepresentationStore} has no remote counterpart at all. Nothing in this
 * interface or in the four tiers it exposes mentions a store; the stores are the default backend's <em>materials</em>
 * (see {@link StoreBackedPeerMemory}), not the extension point.
 *
 * <h2>Capabilities are computed, not declared</h2>
 *
 * <p>
 * There is deliberately <b>no</b> {@code capabilities()} method here. The set of capabilities a backend has is derived
 * from these accessors by {@link MemoryCapabilities#of(PeerMemory)}, so the state "claims CHAT and returns
 * {@link Optional#empty()} from {@link #dialecticEngine()}" has nowhere to be expressed. A {@code default} method
 * would not have held that line — an implementation could override it, and the assembly, which decides tool
 * registration from the capability set alone, would register a tool whose first call finds an empty {@code Optional}.
 *
 * <p>
 * The invariant is true <b>at the tier boundary only</b>. It answers "does this backend do SEARCH", not "does its
 * search return scores" or "does its snapshot carry individual observations" — those losses live inside a tier and are
 * signalled explicitly ({@link MemorySearcher#ranksByScore()}, {@link MemorySnapshot#isObservationsAvailable()},
 * {@link ObservationRecorder#storesConfidence()}). A third case — a tier that is present but throws because it was
 * handed materials it cannot compute with — is a contract violation, not a representable state; see
 * {@link StoreBackedPeerMemory.Builder#observationStore(ObservationStore)}.
 *
 * <h2>Lifecycle</h2>
 *
 * <p>
 * Application-scoped: one instance serves every agent runtime and every session in the process, and it must therefore
 * not be bound to a single workspace — the workspace arrives with each query, on the query's {@link PeerView}.
 *
 * <p>
 * {@link AutoCloseable} is <b>not</b> required. Backends holding native resources (an adapter's HTTP client, a
 * connection pool) implement it themselves and the assembly checks with {@code instanceof} before putting them on
 * teardown. That check must be applied to whatever object actually owns the resource, not to a decorator wrapping it.
 */
public interface PeerMemory extends ApplicationScoped {

    /**
     * Returns the identifier this backend is named by in logs, diagnostics and degradation messages.
     *
     * @return a short stable id such as {@code "default"}; never null or blank
     */
    String backendId();

    /**
     * Returns the tier that reads peer snapshots for prompt injection and recall.
     *
     * @return the reader, or empty when this backend has no {@link MemoryCapability#SNAPSHOT}
     */
    Optional<MemorySnapshotReader> snapshotReader();

    /**
     * Returns the tier that searches a peer's observations.
     *
     * @return the searcher, or empty when this backend has no {@link MemoryCapability#SEARCH}
     */
    Optional<MemorySearcher> searcher();

    /**
     * Returns the tier that answers natural-language questions about a peer.
     *
     * @return the engine, or empty when this backend has no {@link MemoryCapability#CHAT}
     */
    Optional<DialecticEngine> dialecticEngine();

    /**
     * Returns the tier that records a single fact directly.
     *
     * @return the recorder, or empty when this backend has no {@link MemoryCapability#OBSERVE}
     */
    Optional<ObservationRecorder> observationRecorder();

    /**
     * Returns the tier that takes conversation messages in for derivation.
     *
     * @return the ingestor, or empty when this backend has no {@link MemoryCapability#INGEST}
     */
    Optional<MemoryIngestor> ingestor();
}
