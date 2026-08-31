package at.aimon.core.memory.deriver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.base.Principal;
import at.aimon.core.memory.PeerView;
import at.aimon.core.memory.Workspace;

@DisplayName("DerivationWorkUnit")
class DerivationWorkUnitTest {

    private static final Workspace WS = Workspace.builder().id("ws-1").build();
    private static final PeerView ALICE = PeerView.of(WS, Principal.user("alice"));
    private static final PeerView ALICE_SERVICE = PeerView.of(WS, Principal.service("alice", "Alice service"));

    @Test
    @DisplayName("(workspace, sessionId, observer) factory pulls type and id from principal")
    void factoryFromPeer() {
        DerivationWorkUnit unit = DerivationWorkUnit.of(WS, "sess-1", ALICE);

        assertThat(unit.getWorkspaceId()).isEqualTo("ws-1");
        assertThat(unit.getSessionId()).isEqualTo("sess-1");
        assertThat(unit.getObserverType()).isEqualTo(Principal.Type.USER);
        assertThat(unit.getObserverId()).isEqualTo("alice");
    }

    @Test
    @DisplayName("equality uses all four components")
    void equality() {
        DerivationWorkUnit a = DerivationWorkUnit.of(WS, "sess", ALICE);
        DerivationWorkUnit b = DerivationWorkUnit.of(WS, "sess", ALICE);
        DerivationWorkUnit differentSession = DerivationWorkUnit.of(WS, "other", ALICE);

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(differentSession);
    }

    @Test
    @DisplayName("same id but different principal type does not collide")
    void typeDistinguishes() {
        DerivationWorkUnit user = DerivationWorkUnit.of(WS, "sess", ALICE);
        DerivationWorkUnit service = DerivationWorkUnit.of(WS, "sess", ALICE_SERVICE);

        assertThat(user).isNotEqualTo(service);
    }

    @Test
    @DisplayName("blank session id is rejected")
    void rejectsBlankSession() {
        assertThatThrownBy(() -> DerivationWorkUnit.of(WS, "  ", ALICE)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sessionId");
    }
}
