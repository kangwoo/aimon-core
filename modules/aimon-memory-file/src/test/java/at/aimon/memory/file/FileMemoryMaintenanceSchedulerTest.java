package at.aimon.memory.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
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
import at.aimon.core.memory.index.InMemoryObservationIndex;

@DisplayName("FileMemoryMaintenanceScheduler")
class FileMemoryMaintenanceSchedulerTest {

    @TempDir
    Path tmp;

    @Test
    @DisplayName("runOnce purges soft-deleted observations and prunes old representations past retention")
    void runOncePurgesAndPrunes() throws Exception {
        FileWorkspaceStore workspaceStore = new FileWorkspaceStore(tmp.resolve("ws.jsonl"), false);
        FileObservationStore observationStore = new FileObservationStore(tmp.resolve("obs.jsonl"),
                new InMemoryObservationIndex(), false);
        FileRepresentationStore representationStore = new FileRepresentationStore(tmp.resolve("rep.jsonl"), false);
        try {
            Workspace workspace = Workspace.builder().id("ws-1").build();
            workspaceStore.create(workspace);
            PeerView alice = PeerView.of(workspace, Principal.user("alice", "Alice"));

            Observation obs = Observation.builder().id(ObservationId.of(workspace, "o-1")).subject(alice)
                    .observer(alice).content("x").type(ObservationType.EXPLICIT).createdAt(Instant.now())
                    .confidence(0.7d).build();
            observationStore.save(obs);
            observationStore.softDelete(obs.getId());
            assertThat(observationStore.auditSize()).isEqualTo(1);

            representationStore
                    .save(Representation.builder().subject(alice).summary("old").generatedAt(Instant.now()).build());
            assertThat(representationStore.size()).isEqualTo(1);

            // Tiny retention windows so anything created a moment ago is already past retention.
            Thread.sleep(10);
            FileMemoryMaintenanceScheduler maintenance = new FileMemoryMaintenanceScheduler(workspaceStore,
                    observationStore, representationStore,
                    List.of(workspaceStore, observationStore, representationStore), Duration.ofNanos(1),
                    Duration.ofNanos(1), Duration.ofHours(1));

            maintenance.runOnce();

            assertThat(observationStore.auditSize()).isZero(); // soft-deleted observation purged
            assertThat(representationStore.size()).isZero(); // old representation pruned
        } finally {
            workspaceStore.close();
            observationStore.close();
            representationStore.close();
        }
    }

    @Test
    @DisplayName("constructor rejects non-positive retention/interval")
    void rejectsBadConfig() {
        FileWorkspaceStore ws = new FileWorkspaceStore(tmp.resolve("ws.jsonl"), false);
        FileObservationStore obs = new FileObservationStore(tmp.resolve("obs.jsonl"), new InMemoryObservationIndex(),
                false);
        FileRepresentationStore rep = new FileRepresentationStore(tmp.resolve("rep.jsonl"), false);
        try {
            assertThatThrownBy(() -> new FileMemoryMaintenanceScheduler(ws, obs, rep, List.of(ws, obs, rep),
                    Duration.ZERO, Duration.ofDays(1), Duration.ofHours(1)))
                    .isInstanceOf(IllegalArgumentException.class);
        } finally {
            ws.close();
            obs.close();
            rep.close();
        }
    }
}
