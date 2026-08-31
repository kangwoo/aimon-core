/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.memory.mongodb;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import at.aimon.core.base.Principal;
import at.aimon.core.memory.Observation;
import at.aimon.core.memory.ObservationId;
import at.aimon.core.memory.ObservationType;
import at.aimon.core.memory.PeerView;
import at.aimon.core.memory.Representation;
import at.aimon.core.memory.Workspace;

@DisplayName("MongoRepresentationStore integration")
@Tag("docker")
class MongoRepresentationStoreIntegrationTest {

    private static final Instant T0 = Instant.parse("2025-01-15T10:00:00Z");
    private static final Workspace WS = Workspace.builder().id("ws-1").build();
    private static final PeerView ALICE = PeerView.of(WS, Principal.user("alice", "Alice"));
    private static final PeerView BOB = PeerView.of(WS, Principal.user("bob", "Bob"));

    private MongoRepresentationStore store;

    @BeforeEach
    void setUp() {
        MongoMemoryTestSupport.dropAndApplyDdl();
        store = new MongoRepresentationStore(MongoMemoryTestSupport.sharedDatabase());
    }

    @Test
    @DisplayName("findLatestGlobal returns the newest global snapshot")
    void latestGlobal() {
        store.save(global(ALICE, "v1", T0));
        store.save(global(ALICE, "v2", T0.plusSeconds(60)));

        assertThat(store.findLatestGlobal(ALICE)).isPresent();
        assertThat(store.findLatestGlobal(ALICE).orElseThrow().getSummary()).isEqualTo("v2");
    }

    @Test
    @DisplayName("findLatestLocal honours observer + sessionId")
    void latestLocal() {
        store.save(local(ALICE, BOB, "sess-1", T0));
        store.save(local(ALICE, BOB, "sess-1", T0.plusSeconds(60)));
        store.save(local(ALICE, BOB, "sess-2", T0.plusSeconds(120)));

        assertThat(store.findLatestLocal(ALICE, BOB, "sess-1").orElseThrow().getGeneratedAt())
                .isEqualTo(T0.plusSeconds(60));
        assertThat(store.findLatestLocal(ALICE, BOB, "sess-2").orElseThrow().getGeneratedAt())
                .isEqualTo(T0.plusSeconds(120));
    }

    @Test
    @DisplayName("findLatestLocal with null sessionId matches only null-session snapshots")
    void latestLocalNullSession() {
        store.save(local(ALICE, BOB, "sess-1", T0));
        store.save(local(ALICE, BOB, null, T0.plusSeconds(60)));

        assertThat(store.findLatestLocal(ALICE, BOB, null).orElseThrow().getGeneratedAt())
                .isEqualTo(T0.plusSeconds(60));
    }

    @Test
    @DisplayName("global and local queries do not cross over")
    void globalLocalSeparation() {
        store.save(global(ALICE, "global", T0.plusSeconds(200)));
        store.save(local(ALICE, BOB, "sess-1", T0.plusSeconds(300)));

        assertThat(store.findLatestGlobal(ALICE).orElseThrow().getSummary()).isEqualTo("global");
        assertThat(store.findLatestLocal(ALICE, BOB, "sess-1").orElseThrow().isLocal()).isTrue();
    }

    @Test
    @DisplayName("observations + tokenCount + summary round-trip")
    void payloadRoundTrip() {
        Observation obs = Observation.builder().id(ObservationId.of(WS, "o-1")).subject(ALICE)
                .observer(PeerView.of(WS, Principal.system())).content("alice likes tea").type(ObservationType.EXPLICIT)
                .confidence(0.8d).createdAt(T0).build();
        store.save(Representation.builder().subject(ALICE).observations(List.of(obs)).summary("rich").generatedAt(T0)
                .tokenCount(42).build());

        Representation read = store.findLatestGlobal(ALICE).orElseThrow();
        assertThat(read.getSummary()).isEqualTo("rich");
        assertThat(read.getTokenCount()).isEqualTo(42);
        assertThat(read.getObservations()).hasSize(1);
        assertThat(read.getObservations().get(0).getContent()).isEqualTo("alice likes tea");
    }

    @Test
    @DisplayName("deleteOlderThan prunes by generatedAt and isolates by workspace")
    void deleteOlderThan() {
        store.save(global(ALICE, "old", T0));
        store.save(global(ALICE, "new", T0.plusSeconds(200)));

        store.deleteOlderThan(WS, T0.plusSeconds(100));

        assertThat(store.findLatestGlobal(ALICE).orElseThrow().getSummary()).isEqualTo("new");
    }

    private static Representation global(PeerView subject, String summary, Instant generatedAt) {
        return Representation.builder().subject(subject).summary(summary).generatedAt(generatedAt).build();
    }

    private static Representation local(PeerView subject, PeerView observer, String sessionId, Instant generatedAt) {
        return Representation.builder().subject(subject).observer(observer).sessionId(sessionId)
                .generatedAt(generatedAt).build();
    }
}
