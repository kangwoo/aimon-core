package at.aimon.spring.boot;

import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Flow;

import at.aimon.core.agent.AgentExecutionResult;
import at.aimon.core.agent.budget.ExecutionBudget;
import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.agent.session.LiveSessionOptions;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.TurnId;
import at.aimon.core.agent.stream.AgentExecutionEvent;
import at.aimon.core.base.Principal;
import at.aimon.session.routing.SessionRouter;
import at.aimon.session.routing.SubmitDisposition;
import at.aimon.session.routing.SubmitRequest;

/**
 * {@link AimonSessions} over a {@link SessionRouter}, applying the configured defaults to every request.
 *
 * <p>
 * The budget deserves a note, because it is the one default that would otherwise be lost in silence.
 * {@code AimonStackSpec.getDefaultBudget()} has no consumer inside the stack — the live-session opener passes
 * the caller's {@link LiveSessionOptions} straight through, so a budget configured on the spec reaches nothing
 * unless somebody puts it on the request. That somebody is this class. Without it,
 * {@code aimon.budget.max-iterations} would be a property that binds, validates, appears in IDE completion,
 * and does nothing — which is worse than not offering it at all.
 */
public class DefaultAimonSessions implements AimonSessions {

    private final SessionRouter sessionRouter;

    private final String defaultAgentRef;

    private final LiveSessionOptions defaultOptions;

    private final Principal defaultInitiator;

    /**
     * Creates a facade over the given router.
     *
     * @param sessionRouter
     *            the router that owns session routing and turn execution (must not be null)
     * @param defaultAgentRef
     *            the agent used when a caller does not name one (must not be null)
     * @param defaultBudget
     *            the budget attached to requests that carry no options of their own (must not be null)
     */
    public DefaultAimonSessions(SessionRouter sessionRouter, String defaultAgentRef, ExecutionBudget defaultBudget) {
        this.sessionRouter = Objects.requireNonNull(sessionRouter, "Session router cannot be null");
        this.defaultAgentRef = Objects.requireNonNull(defaultAgentRef, "Default agent ref cannot be null");
        this.defaultOptions = LiveSessionOptions.builder()
                .budget(Objects.requireNonNull(defaultBudget, "Default budget cannot be null")).build();
        this.defaultInitiator = Principal.system();
    }

    @Override
    public AgentExecutionResult submit(SessionId sessionId, String input) {
        return submit(sessionId, null, input, null);
    }

    @Override
    public AgentExecutionResult submit(SessionId sessionId, String agentRef, String input, LiveSessionOptions options) {
        final SubmitRequest.Builder builder = newRequest(sessionId, input);
        if (agentRef != null) {
            builder.agentRef(agentRef);
        }
        if (options != null) {
            builder.options(options);
        }
        return await(submitAsync(builder.build()));
    }

    @Override
    public SubmitDisposition submitAsync(SessionId sessionId, String input) {
        return submitAsync(newRequest(sessionId, input).build());
    }

    @Override
    public SubmitDisposition submitAsync(SubmitRequest request) {
        Objects.requireNonNull(request, "Request cannot be null");
        return sessionRouter.submit(request);
    }

    @Override
    public SubmitRequest.Builder newRequest(SessionId sessionId, String input) {
        Objects.requireNonNull(sessionId, "Session id cannot be null");
        Objects.requireNonNull(input, "Input cannot be null");
        return SubmitRequest.builder().sessionId(sessionId).userInput(input).agentRef(defaultAgentRef)
                .initiator(defaultInitiator).options(defaultOptions);
    }

    @Override
    public Flow.Publisher<AgentExecutionEvent> events(SessionId sessionId) {
        Objects.requireNonNull(sessionId, "Session id cannot be null");
        return sessionRouter.events(sessionId);
    }

    @Override
    public void interrupt(SessionId sessionId, TurnId turnId, InterruptReason reason) {
        Objects.requireNonNull(sessionId, "Session id cannot be null");
        Objects.requireNonNull(turnId, "Turn id cannot be null");
        Objects.requireNonNull(reason, "Interrupt reason cannot be null");
        sessionRouter.interrupt(sessionId, turnId, reason);
    }

    @Override
    public void release(SessionId sessionId) {
        Objects.requireNonNull(sessionId, "Session id cannot be null");
        sessionRouter.releaseSession(sessionId);
    }

    /**
     * Blocks on a disposition's future, unwrapping the {@link CompletionException} the join wrapper adds.
     *
     * <p>
     * Rethrowing the wrapper would hand the caller a stack trace whose top frame is this method and whose
     * message is the class name of the real failure — the agent's own exception, one level down, is what the
     * caller wrote a catch block for.
     */
    private static AgentExecutionResult await(SubmitDisposition disposition) {
        try {
            return disposition.getFuture().toCompletableFuture().join();
        } catch (CompletionException e) {
            final Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw e;
        }
    }
}
