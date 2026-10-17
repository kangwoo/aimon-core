package at.aimon.core.agent.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.base.Principal;

@DisplayName("SessionDeletedEvent")
class SessionDeletedEventTest {

    private static final SessionId ID = SessionId.of("gone");
    private static final Instant AT = Instant.parse("2026-10-17T00:00:00Z");

    @Test
    @DisplayName("requires the session id and the deletion time")
    void requiresIdAndTime() {
        assertThatNullPointerException().isThrownBy(() -> SessionDeletedEvent.builder().deletedAt(AT).build())
                .withMessageContaining("sessionId");
        assertThatNullPointerException().isThrownBy(() -> SessionDeletedEvent.builder().sessionId(ID).build())
                .withMessageContaining("deletedAt");
    }

    @Test
    @DisplayName("owner and binding are empty unless given")
    void optionalsDefaultToEmpty() {
        final SessionDeletedEvent event = SessionDeletedEvent.builder().sessionId(ID).deletedAt(AT).build();

        assertThat(event.getSessionId()).isEqualTo(ID);
        assertThat(event.getDeletedAt()).isEqualTo(AT);
        assertThat(event.getOwner()).isEmpty();
        assertThat(event.getAgentRef()).isEmpty();
    }

    @Test
    @DisplayName("carries what it is given")
    void carriesValues() {
        final SessionDeletedEvent event = SessionDeletedEvent.builder().sessionId(ID).deletedAt(AT)
                .owner(Principal.user("alice")).agentRef("agent-a").build();

        assertThat(event.getOwner()).contains(Principal.user("alice"));
        assertThat(event.getAgentRef()).contains("agent-a");
        assertThat(event.toString()).contains("USER:alice").contains("agent-a");
    }

    @Test
    @DisplayName("a listener that overrides nothing does nothing")
    void listenerDefaultIsANoOp() {
        final SessionLifecycleListener listener = new SessionLifecycleListener() {
        };

        listener.onDeleted(SessionDeletedEvent.builder().sessionId(ID).deletedAt(AT).build());
    }
}
