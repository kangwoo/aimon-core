package at.aimon.spring.boot;

import java.util.List;
import java.util.Objects;

import at.aimon.bootstrap.runtime.AgentRuntimeResolver;
import at.aimon.bootstrap.spec.AgentDescriptor;
import at.aimon.core.agent.AgentRuntimeId;

/**
 * The {@link AimonAgents} backed by a running stack.
 *
 * <p>
 * A thin adapter over two things the stack already exposes — the descriptors it built its agents from and the
 * resolver that owns the tenant runtimes. It holds those two rather than the {@code AimonStack} so that the
 * dependency is visible in the constructor and a test can drive it without assembling one.
 */
public class DefaultAimonAgents implements AimonAgents {

    private final List<AgentDescriptor> descriptors;
    private final AgentRuntimeResolver resolver;

    /**
     * Creates the facade.
     *
     * @param descriptors
     *            what each configured agent was built from (must not be null)
     * @param resolver
     *            the resolver owning the tenant runtimes (must not be null)
     */
    public DefaultAimonAgents(List<AgentDescriptor> descriptors, AgentRuntimeResolver resolver) {
        this.descriptors = List.copyOf(Objects.requireNonNull(descriptors, "descriptors must not be null"));
        this.resolver = Objects.requireNonNull(resolver, "resolver must not be null");
    }

    @Override
    public List<AgentDescriptor> list() {
        return descriptors;
    }

    @Override
    public void invalidate(String agentRef, String discriminator) {
        requireText(agentRef, "agentRef");
        requireText(discriminator, "discriminator");
        resolver.invalidate(AgentRuntimeId.fromName(agentRef, discriminator));
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * Iterating the tracked ids is the whole implementation because the resolver tracks tenant runtimes only —
     * the startup runtimes are pinned and never appear here, which is what keeps a sweep of "every runtime of
     * this agent" from taking down the one the fail-fast check ran against.
     */
    @Override
    public void invalidate(String agentRef) {
        requireText(agentRef, "agentRef");
        for (AgentRuntimeId id : resolver.trackedIds()) {
            if (agentRef.equals(id.agentName())) {
                resolver.invalidate(id);
            }
        }
    }

    private static void requireText(String value, String what) {
        Objects.requireNonNull(value, what + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(what + " must not be blank");
        }
    }
}
