package at.aimon.memory.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import at.aimon.core.base.Principal;
import at.aimon.core.memory.Observation;
import at.aimon.core.memory.ObservationId;
import at.aimon.core.memory.ObservationType;
import at.aimon.core.memory.PeerView;
import at.aimon.core.memory.Representation;
import at.aimon.core.memory.Workspace;

/**
 * Integration tests for {@link PostgresRepresentationStore} against a real Postgres container.
 */
@DisplayName("PostgresRepresentationStore integration")
@Tag("docker")
class PostgresRepresentationStoreIntegrationTest {

    private static final String WS_A = "ws-a";
    private static final String WS_B = "ws-b";

    private DataSource dataSource;
    private PostgresRepresentationStore store;
    private Workspace workspaceA;
    private Workspace workspaceB;

    @BeforeEach
    void setUp() throws SQLException {
        PostgresTestSupport.truncateAll();
        dataSource = PostgresTestSupport.dataSource();
        store = new PostgresRepresentationStore(dataSource, new ObjectMapper());

        seedWorkspace(WS_A);
        seedWorkspace(WS_B);
        workspaceA = Workspace.builder().id(WS_A).displayName(WS_A).build();
        workspaceB = Workspace.builder().id(WS_B).displayName(WS_B).build();
    }

    @Test
    @DisplayName("save + findLatestGlobal round-trips a global representation")
    void saveAndFindLatestGlobal() {
        final PeerView subject = peer(workspaceA, "alice", Principal.Type.USER);
        final Instant now = nowMillis();
        final Representation rep = Representation.builder().subject(subject).summary("global summary about alice")
                .tokenCount(42).generatedAt(now).build();

        store.save(rep);

        final Optional<Representation> found = store.findLatestGlobal(subject);
        assertThat(found).isPresent();
        final Representation loaded = found.orElseThrow();
        assertThat(loaded.isGlobal()).isTrue();
        assertThat(loaded.getSubject()).isEqualTo(subject);
        assertThat(loaded.getSummary()).isEqualTo("global summary about alice");
        assertThat(loaded.getTokenCount()).isEqualTo(42);
        assertThat(loaded.getGeneratedAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("save + findLatestLocal round-trips with sessionId set")
    void saveAndFindLatestLocalWithSession() {
        final PeerView subject = peer(workspaceA, "alice", Principal.Type.USER);
        final PeerView observer = peer(workspaceA, "bob", Principal.Type.USER);
        final Instant now = nowMillis();
        final Representation rep = Representation.builder().subject(subject).observer(observer).sessionId("s-1")
                .summary("bob's view of alice in s-1").tokenCount(7).generatedAt(now).build();

        store.save(rep);

        final Optional<Representation> found = store.findLatestLocal(subject, observer, "s-1");
        assertThat(found).isPresent();
        final Representation loaded = found.orElseThrow();
        assertThat(loaded.isLocal()).isTrue();
        assertThat(loaded.getObserver()).isPresent();
        assertThat(loaded.getObserver().orElseThrow()).isEqualTo(observer);
        assertThat(loaded.getSessionId()).hasValue("s-1");
        assertThat(loaded.getSummary()).isEqualTo("bob's view of alice in s-1");
    }

    @Test
    @DisplayName("save + findLatestLocal round-trips with sessionId unset (cross-session)")
    void saveAndFindLatestLocalNullSession() {
        final PeerView subject = peer(workspaceA, "alice", Principal.Type.USER);
        final PeerView observer = peer(workspaceA, "bob", Principal.Type.USER);
        final Instant now = nowMillis();
        final Representation rep = Representation.builder().subject(subject).observer(observer).sessionId(null)
                .summary("cross-session view").tokenCount(0).generatedAt(now).build();

        store.save(rep);

        final Optional<Representation> nullSessionFound = store.findLatestLocal(subject, observer, null);
        assertThat(nullSessionFound).isPresent();
        assertThat(nullSessionFound.orElseThrow().getSessionId()).isEmpty();

        // Must NOT match a session-scoped lookup.
        assertThat(store.findLatestLocal(subject, observer, "s-1")).isEmpty();
    }

    @Test
    @DisplayName("findLatestGlobal returns the most recent snapshot when multiple exist")
    void findLatestGlobalPicksMostRecent() {
        final PeerView subject = peer(workspaceA, "alice", Principal.Type.USER);
        final Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        final Instant t1 = Instant.parse("2026-01-02T00:00:00Z");
        final Instant t2 = Instant.parse("2026-01-03T00:00:00Z");

        store.save(repGlobal(subject, "v0", t0));
        store.save(repGlobal(subject, "v2", t2));
        store.save(repGlobal(subject, "v1", t1));

        final Optional<Representation> found = store.findLatestGlobal(subject);
        assertThat(found).isPresent();
        assertThat(found.orElseThrow().getSummary()).isEqualTo("v2");
        assertThat(found.orElseThrow().getGeneratedAt()).isEqualTo(t2);
    }

    @Test
    @DisplayName("findLatestLocal requires observer match and respects sessionId nullability")
    void findLatestLocalScoping() {
        final PeerView subject = peer(workspaceA, "alice", Principal.Type.USER);
        final PeerView bob = peer(workspaceA, "bob", Principal.Type.USER);
        final PeerView carol = peer(workspaceA, "carol", Principal.Type.USER);
        final Instant now = nowMillis();

        store.save(Representation.builder().subject(subject).observer(bob).sessionId("s-1").summary("bob/s-1")
                .generatedAt(now).build());
        store.save(Representation.builder().subject(subject).observer(carol).sessionId("s-1").summary("carol/s-1")
                .generatedAt(now.plusSeconds(1)).build());
        store.save(Representation.builder().subject(subject).observer(bob).sessionId(null).summary("bob/null")
                .generatedAt(now.plusSeconds(2)).build());

        // Different observer — must not leak across.
        final Optional<Representation> bobS1 = store.findLatestLocal(subject, bob, "s-1");
        assertThat(bobS1).isPresent();
        assertThat(bobS1.orElseThrow().getSummary()).isEqualTo("bob/s-1");

        final Optional<Representation> carolS1 = store.findLatestLocal(subject, carol, "s-1");
        assertThat(carolS1).isPresent();
        assertThat(carolS1.orElseThrow().getSummary()).isEqualTo("carol/s-1");

        // sessionId nullability must be respected exactly.
        final Optional<Representation> bobNull = store.findLatestLocal(subject, bob, null);
        assertThat(bobNull).isPresent();
        assertThat(bobNull.orElseThrow().getSummary()).isEqualTo("bob/null");

        // Unknown session id → empty.
        assertThat(store.findLatestLocal(subject, bob, "s-unknown")).isEmpty();
    }

    @Test
    @DisplayName("findLatestLocal rejects observer in a different workspace")
    void findLatestLocalRejectsCrossWorkspaceObserver() {
        final PeerView subject = peer(workspaceA, "alice", Principal.Type.USER);
        final PeerView observerOther = peer(workspaceB, "bob", Principal.Type.USER);
        assertThatThrownBy(() -> store.findLatestLocal(subject, observerOther, "s-1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("same workspace");
    }

    @Test
    @DisplayName("deleteOlderThan removes only stale rows in the targeted workspace")
    void deleteOlderThanScopedToWorkspace() {
        final PeerView subjectA = peer(workspaceA, "alice", Principal.Type.USER);
        final PeerView subjectB = peer(workspaceB, "alice", Principal.Type.USER);
        final Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        final Instant t1 = Instant.parse("2026-02-01T00:00:00Z");
        final Instant t2 = Instant.parse("2026-03-01T00:00:00Z");

        store.save(repGlobal(subjectA, "A-old", t0));
        store.save(repGlobal(subjectA, "A-mid", t1));
        store.save(repGlobal(subjectA, "A-new", t2));
        store.save(repGlobal(subjectB, "B-old", t0));

        store.deleteOlderThan(workspaceA, t2); // strictly less than t2 → keeps only A-new in WS_A

        final Optional<Representation> stillA = store.findLatestGlobal(subjectA);
        assertThat(stillA).isPresent();
        assertThat(stillA.orElseThrow().getSummary()).isEqualTo("A-new");

        // Workspace B is untouched even though its row predates the cutoff.
        final Optional<Representation> stillB = store.findLatestGlobal(subjectB);
        assertThat(stillB).isPresent();
        assertThat(stillB.orElseThrow().getSummary()).isEqualTo("B-old");
    }

    @Test
    @DisplayName("payload_blob is the source of truth: snapshot's observations survive even after the underlying "
            + "mem_observation rows would be deleted")
    void payloadBlobIsSourceOfTruth() throws SQLException {
        final PeerView subject = peer(workspaceA, "alice", Principal.Type.USER);
        final PeerView observer = peer(workspaceA, "bob", Principal.Type.USER);

        // Pre-seed an observation row that the representation references by id.
        seedObservation(WS_A, "obs-1", subject, observer, "alice likes coffee");
        seedObservation(WS_A, "obs-2", subject, observer, "alice prefers oat milk");

        final Observation o1 = Observation.builder().id(ObservationId.of(workspaceA, "obs-1")).subject(subject)
                .observer(observer).content("alice likes coffee").type(ObservationType.EXPLICIT).confidence(0.9d)
                .sourceMessageIds(List.of("m-1")).metadata(Map.of("topic", "beverage")).build();
        final Observation o2 = Observation.builder().id(ObservationId.of(workspaceA, "obs-2")).subject(subject)
                .observer(observer).content("alice prefers oat milk").type(ObservationType.DEDUCTIVE).confidence(0.6d)
                .sourceMessageIds(List.of("m-2", "m-3")).build();

        final Instant now = nowMillis();
        final Representation rep = Representation.builder().subject(subject).observer(observer).sessionId("s-1")
                .summary("alice's coffee profile").tokenCount(11).generatedAt(now).observations(List.of(o1, o2))
                .build();
        store.save(rep);

        // Wipe out the mem_observation rows the snapshot was built from.
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement("DELETE FROM mem_observation WHERE workspace_id = ?")) {
            ps.setString(1, WS_A);
            ps.executeUpdate();
        }

        // Snapshot must still hydrate fully from payload_blob alone.
        final Optional<Representation> reloaded = store.findLatestLocal(subject, observer, "s-1");
        assertThat(reloaded).isPresent();
        final Representation loaded = reloaded.orElseThrow();
        assertThat(loaded.getObservations()).hasSize(2);
        assertThat(loaded.getObservations()).extracting(o -> o.getId().getLocalId()).containsExactly("obs-1", "obs-2");
        assertThat(loaded.getObservations().get(0).getContent()).isEqualTo("alice likes coffee");
        assertThat(loaded.getObservations().get(0).getType()).isEqualTo(ObservationType.EXPLICIT);
        assertThat(loaded.getObservations().get(0).getConfidence()).isEqualTo(0.9d);
        assertThat(loaded.getObservations().get(0).getSourceMessageIds()).containsExactly("m-1");
        assertThat(loaded.getObservations().get(0).getMetadata()).containsEntry("topic", "beverage");
        assertThat(loaded.getObservations().get(1).getType()).isEqualTo(ObservationType.DEDUCTIVE);
        assertThat(loaded.getObservations().get(1).getSourceMessageIds()).containsExactly("m-2", "m-3");
    }

    // -------------------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------------------

    private static Instant nowMillis() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    private static PeerView peer(Workspace workspace, String id, Principal.Type type) {
        return PeerView.of(workspace, Principal.builder().type(type).id(id)
                .displayName(Character.toUpperCase(id.charAt(0)) + id.substring(1)).build());
    }

    private static Representation repGlobal(PeerView subject, String summary, Instant when) {
        return Representation.builder().subject(subject).summary(summary).generatedAt(when).build();
    }

    private void seedWorkspace(String id) throws SQLException {
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO mem_workspace (id, display_name, created_at) VALUES (?, ?, ?)")) {
            ps.setString(1, id);
            ps.setString(2, id);
            ps.setTimestamp(3, Timestamp.from(Instant.now()));
            ps.executeUpdate();
        }
    }

    private void seedObservation(String workspaceId, String localId, PeerView subject, PeerView observer,
            String content) throws SQLException {
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement("INSERT INTO mem_observation (workspace_id, local_id, "
                        + "subject_principal_type, subject_principal_id, subject_principal_display_name, "
                        + "observer_principal_type, observer_principal_id, observer_principal_display_name, "
                        + "content, obs_type, confidence, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, workspaceId);
            ps.setString(2, localId);
            ps.setString(3, subject.getPrincipal().getType().name());
            ps.setString(4, subject.getPrincipal().getId());
            ps.setString(5, subject.getPrincipal().getDisplayName());
            ps.setString(6, observer.getPrincipal().getType().name());
            ps.setString(7, observer.getPrincipal().getId());
            ps.setString(8, observer.getPrincipal().getDisplayName());
            ps.setString(9, content);
            ps.setString(10, "EXPLICIT");
            ps.setDouble(11, 0.9d);
            ps.setTimestamp(12, Timestamp.from(Instant.now()));
            ps.executeUpdate();
        }
    }
}
