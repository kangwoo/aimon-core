package at.aimon.core.subagent;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Supplies the trusted {@link SubagentExecutionPolicy} a spawn site applies to the subagent it launches.
 *
 * <p>
 * Installed by code — on {@code TaskTool} (the constructor that takes one), or for every agent of a
 * runtime factory ({@code OrcaAgentRuntimeFactory#withSubagentExecutionPolicies}) — and asked with the name of the
 * subagent about to run. The name is the one thing about the launch the model chooses; the policy for it is not, so a
 * model cannot raise a limit by what it writes into the {@code Task} call, and a subagent definition file cannot
 * either.
 * An empty answer launches the subagent exactly as before the policy existed.
 *
 * <p>
 * Implementations must be thread-safe: one resolver serves every launch of a runtime.
 */
@FunctionalInterface
public interface SubagentExecutionPolicyResolver {

    /**
     * Returns the policy for the subagent about to be launched.
     *
     * @param subagentName
     *            the name of the subagent (never null)
     * @return the policy, or empty to launch it without one
     */
    Optional<SubagentExecutionPolicy> resolve(String subagentName);

    /**
     * @return a resolver that applies no policy to any subagent
     */
    static SubagentExecutionPolicyResolver none() {
        return name -> Optional.empty();
    }

    /**
     * Returns a resolver that looks the policy up by subagent name in a fixed map.
     *
     * @param policiesBySubagentName
     *            the policies, keyed by subagent name (copied; must not be null)
     * @return the resolver
     */
    static SubagentExecutionPolicyResolver of(Map<String, SubagentExecutionPolicy> policiesBySubagentName) {
        final Map<String, SubagentExecutionPolicy> policies = Map
                .copyOf(Objects.requireNonNull(policiesBySubagentName, "policiesBySubagentName cannot be null"));
        return name -> Optional.ofNullable(policies.get(name));
    }
}
