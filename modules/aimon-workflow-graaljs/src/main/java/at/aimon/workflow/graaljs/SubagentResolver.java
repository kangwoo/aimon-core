package at.aimon.workflow.graaljs;

import java.util.Collection;
import java.util.Objects;
import java.util.Set;

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
     * The default inline resolver with deterministic SHA-256-derived names and no registry. A step has no attributes:
     * there is no registered definition to copy them from, and a script may set none — a descriptor that carries
     * {@code attributes} fails with a {@code JsScriptException}.
     */
    static SubagentResolver inline() {
        return new InlineSubagentResolver(null, Set.of());
    }

    /**
     * The default inline resolver that also copies the attributes of the subagent registered under a step's
     * {@code agentType}. A script may set none of its own: a descriptor attribute fails with a
     * {@code JsScriptException} unless it restates a registered key with the registered value. Use
     * {@link #inline(SubagentRegistry, Collection)} to let a script set chosen keys.
     *
     * @param registry
     *            the registry looked up by {@code agentType} (must not be null)
     * @return the resolver
     */
    static SubagentResolver inline(SubagentRegistry registry) {
        return inline(registry, Set.of());
    }

    /**
     * The default inline resolver that copies the attributes of the subagent registered under a step's
     * {@code agentType} and lets the script add the keys listed here, and no others.
     *
     * <p>
     * An attribute is what an execution environment provider reads to place a step, and a script is model-authored, so
     * two rules bound what a script can ask for. The registered keys are <b>pinned</b>: a descriptor value for one of
     * them fails with a {@code JsScriptException} unless it is identical, so a script cannot move a registered subagent
     * to another placement. Every other key — one the registered definition does not set, or any key of a step whose
     * {@code agentType} is unregistered or absent — must be in {@code scriptAttributeKeys}, or the step fails the same
     * way, naming the key. Listing a key does not unpin it.
     *
     * @param registry
     *            the registry looked up by {@code agentType} (must not be null)
     * @param scriptAttributeKeys
     *            the attribute keys a script may set, as flattened dotted keys ({@code sandbox.profile}); must not be
     *            null, nor contain a null or blank key. Empty allows none
     * @return the resolver
     * @throws IllegalArgumentException
     *             if a key is null or blank
     */
    static SubagentResolver inline(SubagentRegistry registry, Collection<String> scriptAttributeKeys) {
        return new InlineSubagentResolver(Objects.requireNonNull(registry, "registry must not be null"),
                scriptAttributeKeys);
    }
}
