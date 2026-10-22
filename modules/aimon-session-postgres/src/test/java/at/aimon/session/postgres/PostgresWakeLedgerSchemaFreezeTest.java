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
 * Freezes {@code db/postgres/V4__wake_ledger.sql} the way {@link PostgresSegmentSchemaFreezeTest} freezes V2: the whole
 * file is written out as a literal, so a rename that moves the DDL and the SQL constants of {@link PostgresWakeLedger}
 * together still fails here instead of leaving deployed databases behind.
 */
@DisplayName("V4__wake_ledger.sql — schema identifiers frozen")
class PostgresWakeLedgerSchemaFreezeTest {

    private static final String DDL_RESOURCE = "/db/postgres/V4__wake_ledger.sql";

    @Test
    @DisplayName("the wake_ledger table, its columns, its key and its index are frozen")
    void wakeLedgerTableIsFrozen() throws IOException {
        final String ddl;
        try (InputStream in = getClass().getResourceAsStream(DDL_RESOURCE)) {
            Objects.requireNonNull(in, "resource not on classpath: " + DDL_RESOURCE);
            ddl = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        final String flattened = ddl.replaceAll("--[^\\n]*", "").replaceAll("\\s+", " ").toLowerCase(Locale.ROOT)
                .trim();
        assertThat(flattened).isEqualTo("create table if not exists wake_ledger ( session_id text primary key,"
                + " recent_wakes bigint[] not null, consecutive integer not null, turn_count_after_wake integer,"
                + " outstanding boolean not null, updated_at timestamptz not null ); create index if not exists"
                + " wake_ledger_updated_at on wake_ledger (updated_at);");
    }
}
