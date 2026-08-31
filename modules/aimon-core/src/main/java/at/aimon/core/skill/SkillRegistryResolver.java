package at.aimon.core.skill;

import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.AgentRuntimeId;

/**
 * Answers which {@link SkillRegistry} serves a given agent runtime.
 *
 * <p>
 * A {@code SkillRegistry} is <b>agent-scoped</b>: it is materialised per agent from that agent's bundle, and two
 * agents in one process hold two different registries. Anything application-scoped that has to resolve a skill
 * name therefore cannot hold a registry — it has to ask, per runtime, which one applies. That is what this
 * interface is for, and {@code SkillPreflightScanner} is its reason for existing: the scanner lives inside the
 * shared agent executor, so a fixed registry there silently resolves every agent's skill names through the first
 * agent's bundle.
 *
 * <p>
 * <b>The failure it prevents is silent, which is why it is an interface rather than a field.</b> A skill the
 * scanner cannot resolve is skipped, and skipping means the invocation reaches {@code SkillTool}, which re-checks
 * the policy and refuses on {@code ASK}. The user is never prompted and the skill simply never runs — no error,
 * no log at a level anyone watches. Resolving through the runtime that is actually executing the turn makes that
 * state unreachable.
 *
 * <p>
 * Implementations must be thread-safe: a resolver is consulted from every turn loop in the process, and in a
 * deployment with lazily created tenant runtimes the set of resolvable ids grows while those calls are in
 * flight.
 */
@FunctionalInterface
public interface SkillRegistryResolver {

    /**
     * Resolves the registry serving the given runtime.
     *
     * @param agentRuntimeId
     *            the runtime executing the turn; may be null for system or scheduled paths that have no runtime
     *            bound
     * @return the registry, or empty when the id resolves to nothing — an unregistered, evicted or invalidated
     *         runtime. Callers must treat empty as "no skill names can be resolved", not as "no skills exist".
     */
    Optional<SkillRegistry> resolve(AgentRuntimeId agentRuntimeId);

    /**
     * Returns a resolver that answers with one registry regardless of the runtime asked about.
     *
     * <p>
     * Correct for a single-agent process and for tests, and wrong the moment a second agent exists — which is
     * exactly why the multi-agent form is the interface and this is the named exception to it.
     *
     * @param registry
     *            the registry to answer with (must not be null)
     * @return a resolver over that single registry
     * @throws NullPointerException
     *             if {@code registry} is null
     */
    static SkillRegistryResolver fixed(SkillRegistry registry) {
        Objects.requireNonNull(registry, "registry cannot be null");
        final Optional<SkillRegistry> answer = Optional.of(registry);
        return agentRuntimeId -> answer;
    }
}
