package at.aimon.core.agent.impl;

import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.Agent;
import at.aimon.core.skill.SkillRegistry;
import at.aimon.core.subagent.SubagentRegistry;

/**
 * Bundle containing an agent and its associated registries.
 *
 * <p>
 * Groups an {@link Agent} with optional {@link SubagentRegistry} and {@link SkillRegistry} that are bundled alongside
 * the agent definition. This enables per-agent builtin subagent and skill configuration, where each agent can have its
 * own set of bundled subagents and skills.
 *
 * <p>
 * Immutable value object.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     AgentBundle bundle = AgentBundle.builder().agent(myAgent).subagentRegistry(subagentRegistry)
 *             .skillRegistry(skillRegistry).build();
 *
 *     Agent agent = bundle.getAgent();
 *     bundle.getSubagentRegistry().ifPresent(registry -> {
 *         // use registry
 *     });
 * }
 * </pre>
 *
 * @see Agent
 * @see SubagentRegistry
 * @see SkillRegistry
 */
public final class AgentBundle {

    private final Agent agent;
    private final SubagentRegistry subagentRegistry;
    private final SkillRegistry skillRegistry;

    private AgentBundle(Builder builder) {
        this.agent = Objects.requireNonNull(builder.agent, "Agent cannot be null");
        this.subagentRegistry = builder.subagentRegistry;
        this.skillRegistry = builder.skillRegistry;
    }

    /**
     * Creates a new builder.
     *
     * @return a new AgentBundle.Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Gets the agent.
     *
     * @return the agent (never null)
     */
    public Agent getAgent() {
        return agent;
    }

    /**
     * Gets the bundled subagent registry.
     *
     * @return an Optional containing the subagent registry, or empty if not bundled
     */
    public Optional<SubagentRegistry> getSubagentRegistry() {
        return Optional.ofNullable(subagentRegistry);
    }

    /**
     * Gets the bundled skill registry.
     *
     * @return an Optional containing the skill registry, or empty if not bundled
     */
    public Optional<SkillRegistry> getSkillRegistry() {
        return Optional.ofNullable(skillRegistry);
    }

    @Override
    public String toString() {
        return "AgentBundle{" + "agent=" + agent + ", hasSubagentRegistry=" + (subagentRegistry != null)
                + ", hasSkillRegistry=" + (skillRegistry != null) + '}';
    }

    /** Builder for AgentBundle. */
    public static final class Builder {

        private Agent agent;
        private SubagentRegistry subagentRegistry;
        private SkillRegistry skillRegistry;

        private Builder() {
        }

        /**
         * Sets the agent (required).
         *
         * @param agent
         *            the agent (must not be null)
         * @return this builder
         * @throws NullPointerException
         *             if agent is null
         */
        public Builder agent(Agent agent) {
            this.agent = Objects.requireNonNull(agent, "Agent cannot be null");
            return this;
        }

        /**
         * Sets the bundled subagent registry (optional).
         *
         * @param subagentRegistry
         *            the subagent registry, or null
         * @return this builder
         */
        public Builder subagentRegistry(SubagentRegistry subagentRegistry) {
            this.subagentRegistry = subagentRegistry;
            return this;
        }

        /**
         * Sets the bundled skill registry (optional).
         *
         * @param skillRegistry
         *            the skill registry, or null
         * @return this builder
         */
        public Builder skillRegistry(SkillRegistry skillRegistry) {
            this.skillRegistry = skillRegistry;
            return this;
        }

        /**
         * Builds the AgentBundle.
         *
         * @return a new AgentBundle
         * @throws NullPointerException
         *             if agent is null
         */
        public AgentBundle build() {
            return new AgentBundle(this);
        }

    }

}
