/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.memory.mongodb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import at.aimon.core.base.Principal;
import at.aimon.core.memory.DefaultWorkspaceAccessPolicy;
import at.aimon.core.memory.Workspace;

@DisplayName("MongoWorkspaceStore integration")
@Tag("docker")
class MongoWorkspaceStoreIntegrationTest {

    private MongoWorkspaceStore store;

    @BeforeEach
    void setUp() {
        MongoMemoryTestSupport.dropAndApplyDdl();
        store = new MongoWorkspaceStore(MongoMemoryTestSupport.sharedDatabase());
    }

    @Test
    @DisplayName("create + findById round-trips, including metadata")
    void createAndFindById() {
        Workspace ws = Workspace.builder().id("ws-1").displayName("Acme").metadata(Map.of("env", "prod")).build();
        store.create(ws);

        assertThat(store.findById("ws-1")).isPresent();
        assertThat(store.findById("ws-1").get().getDisplayName()).isEqualTo("Acme");
        assertThat(store.findById("ws-1").get().getMetadata()).containsEntry("env", "prod");
    }

    @Test
    @DisplayName("create rejects a duplicate id")
    void createRejectsDuplicate() {
        store.create(Workspace.builder().id("ws-1").build());
        assertThatThrownBy(() -> store.create(Workspace.builder().id("ws-1").build()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("ws-1");
    }

    @Test
    @DisplayName("delete removes the workspace")
    void deleteRemoves() {
        Workspace ws = Workspace.builder().id("ws-1").build();
        store.create(ws);
        store.delete(ws);
        assertThat(store.findById("ws-1")).isEmpty();
    }

    @Test
    @DisplayName("findAll applies the access policy")
    void findAllAppliesAcl() {
        store.create(Workspace.builder().id("public-ws").build());
        store.create(Workspace.builder().id("alice-ws")
                .metadata(Map.of(DefaultWorkspaceAccessPolicy.META_OWNER, "USER:alice")).build());

        Principal alice = Principal.user("alice", "Alice");
        Principal bob = Principal.user("bob", "Bob");

        assertThat(store.findAll(Principal.system())).extracting(Workspace::getId)
                .containsExactlyInAnyOrder("public-ws", "alice-ws");
        assertThat(store.findAll(alice)).extracting(Workspace::getId).containsExactlyInAnyOrder("public-ws",
                "alice-ws");
        assertThat(store.findAll(bob)).extracting(Workspace::getId).containsExactly("public-ws");
    }
}
