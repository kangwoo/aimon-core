package at.aimon.memory.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import at.aimon.core.base.Principal;
import at.aimon.core.memory.Workspace;

/**
 * Integration tests for {@link PostgresWorkspaceStore} against a real Postgres container.
 */
@DisplayName("PostgresWorkspaceStore integration")
@Tag("docker")
class PostgresWorkspaceStoreIntegrationTest {

    private PostgresWorkspaceStore store;

    @BeforeEach
    void setUp() {
        PostgresTestSupport.truncateAll();
        store = new PostgresWorkspaceStore(PostgresTestSupport.dataSource(), new ObjectMapper());
    }

    @Test
    @DisplayName("create then findById round-trips id, displayName, metadata, createdAt")
    void createFindByIdRoundTrip() {
        // Truncate Instant to milliseconds — Postgres timestamptz has microsecond
        // resolution but JDBC Timestamp.from() can lose sub-millisecond fractions
        // depending on driver, so normalize on the boundary we control.
        final Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        final Workspace ws = Workspace.builder().id("ws-1").displayName("Workspace One")
                .metadata(Map.of("env", "prod", "region", "eu-1")).createdAt(createdAt).build();

        store.create(ws);

        final Optional<Workspace> found = store.findById("ws-1");
        assertThat(found).isPresent();
        final Workspace loaded = found.orElseThrow();
        assertThat(loaded.getId()).isEqualTo("ws-1");
        assertThat(loaded.getDisplayName()).isEqualTo("Workspace One");
        assertThat(loaded.getMetadata()).containsExactlyInAnyOrderEntriesOf(Map.of("env", "prod", "region", "eu-1"));
        assertThat(loaded.getCreatedAt()).isEqualTo(createdAt);
    }

    @Test
    @DisplayName("create rejects duplicate id with IllegalStateException")
    void createDuplicateIdRejected() {
        final Workspace ws = Workspace.builder().id("dup").displayName("First").build();
        store.create(ws);

        final Workspace dup = Workspace.builder().id("dup").displayName("Second").build();
        assertThatThrownBy(() -> store.create(dup)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Workspace already exists: dup");
    }

    @Test
    @DisplayName("findById returns empty for unknown id")
    void findByIdMissing() {
        assertThat(store.findById("nope")).isEmpty();
    }

    @Test
    @DisplayName("delete removes the row")
    void deleteRemovesRow() {
        final Workspace ws = Workspace.builder().id("ws-del").displayName("Del").build();
        store.create(ws);
        assertThat(store.findById("ws-del")).isPresent();

        store.delete(ws);

        assertThat(store.findById("ws-del")).isEmpty();
    }

    @Test
    @DisplayName("delete is idempotent for unknown ids")
    void deleteUnknownIsNoop() {
        final Workspace ghost = Workspace.builder().id("ghost").displayName("ghost").build();
        // Should not throw.
        store.delete(ghost);
        assertThat(store.findById("ghost")).isEmpty();
    }

    @Test
    @DisplayName("findAll returns multiple workspaces")
    void findAllReturnsMultiple() {
        store.create(Workspace.builder().id("a").displayName("A").build());
        store.create(Workspace.builder().id("b").displayName("B").build());
        store.create(Workspace.builder().id("c").displayName("C").build());

        final List<Workspace> all = store.findAll(Principal.system());
        assertThat(all).extracting(Workspace::getId).containsExactlyInAnyOrder("a", "b", "c");
    }

    @Test
    @DisplayName("findAll returns empty list when no workspaces exist")
    void findAllEmpty() {
        assertThat(store.findAll(Principal.system())).isEmpty();
    }

    @Test
    @DisplayName("create persists empty metadata as empty JSON object")
    void createWithEmptyMetadata() {
        final Workspace ws = Workspace.builder().id("ws-empty").displayName("Empty").metadata(Map.of()).build();
        store.create(ws);

        final Optional<Workspace> found = store.findById("ws-empty");
        assertThat(found).isPresent();
        assertThat(found.orElseThrow().getMetadata()).isEmpty();
    }
}
