package at.aimon.core.memory.file;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.base.Principal;
import at.aimon.core.memory.Observation;
import at.aimon.core.memory.ObservationId;
import at.aimon.core.memory.ObservationType;
import at.aimon.core.memory.PeerView;
import at.aimon.core.memory.Representation;
import at.aimon.core.memory.Workspace;

@DisplayName("FileRepresentationStore")
class FileRepresentationStoreTest {

    private static final Instant T0 = Instant.parse("2025-01-15T10:00:00Z");
    private static final Workspace WS = Workspace.builder().id("ws-1").build();
    private static final PeerView ALICE = PeerView.of(WS, Principal.user("alice", "Alice"));
    private static final PeerView BOB = PeerView.of(WS, Principal.user("bob", "Bob"));

    @TempDir
    Path tmp;

    @Test
    @DisplayName("save → reopen → findLatestGlobal returns the persisted representation")
    void globalRoundTrip() {
        Path log = tmp.resolve("rep.jsonl");

        FileRepresentationStore writer = new FileRepresentationStore(log, false);
        writer.save(global(ALICE, "v1", T0));
        writer.save(global(ALICE, "v2", T0.plusSeconds(60)));

        writer.close(); // release the lock so the "reopen" (a fresh process) can acquire it
        FileRepresentationStore reader = new FileRepresentationStore(log, false);
        assertThat(reader.findLatestGlobal(ALICE)).isPresent();
        assertThat(reader.findLatestGlobal(ALICE).get().getSummary()).isEqualTo("v2");
        assertThat(reader.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("findLatestLocal honours observer + sessionId on reopen")
    void localRoundTrip() {
        Path log = tmp.resolve("rep.jsonl");

        FileRepresentationStore writer = new FileRepresentationStore(log, false);
        writer.save(local(ALICE, BOB, "sess-1", T0));
        writer.save(local(ALICE, BOB, "sess-1", T0.plusSeconds(60)));
        writer.save(local(ALICE, BOB, "sess-2", T0.plusSeconds(120)));

        writer.close(); // release the lock so the "reopen" (a fresh process) can acquire it
        FileRepresentationStore reader = new FileRepresentationStore(log, false);
        assertThat(reader.findLatestLocal(ALICE, BOB, "sess-1")).isPresent();
        assertThat(reader.findLatestLocal(ALICE, BOB, "sess-1").get().getGeneratedAt()).isEqualTo(T0.plusSeconds(60));
        assertThat(reader.findLatestLocal(ALICE, BOB, "sess-2").get().getGeneratedAt()).isEqualTo(T0.plusSeconds(120));
    }

    @Test
    @DisplayName("findLatestLocal with null sessionId matches null-session entries only")
    void localNullSession() {
        FileRepresentationStore store = new FileRepresentationStore(tmp.resolve("rep.jsonl"), false);
        store.save(local(ALICE, BOB, "sess-1", T0));
        store.save(local(ALICE, BOB, null, T0.plusSeconds(60)));
        store.save(local(ALICE, BOB, null, T0.plusSeconds(120)));

        assertThat(store.findLatestLocal(ALICE, BOB, null)).isPresent();
        assertThat(store.findLatestLocal(ALICE, BOB, null).get().getGeneratedAt()).isEqualTo(T0.plusSeconds(120));
    }

    @Test
    @DisplayName("deleteOlderThan tombstone is replayed on reopen")
    void deleteOlderThanReplay() {
        Path log = tmp.resolve("rep.jsonl");

        FileRepresentationStore writer = new FileRepresentationStore(log, false);
        writer.save(global(ALICE, "old", T0));
        writer.save(global(ALICE, "boundary", T0.plusSeconds(100)));
        writer.save(global(ALICE, "new", T0.plusSeconds(200)));
        writer.deleteOlderThan(WS, T0.plusSeconds(100));

        writer.close(); // release the lock so the "reopen" (a fresh process) can acquire it
        FileRepresentationStore reader = new FileRepresentationStore(log, false);
        assertThat(reader.size()).isEqualTo(2);
        assertThat(reader.findLatestGlobal(ALICE).get().getSummary()).isEqualTo("new");
    }

    @Test
    @DisplayName("deleteOlderThan with no matching rows is a silent no-op (no log line written)")
    void deleteOlderThanNoOp() {
        Path log = tmp.resolve("rep.jsonl");
        FileRepresentationStore store = new FileRepresentationStore(log, false);
        store.save(global(ALICE, "future", T0.plusSeconds(1000)));

        store.deleteOlderThan(WS, T0);

        store.close(); // release the lock so the "reopen" (a fresh process) can acquire it
        FileRepresentationStore reader = new FileRepresentationStore(log, false);
        assertThat(reader.size()).isEqualTo(1);
        assertThat(reader.findLatestGlobal(ALICE).get().getSummary()).isEqualTo("future");
    }

    @Test
    @DisplayName("deleteOlderThan isolates by workspace")
    void deleteOlderThanWorkspaceIsolation() {
        Workspace ws2 = Workspace.builder().id("ws-2").build();
        PeerView aliceWs2 = PeerView.of(ws2, Principal.user("alice", "Alice"));

        FileRepresentationStore store = new FileRepresentationStore(tmp.resolve("rep.jsonl"), false);
        store.save(global(ALICE, "ws1", T0));
        store.save(global(aliceWs2, "ws2", T0));

        store.deleteOlderThan(WS, T0.plusSeconds(60));

        assertThat(store.size()).isEqualTo(1);
        assertThat(store.findLatestGlobal(aliceWs2)).isPresent();
        assertThat(store.findLatestGlobal(ALICE)).isEmpty();
    }

    @Test
    @DisplayName("observations + tokenCount + summary round-trip through the log")
    void payloadRoundTrip() {
        Path log = tmp.resolve("rep.jsonl");
        Observation obs = Observation.builder().id(ObservationId.of(WS, "o-1")).subject(ALICE)
                .observer(PeerView.of(WS, Principal.system())).content("alice likes tea").type(ObservationType.EXPLICIT)
                .createdAt(T0).confidence(0.8d).build();

        FileRepresentationStore writer = new FileRepresentationStore(log, false);
        writer.save(Representation.builder().subject(ALICE).observations(List.of(obs)).summary("rich summary")
                .generatedAt(T0).tokenCount(42).build());

        writer.close(); // release the lock so the "reopen" (a fresh process) can acquire it
        FileRepresentationStore reader = new FileRepresentationStore(log, false);
        Representation read = reader.findLatestGlobal(ALICE).orElseThrow();
        assertThat(read.getSummary()).isEqualTo("rich summary");
        assertThat(read.getTokenCount()).isEqualTo(42);
        assertThat(read.getObservations()).hasSize(1);
        assertThat(read.getObservations().get(0).getContent()).isEqualTo("alice likes tea");
    }

    private static Representation global(PeerView subject, String summary, Instant generatedAt) {
        return Representation.builder().subject(subject).summary(summary).generatedAt(generatedAt).build();
    }

    private static Representation local(PeerView subject, PeerView observer, String sessionId, Instant generatedAt) {
        return Representation.builder().subject(subject).observer(observer).sessionId(sessionId)
                .generatedAt(generatedAt).build();
    }
}
