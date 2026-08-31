package at.aimon.memory.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Smoke test: boot Testcontainers PG, apply V1__init.sql, verify the five
 * memory tables exist and are wiped by {@link PostgresTestSupport#truncateAll()}.
 *
 * <p>
 * Acts as a baseline before parallel store implementations are layered on.
 */
@DisplayName("aimon-memory-postgres schema smoke")
@Tag("docker")
class SchemaSmokeTest {

    @BeforeEach
    void setUp() {
        PostgresTestSupport.truncateAll();
    }

    @Test
    @DisplayName("V1 schema creates all five mem_* tables")
    void allTablesExist() throws Exception {
        try (Connection c = PostgresTestSupport.dataSource().getConnection();
                PreparedStatement ps = c.prepareStatement("SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_name LIKE 'mem_%' ORDER BY table_name")) {
            try (ResultSet rs = ps.executeQuery()) {
                List<String> tables = new ArrayList<>();
                while (rs.next()) {
                    tables.add(rs.getString(1));
                }
                assertThat(tables).containsExactly("mem_active_work_unit", "mem_observation", "mem_outbox",
                        "mem_representation", "mem_workspace");
            }
        }
    }

    @Test
    @DisplayName("mem_observation enforces obs_type CHECK")
    void observationTypeCheck() throws Exception {
        seedWorkspace("ws-1");
        try (Connection c = PostgresTestSupport.dataSource().getConnection();
                PreparedStatement ps = c.prepareStatement("INSERT INTO mem_observation (workspace_id, local_id, "
                        + "subject_principal_type, subject_principal_id, subject_principal_display_name, "
                        + "observer_principal_type, observer_principal_id, observer_principal_display_name, "
                        + "content, obs_type, confidence, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())")) {
            ps.setString(1, "ws-1");
            ps.setString(2, "o1");
            ps.setString(3, "USER");
            ps.setString(4, "alice");
            ps.setString(5, "Alice");
            ps.setString(6, "USER");
            ps.setString(7, "bob");
            ps.setString(8, "Bob");
            ps.setString(9, "hello");
            ps.setString(10, "BOGUS");
            ps.setDouble(11, 0.5d);

            assertThat(catching(ps::executeUpdate)).isNotNull().hasMessageContaining("mem_observation_obs_type");
        }
    }

    @Test
    @DisplayName("mem_observation enforces confidence in [0, 1]")
    void observationConfidenceCheck() throws Exception {
        seedWorkspace("ws-1");
        try (Connection c = PostgresTestSupport.dataSource().getConnection();
                PreparedStatement ps = c.prepareStatement("INSERT INTO mem_observation (workspace_id, local_id, "
                        + "subject_principal_type, subject_principal_id, subject_principal_display_name, "
                        + "observer_principal_type, observer_principal_id, observer_principal_display_name, "
                        + "content, obs_type, confidence, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())")) {
            ps.setString(1, "ws-1");
            ps.setString(2, "o1");
            ps.setString(3, "USER");
            ps.setString(4, "alice");
            ps.setString(5, "Alice");
            ps.setString(6, "USER");
            ps.setString(7, "bob");
            ps.setString(8, "Bob");
            ps.setString(9, "hello");
            ps.setString(10, "EXPLICIT");
            ps.setDouble(11, 1.5d);

            assertThat(catching(ps::executeUpdate)).isNotNull()
                    .hasMessageContaining("mem_observation_confidence_range");
        }
    }

    @Test
    @DisplayName("mem_outbox enforces operation CHECK")
    void outboxOperationCheck() throws Exception {
        try (Connection c = PostgresTestSupport.dataSource().getConnection();
                PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO mem_outbox (workspace_id, observation_local_id, subject_key, operation) "
                                + "VALUES (?, ?, ?, ?)")) {
            ps.setString(1, "ws-1");
            ps.setString(2, "o1");
            ps.setString(3, "ws-1:USER:alice");
            ps.setString(4, "BOGUS");

            assertThat(catching(ps::executeUpdate)).isNotNull().hasMessageContaining("mem_outbox_operation");
        }
    }

    private static void seedWorkspace(String id) throws Exception {
        try (Connection c = PostgresTestSupport.dataSource().getConnection();
                PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO mem_workspace (id, display_name, created_at) VALUES (?, ?, now())")) {
            ps.setString(1, id);
            ps.setString(2, id);
            ps.executeUpdate();
        }
    }

    private static Throwable catching(ThrowingRunnable r) {
        try {
            r.run();
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
