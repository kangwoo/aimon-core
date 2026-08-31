package at.aimon.cli.factory;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.cli.config.CliSettings;
import at.aimon.cli.config.MemoryConfig;
import at.aimon.cli.factory.AgentSetupFactory.MemoryWiring;
import at.aimon.cli.repl.OutputFormatter;
import at.aimon.core.base.Principal;
import at.aimon.core.memory.InMemoryObservationStore;
import at.aimon.core.memory.InMemoryRepresentationStore;
import at.aimon.core.memory.Observation;
import at.aimon.core.memory.ObservationId;
import at.aimon.core.memory.ObservationStore;
import at.aimon.core.memory.ObservationType;
import at.aimon.core.memory.PeerView;
import at.aimon.core.memory.Workspace;
import at.aimon.memory.file.FileObservationStore;
import at.aimon.memory.file.FileRepresentationStore;

@DisplayName("AgentSetupFactory store-backend selection")
class AgentSetupFactoryStoreBackendTest {

    private AgentSetupFactory factory;
    private OutputFormatter outputFormatter;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        factory = new AgentSetupFactory();
        outputFormatter = new OutputFormatter(new CliSettings());
    }

    private MemoryConfig config(String backend) {
        final MemoryConfig config = new MemoryConfig();
        config.setWorkspaceId("ws-test");
        config.setPeerId("peer-test");
        config.setStoragePath(tempDir.resolve("representations.jsonl").toString());
        config.setBackend(backend);
        return config;
    }

    @Test
    @DisplayName("observation store is null when memory is disabled")
    void observationStoreNullWhenDisabled() {
        assertThat(factory.createObservationStore(null, MemoryWiring.disabled(), outputFormatter)).isNull();
    }

    @Test
    @DisplayName("default (null backend) wires a file-backed observation store that persists to a sibling file")
    void defaultBackendIsFileAndPersists() {
        final MemoryConfig config = config(null);
        final ObservationStore store = factory.createObservationStore(config, factory.buildMemoryWiring(config),
                outputFormatter);

        assertThat(store).isInstanceOf(FileObservationStore.class);

        final Workspace ws = Workspace.builder().id("ws-test").build();
        final PeerView peer = PeerView.of(ws, Principal.user("peer-test", "Peer"));
        store.save(
                Observation.builder().id(ObservationId.of(ws, "o1")).subject(peer).observer(peer).content("disk truth")
                        .type(ObservationType.EXPLICIT).confidence(0.9d).createdAt(Instant.now()).build());

        assertThat(Files.exists(tempDir.resolve("observations.jsonl"))).isTrue();
    }

    @Test
    @DisplayName("backend=in-memory wires non-durable stores")
    void inMemoryBackend() {
        final MemoryConfig config = config("in-memory");

        assertThat(factory.createObservationStore(config, factory.buildMemoryWiring(config), outputFormatter))
                .isInstanceOf(InMemoryObservationStore.class);
        assertThat(factory.createRepresentationStore(config, outputFormatter))
                .isInstanceOf(InMemoryRepresentationStore.class);
    }

    @Test
    @DisplayName("backend=file wires file-backed stores")
    void fileBackend() {
        final MemoryConfig config = config("file");

        assertThat(factory.createObservationStore(config, factory.buildMemoryWiring(config), outputFormatter))
                .isInstanceOf(FileObservationStore.class);
        assertThat(factory.createRepresentationStore(config, outputFormatter))
                .isInstanceOf(FileRepresentationStore.class);
    }

    @Test
    @DisplayName("unknown backend falls back to file")
    void unknownBackendFallsBackToFile() {
        final MemoryConfig config = config("cassandra");

        assertThat(factory.createObservationStore(config, factory.buildMemoryWiring(config), outputFormatter))
                .isInstanceOf(FileObservationStore.class);
    }

    @Test
    @DisplayName("representation store is null when config is disabled")
    void representationStoreNullWhenDisabled() {
        assertThat(factory.createRepresentationStore(null, outputFormatter)).isNull();
    }
}
