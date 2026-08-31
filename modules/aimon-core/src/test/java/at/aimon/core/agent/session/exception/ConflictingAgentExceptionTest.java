package at.aimon.core.agent.session.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.base.exception.AimonException;

class ConflictingAgentExceptionTest {

    @Test
    void carriesAllFieldsAndBuildsActionableMessage() {
        SessionId id = new SessionId("conv-42");
        ConflictingAgentException ex = new ConflictingAgentException(id, "agent-new", "agent-existing");

        assertThat(ex).isInstanceOf(AimonException.class);
        assertThat(ex.getSessionId()).isEqualTo(id);
        assertThat(ex.getRequestedAgent()).isEqualTo("agent-new");
        assertThat(ex.getExistingAgent()).isEqualTo("agent-existing");
        assertThat(ex.getMessage()).contains("conv-42").contains("agent-new").contains("agent-existing");
    }

    @Test
    void rejectsNullArguments() {
        SessionId id = new SessionId("c");
        assertThatThrownBy(() -> new ConflictingAgentException(null, "a", "b"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ConflictingAgentException(id, null, "b")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ConflictingAgentException(id, "a", null)).isInstanceOf(NullPointerException.class);
    }
}
