package at.aimon.core.memory.dialectic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.base.Principal;
import at.aimon.core.memory.PeerView;
import at.aimon.core.memory.Workspace;

@DisplayName("DialecticQuery")
class DialecticQueryTest {

    private static final Workspace WS = Workspace.builder().id("ws-1").build();
    private static final Workspace OTHER_WS = Workspace.builder().id("ws-2").build();
    private static final PeerView ALICE = PeerView.of(WS, Principal.user("alice"));
    private static final PeerView ALICE_OTHER = PeerView.of(OTHER_WS, Principal.user("alice"));

    @Test
    @DisplayName("default level is BALANCED, sessionId optional")
    void defaults() {
        DialecticQuery q = DialecticQuery.builder().workspace(WS).subject(ALICE).observer(ALICE).question("anything?")
                .build();

        assertThat(q.getLevel()).isEqualTo(ReasoningLevel.BALANCED);
        assertThat(q.getSessionId()).isEmpty();
        assertThat(q.getQuestion()).isEqualTo("anything?");
    }

    @Test
    @DisplayName("sessionId surfaces as Optional when set")
    void sessionId() {
        DialecticQuery q = DialecticQuery.builder().workspace(WS).subject(ALICE).observer(ALICE).question("hi")
                .sessionId("sess-1").build();

        assertThat(q.getSessionId()).contains("sess-1");
    }

    @Test
    @DisplayName("blank question is rejected")
    void rejectsBlankQuestion() {
        assertThatThrownBy(
                () -> DialecticQuery.builder().workspace(WS).subject(ALICE).observer(ALICE).question("   ").build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("question");
    }

    @Test
    @DisplayName("subject from a different workspace is rejected")
    void rejectsCrossWorkspaceSubject() {
        assertThatThrownBy(
                () -> DialecticQuery.builder().workspace(WS).subject(ALICE_OTHER).observer(ALICE).question("?").build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("subject");
    }

    @Test
    @DisplayName("observer from a different workspace is rejected")
    void rejectsCrossWorkspaceObserver() {
        assertThatThrownBy(
                () -> DialecticQuery.builder().workspace(WS).subject(ALICE).observer(ALICE_OTHER).question("?").build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("observer");
    }
}
