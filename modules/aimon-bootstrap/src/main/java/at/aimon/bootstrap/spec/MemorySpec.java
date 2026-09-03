package at.aimon.bootstrap.spec;

import java.util.Objects;
import java.util.Optional;

import at.aimon.core.base.Principal;
import at.aimon.core.memory.MemoryCapabilities;
import at.aimon.core.memory.MemoryCapability;
import at.aimon.core.memory.MemoryIngestMode;
import at.aimon.core.memory.MemoryInjectionMode;
import at.aimon.core.memory.ObservationStore;
import at.aimon.core.memory.PeerMemory;
import at.aimon.core.memory.RepresentationStore;
import at.aimon.core.memory.Workspace;
import at.aimon.core.memory.redaction.RedactionPolicy;

/**
 * Declares the peer memory the stack reads from — the workspace, the stores, and whose memory a turn gets.
 *
 * <p>
 * This spec carries <b>materials</b>, not objects: a backend (or the stores one is built from) and the peer decision.
 * The context provider, the tool context enricher and the tool provider built from them are assembled by the stack
 * builder, which is where every other slice's assembly happens too.
 *
 * <h2>Two ways to say which memory</h2>
 *
 * <p>
 * {@link Builder#peerMemory(PeerMemory)} names the backend directly, and is the way to reach anything that is not
 * built out of AIMON's stores. {@link Builder#representationStore(RepresentationStore)} and
 * {@link Builder#observationStore(ObservationStore)} name the default backend's materials and are folded into a
 * {@code StoreBackedPeerMemory} by the assembly. The two are <b>mutually exclusive</b>: given both, the stores would
 * describe a backend that is not the one being used.
 *
 * <h2>Whose memory — the one decision that has to be made explicitly</h2>
 *
 * <p>
 * There is no {@code builder()} here, only {@link #forPeer(Workspace, Principal)} and
 * {@link #perCaller(Workspace)}. Peer resolution has no defensible default, so it is a required choice rather than a
 * field someone can leave unset:
 *
 * <ul>
 * <li><b>{@link #forPeer(Workspace, Principal) forPeer}</b> — one process, one peer. Every execution reads and writes
 * the same peer's memory whether or not the caller was identified. Right for a single-tenant service or a CLI.
 * <li><b>{@link #perCaller(Workspace) perCaller}</b> — the memory of whoever made the call. Executions that arrive
 * without a principal get nothing, which is the answer that cannot be wrong.
 * </ul>
 *
 * <p>
 * The two are deliberately not combinable. "Use the caller when there is one, the configured peer otherwise" reads as
 * a sensible fallback and is in fact a third arrangement, in which identified traffic reads one peer's memory and
 * anonymous traffic reads another's — nothing fails, and the answers are quietly wrong for half the requests. The same
 * reasoning is recorded on {@link at.aimon.core.memory.MemoryPeerResolver}, which is the type this decision becomes.
 *
 * <h2>What per-caller mode costs today</h2>
 *
 * <p>
 * Per-caller mode wires the <b>injected prompt part only</b>. The memory tools cannot be registered, because they read
 * their workspace and observer from the {@code ToolContext} and the enricher that puts them there
 * ({@link at.aimon.core.tools.memory.MemoryToolContextEnricher}) is bound to one fixed observer — the enrichment info
 * it is handed carries a session and an execution, but no principal. Registering them anyway would give the model
 * three tools that answer "no workspace in context" to every call. So per-caller mode requires the
 * {@link MemoryCapability#SNAPSHOT} capability — a {@link RepresentationStore}, or a backend that serves that tier:
 * with only an observation store it would wire nothing whatsoever while looking configured, and
 * {@link Builder#build()} rejects it.
 *
 * <h2>Read path only</h2>
 *
 * <p>
 * Nothing in this spec writes memory. The deriver, its queue and the dreamer are not part of the stack, so
 * representations and observations have to be produced by something else — another process against the same store, or
 * an explicit {@code Observe} call. The stack records this as a runtime degradation rather than leaving the operator
 * to discover it from an injected memory part that is empty forever.
 */
public final class MemorySpec {

    private final Workspace workspace;
    private final Principal fixedPeer;
    private final PeerMemory peerMemory;
    private final RepresentationStore representationStore;
    private final ObservationStore observationStore;
    private final MemoryInjectionMode injectionMode;
    private final MemoryIngestMode ingestMode;
    private final int maxTokens;
    private final RedactionPolicy redactionPolicy;

    private MemorySpec(Builder builder) {
        this.workspace = builder.workspace;
        this.fixedPeer = builder.fixedPeer;
        this.peerMemory = builder.peerMemory;
        this.representationStore = builder.representationStore;
        this.observationStore = builder.observationStore;
        this.injectionMode = Objects.requireNonNullElse(builder.injectionMode, MemoryInjectionMode.SUMMARY_ONLY);
        this.ingestMode = Objects.requireNonNullElse(builder.ingestMode, MemoryIngestMode.OFF);
        this.maxTokens = builder.maxTokens;
        this.redactionPolicy = builder.redactionPolicy;

        if (this.peerMemory != null && (this.representationStore != null || this.observationStore != null)) {
            throw new IllegalArgumentException(
                    "Set either peerMemory or the stores, not both — the stores describe how to build the default"
                            + " backend, and supplying them alongside a backend describes one that is not in use");
        }
        if (this.peerMemory == null && this.representationStore == null && this.observationStore == null) {
            throw new IllegalArgumentException(
                    "A memory spec needs a PeerMemory or at least one store — with neither, memory is configured,"
                            + " reported as present, and does nothing");
        }
        if (this.fixedPeer == null && !providesSnapshot()) {
            // Same rule as before, said in the vocabulary that now covers both paths: per-caller mode wires the
            // injected prompt part and nothing else, so a spec that cannot produce a snapshot wires nothing at all.
            throw new IllegalArgumentException(
                    "Per-caller memory requires the SNAPSHOT capability: the memory tools cannot be registered without"
                            + " a fixed observer, so a backend that cannot answer for the injected prompt part would"
                            + " wire nothing. Use MemorySpec.forPeer(workspace, peer) if the tools are what you"
                            + " wanted.");
        }
        if (this.maxTokens < 0) {
            throw new IllegalArgumentException("maxTokens must be >= 0 (0 means no cap), got: " + this.maxTokens);
        }
    }

    /**
     * Starts a spec whose every execution reads the same peer's memory.
     *
     * @param workspace
     *            the memory workspace (must not be null)
     * @param peer
     *            the peer every execution is answered for (must not be null)
     * @return a builder
     */
    public static Builder forPeer(Workspace workspace, Principal peer) {
        return new Builder(Objects.requireNonNull(workspace, "workspace must not be null"),
                Objects.requireNonNull(peer, "peer must not be null — use MemorySpec.perCaller(workspace) instead"));
    }

    /**
     * Starts a spec whose executions read the memory of whoever made the call, and nothing when the caller was not
     * identified.
     *
     * @param workspace
     *            the memory workspace (must not be null)
     * @return a builder
     */
    public static Builder perCaller(Workspace workspace) {
        return new Builder(Objects.requireNonNull(workspace, "workspace must not be null"), null);
    }

    /**
     * Returns the workspace every peer in this stack belongs to.
     *
     * @return the workspace, never null
     */
    public Workspace getWorkspace() {
        return workspace;
    }

    /**
     * Returns the single peer every execution is answered for.
     *
     * @return the peer, or empty in per-caller mode
     */
    public Optional<Principal> getFixedPeer() {
        return Optional.ofNullable(fixedPeer);
    }

    /**
     * Returns whether each execution is answered for its own caller rather than for one configured peer.
     *
     * @return {@code true} in per-caller mode
     */
    public boolean isPerCaller() {
        return fixedPeer == null;
    }

    /**
     * Returns whether this spec can produce the injected memory prompt part.
     *
     * <p>
     * True for a representation store, and for a backend whose computed capabilities include
     * {@link MemoryCapability#SNAPSHOT}. The two are the same question asked of the two ways of naming a memory.
     *
     * @return {@code true} when a snapshot can be read
     */
    public boolean providesSnapshot() {
        if (representationStore != null) {
            return true;
        }
        return peerMemory != null && MemoryCapabilities.of(peerMemory).contains(MemoryCapability.SNAPSHOT);
    }

    /**
     * Returns the backend this stack reads and writes memory through.
     *
     * @return the backend, or empty when the spec names stores instead
     */
    public Optional<PeerMemory> getPeerMemory() {
        return Optional.ofNullable(peerMemory);
    }

    /**
     * Returns the store holding derived peer snapshots — what the injected memory prompt part is read from.
     *
     * @return the store, or empty when no snapshot is injected or recalled
     */
    public Optional<RepresentationStore> getRepresentationStore() {
        return Optional.ofNullable(representationStore);
    }

    /**
     * Returns the store holding individual observations — what the search and observe tools use.
     *
     * @return the store, or empty when those tools are not wired
     */
    public Optional<ObservationStore> getObservationStore() {
        return Optional.ofNullable(observationStore);
    }

    /**
     * Returns how much of the snapshot the injected prompt part carries.
     *
     * @return the mode, never null
     */
    public MemoryInjectionMode getInjectionMode() {
        return injectionMode;
    }

    /**
     * Returns when conversation is fed into the backend.
     *
     * <p>
     * Defaults to {@link MemoryIngestMode#OFF}, which is what every stack-assembled deployment does today: nothing
     * has ever written memory from a conversation through this path, so anything else would be a bill nobody asked
     * for. A front end whose users expect memory to fill — the CLI does — sets it explicitly.
     *
     * @return the mode, never null
     */
    public MemoryIngestMode getIngestMode() {
        return ingestMode;
    }

    /**
     * Returns the cap on the injected part's estimated token count.
     *
     * @return the cap, or {@code 0} for no cap
     */
    public int getMaxTokens() {
        return maxTokens;
    }

    /**
     * Returns the policy applied at the memory tool boundary.
     *
     * @return the policy, or empty when queries and stored content pass through unredacted
     */
    public Optional<RedactionPolicy> getRedactionPolicy() {
        return Optional.ofNullable(redactionPolicy);
    }

    @Override
    public String toString() {
        return "MemorySpec[workspace=" + workspace.getId() + ", peer="
                + (fixedPeer == null ? "per-caller" : fixedPeer.getId()) + ", backend="
                + (peerMemory == null ? "stores" : peerMemory.backendId()) + ", representations="
                + (representationStore != null) + ", observations=" + (observationStore != null) + ", injection="
                + injectionMode + ", ingest=" + ingestMode + "]";
    }

    /** Builder for {@link MemorySpec}. */
    public static final class Builder {

        private final Workspace workspace;
        private final Principal fixedPeer;
        private PeerMemory peerMemory;
        private RepresentationStore representationStore;
        private ObservationStore observationStore;
        private MemoryInjectionMode injectionMode;
        private MemoryIngestMode ingestMode;
        private int maxTokens;
        private RedactionPolicy redactionPolicy;

        private Builder(Workspace workspace, Principal fixedPeer) {
            this.workspace = workspace;
            this.fixedPeer = fixedPeer;
        }

        /**
         * Sets the backend the stack reads and writes memory through.
         *
         * <p>
         * Mutually exclusive with {@link #representationStore(RepresentationStore)} and
         * {@link #observationStore(ObservationStore)}, which name the default backend's materials instead.
         *
         * @param peerMemory
         *            the backend, or {@code null} to name stores instead
         * @return this builder
         */
        public Builder peerMemory(PeerMemory peerMemory) {
            this.peerMemory = peerMemory;
            return this;
        }

        /**
         * Sets the store the injected memory part and the recall tool read from.
         *
         * @param representationStore
         *            the store, or {@code null} for none
         * @return this builder
         */
        public Builder representationStore(RepresentationStore representationStore) {
            this.representationStore = representationStore;
            return this;
        }

        /**
         * Sets the store the search and observe tools use.
         *
         * @param observationStore
         *            the store, or {@code null} for none
         * @return this builder
         */
        public Builder observationStore(ObservationStore observationStore) {
            this.observationStore = observationStore;
            return this;
        }

        /**
         * Sets how much of the snapshot reaches the prompt. Defaults to
         * {@link MemoryInjectionMode#SUMMARY_ONLY}, which keeps the per-turn cost predictable.
         *
         * @param injectionMode
         *            the mode, or {@code null} for the default
         * @return this builder
         */
        public Builder injectionMode(MemoryInjectionMode injectionMode) {
            this.injectionMode = injectionMode;
            return this;
        }

        /**
         * Sets when conversation is fed into the backend. Defaults to {@link MemoryIngestMode#OFF}.
         *
         * @param ingestMode
         *            the mode, or {@code null} for the default
         * @return this builder
         */
        public Builder ingestMode(MemoryIngestMode ingestMode) {
            this.ingestMode = ingestMode;
            return this;
        }

        /**
         * Caps the injected part's estimated token count.
         *
         * @param maxTokens
         *            the cap, or {@code 0} for no cap
         * @return this builder
         */
        public Builder maxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        /**
         * Sets the policy applied to a search query before it runs and to observed content before it is stored.
         *
         * @param redactionPolicy
         *            the policy, or {@code null} to pass both through unredacted
         * @return this builder
         */
        public Builder redactionPolicy(RedactionPolicy redactionPolicy) {
            this.redactionPolicy = redactionPolicy;
            return this;
        }

        /**
         * Validates and builds the spec.
         *
         * @return the immutable spec
         * @throws IllegalArgumentException
         *             if the spec would wire nothing, if it names both a backend and stores, or if per-caller mode
         *             was chosen for a memory that cannot produce a snapshot
         */
        public MemorySpec build() {
            return new MemorySpec(this);
        }
    }
}
