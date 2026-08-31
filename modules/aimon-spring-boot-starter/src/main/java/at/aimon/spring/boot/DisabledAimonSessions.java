package at.aimon.spring.boot;

import java.util.concurrent.Flow;

import at.aimon.core.agent.AgentExecutionResult;
import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.agent.session.LiveSessionOptions;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.TurnId;
import at.aimon.core.agent.stream.AgentExecutionEvent;
import at.aimon.session.routing.SubmitDisposition;
import at.aimon.session.routing.SubmitRequest;

/**
 * The {@link AimonSessions} published when {@code aimon.enabled=false}.
 *
 * <p>
 * Every method throws {@link AimonDisabledException}. It exists so that turning the agent off is a
 * configuration change and not a code change: the host's own beans keep their {@code AimonSessions}
 * dependency, the context still starts, and no LLM client, agent runtime or workspace is created.
 *
 * <p>
 * Throwing rather than returning something empty is the deliberate half. A no-op that answered with an empty
 * result would be indistinguishable from a working agent that had nothing to say, and the property that caused
 * it would never appear in any log. The message names {@code aimon.enabled} for exactly that reason.
 */
public class DisabledAimonSessions implements AimonSessions {

    private static final String MESSAGE = "AIMON is disabled (aimon.enabled=false), so no turn can run."
            + " Remove the property or set aimon.enabled=true to start the agent.";

    @Override
    public AgentExecutionResult submit(SessionId sessionId, String input) {
        throw new AimonDisabledException(MESSAGE);
    }

    @Override
    public AgentExecutionResult submit(SessionId sessionId, String agentRef, String input, LiveSessionOptions options) {
        throw new AimonDisabledException(MESSAGE);
    }

    @Override
    public SubmitDisposition submitAsync(SessionId sessionId, String input) {
        throw new AimonDisabledException(MESSAGE);
    }

    @Override
    public SubmitDisposition submitAsync(SubmitRequest request) {
        throw new AimonDisabledException(MESSAGE);
    }

    @Override
    public SubmitRequest.Builder newRequest(SessionId sessionId, String input) {
        throw new AimonDisabledException(MESSAGE);
    }

    @Override
    public Flow.Publisher<AgentExecutionEvent> events(SessionId sessionId) {
        throw new AimonDisabledException(MESSAGE);
    }

    @Override
    public void interrupt(SessionId sessionId, TurnId turnId, InterruptReason reason) {
        throw new AimonDisabledException(MESSAGE);
    }

    @Override
    public void release(SessionId sessionId) {
        throw new AimonDisabledException(MESSAGE);
    }
}
