package at.aimon.core.memory.deriver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.base.Principal;
import at.aimon.core.llm.Message;
import at.aimon.core.memory.PeerView;
import at.aimon.core.memory.Workspace;

@DisplayName("DerivationTask")
class DerivationTaskTest {

    private static final Workspace WS = Workspace.builder().id("ws-1").build();
    private static final Workspace OTHER_WS = Workspace.builder().id("ws-2").build();
    private static final PeerView OBSERVER = PeerView.of(WS, Principal.user("alice"));

    @Test
    @DisplayName("workUnit() exposes the claim key")
    void workUnit() {
        DerivationTask task = newTask(List.of(Message.user("hello")));
        DerivationWorkUnit unit = task.workUnit();

        assertThat(unit.getWorkspaceId()).isEqualTo("ws-1");
        assertThat(unit.getSessionId()).isEqualTo("sess-1");
        assertThat(unit.getObserverId()).isEqualTo("alice");
    }

    @Test
    @DisplayName("withMessages returns a copy with messages replaced")
    void withMessagesIsImmutable() {
        DerivationTask original = newTask(List.of(Message.user("dirty token=abc")));
        DerivationTask redacted = original.withMessages(List.of(Message.user("dirty token=[REDACTED]")));

        assertThat(redacted).isNotSameAs(original);
        assertThat(redacted.getMessages()).hasSize(1);
        assertThat(redacted.getMessages().get(0).getContent()).contains("[REDACTED]");
        assertThat(original.getMessages().get(0).getContent()).contains("abc");
    }

    @Test
    @DisplayName("observer workspace must match task workspace")
    void rejectsCrossWorkspaceObserver() {
        PeerView crossObserver = PeerView.of(OTHER_WS, Principal.user("alice"));
        assertThatThrownBy(() -> DerivationTask.builder().workspace(WS).sessionId("s").observer(crossObserver)
                .messages(List.of(Message.user("x"))).scheduledAt(Instant.now()).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("workspace");
    }

    @Test
    @DisplayName("empty messages list is rejected")
    void rejectsEmptyMessages() {
        assertThatThrownBy(() -> DerivationTask.builder().workspace(WS).sessionId("s").observer(OBSERVER)
                .messages(List.of()).build()).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("messages");
    }

    private DerivationTask newTask(List<Message> messages) {
        return DerivationTask.builder().workspace(WS).sessionId("sess-1").observer(OBSERVER).messages(messages).build();
    }
}
