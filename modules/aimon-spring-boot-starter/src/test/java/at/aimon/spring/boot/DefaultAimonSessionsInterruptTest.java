package at.aimon.spring.boot;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.budget.ExecutionBudget;
import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.TurnId;
import at.aimon.session.routing.SessionRouter;

@DisplayName("DefaultAimonSessions interrupt forwarding")
class DefaultAimonSessionsInterruptTest {

    private final SessionRouter router = mock(SessionRouter.class);
    private final DefaultAimonSessions sessions = new DefaultAimonSessions(router, "alpha",
            ExecutionBudget.unlimited());

    @Test
    @DisplayName("interrupt with a detail reaches the router with the detail")
    void detailIsForwarded() {
        final SessionId sessionId = SessionId.of("s-1");
        final TurnId turnId = TurnId.of("t-1");

        sessions.interrupt(sessionId, turnId, InterruptReason.USER_SIGINT, "wrong directory");

        verify(router).interrupt(sessionId, turnId, InterruptReason.USER_SIGINT, "wrong directory");
    }

    @Test
    @DisplayName("interrupt without a detail reaches the router's detail form with none")
    void noDetailIsForwardedAsNull() {
        final SessionId sessionId = SessionId.of("s-2");
        final TurnId turnId = TurnId.of("t-2");

        sessions.interrupt(sessionId, turnId, InterruptReason.USER_SIGINT);

        verify(router).interrupt(sessionId, turnId, InterruptReason.USER_SIGINT, null);
    }
}
