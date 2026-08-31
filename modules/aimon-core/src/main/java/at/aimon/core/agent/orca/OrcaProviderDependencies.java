package at.aimon.core.agent.orca;

import at.aimon.core.agent.Environment;
import at.aimon.core.agent.compact.CompactionEngine;
import at.aimon.core.agent.compact.CompactionGuard;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.credential.CredentialStore;
import at.aimon.core.hook.HookExecutionManager;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.hook.rewake.RewakeService;
import at.aimon.core.scheduling.ScheduledTaskManager;
import at.aimon.core.skill.SkillRegistry;
import at.aimon.core.skill.policy.SkillInvocationPolicy;
import at.aimon.core.skill.policy.agent.AgentApprovalStore;
import at.aimon.core.skill.policy.pending.PendingTurnRegistry;
import at.aimon.core.skill.policy.session.SessionApprovalStore;
import at.aimon.core.subagent.SubagentExecutionManager;
import at.aimon.core.subagent.SubagentRegistry;
import at.aimon.core.subagent.task.SessionSnapshotStore;
import at.aimon.core.subagent.task.TaskOutputStore;
import at.aimon.core.subagent.task.TaskResultStore;

/**
 * Shared dependencies used by both Orca tool providers and command providers.
 *
 * <p>
 * This class extracts the common registry and manager dependencies that {@code OrcaToolProviderContext} and
 * {@code OrcaCommandProviderContext} both require, eliminating duplication and ensuring consistency.
 *
 * <p>
 * Immutable value object constructed via the Builder pattern.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     OrcaProviderDependencies deps = OrcaProviderDependencies.builder().subagentRegistry(subagentRegistry)
 *             .subagentExecutionManager(subagentExecutionManager).skillRegistry(skillRegistry)
 *             .toolRegistry(toolRegistry).hookRegistry(hookRegistry).scheduledTaskManager(scheduledTaskManager)
 *             .credentialStore(credentialStore).environment(environment).compactionEngine(engine)
 *             .compactionGuard(guard).build();
 * }
 * </pre>
 */
public final class OrcaProviderDependencies {

    /**
     * Creates a new builder instance.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    private final SubagentRegistry subagentRegistry;
    private final SubagentExecutionManager subagentExecutionManager;
    private final TaskOutputStore taskOutputStore;
    private final TaskResultStore taskResultStore;
    private final SessionSnapshotStore sessionSnapshotStore;
    private final SkillRegistry skillRegistry;
    private final ToolRegistry toolRegistry;
    private final HookRegistry hookRegistry;
    private final HookExecutionManager hookExecutionManager;
    private final ScheduledTaskManager scheduledTaskManager;
    private final CredentialStore credentialStore;
    private final Environment environment;
    private final CompactionEngine compactionEngine;
    private final CompactionGuard compactionGuard;
    private final PendingTurnRegistry pendingTurnRegistry;
    private final AgentApprovalStore agentApprovalStore;
    private final SessionApprovalStore sessionApprovalStore;
    private final SkillInvocationPolicy skillInvocationPolicy;
    private final RewakeService rewakeService;

    private OrcaProviderDependencies(Builder builder) {
        subagentRegistry = builder.subagentRegistry;
        subagentExecutionManager = builder.subagentExecutionManager;
        taskOutputStore = builder.taskOutputStore;
        taskResultStore = builder.taskResultStore;
        sessionSnapshotStore = builder.sessionSnapshotStore;
        skillRegistry = builder.skillRegistry;
        toolRegistry = builder.toolRegistry;
        hookRegistry = builder.hookRegistry;
        hookExecutionManager = builder.hookExecutionManager;
        scheduledTaskManager = builder.scheduledTaskManager;
        credentialStore = builder.credentialStore;
        environment = builder.environment;
        compactionEngine = builder.compactionEngine;
        compactionGuard = builder.compactionGuard;
        pendingTurnRegistry = builder.pendingTurnRegistry;
        agentApprovalStore = builder.agentApprovalStore;
        sessionApprovalStore = builder.sessionApprovalStore;
        skillInvocationPolicy = builder.skillInvocationPolicy;
        rewakeService = builder.rewakeService;
    }

    /**
     * Returns the subagent registry.
     *
     * @return the subagent registry, may be null
     */
    public SubagentRegistry getSubagentRegistry() {
        return subagentRegistry;
    }

    /**
     * Returns the subagent execution manager.
     *
     * @return the subagent execution manager, may be null
     */
    public SubagentExecutionManager getSubagentExecutionManager() {
        return subagentExecutionManager;
    }

    /**
     * Returns the task output store used to record and tail background subagents' live progress logs.
     *
     * @return the task output store, may be null when live-output streaming is not configured
     */
    public TaskOutputStore getTaskOutputStore() {
        return taskOutputStore;
    }

    /**
     * Returns the task result store holding what each background task finally produced.
     *
     * @return the task result store, may be null when result retention is not configured
     */
    public TaskResultStore getTaskResultStore() {
        return taskResultStore;
    }

    /**
     * Returns the session snapshot store used to persist a finished subagent's transcript for later resume.
     *
     * @return the session snapshot store, may be null when resume is not configured
     */
    public SessionSnapshotStore getSessionSnapshotStore() {
        return sessionSnapshotStore;
    }

    /**
     * Returns the skill registry.
     *
     * @return the skill registry, may be null
     */
    public SkillRegistry getSkillRegistry() {
        return skillRegistry;
    }

    /**
     * Returns the tool registry.
     *
     * @return the tool registry, may be null
     */
    public ToolRegistry getToolRegistry() {
        return toolRegistry;
    }

    /**
     * Returns the hook registry.
     *
     * @return the hook registry, may be null
     */
    public HookRegistry getHookRegistry() {
        return hookRegistry;
    }

    /**
     * Returns the hook execution manager.
     *
     * <p>
     * Used by command providers (e.g. {@code CompactCommand}) that need to fire {@code OnStopHook} or other lifecycle
     * hooks outside the agent execution loop.
     *
     * @return the hook execution manager, may be null when not configured
     */
    public HookExecutionManager getHookExecutionManager() {
        return hookExecutionManager;
    }

    /**
     * Returns the scheduled task manager.
     *
     * @return the scheduled task manager, may be null
     */
    public ScheduledTaskManager getScheduledTaskManager() {
        return scheduledTaskManager;
    }

    /**
     * Returns the credential store.
     *
     * @return the credential store, may be null
     */
    public CredentialStore getCredentialStore() {
        return credentialStore;
    }

    /**
     * Returns the runtime environment.
     *
     * @return the environment, may be null
     */
    public Environment getEnvironment() {
        return environment;
    }

    /**
     * Returns the conversation compaction engine.
     *
     * @return the compaction engine, may be null when compaction is not configured
     */
    public CompactionEngine getCompactionEngine() {
        return compactionEngine;
    }

    /**
     * Returns the compaction guard.
     *
     * @return the compaction guard, may be null when compaction is not configured
     */
    public CompactionGuard getCompactionGuard() {
        return compactionGuard;
    }

    /**
     * Returns the pending turn registry used by the SK-11 atomic-suspension flow.
     *
     * @return the pending turn registry, may be null when skill suspension is not configured
     */
    public PendingTurnRegistry getPendingTurnRegistry() {
        return pendingTurnRegistry;
    }

    /**
     * Returns the agent-scoped skill approval store consulted by SK-11 to short-circuit ASK decisions for skills the
     * user already approved. Entries are keyed by {@code AgentRuntimeId}, so an approval carries across every
     * session of that agent, not just the one that granted it.
     *
     * @return the approval store, may be null when skill suspension is not configured
     */
    public AgentApprovalStore getAgentApprovalStore() {
        return agentApprovalStore;
    }

    /**
     * Returns the session-scoped skill approval store — the narrow default the approval channel writes a plain
     * {@code y} into. Entries are keyed by {@code SessionId}, so an approval granted here stops at the
     * session that granted it.
     *
     * <p>
     * The commands consult it before {@link #getAgentApprovalStore()}: {@code /clear} drops this session's
     * entries, and {@code /revoke}, {@code /approve} and {@code /deny} act on this scope unless the user passes
     * {@code --agent}.
     *
     * @return the session-scoped approval store, may be null when SK-11 wiring is absent
     */
    public SessionApprovalStore getSessionApprovalStore() {
        return sessionApprovalStore;
    }

    /**
     * Returns the {@link SkillInvocationPolicy} that gates {@code Skill} tool invocations.
     *
     * <p>
     * When non-null, both the {@code OrcaAgentExecutor}'s pre-flight scanner (SK-11.4) and the {@code SkillTool}
     * itself (SK-11.1) consult this policy so the suspend/approve flow and the per-tool fail-closed path agree on
     * which skills require approval. When null, callers fall back to {@code AlwaysAllowSkillInvocationPolicy} for
     * backward compatibility with deployments that have not yet wired SK-11.
     *
     * @return the skill invocation policy, may be null when SK-11 wiring is absent
     */
    public SkillInvocationPolicy getSkillInvocationPolicy() {
        return skillInvocationPolicy;
    }

    /**
     * Returns the application-scoped {@link RewakeService} used to schedule and surface async-rewake envelopes.
     *
     * @return the rewake service, may be null when async-rewake wiring is absent (callers should fall back to
     *         {@link RewakeService#NOOP} or skip the related command registration)
     */
    public RewakeService getRewakeService() {
        return rewakeService;
    }

    /**
     * Builder for {@link OrcaProviderDependencies}.
     */
    public static final class Builder {
        private SubagentRegistry subagentRegistry;
        private SubagentExecutionManager subagentExecutionManager;
        private TaskOutputStore taskOutputStore;
        private TaskResultStore taskResultStore;
        private SessionSnapshotStore sessionSnapshotStore;
        private SkillRegistry skillRegistry;
        private ToolRegistry toolRegistry;
        private HookRegistry hookRegistry;
        private HookExecutionManager hookExecutionManager;
        private ScheduledTaskManager scheduledTaskManager;
        private CredentialStore credentialStore;
        private Environment environment;
        private CompactionEngine compactionEngine;
        private CompactionGuard compactionGuard;
        private PendingTurnRegistry pendingTurnRegistry;
        private AgentApprovalStore agentApprovalStore;
        private SessionApprovalStore sessionApprovalStore;
        private SkillInvocationPolicy skillInvocationPolicy;
        private RewakeService rewakeService;

        private Builder() {
        }

        /**
         * Sets the subagent registry.
         *
         * @param subagentRegistry
         *            the subagent registry
         * @return this builder
         */
        public Builder subagentRegistry(SubagentRegistry subagentRegistry) {
            this.subagentRegistry = subagentRegistry;
            return this;
        }

        /**
         * Sets the subagent execution manager.
         *
         * @param subagentExecutionManager
         *            the subagent execution manager
         * @return this builder
         */
        public Builder subagentExecutionManager(SubagentExecutionManager subagentExecutionManager) {
            this.subagentExecutionManager = subagentExecutionManager;
            return this;
        }

        /**
         * Sets the task output store used to record and tail background subagents' live progress logs.
         *
         * @param taskOutputStore
         *            the task output store (may be null when live-output streaming is not configured)
         * @return this builder
         */
        public Builder taskOutputStore(TaskOutputStore taskOutputStore) {
            this.taskOutputStore = taskOutputStore;
            return this;
        }

        /**
         * Sets the task result store holding what each background task finally produced.
         *
         * @param taskResultStore
         *            the task result store (may be null when result retention is not configured)
         * @return this builder
         */
        public Builder taskResultStore(TaskResultStore taskResultStore) {
            this.taskResultStore = taskResultStore;
            return this;
        }

        /**
         * Sets the session snapshot store used to persist a finished subagent's transcript for later resume.
         *
         * @param sessionSnapshotStore
         *            the session snapshot store (may be null when resume is not configured)
         * @return this builder
         */
        public Builder sessionSnapshotStore(SessionSnapshotStore sessionSnapshotStore) {
            this.sessionSnapshotStore = sessionSnapshotStore;
            return this;
        }

        /**
         * Sets the skill registry.
         *
         * @param skillRegistry
         *            the skill registry
         * @return this builder
         */
        public Builder skillRegistry(SkillRegistry skillRegistry) {
            this.skillRegistry = skillRegistry;
            return this;
        }

        /**
         * Sets the tool registry.
         *
         * @param toolRegistry
         *            the tool registry
         * @return this builder
         */
        public Builder toolRegistry(ToolRegistry toolRegistry) {
            this.toolRegistry = toolRegistry;
            return this;
        }

        /**
         * Sets the hook registry.
         *
         * @param hookRegistry
         *            the hook registry
         * @return this builder
         */
        public Builder hookRegistry(HookRegistry hookRegistry) {
            this.hookRegistry = hookRegistry;
            return this;
        }

        /**
         * Sets the hook execution manager.
         *
         * @param hookExecutionManager
         *            the hook execution manager (may be null)
         * @return this builder
         */
        public Builder hookExecutionManager(HookExecutionManager hookExecutionManager) {
            this.hookExecutionManager = hookExecutionManager;
            return this;
        }

        /**
         * Sets the scheduled task manager.
         *
         * @param scheduledTaskManager
         *            the scheduled task manager
         * @return this builder
         */
        public Builder scheduledTaskManager(ScheduledTaskManager scheduledTaskManager) {
            this.scheduledTaskManager = scheduledTaskManager;
            return this;
        }

        /**
         * Sets the credential store.
         *
         * @param credentialStore
         *            the credential store
         * @return this builder
         */
        public Builder credentialStore(CredentialStore credentialStore) {
            this.credentialStore = credentialStore;
            return this;
        }

        /**
         * Sets the runtime environment.
         *
         * @param environment
         *            the environment
         * @return this builder
         */
        public Builder environment(Environment environment) {
            this.environment = environment;
            return this;
        }

        /**
         * Sets the compaction engine.
         *
         * @param compactionEngine
         *            the compaction engine (may be null)
         * @return this builder
         */
        public Builder compactionEngine(CompactionEngine compactionEngine) {
            this.compactionEngine = compactionEngine;
            return this;
        }

        /**
         * Sets the compaction guard.
         *
         * @param compactionGuard
         *            the compaction guard (may be null)
         * @return this builder
         */
        public Builder compactionGuard(CompactionGuard compactionGuard) {
            this.compactionGuard = compactionGuard;
            return this;
        }

        /**
         * Sets the pending turn registry.
         *
         * @param pendingTurnRegistry
         *            the pending turn registry (may be null when skill suspension is not configured)
         * @return this builder
         */
        public Builder pendingTurnRegistry(PendingTurnRegistry pendingTurnRegistry) {
            this.pendingTurnRegistry = pendingTurnRegistry;
            return this;
        }

        /**
         * Sets the agent-scoped skill approval store.
         *
         * @param agentApprovalStore
         *            the approval store (may be null when skill suspension is not configured)
         * @return this builder
         */
        public Builder agentApprovalStore(AgentApprovalStore agentApprovalStore) {
            this.agentApprovalStore = agentApprovalStore;
            return this;
        }

        /**
         * Sets the session-scoped skill approval store, the narrow default the commands act on.
         *
         * @param sessionApprovalStore
         *            the approval store (may be null when SK-11 wiring is absent, in which case the commands fall
         *            back to the agent-scoped store)
         * @return this builder
         */
        public Builder sessionApprovalStore(SessionApprovalStore sessionApprovalStore) {
            this.sessionApprovalStore = sessionApprovalStore;
            return this;
        }

        /**
         * Sets the {@link SkillInvocationPolicy} consulted by the executor's pre-flight scanner and the
         * {@link at.aimon.core.tools.skill.SkillTool}'s per-call check. Both should reference the same policy
         * instance so suspend/approve decisions agree with per-tool fail-closed behaviour.
         *
         * @param skillInvocationPolicy
         *            the skill invocation policy (may be null when SK-11 wiring is absent)
         * @return this builder
         */
        public Builder skillInvocationPolicy(SkillInvocationPolicy skillInvocationPolicy) {
            this.skillInvocationPolicy = skillInvocationPolicy;
            return this;
        }

        /**
         * Sets the application-scoped {@link RewakeService} that backs the {@code /rewakes} command and any other
         * rewake-aware surface. Pass {@code null} (or omit) when async-rewake is not wired.
         *
         * @param rewakeService
         *            the rewake service (may be null)
         * @return this builder
         */
        public Builder rewakeService(RewakeService rewakeService) {
            this.rewakeService = rewakeService;
            return this;
        }

        /**
         * Builds the dependencies.
         *
         * @return a new {@link OrcaProviderDependencies} instance
         */
        public OrcaProviderDependencies build() {
            return new OrcaProviderDependencies(this);
        }
    }
}
