package at.aimon.core.agent.impl.orca.command;

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.Environment;
import at.aimon.core.agent.compact.CompactionEngine;
import at.aimon.core.agent.compact.CompactionGuard;
import at.aimon.core.command.CommandRegistry;
import at.aimon.core.command.MutableCommandRegistry;
import at.aimon.core.command.system.AgentListCommand;
import at.aimon.core.command.system.ApproveTurnCommand;
import at.aimon.core.command.system.ClearCommand;
import at.aimon.core.command.system.CommandListCommand;
import at.aimon.core.command.system.CompactCommand;
import at.aimon.core.command.system.DenyTurnCommand;
import at.aimon.core.command.system.HelpCommand;
import at.aimon.core.command.system.PendingTurnsCommand;
import at.aimon.core.command.system.RevokeApprovalsCommand;
import at.aimon.core.command.system.RewakeListCommand;
import at.aimon.core.command.system.SkillListCommand;
import at.aimon.core.command.system.VersionCommand;
import at.aimon.core.hook.HookExecutionManager;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.hook.rewake.RewakeService;
import at.aimon.core.skill.policy.agent.AgentApprovalStore;
import at.aimon.core.skill.policy.pending.PendingTurnRegistry;
import at.aimon.core.skill.policy.session.SessionApprovalStore;

/**
 * Provides system commands to the Orca agent system.
 *
 * <p>
 * This provider registers built-in system commands including:
 *
 * <ul>
 * <li>{@link HelpCommand} - Display help information
 * <li>{@link VersionCommand} - Display version information
 * <li>{@link ClearCommand} - Clear the conversation history, plus that session's skill approvals when a
 * {@link SessionApprovalStore} is present in the provided context
 * <li>{@link CompactCommand} - Manually compact the current conversation (only when a {@link CompactionEngine} and
 * {@link CompactionGuard} are present in the provided context)
 * <li>{@link PendingTurnsCommand} - List turns suspended awaiting user approval (only when a
 * {@link PendingTurnRegistry} is present in the provided context)
 * <li>{@link DenyTurnCommand} - Deny a suspended turn by id (only when a {@link PendingTurnRegistry} is present in
 * the provided context)
 * <li>{@link ApproveTurnCommand} - Approve a suspended turn by id (only when both a {@link PendingTurnRegistry} and a
 * {@link AgentApprovalStore} are present in the provided context)
 * <li>{@link RevokeApprovalsCommand} - Forget the skill approvals granted in this session, or agent-wide with
 * {@code --agent} (only when a {@link AgentApprovalStore} is present in the provided context; unlike
 * {@code /approve} it needs no {@link PendingTurnRegistry}, since revoking is meaningful even where suspension is not
 * configured)
 * <li>{@link RewakeListCommand} - List pending async-rewake envelopes (only when a non-NOOP {@link RewakeService} is
 * present in the provided context)
 * </ul>
 *
 * @see OrcaCommandProvider
 */
public class OrcaSystemCommandProvider implements OrcaCommandProvider {

    private static final Logger log = LoggerFactory.getLogger(OrcaSystemCommandProvider.class);

    @Override
    public void registerCommands(MutableCommandRegistry registry, OrcaCommandProviderContext context) {
        Objects.requireNonNull(registry, "registry must not be null");
        Objects.requireNonNull(context, "context must not be null");

        final CommandRegistry commandRegistry = context.getCommandRegistry();
        final String version = context.getVersion();

        Objects.requireNonNull(commandRegistry, "commandRegistry must not be null in context");
        Objects.requireNonNull(version, "version must not be null in context");

        registry.registerSystemCommand(new HelpCommand(() -> commandRegistry));
        registry.registerSystemCommand(new VersionCommand(version));
        registry.registerSystemCommand(new ClearCommand(context.getSessionApprovalStore()));

        registry.registerSystemCommand(new CommandListCommand(commandRegistry));
        registry.registerSystemCommand(new AgentListCommand(context.getSubagentRegistry()));
        registry.registerSystemCommand(new SkillListCommand(context.getSkillRegistry()));

        registerCompactCommand(registry, context);
        registerPendingTurnCommands(registry, context);
        registerRevokeApprovalsCommand(registry, context);
        registerRewakeListCommand(registry, context);
    }

    /**
     * Registers {@code /revoke} whenever an approval store exists. Deliberately separate from
     * {@link #registerPendingTurnCommands}: that method bails out when no {@link PendingTurnRegistry} is configured,
     * but approvals can still be granted inline through a
     * {@link at.aimon.core.skill.policy.approval.SkillApprovalChannel} in that setup — leaving the user no way to take
     * them back.
     */
    private void registerRevokeApprovalsCommand(MutableCommandRegistry registry, OrcaCommandProviderContext context) {
        final AgentApprovalStore agentApprovalStore = context.getAgentApprovalStore();
        if (agentApprovalStore == null) {
            log.debug("Skipping /revoke command registration: AgentApprovalStore is not configured");
            return;
        }
        registry.registerSystemCommand(
                new RevokeApprovalsCommand(context.getSessionApprovalStore(), agentApprovalStore));
    }

    private void registerRewakeListCommand(MutableCommandRegistry registry, OrcaCommandProviderContext context) {
        final RewakeService rewakeService = context.getRewakeService();
        if (rewakeService == null || rewakeService == RewakeService.NOOP) {
            log.debug("Skipping /rewakes command registration: RewakeService is not wired (was {})",
                    rewakeService == null ? "null" : "NOOP");
            return;
        }
        registry.registerSystemCommand(new RewakeListCommand(rewakeService));
    }

    private void registerPendingTurnCommands(MutableCommandRegistry registry, OrcaCommandProviderContext context) {
        final PendingTurnRegistry pendingTurnRegistry = context.getPendingTurnRegistry();
        if (pendingTurnRegistry == null) {
            log.debug("Skipping /pending, /deny and /approve command registration: PendingTurnRegistry is not"
                    + " configured");
            return;
        }
        final SessionApprovalStore sessionApprovalStore = context.getSessionApprovalStore();
        final AgentApprovalStore agentApprovalStore = context.getAgentApprovalStore();

        registry.registerSystemCommand(new PendingTurnsCommand(pendingTurnRegistry));
        registry.registerSystemCommand(
                new DenyTurnCommand(pendingTurnRegistry, sessionApprovalStore, agentApprovalStore));

        if (agentApprovalStore == null) {
            // /deny still registers above: dropping a suspended turn is meaningful with no store to cache into, it
            // simply degrades to single-shot. /approve has nothing to write at all, so it stays out.
            log.debug("Skipping /approve command registration: AgentApprovalStore is not configured");
            return;
        }
        registry.registerSystemCommand(
                new ApproveTurnCommand(pendingTurnRegistry, sessionApprovalStore, agentApprovalStore));
    }

    private void registerCompactCommand(MutableCommandRegistry registry, OrcaCommandProviderContext context) {
        final CompactionEngine engine = context.getCompactionEngine();
        final CompactionGuard guard = context.getCompactionGuard();
        final HookRegistry hooks = context.getHookRegistry();
        final HookExecutionManager hookExecutionManager = context.getHookExecutionManager();
        final Environment env = context.getEnvironment();
        if (engine == null || guard == null || hooks == null || hookExecutionManager == null || env == null) {
            log.debug(
                    "Skipping /compact command registration: compaction collaborators not configured "
                            + "(engine={}, guard={}, hookRegistry={}, hookExecutionManager={}, environment={})",
                    engine != null, guard != null, hooks != null, hookExecutionManager != null, env != null);
            return;
        }
        registry.registerSystemCommand(new CompactCommand(engine, guard, hooks, hookExecutionManager, env));
    }
}
