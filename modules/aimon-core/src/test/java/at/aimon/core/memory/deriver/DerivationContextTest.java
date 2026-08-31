package at.aimon.core.memory.deriver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.base.Principal;
import at.aimon.core.llm.Message;
import at.aimon.core.memory.PeerView;
import at.aimon.core.memory.Workspace;

@DisplayName("DerivationContext")
class DerivationContextTest {

    private static final Workspace WS = Workspace.builder().id("ws-1").build();
    private static final Workspace OTHER_WS = Workspace.builder().id("ws-2").build();
    private static final PeerView OBSERVER = PeerView.of(WS, Principal.user("alice"));

    @Test
    @DisplayName("builder defaults tokenBudget to 8000")
    void defaultTokenBudget() {
        DerivationContext ctx = DerivationContext.builder().workspace(WS).sessionId("s").observer(OBSERVER)
                .messages(List.of(Message.user("hi"))).build();

        assertThat(ctx.getTokenBudget()).isEqualTo(8000);
    }

    @Test
    @DisplayName("custom tokenBudget is honoured")
    void customTokenBudget() {
        DerivationContext ctx = DerivationContext.builder().workspace(WS).sessionId("s").observer(OBSERVER)
                .messages(List.of(Message.user("hi"))).tokenBudget(2048).build();

        assertThat(ctx.getTokenBudget()).isEqualTo(2048);
    }

    @Test
    @DisplayName("zero tokenBudget is rejected")
    void rejectsNonPositiveTokenBudget() {
        assertThatThrownBy(() -> DerivationContext.builder().workspace(WS).sessionId("s").observer(OBSERVER)
                .messages(List.of(Message.user("hi"))).tokenBudget(0).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("tokenBudget");
    }

    @Test
    @DisplayName("observer workspace must match context workspace")
    void rejectsCrossWorkspaceObserver() {
        PeerView crossObserver = PeerView.of(OTHER_WS, Principal.user("alice"));
        assertThatThrownBy(() -> DerivationContext.builder().workspace(WS).sessionId("s").observer(crossObserver)
                .messages(List.of(Message.user("hi"))).build()).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("workspace");
    }
}
