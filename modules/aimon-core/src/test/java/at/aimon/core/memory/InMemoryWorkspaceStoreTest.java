package at.aimon.core.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.base.Principal;

@DisplayName("InMemoryWorkspaceStore")
class InMemoryWorkspaceStoreTest {

    private InMemoryWorkspaceStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryWorkspaceStore();
    }

    private Workspace ws(String id) {
        return Workspace.builder().id(id).build();
    }

    @Test
    @DisplayName("create persists a workspace and returns the stored instance")
    void createPersists() {
        Workspace ws = ws("ws-1");
        Workspace returned = store.create(ws);

        assertThat(returned).isEqualTo(ws);
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("findById returns the stored workspace")
    void findByIdReturns() {
        Workspace ws = ws("ws-1");
        store.create(ws);

        Optional<Workspace> found = store.findById("ws-1");

        assertThat(found).isPresent().contains(ws);
    }

    @Test
    @DisplayName("findById returns empty for unknown id")
    void findByIdEmpty() {
        assertThat(store.findById("missing")).isEmpty();
    }

    @Test
    @DisplayName("create rejects duplicate id with IllegalStateException")
    void duplicateCreateRejected() {
        store.create(ws("ws-1"));

        assertThatThrownBy(() -> store.create(ws("ws-1"))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ws-1");
    }

    @Test
    @DisplayName("delete removes the workspace")
    void deleteRemoves() {
        Workspace ws = ws("ws-1");
        store.create(ws);

        store.delete(ws);

        assertThat(store.findById("ws-1")).isEmpty();
        assertThat(store.size()).isZero();
    }

    @Test
    @DisplayName("findAll returns every workspace regardless of requester (stage 1: no ACL)")
    void findAllNoAcl() {
        store.create(ws("ws-1"));
        store.create(ws("ws-2"));
        store.create(ws("ws-3"));

        List<Workspace> asUser = store.findAll(Principal.user("alice", "Alice"));
        List<Workspace> asSystem = store.findAll(Principal.system());

        assertThat(asUser).hasSize(3);
        assertThat(asSystem).hasSize(3);
    }

    @Test
    @DisplayName("size() reflects current number of stored workspaces")
    void sizeHelper() {
        assertThat(store.size()).isZero();
        store.create(ws("a"));
        store.create(ws("b"));
        assertThat(store.size()).isEqualTo(2);
        store.delete(ws("a"));
        assertThat(store.size()).isEqualTo(1);
    }
}
