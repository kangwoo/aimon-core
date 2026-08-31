package at.aimon.core.subagent.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.transcript.SessionSnapshot;
import at.aimon.core.filesystem.impl.local.LocalFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystemConfig;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;
import at.aimon.core.llm.content.ImageContentBlock;
import at.aimon.core.llm.content.TextContentBlock;
import at.aimon.core.subagent.task.codec.JsonSessionSnapshotCodec;

class VfsSessionSnapshotStoreTest {

    @TempDir
    Path tempDir;

    private LocalFileSystem fileSystem;
    private VfsSessionSnapshotStore store;

    @BeforeEach
    void setUp() {
        fileSystem = new LocalFileSystem(new LocalFileSystemConfig(tempDir.toString()));
        fileSystem.initialize();
        store = new VfsSessionSnapshotStore(fileSystem);
    }

    private static SessionSnapshot sampleSnapshot(String systemPrompt) {
        final Message user = Message.user(List.of(TextContentBlock.of("Look at this 🚀"),
                ImageContentBlock.ofBase64(new byte[]{1, 2, 3, -1}, "image/png")));
        final Message assistant = Message.assistant("On it.",
                List.of(ToolUse.of("call_1", "Bash", Map.of("command", "ls -la"))), List.of());
        final Message tool = Message.toolUseResults(List.of(ToolUseResult.success("call_1", "done")));
        return SessionSnapshot.of(SessionId.of("conv-1"), systemPrompt, List.of(user, assistant, tool));
    }

    /** Reads an object's bytes straight off the backend, bypassing the store, to assert on the persisted form. */
    private String readRaw(String path) throws IOException {
        try (InputStream in = fileSystem.read(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static List<String> fieldNames(JsonNode node) {
        final List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    @Test
    void constructorRejectsNullFileSystem() {
        assertThatNullPointerException().isThrownBy(() -> new VfsSessionSnapshotStore(null));
    }

    @Test
    void constructorRejectsNullCodec() {
        assertThatNullPointerException().isThrownBy(() -> new VfsSessionSnapshotStore(fileSystem, null, "dir"));
    }

    @Test
    void constructorRejectsBlankBaseDir() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new VfsSessionSnapshotStore(fileSystem, new JsonSessionSnapshotCodec(), "  "));
    }

    @Test
    void loadUnknownTaskReturnsEmpty() {
        assertThat(store.load("nope")).isEmpty();
    }

    @Test
    void saveThenLoadRoundTripsSnapshotAndOwner() {
        final SessionSnapshot snapshot = sampleSnapshot("You are a helper.");

        store.save("t", "code-reviewer", snapshot);

        assertThat(store.load("t")).get().satisfies(resumable -> {
            assertThat(resumable.getSubagentName()).isEqualTo("code-reviewer");
            assertThat(resumable.getSnapshot()).isEqualTo(snapshot);
        });
    }

    @Test
    void saveThenLoadRoundTripsOwningRuntimeIdAcrossNodes() {
        final SessionSnapshot snapshot = sampleSnapshot("You are a helper.");
        store.save("t", "code-reviewer", AgentRuntimeId.of("agent:reviewer"), snapshot);

        // A fresh store over the same backing filesystem simulates a different node with no shared memory: the owning
        // context must survive serialization so cross-node resume can still be authorized to the right agent.
        final VfsSessionSnapshotStore otherNode = new VfsSessionSnapshotStore(fileSystem);

        assertThat(otherNode.load("t")).get().satisfies(
                resumable -> assertThat(resumable.getAgentRuntimeId()).contains(AgentRuntimeId.of("agent:reviewer")));
    }

    @Test
    void untaggedSnapshotLoadsWithNoRuntimeId() {
        store.save("t", "code-reviewer", sampleSnapshot("sys"));

        assertThat(store.load("t")).get().satisfies(resumable -> assertThat(resumable.getAgentRuntimeId()).isEmpty());
    }

    @Test
    void corruptRuntimeIdLoadsWithNoRuntimeIdButKeepsSnapshot() {
        // A malformed contextId must not sink the whole transcript; it degrades to unverifiable ownership (empty).
        fileSystem.write(VfsSessionSnapshotStore.DEFAULT_BASE_DIR + "/t.json",
                "{\"v\":1,\"subagentName\":\"x\",\"contextId\":\"not a valid id\",\"snapshot\":"
                        + "{\"version\":1,\"conversationId\":\"c\",\"systemPrompt\":\"s\",\"messages\":[]}}");

        assertThat(store.load("t")).get().satisfies(resumable -> {
            assertThat(resumable.getSubagentName()).isEqualTo("x");
            assertThat(resumable.getAgentRuntimeId()).isEmpty();
        });
    }

    @Test
    void envelopeTagForTheOwningRuntimeIsWrittenAsContextId() throws IOException {
        // FROZEN WIRE FORMAT. The agent-scope refactor renamed AgentExecutionContext to AgentRuntime throughout the
        // Java sources but deliberately left persisted field names alone (see CHANGELOG, "Not changed (deliberately
        // frozen)"). FIELD_CONTEXT_ID is private, so the only way to pin it is through the bytes that reach the
        // backend. A round-trip test cannot protect this: renaming the writer's key and the reader's key together
        // keeps every round-trip green while snapshots already on the backend — and snapshots written by nodes still
        // on the old build during a rolling deploy — silently lose their owner and resume unscoped.
        store.save("t", "code-reviewer", AgentRuntimeId.of("agent:reviewer"), sampleSnapshot("sys"));

        final JsonNode envelope = new ObjectMapper()
                .readTree(readRaw(VfsSessionSnapshotStore.DEFAULT_BASE_DIR + "/t.json"));

        assertThat(fieldNames(envelope)).contains("contextId");
        assertThat(envelope.get("contextId").asText()).isEqualTo("agent:reviewer");
    }

    @Test
    void snapshotTaggedWithTheFrozenContextIdKeyIsStillReadable() {
        // The other half of the freeze: the writer test alone would not stop a read-side rename from orphaning every
        // snapshot already persisted. Here the writer and reader share one private FIELD_CONTEXT_ID, so the usual
        // sweep trips both tests at once; this one earns its place against a divergence at the read site alone.
        // Note it is NOT implied by corruptRuntimeIdLoadsWithNoRuntimeIdButKeepsSnapshot above: that test asserts an
        // EMPTY runtime id, which is exactly what a renamed reader produces when it cannot find its key, so it stays
        // green through the very rename this test exists to catch.
        fileSystem.write(VfsSessionSnapshotStore.DEFAULT_BASE_DIR + "/t.json",
                "{\"v\":1,\"subagentName\":\"code-reviewer\",\"contextId\":\"agent:reviewer\",\"snapshot\":"
                        + "{\"version\":1,\"conversationId\":\"c\",\"systemPrompt\":\"s\",\"messages\":[]}}");

        assertThat(store.load("t")).get().satisfies(
                resumable -> assertThat(resumable.getAgentRuntimeId()).contains(AgentRuntimeId.of("agent:reviewer")));
    }

    @Test
    void saveOverwritesPreviousSnapshotForSameTask() {
        store.save("t", "Explore", sampleSnapshot("first"));
        final SessionSnapshot second = SessionSnapshot.of(SessionId.of("conv-2"), "second",
                List.of(Message.user("later")));

        store.save("t", "Plan", second);

        assertThat(store.load("t")).get().satisfies(resumable -> {
            assertThat(resumable.getSubagentName()).isEqualTo("Plan");
            assertThat(resumable.getSnapshot()).isEqualTo(second);
        });
    }

    @Test
    void anotherInstanceLoadsSnapshotFromTheSameDirectory() {
        final SessionSnapshot snapshot = sampleSnapshot("shared");
        store.save("t", "code-reviewer", snapshot);

        // A fresh store over the same backing filesystem simulates a different node with no shared memory.
        final VfsSessionSnapshotStore otherNode = new VfsSessionSnapshotStore(fileSystem);

        assertThat(otherNode.load("t")).get().satisfies(resumable -> {
            assertThat(resumable.getSubagentName()).isEqualTo("code-reviewer");
            assertThat(resumable.getSnapshot()).isEqualTo(snapshot);
        });
    }

    @Test
    void evictRemovesSnapshot() {
        store.save("t", "Explore", sampleSnapshot("sys"));

        store.evict("t");

        assertThat(store.load("t")).isEmpty();
        assertThat(fileSystem.exists(VfsSessionSnapshotStore.DEFAULT_BASE_DIR + "/t.json")).isFalse();
    }

    @Test
    void evictUnknownTaskIsNoOp() {
        store.evict("nope");

        assertThat(store.load("nope")).isEmpty();
    }

    @Test
    void tasksAreIsolatedById() {
        final SessionSnapshot a = sampleSnapshot("a");
        final SessionSnapshot b = SessionSnapshot.of(SessionId.of("conv-b"), "b", List.of(Message.user("b-msg")));

        store.save("a", "Explore", a);
        store.save("b", "Plan", b);

        assertThat(store.load("a")).get().extracting(ResumableSession::getSnapshot).isEqualTo(a);
        assertThat(store.load("b")).get().extracting(ResumableSession::getSnapshot).isEqualTo(b);
    }

    @Test
    void corruptSnapshotFileLoadsAsEmpty() {
        fileSystem.write(VfsSessionSnapshotStore.DEFAULT_BASE_DIR + "/t.json", "{ not valid json");

        assertThat(store.load("t")).isEmpty();
    }

    @Test
    void unsupportedEnvelopeVersionLoadsAsEmpty() {
        fileSystem.write(VfsSessionSnapshotStore.DEFAULT_BASE_DIR + "/t.json",
                "{\"v\":999,\"subagentName\":\"x\",\"snapshot\":{}}");

        assertThat(store.load("t")).isEmpty();
    }

    @Test
    void customBaseDirIsHonoured() {
        final VfsSessionSnapshotStore custom = new VfsSessionSnapshotStore(fileSystem, new JsonSessionSnapshotCodec(),
                "custom/snapshots/");
        custom.save("t", "Explore", sampleSnapshot("sys"));

        assertThat(fileSystem.exists("custom/snapshots/t.json")).isTrue();
        assertThat(custom.load("t")).isPresent();
    }

    @Test
    void nullArgumentsRejected() {
        final SessionSnapshot snapshot = sampleSnapshot("sys");
        assertThatNullPointerException().isThrownBy(() -> store.save(null, "Explore", snapshot));
        assertThatNullPointerException().isThrownBy(() -> store.save("t", null, snapshot));
        assertThatNullPointerException().isThrownBy(() -> store.save("t", "Explore", null));
        assertThatNullPointerException().isThrownBy(() -> store.load(null));
        assertThatNullPointerException().isThrownBy(() -> store.evict(null));
    }
}
