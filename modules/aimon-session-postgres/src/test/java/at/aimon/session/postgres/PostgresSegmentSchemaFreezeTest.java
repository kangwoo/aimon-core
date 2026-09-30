package at.aimon.session.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Freezes {@code db/postgres/V2__session_log_segment.sql} the way {@link PostgresSchemaFreezeTest} freezes V1: the
 * whole statement is written out as a literal, so a rename that moves the DDL and the SQL constants of
 * {@link PostgresSessionLogSegmentStore} together still fails here instead of leaving deployed databases behind.
 */
@DisplayName("V2__session_log_segment.sql — schema identifiers frozen")
class PostgresSegmentSchemaFreezeTest {

    private static final String DDL_RESOURCE = "/db/postgres/V2__session_log_segment.sql";

    @Test
    @DisplayName("the session_log_segment table, its columns and its key are frozen")
    void segmentTableIsFrozen() throws IOException {
        final String ddl;
        try (InputStream in = getClass().getResourceAsStream(DDL_RESOURCE)) {
            Objects.requireNonNull(in, "resource not on classpath: " + DDL_RESOURCE);
            ddl = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        final String flattened = ddl.replaceAll("--[^\\n]*", "").replaceAll("\\s+", " ").toLowerCase(Locale.ROOT)
                .trim();
        assertThat(flattened).isEqualTo("create table if not exists session_log_segment ( session_id text not null,"
                + " segment_id text not null, from_seq bigint not null, to_seq bigint not null, entry_count integer"
                + " not null, payload text not null, created_at timestamptz not null, primary key (session_id,"
                + " segment_id) );");
    }
}
