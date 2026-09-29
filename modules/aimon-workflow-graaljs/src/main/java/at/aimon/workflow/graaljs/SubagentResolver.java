package at.aimon.workflow.graaljs;

import java.util.Objects;

import at.aimon.core.subagent.Subagent;
import at.aimon.core.subagent.SubagentRegistry;

/**
 * Resolves a JS agent descriptor's identity fields ({@link SubagentDescriptor}) into a core {@link Subagent}.
 *
 * <p>
 * {@code AgentTask} carries an <b>inline</b> subagent, and {@code Subagent.builder} requires {@code name} +
 * {@code systemPrompt}. Implementations therefore synthesize a <b>deterministic, cross-JVM-stable</b> name so
 * shared/persistent resume caches replay without spurious misses. A registered subagent of the same
 * {@code agentType} is not used in place of the inline one: the default resolver consults the registry for its
 * {@code attributes} only (EE-42), so that an execution environment provider can place the step as it would place that
 * subagent.
 */
public interface SubagentResolver {

    /**
     * Builds an inline {@link Subagent} from a descriptor.
     *
     * @param descriptor
     *            the step's identity fields (never null); must carry an {@code agentType} or a {@code systemPrompt}
     * @return an inline subagent (never {@code null})
     */
    Subagent resolve(SubagentDescriptor descriptor);

    /**
     * The default inline resolver with deterministic SHA-256-derived names and no registry: a step's attributes are
     * only the ones its descriptor gives.
     */
    static SubagentResolver inline() {
        return new InlineSubagentResolver(null);
    }

    /**
     * The default inline resolver that also copies the attributes of the subagent registered under a step's
     * {@code agentType}, with the descriptor's own attributes winning on the same key.
     *
     * @param registry
     *            the registry looked up by {@code agentType} (must not be null)
     * @return the resolver
     */
    static SubagentResolver inline(SubagentRegistry registry) {
        return new InlineSubagentResolver(Objects.requireNonNull(registry, "registry must not be null"));
    }
}
