package at.aimon.memory.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.base.Principal;
import at.aimon.core.base.exception.AimonException;
import at.aimon.core.memory.DefaultWorkspaceAccessPolicy;
import at.aimon.core.memory.Workspace;

@DisplayName("FileWorkspaceStore")
class FileWorkspaceStoreTest {

    @TempDir
    Path tmp;

    @Test
    @DisplayName("create + findById round-trips through the log on reopen")
    void persistenceRoundTrip() {
        Path log = tmp.resolve("workspaces.jsonl");

        FileWorkspaceStore writer = new FileWorkspaceStore(log, false);
        Workspace ws = Workspace.builder().id("ws-1").displayName("Acme").metadata(Map.of("env", "prod")).build();
        writer.create(ws);

        writer.close(); // release the lock so the "reopen" (a fresh process) can acquire it
        FileWorkspaceStore reader = new FileWorkspaceStore(log, false);
        assertThat(reader.findById("ws-1")).isPresent();
        assertThat(reader.findById("ws-1").get().getDisplayName()).isEqualTo("Acme");
        assertThat(reader.findById("ws-1").get().getMetadata()).containsEntry("env", "prod");
    }

    @Test
    @DisplayName("delete is replayed: tombstone removes the workspace from the reopened mirror")
    void deleteReplay() {
        Path log = tmp.resolve("workspaces.jsonl");

        FileWorkspaceStore writer = new FileWorkspaceStore(log, false);
        Workspace ws = Workspace.builder().id("ws-1").build();
        writer.create(ws);
        writer.delete(ws);

        writer.close(); // release the lock so the "reopen" (a fresh process) can acquire it
        FileWorkspaceStore reader = new FileWorkspaceStore(log, false);
        assertThat(reader.findById("ws-1")).isEmpty();
        assertThat(reader.size()).isZero();
    }

    @Test
    @DisplayName("create rejects duplicate id")
    void rejectDuplicate() {
        FileWorkspaceStore store = new FileWorkspaceStore(tmp.resolve("ws.jsonl"), false);
        store.create(Workspace.builder().id("ws-1").build());

        assertThatThrownBy(() -> store.create(Workspace.builder().id("ws-1").build()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("ws-1");
    }

    @Test
    @DisplayName("findAll returns every workspace as snapshot")
    void findAllSnapshot() {
        FileWorkspaceStore store = new FileWorkspaceStore(tmp.resolve("ws.jsonl"), false);
        store.create(Workspace.builder().id("a").build());
        store.create(Workspace.builder().id("b").build());

        assertThat(store.findAll(Principal.system())).extracting(Workspace::getId).containsExactlyInAnyOrder("a", "b");
    }

    @Test
    @DisplayName("delete on missing id is a silent no-op")
    void deleteMissingNoOp() {
        FileWorkspaceStore store = new FileWorkspaceStore(tmp.resolve("ws.jsonl"), false);
        store.delete(Workspace.builder().id("never-created").build());
        assertThat(store.size()).isZero();
    }

    @Test
    @DisplayName("malformed/torn lines are skipped during replay")
    void replaySkipsMalformedLines() throws Exception {
        Path log = tmp.resolve("ws.jsonl");
        FileWorkspaceStore writer = new FileWorkspaceStore(log, false);
        writer.create(Workspace.builder().id("ws-1").build());

        // Append garbage to simulate a torn final line.
        Files.writeString(log, "not-json-at-all\n", java.nio.file.StandardOpenOption.APPEND);
        writer.create(Workspace.builder().id("ws-2").build());

        writer.close(); // release the lock so the "reopen" (a fresh process) can acquire it
        FileWorkspaceStore reader = new FileWorkspaceStore(log, false);
        assertThat(reader.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("a second open of the same log fails fast (single-process lock)")
    void lockFailFast() {
        Path log = tmp.resolve("ws.jsonl");
        FileWorkspaceStore first = new FileWorkspaceStore(log, false);
        try {
            assertThatThrownBy(() -> new FileWorkspaceStore(log, false)).isInstanceOf(AimonException.class)
                    .hasMessageContaining("lock");
        } finally {
            first.close();
        }
        // Once the holder closes, a fresh open (a restarted process) acquires the lock again.
        FileWorkspaceStore reopened = new FileWorkspaceStore(log, false);
        reopened.close();
    }

    @Test
    @DisplayName("findAll applies the access policy: non-owners don't see owned workspaces")
    void findAllAcl() {
        FileWorkspaceStore store = new FileWorkspaceStore(tmp.resolve("ws.jsonl"), false);
        store.create(Workspace.builder().id("public-ws").build()); // unowned → visible to all
        store.create(Workspace.builder().id("alice-ws")
                .metadata(Map.of(DefaultWorkspaceAccessPolicy.META_OWNER, "USER:alice")).build());

        Principal alice = Principal.user("alice", "Alice");
        Principal bob = Principal.user("bob", "Bob");

        assertThat(store.findAll(Principal.system())).extracting(Workspace::getId)
                .containsExactlyInAnyOrder("public-ws", "alice-ws"); // SYSTEM sees all
        assertThat(store.findAll(alice)).extracting(Workspace::getId).containsExactlyInAnyOrder("public-ws",
                "alice-ws"); // owner sees hers + the unowned one
        assertThat(store.findAll(bob)).extracting(Workspace::getId).containsExactly("public-ws"); // bob: only unowned
        store.close();
    }

    @Test
    @DisplayName("compact() rewrites the log to live state and survives reopen")
    void compaction() throws Exception {
        Path log = tmp.resolve("ws.jsonl");
        FileWorkspaceStore writer = new FileWorkspaceStore(log, false);
        for (int i = 0; i < 50; i++) {
            Workspace ws = Workspace.builder().id("ws-" + i).build();
            writer.create(ws);
            writer.delete(ws);
        }
        writer.create(Workspace.builder().id("survivor").build());
        long linesBefore = countNonBlankLines(log);

        writer.compact();
        long linesAfter = countNonBlankLines(log);

        assertThat(linesAfter).isLessThan(linesBefore);
        assertThat(linesAfter).isEqualTo(1L); // only the survivor remains
        writer.close();

        FileWorkspaceStore reader = new FileWorkspaceStore(log, false);
        assertThat(reader.findById("survivor")).isPresent();
        assertThat(reader.size()).isEqualTo(1);
        reader.close();
    }

    private static long countNonBlankLines(Path p) throws Exception {
        try (java.util.stream.Stream<String> lines = Files.lines(p)) {
            return lines.filter(l -> !l.isBlank()).count();
        }
    }
}
