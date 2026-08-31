package at.aimon.memory.postgres;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

import javax.sql.DataSource;

import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Shared test infrastructure: singleton Postgres container, helpers for HikariCP {@link DataSource} construction, and
 * {@code TRUNCATE} between tests so each test sees a clean schema.
 *
 * <p>
 * The DDL in {@code src/main/resources/db/postgres/V1__init.sql} is applied here — and only here — at static-init time
 * via plain JDBC. Production code never executes DDL (Flyway is operator-applied).
 */
public final class PostgresTestSupport {

    public static final PostgreSQLContainer<?> PG;

    private static final HikariDataSource SHARED;

    static {
        PG = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));
        PG.start();
        applySchema(PG, "/db/postgres/V1__init.sql");
        SHARED = buildHikari(PG, 8);
    }

    private PostgresTestSupport() {
    }

    public static DataSource dataSource() {
        return SHARED;
    }

    public static HikariDataSource isolatedDataSource(int maxPoolSize) {
        return buildHikari(PG, maxPoolSize);
    }

    public static String jdbcUrl() {
        return PG.getJdbcUrl();
    }

    /** Wipes every table this module owns. Call from {@code @BeforeEach} so tests are isolated. */
    public static void truncateAll() {
        try (Connection c = SHARED.getConnection(); Statement s = c.createStatement()) {
            s.execute("TRUNCATE mem_outbox, mem_active_work_unit, mem_representation, "
                    + "mem_observation, mem_workspace RESTART IDENTITY CASCADE");
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to truncate test tables", e);
        }
    }

    private static HikariDataSource buildHikari(PostgreSQLContainer<?> pg, int maxPoolSize) {
        final HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(pg.getJdbcUrl());
        cfg.setUsername(pg.getUsername());
        cfg.setPassword(pg.getPassword());
        cfg.setMaximumPoolSize(maxPoolSize);
        cfg.setMinimumIdle(1);
        cfg.setConnectionTimeout(5_000L);
        cfg.setPoolName("aimon-memory-test-" + System.identityHashCode(pg) + "-" + maxPoolSize);
        return new HikariDataSource(cfg);
    }

    private static void applySchema(PostgreSQLContainer<?> pg, String resource) {
        final String sql = readResource(resource);
        try (Connection c = java.sql.DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
                Statement s = c.createStatement()) {
            s.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to apply schema " + resource, e);
        }
    }

    private static String readResource(String resource) {
        try (InputStream in = PostgresTestSupport.class.getResourceAsStream(resource)) {
            Objects.requireNonNull(in, "resource not on classpath: " + resource);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + resource, e);
        }
    }
}
