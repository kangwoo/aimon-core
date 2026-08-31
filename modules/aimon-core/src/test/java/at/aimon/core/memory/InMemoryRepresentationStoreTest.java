package at.aimon.core.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.base.Principal;

@DisplayName("InMemoryRepresentationStore")
class InMemoryRepresentationStoreTest {

    private static final Instant T0 = Instant.parse("2024-01-15T10:00:00Z");

    private InMemoryRepresentationStore store;
    private Workspace ws;
    private PeerView alice;
    private PeerView bob;
    private PeerView carol;

    @BeforeEach
    void setUp() {
        store = new InMemoryRepresentationStore();
        ws = Workspace.builder().id("ws-1").build();
        alice = PeerView.of(ws, Principal.user("alice", "Alice"));
        bob = PeerView.of(ws, Principal.user("bob", "Bob"));
        carol = PeerView.of(ws, Principal.user("carol", "Carol"));
    }

    private Representation global(PeerView subject, String summary, Instant generatedAt) {
        return Representation.builder().subject(subject).summary(summary).generatedAt(generatedAt).build();
    }

    private Representation local(PeerView subject, PeerView observer, String sessionId, Instant generatedAt) {
        return Representation.builder().subject(subject).observer(observer).sessionId(sessionId)
                .generatedAt(generatedAt).build();
    }

    @Test
    @DisplayName("save returns the persisted representation")
    void saveReturns() {
        Representation r = global(alice, "summary", T0);

        assertThat(store.save(r)).isEqualTo(r);
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("findLatestGlobal returns most recent global representation")
    void findLatestGlobal() {
        Representation older = global(alice, "older", T0);
        Representation newest = global(alice, "newest", T0.plusSeconds(120));
        Representation mid = global(alice, "mid", T0.plusSeconds(60));
        store.save(older);
        store.save(newest);
        store.save(mid);

        Optional<Representation> latest = store.findLatestGlobal(alice);

        assertThat(latest).isPresent();
        assertThat(latest.get().getSummary()).isEqualTo("newest");
    }

    @Test
    @DisplayName("findLatestGlobal returns empty when none stored")
    void findLatestGlobalEmpty() {
        assertThat(store.findLatestGlobal(alice)).isEmpty();
    }

    @Test
    @DisplayName("findLatestLocal matches observer and sessionId")
    void findLatestLocalMatchSession() {
        Representation r1 = local(alice, bob, "sess-1", T0);
        Representation r2 = local(alice, bob, "sess-2", T0.plusSeconds(60));
        Representation r3 = local(alice, bob, "sess-1", T0.plusSeconds(120));
        store.save(r1);
        store.save(r2);
        store.save(r3);

        Optional<Representation> latest = store.findLatestLocal(alice, bob, "sess-1");

        assertThat(latest).isPresent();
        assertThat(latest.get().getGeneratedAt()).isEqualTo(T0.plusSeconds(120));
    }

    @Test
    @DisplayName("findLatestLocal with null sessionId matches null sessionId entries")
    void findLatestLocalNullSession() {
        Representation withSession = local(alice, bob, "sess-1", T0);
        Representation noSession1 = local(alice, bob, null, T0.plusSeconds(60));
        Representation noSession2 = local(alice, bob, null, T0.plusSeconds(120));
        store.save(withSession);
        store.save(noSession1);
        store.save(noSession2);

        Optional<Representation> latest = store.findLatestLocal(alice, bob, null);

        assertThat(latest).isPresent();
        assertThat(latest.get().getGeneratedAt()).isEqualTo(T0.plusSeconds(120));
    }

    @Test
    @DisplayName("findLatestLocal returns empty when observer differs")
    void findLatestLocalObserverIsolation() {
        store.save(local(alice, bob, "sess-1", T0));

        assertThat(store.findLatestLocal(alice, carol, "sess-1")).isEmpty();
    }

    @Test
    @DisplayName("findLatestLocal does not return global representations")
    void findLatestLocalIgnoresGlobal() {
        store.save(global(alice, "global summary", T0));

        assertThat(store.findLatestLocal(alice, bob, null)).isEmpty();
    }

    @Test
    @DisplayName("findLatestGlobal does not return local representations")
    void findLatestGlobalIgnoresLocal() {
        store.save(local(alice, bob, "sess-1", T0));

        assertThat(store.findLatestGlobal(alice)).isEmpty();
    }

    @Test
    @DisplayName("operations isolate by subject")
    void subjectIsolation() {
        store.save(global(alice, "alice global", T0));
        store.save(global(carol, "carol global", T0));

        assertThat(store.findLatestGlobal(alice).get().getSummary()).isEqualTo("alice global");
        assertThat(store.findLatestGlobal(carol).get().getSummary()).isEqualTo("carol global");
    }

    @Test
    @DisplayName("deleteOlderThan removes representations within workspace before cutoff")
    void deleteOlderThan() {
        Instant cutoff = T0.plusSeconds(100);
        store.save(global(alice, "old", T0));
        store.save(global(alice, "boundary", T0.plusSeconds(100))); // not before cutoff
        store.save(global(alice, "new", T0.plusSeconds(200)));

        store.deleteOlderThan(ws, cutoff);

        assertThat(store.size()).isEqualTo(2);
        assertThat(store.findLatestGlobal(alice).get().getSummary()).isEqualTo("new");
    }

    @Test
    @DisplayName("deleteOlderThan does not affect other workspaces")
    void deleteOlderThanWorkspaceIsolation() {
        Workspace otherWs = Workspace.builder().id("ws-2").build();
        PeerView otherAlice = PeerView.of(otherWs, Principal.user("alice", "Alice"));
        store.save(global(alice, "ws1", T0));
        store.save(global(otherAlice, "ws2", T0));

        store.deleteOlderThan(ws, T0.plusSeconds(60));

        assertThat(store.size()).isEqualTo(1);
        assertThat(store.findLatestGlobal(otherAlice)).isPresent();
    }
}
