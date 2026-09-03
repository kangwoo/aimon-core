package at.aimon.core.memory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import at.aimon.core.memory.deriver.DerivationQueueManager;
import at.aimon.core.memory.deriver.DerivationTask;
import at.aimon.core.memory.dialectic.DialecticEngine;

/**
 * The in-tree {@link PeerMemory} backend, built out of the stores and reasoning components AIMON already has.
 *
 * <p>
 * This class is where the demotion of the storage SPI becomes visible. {@link ObservationStore},
 * {@link RepresentationStore} and {@link WorkspaceStore} are no longer the place a memory backend is replaced; they
 * are <em>this</em> backend's materials, and nothing in the five tiers mentions them. Adding a new storage technology
 * still means implementing a store; replacing memory itself means implementing {@link PeerMemory}.
 *
 * <p>
 * Every tier is optional, and a missing material empties the tier it feeds rather than producing one that fails on
 * first use — which is what makes {@link MemoryCapabilities#of(PeerMemory)} an honest answer:
 *
 * <table border="1">
 * <caption>Materials and the tiers they produce</caption>
 * <tr>
 * <th>Material</th>
 * <th>Tier</th>
 * </tr>
 * <tr>
 * <td>{@link RepresentationStore}</td>
 * <td>{@link MemoryCapability#SNAPSHOT}</td>
 * </tr>
 * <tr>
 * <td>{@link ObservationStore}</td>
 * <td>{@link MemoryCapability#SEARCH} and {@link MemoryCapability#OBSERVE}</td>
 * </tr>
 * <tr>
 * <td>{@link DialecticEngine}</td>
 * <td>{@link MemoryCapability#CHAT}</td>
 * </tr>
 * <tr>
 * <td>{@link DerivationQueueManager}</td>
 * <td>{@link MemoryCapability#INGEST}</td>
 * </tr>
 * </table>
 *
 * <p>
 * <b>No workspace is captured here.</b> One instance is application-scoped and may serve several workspaces; the
 * workspace arrives on each query's {@link PeerView}.
 */
public final class StoreBackedPeerMemory implements PeerMemory {

    /** The id this backend reports in logs, diagnostics and degradation messages. */
    public static final String BACKEND_ID = "default";

    private final MemorySnapshotReader snapshotReader;
    private final MemorySearcher searcher;
    private final DialecticEngine dialecticEngine;
    private final ObservationRecorder observationRecorder;
    private final MemoryIngestor ingestor;

    private StoreBackedPeerMemory(Builder builder) {
        this.snapshotReader = builder.representationStore == null
                ? null
                : new RepresentationSnapshotReader(builder.representationStore);
        this.searcher = builder.observationStore == null ? null : new StoreSearcher(builder.observationStore);
        this.dialecticEngine = builder.dialecticEngine;
        this.observationRecorder = builder.observationStore == null
                ? null
                : new StoreObservationRecorder(builder.observationStore);
        this.ingestor = builder.derivationQueue == null ? null : new QueueIngestor(builder.derivationQueue);

        if (this.snapshotReader == null && this.searcher == null && this.dialecticEngine == null
                && this.ingestor == null) {
            throw new IllegalArgumentException("A store-backed memory needs at least one material — with none it"
                    + " serves no capability at all and is indistinguishable from having no memory configured");
        }
    }

    /**
     * Starts a backend.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String backendId() {
        return BACKEND_ID;
    }

    @Override
    public Optional<MemorySnapshotReader> snapshotReader() {
        return Optional.ofNullable(snapshotReader);
    }

    @Override
    public Optional<MemorySearcher> searcher() {
        return Optional.ofNullable(searcher);
    }

    @Override
    public Optional<DialecticEngine> dialecticEngine() {
        return Optional.ofNullable(dialecticEngine);
    }

    @Override
    public Optional<ObservationRecorder> observationRecorder() {
        return Optional.ofNullable(observationRecorder);
    }

    @Override
    public Optional<MemoryIngestor> ingestor() {
        return Optional.ofNullable(ingestor);
    }

    @Override
    public String toString() {
        return "StoreBackedPeerMemory" + MemoryCapabilities.of(this);
    }

    /** Reads snapshots out of a {@link RepresentationStore}. */
    private static final class RepresentationSnapshotReader implements MemorySnapshotReader {

        private final RepresentationStore representationStore;

        RepresentationSnapshotReader(RepresentationStore representationStore) {
            this.representationStore = representationStore;
        }

        @Override
        public Optional<MemorySnapshot> read(MemorySnapshotQuery query) {
            Objects.requireNonNull(query, "query cannot be null");

            final PeerView observer = query.getObserver().orElse(null);
            final String sessionId = query.getSessionId().orElse(null);

            Representation found = null;
            MemorySnapshotScope resolved = null;
            if (query.getScope() != MemorySnapshotScope.GLOBAL && observer != null) {
                found = representationStore.findLatestLocal(query.getSubject(), observer, sessionId).orElse(null);
                resolved = MemorySnapshotScope.LOCAL;
            }
            if (found == null && query.getScope() != MemorySnapshotScope.LOCAL) {
                found = representationStore.findLatestGlobal(query.getSubject()).orElse(null);
                resolved = MemorySnapshotScope.GLOBAL;
            }
            if (found == null) {
                return Optional.empty();
            }
            return Optional.of(toSnapshot(found, resolved, query));
        }

        private static MemorySnapshot toSnapshot(Representation representation, MemorySnapshotScope resolvedScope,
                MemorySnapshotQuery query) {
            final boolean wantsObservations = query.getMode() == MemoryInjectionMode.FULL;
            final boolean overBudget = wantsObservations && query.getMaxTokens() > 0
                    && representation.getTokenCount() > query.getMaxTokens();
            final List<Observation> observations = wantsObservations && !overBudget
                    ? representation.getObservations()
                    : List.of();

            return MemorySnapshot.builder().renderedText(representation.getSummary()).resolvedScope(resolvedScope)
                    .generatedAt(representation.getGeneratedAt()).tokenCount(representation.getTokenCount())
                    .tokenCountEstimated(false).truncated(overBudget).observationsAvailable(true)
                    .confidenceAvailable(true).observations(observations).build();
        }
    }

    /** Searches an {@link ObservationStore}. Order is the ranking; there are no scores to report. */
    private static final class StoreSearcher implements MemorySearcher {

        private final ObservationStore observationStore;

        StoreSearcher(ObservationStore observationStore) {
            this.observationStore = observationStore;
        }

        @Override
        public List<MemoryHit> search(MemorySearchQuery query) {
            Objects.requireNonNull(query, "query cannot be null");
            if (query.getMinScore() > 0.0d) {
                throw new IllegalArgumentException("This backend does not rank by score (ranksByScore() == false), so"
                        + " a minScore of " + query.getMinScore() + " cannot be applied. Rejecting rather than"
                        + " ignoring it: a silently unapplied filter reads as an applied one.");
            }
            final List<Observation> matches = observationStore.semanticSearch(query.getSubject(), query.getQuery(),
                    query.getTopK());
            final List<MemoryHit> hits = new ArrayList<>(matches.size());
            for (Observation match : matches) {
                hits.add(MemoryHit.builder().observation(match).confidenceAvailable(true).build());
            }
            return List.copyOf(hits);
        }

        @Override
        public boolean ranksByScore() {
            // The index underneath promises "ordered from most to least relevant" and nothing more. Deriving a score
            // from the rank would put a number the model reads as measured next to one that is not.
            return false;
        }
    }

    /** Writes drafts into an {@link ObservationStore}, minting the id the draft does not carry. */
    private static final class StoreObservationRecorder implements ObservationRecorder {

        private final ObservationStore observationStore;

        StoreObservationRecorder(ObservationStore observationStore) {
            this.observationStore = observationStore;
        }

        @Override
        public Observation observe(ObservationDraft draft) {
            Objects.requireNonNull(draft, "draft cannot be null");
            final ObservationId id = ObservationId.of(draft.getSubject().getWorkspace(), UUID.randomUUID().toString());
            final Observation observation = Observation.builder().id(id).subject(draft.getSubject())
                    .observer(draft.getObserver()).content(draft.getContent()).type(draft.getType())
                    .confidence(draft.getConfidence()).sourceMessageIds(draft.getSourceMessageIds())
                    .createdAt(Instant.now()).metadata(draft.getMetadata()).build();
            return observationStore.save(observation);
        }

        @Override
        public boolean storesConfidence() {
            return true;
        }
    }

    /** Hands messages to the derivation queue, which applies redaction again on the way in. */
    private static final class QueueIngestor implements MemoryIngestor {

        private final DerivationQueueManager derivationQueue;

        QueueIngestor(DerivationQueueManager derivationQueue) {
            this.derivationQueue = derivationQueue;
        }

        @Override
        public MemoryIngestReceipt ingest(MemoryIngestRequest request) {
            Objects.requireNonNull(request, "request cannot be null");
            derivationQueue.enqueue(DerivationTask.builder().workspace(request.getObserver().getWorkspace())
                    .sessionId(request.getSessionId()).observer(request.getObserver()).messages(request.getMessages())
                    .build());
            // The queue is asynchronous by construction, so read-your-writes is never honoured here whatever was
            // asked for. Saying so is the point of the flag.
            return MemoryIngestReceipt.builder().accepted(request.getMessages().size()).derived(false).build();
        }
    }

    /** Builder for {@link StoreBackedPeerMemory}. */
    public static final class Builder {

        private RepresentationStore representationStore;
        private ObservationStore observationStore;
        private DialecticEngine dialecticEngine;
        private DerivationQueueManager derivationQueue;

        private Builder() {
        }

        /**
         * Sets the store the {@link MemoryCapability#SNAPSHOT} tier reads from.
         *
         * @param representationStore
         *            the store, or {@code null} to leave the tier empty
         * @return this builder
         */
        public Builder representationStore(RepresentationStore representationStore) {
            this.representationStore = representationStore;
            return this;
        }

        /**
         * Sets the store the {@link MemoryCapability#SEARCH} and {@link MemoryCapability#OBSERVE} tiers use.
         *
         * <p>
         * <b>It must be a store that actually answers {@link ObservationStore#semanticSearch}.</b> Metadata-only
         * stores throw {@link UnsupportedOperationException} there by design, and handing one over unwrapped produces
         * exactly the state the capability model is supposed to make impossible: a SEARCH tier that is present and
         * fails on every call. Wrap such a store in {@link IndexedObservationStore} first — that is the decorator the
         * exception exists for.
         *
         * @param observationStore
         *            the store, or {@code null} to leave both tiers empty
         * @return this builder
         */
        public Builder observationStore(ObservationStore observationStore) {
            this.observationStore = observationStore;
            return this;
        }

        /**
         * Sets the engine the {@link MemoryCapability#CHAT} tier passes through to.
         *
         * @param dialecticEngine
         *            the engine, or {@code null} to leave the tier empty
         * @return this builder
         */
        public Builder dialecticEngine(DialecticEngine dialecticEngine) {
            this.dialecticEngine = dialecticEngine;
            return this;
        }

        /**
         * Sets the queue the {@link MemoryCapability#INGEST} tier feeds.
         *
         * @param derivationQueue
         *            the queue, or {@code null} to leave the tier empty
         * @return this builder
         */
        public Builder derivationQueue(DerivationQueueManager derivationQueue) {
            this.derivationQueue = derivationQueue;
            return this;
        }

        /**
         * Validates and builds the backend.
         *
         * @return the immutable backend
         * @throws IllegalArgumentException
         *             if no material was supplied at all
         */
        public StoreBackedPeerMemory build() {
            return new StoreBackedPeerMemory(this);
        }
    }
}
