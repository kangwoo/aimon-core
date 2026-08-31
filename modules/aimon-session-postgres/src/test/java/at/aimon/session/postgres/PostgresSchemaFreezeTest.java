package at.aimon.session.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Freeze tests over the shipped operator DDL, {@code db/postgres/V1__init.sql}.
 *
 * <p>
 * Nothing else in this module can catch a rename of these identifiers. {@link PostgresTestSupport} applies this very
 * file to a throwaway container before every integration run, so a sweep that rewrites the DDL and the Java SQL
 * constants ({@code PostgresSessionLeaseStore}, {@code PostgresSessionInbox}, {@code PostgresIdempotencyStore},
 * {@code PostgresSessionSignalBus}, {@code ListenDispatcher}) in one pass leaves the whole suite — the
 * {@code @Tag("docker")} tier included — green, while every already-migrated database silently stops matching the
 * queries. The DDL text is the contract with those databases, so it is what gets pinned here.
 *
 * <p>
 * Every expected identifier below is written out as a literal on purpose: reading it back from a constant would make
 * the assertion travel with the rename and never fail. The file is read straight off the test classpath — no
 * container, no connection — so this stays in the daemonless {@code test} task.
 */
@DisplayName("V1__init.sql — schema identifiers frozen against already-migrated databases")
class PostgresSchemaFreezeTest {

    /** Same path {@link PostgresTestSupport} feeds to {@code applySchema}, i.e. the file operators actually ran. */
    private static final String DDL_RESOURCE = "/db/postgres/V1__init.sql";

    private static final Pattern LINE_COMMENT = Pattern.compile("--[^\\n]*");

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /** Greedy body group: table declarations end on the last {@code )}, past the {@code now()} defaults. */
    private static final Pattern CREATE_TABLE = Pattern.compile("create table if not exists (\\w+) \\((.*)\\)");

    private final List<String> statements = readStatements();

    @Test
    @DisplayName("the DDL is on the test classpath, so the assertions below read the shipped file")
    void ddlResourceIsReadable() {
        // Guards the load path itself: a silently missing resource would leave every other assertion vacuously
        // asserting over an empty statement list.
        assertThat(statements).isNotEmpty();
    }

    @Test
    @DisplayName("the table names deployed databases own are frozen")
    void tableNamesAreFrozen() {
        // session_record is the one name spelled for the session rather than the conversation, and deliberately: the
        // five conversation_* tables are contracts with rows that were already migrated, this one had none when it was
        // added. It is pinned here all the same — from its first deployment there is data behind it too.
        assertThat(tableNames()).containsExactly("conversation_lock", "conversation_lock_fence", "conversation_signal",
                "conversation_inbox", "idempotency_entry", "background_task", "session_record");
    }

    @Test
    @DisplayName("the conversation_id column is frozen on every table that carries it")
    void conversationIdColumnIsFrozen() {
        // Both halves matter. The column name is what deployed rows are keyed and indexed by, and the table list is
        // what stops a sweep from moving the column onto a renamed table and calling it unchanged.
        assertThat(tablesDeclaringColumn("conversation_id")).containsExactly("conversation_lock",
                "conversation_lock_fence", "conversation_signal", "conversation_inbox", "idempotency_entry");
    }

    @Test
    @DisplayName("index names and their keyed columns are frozen")
    void indexDeclarationsAreFrozen() {
        // Indexes are the half of the schema an ORM-less codebase never notices losing: a renamed index still lets
        // every query succeed, just sequentially scanned. Pinning the whole statement covers the index name, the table
        // it sits on and the column order the drain/fetch queries depend on.
        assertThat(indexDeclarations()).containsExactly(
                "create index if not exists conversation_lock_lease_expires_at_idx "
                        + "on conversation_lock (lease_expires_at)",
                "create index if not exists conversation_signal_fetch_idx on conversation_signal (conversation_id, id)",
                "create index if not exists conversation_signal_created_at_idx on conversation_signal (created_at)",
                "create index if not exists conversation_inbox_drain_idx "
                        + "on conversation_inbox (conversation_id, priority, id)",
                "create index if not exists idempotency_entry_inflight_stale_idx "
                        + "on idempotency_entry (status, last_touched_at)",
                "create index if not exists idempotency_entry_expires_at_idx on idempotency_entry (expires_at)",
                "create index if not exists background_task_state_idx on background_task (state)",
                "create index if not exists background_task_context_idx on background_task (context_id)");
    }

    @Test
    @DisplayName("the context_id column survives the AgentExecutionContext → AgentRuntime rename")
    void contextIdColumnIsFrozen() {
        // CHANGELOG, "Not changed (deliberately frozen)": the Java type became AgentRuntime but this column and the
        // index over it stayed, so no data migration was needed. Same exposure as conversation_id — the DDL and the
        // Java constants can be swept together without a single test noticing.
        assertThat(tablesDeclaringColumn("context_id")).containsExactly("background_task");
    }

    private List<String> tableNames() {
        final List<String> names = new ArrayList<>();
        for (String statement : statements) {
            final Matcher m = CREATE_TABLE.matcher(statement);
            if (m.matches()) {
                names.add(m.group(1));
            }
        }
        return names;
    }

    /** Returns, in declaration order, the tables whose column list contains {@code column}. */
    private List<String> tablesDeclaringColumn(String column) {
        final List<String> owners = new ArrayList<>();
        for (String statement : statements) {
            final Matcher m = CREATE_TABLE.matcher(statement);
            if (m.matches() && columnNames(m.group(2)).contains(column)) {
                owners.add(m.group(1));
            }
        }
        return owners;
    }

    private List<String> columnNames(String tableBody) {
        final List<String> columns = new ArrayList<>();
        for (String declaration : tableBody.split(",")) {
            final String trimmed = declaration.trim();
            if (!trimmed.isEmpty()) {
                columns.add(trimmed.split(" ")[0]);
            }
        }
        return columns;
    }

    private List<String> indexDeclarations() {
        final List<String> declarations = new ArrayList<>();
        for (String statement : statements) {
            if (statement.startsWith("create index")) {
                declarations.add(statement);
            }
        }
        return declarations;
    }

    /**
     * Reads the DDL and splits it into single-line statements: comments dropped, whitespace collapsed, no trailing
     * {@code ;}. That normalization is what lets the expectations above be written as one flat literal each.
     */
    private List<String> readStatements() {
        final String ddl = LINE_COMMENT.matcher(readResource()).replaceAll("");
        final String flattened = WHITESPACE.matcher(ddl).replaceAll(" ").toLowerCase(Locale.ROOT);
        final List<String> statements = new ArrayList<>();
        for (String raw : Arrays.asList(flattened.split(";"))) {
            final String trimmed = raw.trim();
            if (!trimmed.isEmpty()) {
                statements.add(trimmed);
            }
        }
        return statements;
    }

    private String readResource() {
        try (InputStream in = PostgresSchemaFreezeTest.class.getResourceAsStream(DDL_RESOURCE)) {
            Objects.requireNonNull(in, "resource not on classpath: " + DDL_RESOURCE);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + DDL_RESOURCE, e);
        }
    }
}
