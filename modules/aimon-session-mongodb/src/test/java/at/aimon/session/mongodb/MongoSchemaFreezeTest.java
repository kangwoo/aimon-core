package at.aimon.session.mongodb;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.session.mongodb.internal.DocumentKeys;

/**
 * Pins the collection names and index declarations that a provisioned cluster is already carrying.
 *
 * <p>
 * Two halves have to agree and neither can check the other: the Java constants in {@link DocumentKeys} decide which
 * collection the runtime reads and writes, and {@code db/mongodb/init.js} decides which collections and indexes ops
 * actually creates. The runtime never executes the script, so nothing in the build reads it — a rename that moves the
 * Java constants and the JS together leaves the whole suite green while every already-provisioned cluster keeps its
 * documents under the old names. Mongo makes that failure quiet rather than loud: an insert against a misspelled
 * collection creates it, so the code comes up on an empty collection instead of erroring.
 *
 * <p>
 * Every expectation below is written out as a literal on purpose. Routing an expectation through the constant it is
 * meant to guard makes the assertion move with the rename and pass forever — see the sibling codec tests for the same
 * reasoning applied to field names.
 *
 * <p>
 * The script is compared after whitespace normalization so the assertions can anchor on the whole statement, receiver
 * included. Substring checks on the field name alone would not notice
 * {@code target.session_inbox.createIndex({ sessionId: 1, ... })} — an index correctly spelled but created on a
 * collection nobody queries.
 *
 * <p>
 * {@code src/main/resources} is on the test runtime classpath (it is the main source set's resource output), so the
 * script is read from there rather than through a relative path into the source tree, which would break whenever the
 * working directory is not the module root.
 */
@DisplayName("Mongo schema — frozen collection names and index declarations")
class MongoSchemaFreezeTest {

    /**
     * {@code aimon-memory-mongodb} used to ship an unrelated script at this same resource path, and that module has
     * since been removed — so nothing collides today. The advice it prompted still stands for whoever adds the next
     * one: a second module claiming this path would be resolved by classpath order, so give it a distinct name
     * rather than trusting that order.
     */
    private static final String RESOURCE = "/db/mongodb/init.js";

    private static String script;

    @BeforeAll
    static void loadScript() throws IOException {
        try (InputStream in = MongoSchemaFreezeTest.class.getResourceAsStream(RESOURCE)) {
            Objects.requireNonNull(in, "resource not on test classpath: " + RESOURCE);
            script = new String(in.readAllBytes(), StandardCharsets.UTF_8).replaceAll("\\s+", " ");
        }
    }

    @Test
    @DisplayName("the Java collection constants still name the deployed collections")
    void collectionNamesAreFrozenInJava() {
        // FROZEN WIRE NAMES. These are the collections holding live data, and each one fails differently when it
        // moves: conversation_locks silently drops every lease, so two nodes can hold the same session at once;
        // conversation_inbox strands the undelivered backlog; conversation_signals is the cross-node change-stream
        // channel, so a rename partitions a fleet mid-deploy — upgraded nodes watch one capped collection, the rest
        // watch another, and neither sees the other's signals.
        assertThat(DocumentKeys.COLL_LOCKS).isEqualTo("conversation_locks");
        assertThat(DocumentKeys.COLL_INBOX).isEqualTo("conversation_inbox");
        assertThat(DocumentKeys.COLL_IDEMPOTENCY).isEqualTo("idempotency_entries");
        assertThat(DocumentKeys.COLL_SIGNALS).isEqualTo("conversation_signals");
        assertThat(DocumentKeys.COLL_BACKGROUND_TASK).isEqualTo("background_task");

        // Frozen from birth rather than inherited: session_records was added with MongoSessionRecordStore, so no
        // deployed cluster predates it. It is pinned here for the same reason as the rest — the first deployment puts
        // transcripts in it, and a later rename would leave every resumed session reading an empty collection Mongo
        // creates on demand, which is a silently emptied conversation rather than an error.
        assertThat(DocumentKeys.COLL_SESSION_RECORDS).isEqualTo("session_records");
    }

    @Test
    @DisplayName("init.js still provisions those same collections")
    void collectionNamesAreFrozenInDdl() {
        assertThat(script).contains("ensureCollection(\"conversation_locks\")");
        assertThat(script).contains("ensureCollection(\"conversation_inbox\")");
        assertThat(script).contains("ensureCollection(\"idempotency_entries\")");
        assertThat(script).contains("ensureCollection(\"background_task\")");
        assertThat(script).contains("ensureCollection(\"session_records\")");

        // The signal collection is capped, and that is not decoration: the change-stream bus depends on it. A rename
        // that drops the option provisions an unbounded collection which grows until the disk does.
        assertThat(script)
                .contains("ensureCollection(\"conversation_signals\", { capped: true, size: 64 * 1024 * 1024 })");
    }

    @Test
    @DisplayName("the inbox index is declared on the frozen collection, field and name")
    void inboxIndexIsFrozen() {
        // MongoSessionInbox filters and sorts on (conversationId, priority, deliveredAt) — the id column kept the
        // "conversation" spelling on the wire when the Java identifiers became session. If the Java side is
        // renamed and this script follows, a freshly provisioned cluster gets an index matching the new code while
        // every running cluster keeps one the new code never uses — a collection scan on the hot collect() path. The
        // index name is pinned too: createIndex is idempotent only when spec and name both match, so a renamed name
        // turns a re-run of this script into a second, redundant index rather than a no-op.
        assertThat(script).contains("target.conversation_inbox.createIndex( "
                + "{ conversationId: 1, priority: 1, deliveredAt: 1 }, { name: \"by_conv_priority_fifo\" } );");
    }

    @Test
    @DisplayName("the background-task index is declared on the frozen collection, field and name")
    void backgroundTaskIndexIsFrozen() {
        // Same freeze, different rename: "contextId" deliberately survived AgentExecutionContext -> AgentRuntime (see
        // DocumentKeys.F_CONTEXT_ID). BackgroundTaskDocumentCodecTest pins the document side; without this the DDL
        // half could drift away from it unnoticed.
        assertThat(script)
                .contains("target.background_task.createIndex( { contextId: 1 }, { name: \"by_context\" } );");
    }
}
