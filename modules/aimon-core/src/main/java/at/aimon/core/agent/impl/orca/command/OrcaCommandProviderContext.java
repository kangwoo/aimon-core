package at.aimon.core.agent.impl.orca.command;

import java.util.Objects;

import at.aimon.core.agent.Environment;
import at.aimon.core.agent.compact.CompactionEngine;
import at.aimon.core.agent.compact.CompactionGuard;
import at.aimon.core.agent.orca.OrcaProviderDependencies;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.command.CommandRegistry;
import at.aimon.core.credential.CredentialStore;
import at.aimon.core.hook.HookExecutionManager;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.hook.rewake.RewakeService;
import at.aimon.core.scheduling.ScheduledTaskManager;
import at.aimon.core.skill.SkillRegistry;
import at.aimon.core.skill.policy.agent.AgentApprovalStore;
import at.aimon.core.skill.policy.pending.PendingTurnRegistry;
import at.aimon.core.skill.policy.session.SessionApprovalStore;
import at.aimon.core.subagent.SubagentExecutionManager;
import at.aimon.core.subagent.SubagentRegistry;

/**
 * Context providing dependencies for Orca command providers.
 *
 * <p>
 * This class aggregates all dependencies that command providers might need when creating and registering commands. It
 * uses the Builder pattern to provide a fluent API for construction.
 *
 * <p>
 * Common registry dependencies are held via {@link OrcaProviderDependencies} (composition), while command-specific
 * fields ({@code commandRegistry}, {@code version}) are held directly.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     OrcaCommandProviderContext context = OrcaCommandProviderContext.builder().commandRegistry(commandRegistry)
 *             .version("1.0.0").dependencies(deps).build();
 * }
 * </pre>
 *
 * @see OrcaCommandProvider
 */
public final class OrcaCommandProviderContext {

    /**
     * Creates a new builder instance.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    private final CommandRegistry commandRegistry;
    private final String version;
    private final OrcaProviderDependencies dependencies;

    private OrcaCommandProviderContext(Builder builder) {
        commandRegistry = builder.commandRegistry;
        version = builder.version;
        dependencies = Objects.requireNonNull(builder.dependencies, "dependencies must not be null");
    }

    /**
     * Returns the command registry.
     *
     * @return the command registry, may be null
     */
    public CommandRegistry getCommandRegistry() {
        return commandRegistry;
    }

    /**
     * Returns the version string.
     *
     * @return the version string, may be null
     */
    public String getVersion() {
        return version;
    }

    /**
     * Returns the shared provider dependencies.
     *
     * @return the provider dependencies (never null)
     */
    public OrcaProviderDependencies getDependencies() {
        return dependencies;
    }

    /**
     * Returns the subagent registry.
     *
     * @return the subagent registry, may be null
     */
    public SubagentRegistry getSubagentRegistry() {
        return dependencies.getSubagentRegistry();
    }

    /**
     * Returns the subagent execution manager.
     *
     * @return the subagent execution manager, may be null
     */
    public SubagentExecutionManager getSubagentExecutionManager() {
        return dependencies.getSubagentExecutionManager();
    }

    /**
     * Returns the skill registry.
     *
     * @return the skill registry, may be null
     */
    public SkillRegistry getSkillRegistry() {
        return dependencies.getSkillRegistry();
    }

    /**
     * Returns the tool registry.
     *
     * @return the tool registry, may be null
     */
    public ToolRegistry getToolRegistry() {
        return dependencies.getToolRegistry();
    }

    /**
     * Returns the hook registry.
     *
     * @return the hook registry, may be null
     */
    public HookRegistry getHookRegistry() {
        return dependencies.getHookRegistry();
    }

    /**
     * Returns the hook execution manager.
     *
     * @return the hook execution manager, may be null
     */
    public HookExecutionManager getHookExecutionManager() {
        return dependencies.getHookExecutionManager();
    }

    /**
     * Returns the scheduled task manager.
     *
     * @return the scheduled task manager, may be null
     */
    public ScheduledTaskManager getScheduledTaskManager() {
        return dependencies.getScheduledTaskManager();
    }

    /**
     * Returns the credential store.
     *
     * @return the credential store, may be null
     */
    public CredentialStore getCredentialStore() {
        return dependencies.getCredentialStore();
    }

    /**
     * Returns the runtime environment.
     *
     * @return the environment, may be null
     */
    public Environment getEnvironment() {
        return dependencies.getEnvironment();
    }

    /**
     * Returns the conversation compaction engine.
     *
     * @return the compaction engine, may be null when compaction is not configured
     */
    public CompactionEngine getCompactionEngine() {
        return dependencies.getCompactionEngine();
    }

    /**
     * Returns the compaction guard.
     *
     * @return the compaction guard, may be null when compaction is not configured
     */
    public CompactionGuard getCompactionGuard() {
        return dependencies.getCompactionGuard();
    }

    /**
     * Returns the pending turn registry used by the SK-11 atomic-suspension flow.
     *
     * @return the pending turn registry, may be null when skill suspension is not configured
     */
    public PendingTurnRegistry getPendingTurnRegistry() {
        return dependencies.getPendingTurnRegistry();
    }

    /**
     * Returns the agent-scoped skill approval store used by SK-11 to remember user-granted approvals.
     *
     * @return the approval store, may be null when skill suspension is not configured
     */
    public AgentApprovalStore getAgentApprovalStore() {
        return dependencies.getAgentApprovalStore();
    }

    /**
     * Returns the session-scoped skill approval store the commands act on by default.
     *
     * @return the approval store, may be null when SK-11 wiring is absent
     */
    public SessionApprovalStore getSessionApprovalStore() {
        return dependencies.getSessionApprovalStore();
    }

    /**
     * Returns the application-scoped {@link RewakeService} used by the {@code /rewakes} command.
     *
     * @return the rewake service, may be null when async-rewake is not wired
     */
    public RewakeService getRewakeService() {
        return dependencies.getRewakeService();
    }

    /**
     * Builder for {@link OrcaCommandProviderContext}.
     */
    public static final class Builder {
        private CommandRegistry commandRegistry;
        private String version;
        private OrcaProviderDependencies dependencies;

        private Builder() {
        }

        /**
         * Sets the command registry.
         *
         * @param commandRegistry
         *            the command registry
         * @return this builder
         */
        public Builder commandRegistry(CommandRegistry commandRegistry) {
            this.commandRegistry = commandRegistry;
            return this;
        }

        /**
         * Sets the version string.
         *
         * @param version
         *            the version string
         * @return this builder
         */
        public Builder version(String version) {
            this.version = version;
            return this;
        }

        /**
         * Sets the shared provider dependencies.
         *
         * @param dependencies
         *            the provider dependencies
         * @return this builder
         */
        public Builder dependencies(OrcaProviderDependencies dependencies) {
            this.dependencies = dependencies;
            return this;
        }

        /**
         * Builds the context.
         *
         * @return a new {@link OrcaCommandProviderContext} instance
         */
        public OrcaCommandProviderContext build() {
            return new OrcaCommandProviderContext(this);
        }
    }
}
