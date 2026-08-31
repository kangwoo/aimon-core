package at.aimon.core.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.ExecutionId;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.tool.ToolContext;

@DisplayName("InvokingSessionAccess")
class InvokingSessionAccessTest {

    @Test
    @DisplayName("invokerOf reads the key verbatim and does not fall back")
    void invokerOfDoesNotFallBack() {
        final SessionId own = SessionId.generate();
        final ToolContext context = ToolContext.builder().put(ToolContextKeys.SESSION_ID, own).build();

        // The main agent has a session but no invoker. Reporting its own id here would make it look like a run
        // someone else delegated, which is exactly the confusion the two methods exist to keep apart.
        assertThat(InvokingSessionAccess.invokerOf(context)).isEmpty();
    }

    @Test
    void invokerOfReturnsTheKeyWhenPresent() {
        final SessionId invoker = SessionId.generate();
        final ToolContext context = ToolContext.builder().put(ToolContextKeys.INVOKING_SESSION_ID, invoker).build();

        assertThat(InvokingSessionAccess.invokerOf(context)).contains(invoker);
    }

    @Test
    @DisplayName("idToPropagate hands down the spawner's own id when the spawner is the origin")
    void idToPropagateFallsBackToOwnConversation() {
        final SessionId own = SessionId.generate();
        // A session's turn: the origin of every user-initiated reach, and the one case where reading the context's own
        // session id is right rather than a confusion of the two senses. It publishes no execution id, so the guard
        // that suppresses this read for a session-less run does not apply.
        final ToolContext context = ToolContext.builder().put(ToolContextKeys.SESSION_ID, own).build();

        assertThat(InvokingSessionAccess.idToPropagate(context)).contains(own);
    }

    @Test
    @DisplayName("idToPropagate hands down the inherited id, not the intermediate fork's own — nesting depth 2")
    void idToPropagatePrefersTheInheritedId() {
        final SessionId user = SessionId.generate();
        // The context of a fork that is itself about to spawn something. Propagating the fork's own identity would make
        // the reach stop exactly one level deep: nothing was ever granted under a fork's own id.
        final ToolContext context = ToolContext.builder()
                .put(ToolContextKeys.EXECUTION_ID, ExecutionId.generate("subagent:reviewer"))
                .put(ToolContextKeys.INVOKING_SESSION_ID, user).build();

        assertThat(InvokingSessionAccess.idToPropagate(context)).contains(user);
    }

    /**
     * The guard that keeps the two senses of a {@link SessionId} from being interchangeable. A run that publishes an
     * execution id has stated it has no session to offer, so its own {@code SESSION_ID} — however it got there — must
     * not be promoted into an id the spawned run believes a user granted it.
     */
    @Test
    @DisplayName("idToPropagate refuses to promote a session-less run's own session id")
    void idToPropagateDoesNotPromoteASessionlessRunsOwnId() {
        final ToolContext context = ToolContext.builder()
                .put(ToolContextKeys.EXECUTION_ID, ExecutionId.generate("subagent:reviewer"))
                .put(ToolContextKeys.SESSION_ID, SessionId.generate()).build();

        assertThat(InvokingSessionAccess.idToPropagate(context)).isEmpty();
    }

    @Test
    void bothReturnEmptyWhenTheContextCarriesNoConversation() {
        assertThat(InvokingSessionAccess.invokerOf(ToolContext.empty())).isEmpty();
        assertThat(InvokingSessionAccess.idToPropagate(ToolContext.empty())).isEmpty();
    }

    @Test
    void nullContextIsRejected() {
        assertThatThrownBy(() -> InvokingSessionAccess.invokerOf(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> InvokingSessionAccess.idToPropagate(null)).isInstanceOf(NullPointerException.class);
    }
}
