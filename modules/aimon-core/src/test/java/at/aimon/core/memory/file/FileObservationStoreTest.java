package at.aimon.core.memory.file;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.base.Principal;
import at.aimon.core.memory.Observation;
import at.aimon.core.memory.ObservationId;
import at.aimon.core.memory.ObservationType;
import at.aimon.core.memory.PeerView;
import at.aimon.core.memory.Workspace;

@DisplayName("FileObservationStore")
class FileObservationStoreTest {

    private static final Workspace WS = Workspace.builder().id("ws-1").build();
    private static final PeerView SUBJECT = PeerView.of(WS, Principal.user("alice", "Alice"));
    private static final PeerView OBSERVER = PeerView.of(WS, Principal.system());

    @TempDir
    Path tmp;

    @Test
    @DisplayName("save → reopen → findById returns the observation")
    void saveAndReplay() {
        Path log = tmp.resolve("obs.jsonl");

        FileObservationStore writer = new FileObservationStore(log,
                new at.aimon.core.memory.index.InMemoryObservationIndex(), false);
        writer.save(observation("o-1", "alice likes tea", 0.7d));

        writer.close(); // release the lock so the "reopen" (a fresh process) can acquire it
        FileObservationStore reader = new FileObservationStore(log,
                new at.aimon.core.memory.index.InMemoryObservationIndex(), false);
        assertThat(reader.findById(ObservationId.of(WS, "o-1"))).isPresent();
        assertThat(reader.findById(ObservationId.of(WS, "o-1")).get().getContent()).isEqualTo("alice likes tea");
    }

    @Test
    @DisplayName("delete is replayed; the observation is gone after reopen")
    void deleteReplay() {
        Path log = tmp.resolve("obs.jsonl");

        FileObservationStore writer = new FileObservationStore(log,
                new at.aimon.core.memory.index.InMemoryObservationIndex(), false);
        writer.save(observation("o-1", "first", 0.5d));
        writer.save(observation("o-2", "second", 0.5d));
        writer.delete(ObservationId.of(WS, "o-1"));

        writer.close(); // release the lock so the "reopen" (a fresh process) can acquire it
        FileObservationStore reader = new FileObservationStore(log,
                new at.aimon.core.memory.index.InMemoryObservationIndex(), false);
        assertThat(reader.findById(ObservationId.of(WS, "o-1"))).isEmpty();
        assertThat(reader.findById(ObservationId.of(WS, "o-2"))).isPresent();
        assertThat(reader.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("merge replay: loser dropped, winner replaced by merged content")
    void mergeReplay() {
        Path log = tmp.resolve("obs.jsonl");

        FileObservationStore writer = new FileObservationStore(log,
                new at.aimon.core.memory.index.InMemoryObservationIndex(), false);
        Observation winner = observation("w", "winner content", 0.9d);
        Observation loser = observation("l", "loser content", 0.4d);
        writer.save(winner);
        writer.save(loser);

        Observation merged = Observation.builder().id(winner.getId()).subject(SUBJECT).observer(OBSERVER)
                .content("merged content").type(ObservationType.EXPLICIT).createdAt(Instant.now()).confidence(0.95d)
                .build();
        writer.merge(winner.getId(), loser.getId(), merged);

        writer.close(); // release the lock so the "reopen" (a fresh process) can acquire it
        FileObservationStore reader = new FileObservationStore(log,
                new at.aimon.core.memory.index.InMemoryObservationIndex(), false);
        assertThat(reader.size()).isEqualTo(1);
        assertThat(reader.findById(loser.getId())).isEmpty();
        assertThat(reader.findById(winner.getId())).isPresent();
        assertThat(reader.findById(winner.getId()).get().getContent()).isEqualTo("merged content");
        assertThat(reader.findById(winner.getId()).get().getConfidence()).isEqualTo(0.95d);
    }

    @Test
    @DisplayName("findBySubject orders newest first and honours limit")
    void findBySubjectOrdered() {
        FileObservationStore store = new FileObservationStore(tmp.resolve("obs.jsonl"),
                new at.aimon.core.memory.index.InMemoryObservationIndex(), false);
        Instant base = Instant.parse("2025-01-01T00:00:00Z");
        store.save(observationAt("a", "first", 0.5d, base));
        store.save(observationAt("b", "second", 0.5d, base.plusSeconds(10)));
        store.save(observationAt("c", "third", 0.5d, base.plusSeconds(20)));

        List<Observation> top2 = store.findBySubject(SUBJECT, 2);
        assertThat(top2).extracting(o -> o.getId().getLocalId()).containsExactly("c", "b");
    }

    @Test
    @DisplayName("count + findSubjects + findByConfidenceBelow")
    void aggregates() {
        FileObservationStore store = new FileObservationStore(tmp.resolve("obs.jsonl"),
                new at.aimon.core.memory.index.InMemoryObservationIndex(), false);
        store.save(observation("a", "1", 0.3d));
        store.save(observation("b", "2", 0.6d));
        store.save(observation("c", "3", 0.9d));

        assertThat(store.count(SUBJECT)).isEqualTo(3);
        assertThat(store.findSubjects(WS, 10)).containsExactly(SUBJECT);
        assertThat(store.findByConfidenceBelow(SUBJECT, 0.7d, 10)).extracting(o -> o.getId().getLocalId())
                .containsExactly("a", "b");
    }

    @Test
    @DisplayName("metadata + sourceMessageIds round-trip")
    void roundTripMetadata() {
        Path log = tmp.resolve("obs.jsonl");

        FileObservationStore writer = new FileObservationStore(log,
                new at.aimon.core.memory.index.InMemoryObservationIndex(), false);
        Observation obs = Observation.builder().id(ObservationId.of(WS, "o-1")).subject(SUBJECT).observer(OBSERVER)
                .content("x").type(ObservationType.EXPLICIT).createdAt(Instant.parse("2025-01-01T00:00:00Z"))
                .confidence(0.5d).sourceMessageIds(List.of("msg-1", "msg-2")).metadata(Map.of("k1", "v1", "k2", "v2"))
                .build();
        writer.save(obs);

        writer.close(); // release the lock so the "reopen" (a fresh process) can acquire it
        FileObservationStore reader = new FileObservationStore(log,
                new at.aimon.core.memory.index.InMemoryObservationIndex(), false);
        Observation read = reader.findById(obs.getId()).orElseThrow();
        assertThat(read.getSourceMessageIds()).containsExactly("msg-1", "msg-2");
        assertThat(read.getMetadata()).containsEntry("k1", "v1").containsEntry("k2", "v2");
    }

    @Test
    @DisplayName("merge soft-deletes the loser into a replay-durable audit window")
    void mergeSoftDeleteReplay() {
        Path log = tmp.resolve("obs.jsonl");

        FileObservationStore writer = new FileObservationStore(log,
                new at.aimon.core.memory.index.InMemoryObservationIndex(), false);
        writer.save(observation("win", "winner", 0.5d));
        writer.save(observation("lose", "loser", 0.5d));
        writer.merge(ObservationId.of(WS, "win"), ObservationId.of(WS, "lose"), observation("win", "merged", 0.9d));

        assertThat(writer.findById(ObservationId.of(WS, "lose"))).isEmpty();
        assertThat(writer.size()).isEqualTo(1);
        assertThat(writer.auditSize()).isEqualTo(1);

        // The audit tombstone survives a restart (replayed from the log).
        writer.close(); // release the lock so the "reopen" (a fresh process) can acquire it
        FileObservationStore reader = new FileObservationStore(log,
                new at.aimon.core.memory.index.InMemoryObservationIndex(), false);
        assertThat(reader.findById(ObservationId.of(WS, "lose"))).isEmpty();
        assertThat(reader.size()).isEqualTo(1);
        assertThat(reader.auditSize()).isEqualTo(1);
    }

    @Test
    @DisplayName("softDelete and purge are replay-durable")
    void softDeleteAndPurgeReplay() {
        Path log = tmp.resolve("obs.jsonl");

        FileObservationStore writer = new FileObservationStore(log,
                new at.aimon.core.memory.index.InMemoryObservationIndex(), false);
        writer.save(observation("o-1", "x", 0.5d));
        writer.softDelete(ObservationId.of(WS, "o-1"));
        assertThat(writer.auditSize()).isEqualTo(1);

        // Purge with a future cutoff removes the audit entry and is itself replay-durable.
        assertThat(writer.purgeSoftDeletedBefore(WS, Instant.now().plusSeconds(60))).isEqualTo(1);
        assertThat(writer.auditSize()).isZero();

        writer.close(); // release the lock so the "reopen" (a fresh process) can acquire it
        FileObservationStore reader = new FileObservationStore(log,
                new at.aimon.core.memory.index.InMemoryObservationIndex(), false);
        assertThat(reader.findById(ObservationId.of(WS, "o-1"))).isEmpty();
        assertThat(reader.size()).isZero();
        assertThat(reader.auditSize()).isZero();
    }

    @Test
    @DisplayName("compact() shrinks the log to live + audit and survives reopen")
    void compaction() throws Exception {
        Path log = tmp.resolve("obs.jsonl");
        FileObservationStore writer = new FileObservationStore(log,
                new at.aimon.core.memory.index.InMemoryObservationIndex(), false);
        for (int i = 0; i < 50; i++) {
            writer.save(observation("o-" + i, "content-" + i, 0.5d));
        }
        for (int i = 0; i < 40; i++) {
            writer.softDelete(ObservationId.of(WS, "o-" + i)); // 40 soft-deleted, 10 live
        }
        long before = countNonBlankLines(log);

        writer.compact();
        long after = countNonBlankLines(log);

        assertThat(after).isLessThan(before);
        assertThat(after).isEqualTo(50L); // 10 live SAVE + 40 audit SOFT_DELETE lines
        assertThat(writer.size()).isEqualTo(10);
        assertThat(writer.auditSize()).isEqualTo(40);
        writer.close();

        // The compacted log replays back to the same live + audit state (SOFT_DELETE lines carry the obs).
        FileObservationStore reader = new FileObservationStore(log,
                new at.aimon.core.memory.index.InMemoryObservationIndex(), false);
        assertThat(reader.size()).isEqualTo(10);
        assertThat(reader.auditSize()).isEqualTo(40);
        reader.close();
    }

    private static long countNonBlankLines(Path p) throws Exception {
        try (java.util.stream.Stream<String> lines = Files.lines(p)) {
            return lines.filter(l -> !l.isBlank()).count();
        }
    }

    private static Observation observation(String localId, String content, double confidence) {
        return observationAt(localId, content, confidence, Instant.parse("2025-01-15T10:00:00Z"));
    }

    private static Observation observationAt(String localId, String content, double confidence, Instant createdAt) {
        return Observation.builder().id(ObservationId.of(WS, localId)).subject(SUBJECT).observer(OBSERVER)
                .content(content).type(ObservationType.EXPLICIT).createdAt(createdAt).confidence(confidence).build();
    }
}
