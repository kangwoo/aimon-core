package at.aimon.core.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.base.Principal;
import at.aimon.core.llm.Message;
import at.aimon.core.memory.deriver.DerivationQueueManager;
import at.aimon.core.memory.deriver.DerivationTask;
import at.aimon.core.memory.deriver.QueueStats;
import at.aimon.core.memory.dialectic.DialecticEngine;
import at.aimon.core.memory.dialectic.DialecticQuery;
import at.aimon.core.memory.dialectic.DialecticResponse;

/** The default backend: which tiers each material produces, and what each of those tiers promises. */
@DisplayName("StoreBackedPeerMemory")
class StoreBackedPeerMemoryTest {

    private static final Workspace WS = Workspace.builder().id("ws-1").build();

    private InMemoryRepresentationStore representations;
    private InMemoryObservationStore observations;
    private RecordingQueue queue;

    @BeforeEach
    void setUp() {
        representations = new InMemoryRepresentationStore();
        observations = new InMemoryObservationStore();
        queue = new RecordingQueue();
    }

    private static PeerView peer(String id) {
        return PeerView.of(WS, Principal.user(id, id));
    }

    private static Observation observation(String localId, String content) {
        return Observation.builder().id(ObservationId.of(WS, localId)).subject(peer("alice")).observer(peer("agent"))
                .content(content).type(ObservationType.EXPLICIT).confidence(0.8d)
                .createdAt(Instant.parse("2024-01-15T10:00:00Z")).build();
    }

    @Nested
    @DisplayName("capability negotiation")
    class Capabilities {

        @Test
        @DisplayName("a missing material empties the tier it feeds rather than producing one that fails on first use")
        void materialsMapToTiers() {
            PeerMemory representationsOnly = StoreBackedPeerMemory.builder().representationStore(representations)
                    .build();
            assertThat(MemoryCapabilities.of(representationsOnly)).containsExactly(MemoryCapability.SNAPSHOT);

            PeerMemory observationsOnly = StoreBackedPeerMemory.builder().observationStore(observations).build();
            assertThat(MemoryCapabilities.of(observationsOnly)).containsExactlyInAnyOrder(MemoryCapability.SEARCH,
                    MemoryCapability.OBSERVE);

            PeerMemory queueOnly = StoreBackedPeerMemory.builder().derivationQueue(queue).build();
            assertThat(MemoryCapabilities.of(queueOnly)).containsExactly(MemoryCapability.INGEST);

            PeerMemory everything = StoreBackedPeerMemory.builder().representationStore(representations)
                    .observationStore(observations).dialecticEngine(new StubEngine()).derivationQueue(queue).build();
            assertThat(MemoryCapabilities.of(everything)).containsExactlyInAnyOrder(MemoryCapability.values());
        }

        @Test
        @DisplayName("identifies itself as the default backend")
        void backendId() {
            assertThat(StoreBackedPeerMemory.builder().observationStore(observations).build().backendId())
                    .isEqualTo("default");
        }

        @Test
        @DisplayName("rejects a build with no materials at all — it would serve nothing while looking configured")
        void noMaterialsRejected() {
            assertThatThrownBy(() -> StoreBackedPeerMemory.builder().build())
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at least one material");
        }
    }

    @Nested
    @DisplayName("SNAPSHOT tier")
    class Snapshot {

        private MemorySnapshotReader reader() {
            return StoreBackedPeerMemory.builder().representationStore(representations).build().snapshotReader()
                    .orElseThrow();
        }

        private Representation representation(PeerView observer, String sessionId, String summary, int tokenCount) {
            return Representation.builder().subject(peer("alice")).observer(observer).sessionId(sessionId)
                    .summary(summary).tokenCount(tokenCount).generatedAt(Instant.parse("2024-01-15T10:00:00Z"))
                    .observations(List.of(observation("o-1", "Alice prefers tea"))).build();
        }

        @Test
        @DisplayName("returns empty when the store holds nothing — an answer, not a failure")
        void missIsEmpty() {
            assertThat(reader().read(MemorySnapshotQuery.builder().subject(peer("alice")).build())).isEmpty();
        }

        @Test
        @DisplayName("LOCAL_THEN_GLOBAL prefers the local snapshot and reports which one answered")
        void localWins() {
            representations.save(representation(peer("agent"), "s-1", "local view", 10));
            representations.save(representation(null, null, "global view", 10));

            MemorySnapshot snapshot = reader().read(MemorySnapshotQuery.builder().subject(peer("alice"))
                    .observer(peer("agent")).sessionId("s-1").build()).orElseThrow();

            assertThat(snapshot.getRenderedText()).isEqualTo("local view");
            assertThat(snapshot.getResolvedScope()).isEqualTo(MemorySnapshotScope.LOCAL);
        }

        @Test
        @DisplayName("LOCAL_THEN_GLOBAL falls back to global and says so")
        void globalFallback() {
            representations.save(representation(null, null, "global view", 10));

            MemorySnapshot snapshot = reader().read(MemorySnapshotQuery.builder().subject(peer("alice"))
                    .observer(peer("agent")).sessionId("s-1").build()).orElseThrow();

            assertThat(snapshot.getRenderedText()).isEqualTo("global view");
            assertThat(snapshot.getResolvedScope()).isEqualTo(MemorySnapshotScope.GLOBAL);
        }

        @Test
        @DisplayName("GLOBAL scope never consults the local snapshot")
        void globalIgnoresLocal() {
            representations.save(representation(peer("agent"), "s-1", "local view", 10));

            assertThat(reader().read(MemorySnapshotQuery.builder().subject(peer("alice")).observer(peer("agent"))
                    .sessionId("s-1").scope(MemorySnapshotScope.GLOBAL).build())).isEmpty();
        }

        @Test
        @DisplayName("SUMMARY_ONLY leaves the observations out without calling that truncation")
        void summaryOnlyIsNotTruncation() {
            representations.save(representation(null, null, "global view", 10));

            MemorySnapshot snapshot = reader().read(
                    MemorySnapshotQuery.builder().subject(peer("alice")).mode(MemoryInjectionMode.SUMMARY_ONLY).build())
                    .orElseThrow();

            assertThat(snapshot.getObservations()).isEmpty();
            assertThat(snapshot.isTruncated()).isFalse();
            assertThat(snapshot.isObservationsAvailable()).isTrue();
        }

        @Test
        @DisplayName("FULL within budget carries the observations, and their confidence is real")
        void fullWithinBudget() {
            representations.save(representation(null, null, "global view", 10));

            MemorySnapshot snapshot = reader().read(MemorySnapshotQuery.builder().subject(peer("alice"))
                    .mode(MemoryInjectionMode.FULL).maxTokens(100).build()).orElseThrow();

            assertThat(snapshot.getObservations()).hasSize(1);
            assertThat(snapshot.isTruncated()).isFalse();
            assertThat(snapshot.isConfidenceAvailable()).isTrue();
            assertThat(snapshot.isTokenCountEstimated()).isFalse();
        }

        @Test
        @DisplayName("FULL over budget drops the observations and marks the snapshot truncated")
        void fullOverBudget() {
            representations.save(representation(null, null, "global view", 500));

            MemorySnapshot snapshot = reader().read(MemorySnapshotQuery.builder().subject(peer("alice"))
                    .mode(MemoryInjectionMode.FULL).maxTokens(100).build()).orElseThrow();

            assertThat(snapshot.getObservations()).isEmpty();
            assertThat(snapshot.isTruncated()).isTrue();
        }
    }

    @Nested
    @DisplayName("SEARCH tier")
    class Search {

        private MemorySearcher searcher() {
            return StoreBackedPeerMemory.builder().observationStore(observations).build().searcher().orElseThrow();
        }

        @Test
        @DisplayName("does not rank by score, so every hit's score stays at zero")
        void noScores() {
            observations.save(observation("o-1", "Alice prefers tea"));

            List<MemoryHit> hits = searcher()
                    .search(MemorySearchQuery.builder().subject(peer("alice")).query("tea").build());

            assertThat(searcher().ranksByScore()).isFalse();
            assertThat(hits).hasSize(1);
            assertThat(hits.get(0).getScore()).isZero();
            assertThat(hits.get(0).isConfidenceAvailable()).isTrue();
            assertThat(hits.get(0).getSignals()).isEmpty();
        }

        @Test
        @DisplayName("rejects a positive minScore rather than ignoring it — a filter that did not run must not read"
                + " as one that did")
        void minScoreRejected() {
            assertThatThrownBy(() -> searcher()
                    .search(MemorySearchQuery.builder().subject(peer("alice")).query("tea").minScore(0.5d).build()))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ranksByScore");
        }

        @Test
        @DisplayName("a minScore of zero means no floor and is accepted")
        void zeroMinScoreAccepted() {
            assertThat(searcher()
                    .search(MemorySearchQuery.builder().subject(peer("alice")).query("tea").minScore(0.0d).build()))
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("OBSERVE tier")
    class Observe {

        private ObservationRecorder recorder() {
            return StoreBackedPeerMemory.builder().observationStore(observations).build().observationRecorder()
                    .orElseThrow();
        }

        @Test
        @DisplayName("mints the id the draft does not carry and stores the confidence it was given")
        void recordsDraft() {
            Observation stored = recorder()
                    .observe(ObservationDraft.builder().subject(peer("alice")).observer(peer("agent"))
                            .content("Alice prefers tea").type(ObservationType.EXPLICIT).confidence(0.42d).build());

            assertThat(recorder().storesConfidence()).isTrue();
            assertThat(stored.getId().getWorkspaceId()).isEqualTo("ws-1");
            assertThat(stored.getId().getLocalId()).isNotBlank();
            assertThat(stored.getConfidence()).isEqualTo(0.42d);
            assertThat(observations.findById(stored.getId())).isPresent();
        }
    }

    @Nested
    @DisplayName("INGEST tier")
    class Ingest {

        @Test
        @DisplayName("hands the messages to the queue and reports that derivation has not happened yet")
        void enqueues() {
            MemoryIngestor ingestor = StoreBackedPeerMemory.builder().derivationQueue(queue).build().ingestor()
                    .orElseThrow();

            MemoryIngestReceipt receipt = ingestor.ingest(MemoryIngestRequest.builder().observer(peer("agent"))
                    .sessionId("s-1").messages(List.of(Message.user("hello"), Message.assistant("hi")))
                    .waitForDerivation(true).build());

            assertThat(receipt.getAccepted()).isEqualTo(2);
            assertThat(receipt.isDerived()).isFalse();
            assertThat(queue.enqueued).hasSize(1);
            assertThat(queue.enqueued.get(0).getSessionId()).isEqualTo("s-1");
            assertThat(queue.enqueued.get(0).getWorkspace()).isEqualTo(WS);
        }
    }

    /** Captures what was enqueued without starting a worker pool. */
    private static final class RecordingQueue implements DerivationQueueManager {

        private final List<DerivationTask> enqueued = new ArrayList<>();

        @Override
        public void enqueue(DerivationTask task) {
            enqueued.add(task);
        }

        @Override
        public void start() {
            // no worker pool in this test
        }

        @Override
        public void stop() {
            // no worker pool in this test
        }

        @Override
        public QueueStats stats() {
            return QueueStats.of(enqueued.size(), 0, 0L, 0L);
        }
    }

    private static final class StubEngine implements DialecticEngine {
        @Override
        public DialecticResponse query(DialecticQuery query) {
            return DialecticResponse.builder().answer("stub").build();
        }
    }
}
