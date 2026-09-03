package at.aimon.core.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.memory.dialectic.DialecticEngine;
import at.aimon.core.memory.dialectic.DialecticQuery;
import at.aimon.core.memory.dialectic.DialecticResponse;

/**
 * Capabilities are derived from the tier accessors, and there is no second place they could come from.
 *
 * <p>
 * The backend below is written to be as dishonest as the SPI permits — and the point of these tests is that the most
 * dishonest thing it can do is return an empty {@link Optional}, which is not dishonesty but an accurate report.
 */
@DisplayName("MemoryCapabilities")
class MemoryCapabilitiesTest {

    @Test
    @DisplayName("a backend with no tiers has no capabilities, and all five are missing")
    void emptyBackend() {
        PeerMemory backend = new ConfigurableBackend(false, false, false, false, false);

        assertThat(MemoryCapabilities.of(backend)).isEmpty();
        assertThat(MemoryCapabilities.missingFrom(backend)).containsExactlyInAnyOrder(MemoryCapability.values());
    }

    @Test
    @DisplayName("a backend with every tier has every capability, and none is missing")
    void fullBackend() {
        PeerMemory backend = new ConfigurableBackend(true, true, true, true, true);

        assertThat(MemoryCapabilities.of(backend)).containsExactlyInAnyOrder(MemoryCapability.values());
        assertThat(MemoryCapabilities.missingFrom(backend)).isEmpty();
    }

    @Test
    @DisplayName("each capability tracks exactly its own accessor")
    void oneAccessorPerCapability() {
        assertThat(MemoryCapabilities.of(new ConfigurableBackend(true, false, false, false, false)))
                .containsExactly(MemoryCapability.SNAPSHOT);
        assertThat(MemoryCapabilities.of(new ConfigurableBackend(false, true, false, false, false)))
                .containsExactly(MemoryCapability.SEARCH);
        assertThat(MemoryCapabilities.of(new ConfigurableBackend(false, false, true, false, false)))
                .containsExactly(MemoryCapability.CHAT);
        assertThat(MemoryCapabilities.of(new ConfigurableBackend(false, false, false, true, false)))
                .containsExactly(MemoryCapability.OBSERVE);
        assertThat(MemoryCapabilities.of(new ConfigurableBackend(false, false, false, false, true)))
                .containsExactly(MemoryCapability.INGEST);
    }

    @Test
    @DisplayName("of() and missingFrom() partition the five capabilities between them")
    void presentAndMissingPartition() {
        PeerMemory backend = new ConfigurableBackend(true, false, true, false, true);

        assertThat(MemoryCapabilities.of(backend)).containsExactlyInAnyOrder(MemoryCapability.SNAPSHOT,
                MemoryCapability.CHAT, MemoryCapability.INGEST);
        assertThat(MemoryCapabilities.missingFrom(backend)).containsExactlyInAnyOrder(MemoryCapability.SEARCH,
                MemoryCapability.OBSERVE);
    }

    @Test
    @DisplayName("the returned set is immutable — a caller cannot edit what a backend can do")
    void resultIsImmutable() {
        var capabilities = MemoryCapabilities.of(new ConfigurableBackend(true, false, false, false, false));

        assertThatThrownBy(() -> capabilities.add(MemoryCapability.CHAT))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    /** A backend whose tiers can be switched on and off one at a time. */
    private static final class ConfigurableBackend implements PeerMemory {

        private final boolean snapshot;
        private final boolean search;
        private final boolean chat;
        private final boolean observe;
        private final boolean ingest;

        ConfigurableBackend(boolean snapshot, boolean search, boolean chat, boolean observe, boolean ingest) {
            this.snapshot = snapshot;
            this.search = search;
            this.chat = chat;
            this.observe = observe;
            this.ingest = ingest;
        }

        @Override
        public String backendId() {
            return "configurable";
        }

        @Override
        public Optional<MemorySnapshotReader> snapshotReader() {
            return snapshot ? Optional.of(query -> Optional.empty()) : Optional.empty();
        }

        @Override
        public Optional<MemorySearcher> searcher() {
            return search ? Optional.of(new NoHitSearcher()) : Optional.empty();
        }

        @Override
        public Optional<DialecticEngine> dialecticEngine() {
            return chat ? Optional.of(new SilentEngine()) : Optional.empty();
        }

        @Override
        public Optional<ObservationRecorder> observationRecorder() {
            return observe ? Optional.of(new RefusingRecorder()) : Optional.empty();
        }

        @Override
        public Optional<MemoryIngestor> ingestor() {
            return ingest
                    ? Optional.of(request -> MemoryIngestReceipt.builder().accepted(0).build())
                    : Optional.empty();
        }
    }

    private static final class NoHitSearcher implements MemorySearcher {
        @Override
        public List<MemoryHit> search(MemorySearchQuery query) {
            return List.of();
        }

        @Override
        public boolean ranksByScore() {
            return false;
        }

        @Override
        public boolean narrowsBySession() {
            return false;
        }
    }

    private static final class SilentEngine implements DialecticEngine {
        @Override
        public DialecticResponse query(DialecticQuery query) {
            return DialecticResponse.builder().answer("").build();
        }
    }

    private static final class RefusingRecorder implements ObservationRecorder {
        @Override
        public Observation observe(ObservationDraft draft) {
            throw new UnsupportedOperationException("not used in this test");
        }

        @Override
        public boolean storesConfidence() {
            return false;
        }
    }
}
