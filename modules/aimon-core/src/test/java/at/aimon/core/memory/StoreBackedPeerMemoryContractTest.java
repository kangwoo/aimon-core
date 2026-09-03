package at.aimon.core.memory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;

import at.aimon.core.memory.deriver.DerivationQueueManager;
import at.aimon.core.memory.deriver.DerivationTask;
import at.aimon.core.memory.deriver.QueueStats;
import at.aimon.core.memory.dialectic.DialecticEngine;
import at.aimon.core.memory.dialectic.DialecticQuery;
import at.aimon.core.memory.dialectic.DialecticResponse;
import at.aimon.memory.testkit.AbstractPeerMemoryContractTest;

/**
 * The in-tree backend, held to the shared contract rather than only to its own tests.
 *
 * <p>
 * It serves all five tiers, so nothing here is skipped — which makes it the backend that has to keep the suite
 * honest: a contract only this one can satisfy would be a description of an implementation rather than of a seam.
 *
 * <p>
 * The dialectic engine and the derivation queue are stubs. Neither tier's contract is about what an LLM answers or
 * about what a worker pool does with a task; it is about a present tier answering at all, and about the receipt
 * telling the truth.
 */
@DisplayName("StoreBackedPeerMemory — PeerMemory contract")
class StoreBackedPeerMemoryContractTest extends AbstractPeerMemoryContractTest {

    private ObservationStore observations;
    private RepresentationStore representations;

    @Override
    protected PeerMemory newBackend() {
        observations = new InMemoryObservationStore();
        representations = new InMemoryRepresentationStore();
        return StoreBackedPeerMemory.builder().representationStore(representations).observationStore(observations)
                .dialecticEngine(new StubEngine()).derivationQueue(new CountingQueue()).build();
    }

    /**
     * Seeds through the stores rather than through the OBSERVE tier, so the read cases stay independent of the write
     * one — and because this backend does not derive a representation from an observation. Writing to the two stores
     * is exactly what the deriver would have done, without needing an LLM in the suite.
     */
    @Override
    protected boolean seedObservation(PeerView subject, PeerView observer, String content) {
        observations.save(observation(subject, observer, content));
        return true;
    }

    @Override
    protected boolean seedSnapshot(PeerView subject, PeerView observer, String sessionId, String summary) {
        representations.save(Representation.builder().subject(subject).observer(observer).sessionId(sessionId)
                .summary(summary).observations(List.of(observation(subject, observer, summary)))
                .tokenCount(summary.length() / 4).generatedAt(Instant.now()).build());
        return true;
    }

    private static Observation observation(PeerView subject, PeerView observer, String content) {
        return Observation.builder().id(ObservationId.of(subject.getWorkspace(), UUID.randomUUID().toString()))
                .subject(subject).observer(observer).content(content).type(ObservationType.EXPLICIT).confidence(0.8d)
                .createdAt(Instant.now()).build();
    }

    /** Answers without an LLM: the CHAT contract is about a present tier answering, not about what it says. */
    private static final class StubEngine implements DialecticEngine {
        @Override
        public DialecticResponse query(DialecticQuery query) {
            return DialecticResponse.builder().answer("(stub answer for " + query.getSubject().key() + ")").build();
        }
    }

    /** Accepts tasks without a worker pool: the INGEST contract is about the receipt, not about derivation. */
    private static final class CountingQueue implements DerivationQueueManager {

        private final List<DerivationTask> enqueued = new ArrayList<>();

        @Override
        public void enqueue(DerivationTask task) {
            enqueued.add(task);
        }

        @Override
        public void start() {
            // no worker pool in this suite
        }

        @Override
        public void stop() {
            // no worker pool in this suite
        }

        @Override
        public QueueStats stats() {
            return QueueStats.of(enqueued.size(), 0, 0L, 0L);
        }
    }
}
