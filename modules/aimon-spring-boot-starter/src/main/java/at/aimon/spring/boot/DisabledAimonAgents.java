package at.aimon.spring.boot;

import java.util.List;

import at.aimon.bootstrap.spec.AgentDescriptor;

/**
 * The {@link AimonAgents} published when {@code aimon.enabled=false}.
 *
 * <p>
 * {@link #list()} returns empty rather than throwing: "which agents does this deployment have" has a true
 * answer when AIMON is off, and it is none. An admin page that lists agents should render an empty table on a
 * disabled deployment, not a stack trace.
 *
 * <p>
 * The invalidate methods throw, for the same reason {@link DisabledAimonSessions} does. Silently accepting a
 * request to rebuild a runtime that was never built would make a credential rotation look like it had been
 * applied, and the property responsible would appear in no log.
 */
public class DisabledAimonAgents implements AimonAgents {

    private static final String MESSAGE = "AIMON is disabled (aimon.enabled=false), so there is no agent runtime"
            + " to invalidate. Remove the property or set aimon.enabled=true to start the agent.";

    @Override
    public List<AgentDescriptor> list() {
        return List.of();
    }

    @Override
    public void invalidate(String agentRef, String discriminator) {
        throw new AimonDisabledException(MESSAGE);
    }

    @Override
    public void invalidate(String agentRef) {
        throw new AimonDisabledException(MESSAGE);
    }
}
