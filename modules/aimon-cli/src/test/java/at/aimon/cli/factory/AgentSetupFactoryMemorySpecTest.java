package at.aimon.cli.factory;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.bootstrap.RuntimeDegradations;
import at.aimon.bootstrap.assemble.MemoryAssembly;
import at.aimon.bootstrap.spec.MemorySpec;
import at.aimon.cli.config.MemoryConfig;
import at.aimon.cli.factory.AgentSetupFactory.MemoryWiring;
import at.aimon.core.base.Principal;
import at.aimon.core.llm.Message;
import at.aimon.core.memory.InMemoryObservationStore;
import at.aimon.core.memory.InMemoryRepresentationStore;
import at.aimon.core.memory.MemoryCapabilities;
import at.aimon.core.memory.MemoryCapability;
import at.aimon.core.memory.MemoryIngestReceipt;
import at.aimon.core.memory.MemoryIngestRequest;
import at.aimon.core.memory.MemoryInjectionMode;
import at.aimon.core.memory.PeerView;
import at.aimon.core.memory.RedactingPeerMemory;
import at.aimon.core.memory.Workspace;
import at.aimon.core.memory.deriver.DerivationQueueManager;
import at.aimon.core.memory.deriver.DerivationTask;
import at.aimon.core.memory.deriver.QueueStats;
import at.aimon.core.memory.dialectic.DialecticEngine;
import at.aimon.core.memory.dialectic.DialecticQuery;
import at.aimon.core.memory.dialectic.DialecticResponse;

/**
 * The CLI's memory wiring, now expressed as a {@link MemorySpec} instead of nine hand-written methods.
 *
 * <p>
 * What is worth asserting is that the migration did not quietly narrow anything. The CLI wired all five capabilities
 * before it went through {@code MemoryAssembly} and it still does, so nothing it used to register is now missing and
 * no memory degradation appears where there was none — the CLI has always had a write path, it simply never passed
 * through the place that reports on one.
 */
@DisplayName("AgentSetupFactory memory spec")
class AgentSetupFactoryMemorySpecTest {

    private AgentSetupFactory factory;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        factory = new AgentSetupFactory();
    }

    private MemoryConfig enabledConfig() {
        final MemoryConfig config = new MemoryConfig();
        config.setWorkspaceId("ws-test");
        config.setPeerId("peer-test");
        config.setStoragePath(tempDir.resolve("representations.jsonl").toString());
        config.setBackend(MemoryConfig.BACKEND_IN_MEMORY);
        return config;
    }

    private MemorySpec specFor(MemoryConfig config, DerivationQueueManager queue) {
        return factory.buildMemorySpec(factory.buildMemoryWiring(config), new InMemoryRepresentationStore(),
                new InMemoryObservationStore(), new StubEngine(), queue);
    }

    @Test
    @DisplayName("memory off produces no spec at all, rather than one that wires nothing")
    void disabledMemoryProducesNoSpec() {
        assertThat(factory.buildMemorySpec(MemoryWiring.disabled(), null, null, null, null)).isNull();
    }

    @Test
    @DisplayName("the CLI's backend serves all five capabilities, so nothing it used to register is dropped")
    void allFiveCapabilitiesAreWired() {
        final MemorySpec spec = specFor(enabledConfig(), new NoopQueue());

        assertThat(spec.getPeerMemory()).isPresent();
        assertThat(MemoryCapabilities.of(spec.getPeerMemory().orElseThrow()))
                .containsExactlyInAnyOrder(MemoryCapability.values());
    }

    @Test
    @DisplayName("the spec carries the configured peer, SUMMARY_ONLY injection and a redaction policy")
    void specCarriesTheCliDefaults() {
        final MemorySpec spec = specFor(enabledConfig(), new NoopQueue());

        assertThat(spec.getWorkspace().getId()).isEqualTo("ws-test");
        assertThat(spec.getFixedPeer()).get().extracting(peer -> peer.getId()).isEqualTo("peer-test");
        assertThat(spec.isPerCaller()).isFalse();
        assertThat(spec.getInjectionMode()).isEqualTo(MemoryInjectionMode.SUMMARY_ONLY);
        assertThat(spec.getMaxTokens()).isZero();
        assertThat(spec.getRedactionPolicy()).isPresent();
    }

    @Test
    @DisplayName("without a queue the backend loses only INGEST, and the assembly names that loss")
    void aMissingQueueCostsExactlyIngest() {
        final MemorySpec spec = specFor(enabledConfig(), null);
        final RuntimeDegradations.Collector degradations = RuntimeDegradations.collector();

        MemoryAssembly.from(spec, degradations);

        final RuntimeDegradations recorded = degradations.build();
        assertThat(recorded.has(MemoryAssembly.CAPABILITY_INGEST)).isTrue();
        assertThat(recorded.has(MemoryAssembly.CAPABILITY_SNAPSHOT)).isFalse();
        assertThat(recorded.has(MemoryAssembly.CAPABILITY_CHAT)).isFalse();
    }

    @Test
    @DisplayName("the CLI's ingestor reaches the derivation queue it was assembled over")
    void ingestorReachesTheQueue() {
        final NoopQueue queue = new NoopQueue();
        final MemoryAssembly assembly = MemoryAssembly.from(specFor(enabledConfig(), queue),
                RuntimeDegradations.collector());
        final Workspace workspace = Workspace.builder().id("ws-test").build();

        final MemoryIngestReceipt receipt = assembly.getIngestor().orElseThrow()
                .ingest(MemoryIngestRequest.builder()
                        .observer(PeerView.of(workspace, Principal.user("peer-test", "peer-test"))).sessionId("s-1")
                        .messages(List.of(Message.user("hello"))).build());

        assertThat(receipt.getAccepted()).isEqualTo(1);
        assertThat(queue.enqueued).isEqualTo(1);
    }

    @Test
    @DisplayName("assembling the spec produces the injection provider, the enricher and the tool provider, with the"
            + " backend wrapped for redaction")
    void assemblyProducesEverythingTheCliUsedToWireByHand() {
        final RuntimeDegradations.Collector degradations = RuntimeDegradations.collector();

        final MemoryAssembly assembly = MemoryAssembly.from(specFor(enabledConfig(), new NoopQueue()), degradations);

        assertThat(assembly.getContextProvider()).isPresent();
        assertThat(assembly.getContextEnricher()).isPresent();
        assertThat(assembly.getToolProvider()).isPresent();
        assertThat(assembly.getPeerMemory()).get().isInstanceOf(RedactingPeerMemory.class);
        // No memory degradations: every capability is served and a redaction policy is configured.
        assertThat(degradations.build().isEmpty()).isTrue();
    }

    /** Counts what reached the queue, without starting a worker pool. */
    private static final class NoopQueue implements DerivationQueueManager {

        private int enqueued;

        @Override
        public void enqueue(DerivationTask task) {
            enqueued++;
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
            return QueueStats.of(0, 0, 0L, 0L);
        }
    }

    private static final class StubEngine implements DialecticEngine {
        @Override
        public DialecticResponse query(DialecticQuery query) {
            return DialecticResponse.builder().answer("stub").build();
        }
    }

}
