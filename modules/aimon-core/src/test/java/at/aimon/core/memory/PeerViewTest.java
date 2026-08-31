package at.aimon.core.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.base.Principal;

@DisplayName("PeerView")
class PeerViewTest {

    private Workspace ws() {
        return Workspace.builder().id("ws-1").build();
    }

    @Test
    @DisplayName("key() formats user principals as wsId:USER:principalId")
    void keyForUserPrincipal() {
        PeerView pv = PeerView.of(ws(), Principal.user("alice", "Alice"));

        assertThat(pv.key()).isEqualTo("ws-1:USER:alice");
    }

    @Test
    @DisplayName("key() formats service principals as wsId:SERVICE:principalId")
    void keyForServicePrincipal() {
        PeerView pv = PeerView.of(ws(), Principal.service("billing", "Billing Service"));

        assertThat(pv.key()).isEqualTo("ws-1:SERVICE:billing");
    }

    @Test
    @DisplayName("key() formats system principal as wsId:SYSTEM:system")
    void keyForSystemPrincipal() {
        PeerView pv = PeerView.of(ws(), Principal.system());

        assertThat(pv.key()).isEqualTo("ws-1:SYSTEM:system");
    }

    @Test
    @DisplayName("getWorkspace and getPrincipal expose the underlying components")
    void accessors() {
        Workspace ws = ws();
        Principal alice = Principal.user("alice", "Alice");
        PeerView pv = PeerView.of(ws, alice);

        assertThat(pv.getWorkspace()).isEqualTo(ws);
        assertThat(pv.getPrincipal()).isEqualTo(alice);
    }

    @Test
    @DisplayName("null arguments throw NullPointerException")
    void nullArgumentsRejected() {
        assertThatThrownBy(() -> PeerView.of(null, Principal.user("a", "A"))).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> PeerView.of(ws(), null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("equals compares both workspace and principal")
    void equalsAndHashCode() {
        Principal alice = Principal.user("alice", "Alice");
        PeerView a = PeerView.of(ws(), alice);
        PeerView b = PeerView.of(ws(), alice);
        PeerView differentPrincipal = PeerView.of(ws(), Principal.user("bob", "Bob"));

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
        assertThat(a).isNotEqualTo(differentPrincipal);
    }

    @Test
    @DisplayName("user and service principals with the same id produce different keys")
    void typeSeparatesIdCollisions() {
        PeerView userView = PeerView.of(ws(), Principal.user("alice", "Alice"));
        PeerView serviceView = PeerView.of(ws(), Principal.service("alice", "Alice"));

        assertThat(userView.key()).isNotEqualTo(serviceView.key());
    }
}
