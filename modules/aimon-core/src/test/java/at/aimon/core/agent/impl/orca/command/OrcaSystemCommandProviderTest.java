package at.aimon.core.agent.impl.orca.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import at.aimon.core.agent.Environment;
import at.aimon.core.agent.compact.CompactionEngine;
import at.aimon.core.agent.compact.CompactionGuard;
import at.aimon.core.agent.orca.OrcaProviderDependencies;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.transcript.SessionSnapshot;
import at.aimon.core.agent.session.transcript.TranscriptBuffer;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.command.CommandRegistry;
import at.aimon.core.command.MutableCommandRegistry;
import at.aimon.core.command.SystemCommand;
import at.aimon.core.command.execution.CommandExecutionContext;
import at.aimon.core.command.execution.direct.DirectCommandExecutionRequest;
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
import at.aimon.core.llm.LlmModel;
import at.aimon.core.skill.SkillRegistry;
import at.aimon.core.skill.policy.agent.AgentApprovalStore;
import at.aimon.core.skill.policy.pending.PendingTurnRegistry;
import at.aimon.core.skill.policy.session.SessionApprovalStore;
import at.aimon.core.subagent.SubagentRegistry;

class OrcaSystemCommandProviderTest {

    private OrcaSystemCommandProvider provider;
    private MutableCommandRegistry registry;
    private CommandRegistry commandRegistry;

    @BeforeEach
    void setUp() {
        provider = new OrcaSystemCommandProvider();
        registry = mock(MutableCommandRegistry.class);
        commandRegistry = mock(CommandRegistry.class);
    }

    private OrcaCommandProviderContext context(OrcaProviderDependencies deps) {
        return OrcaCommandProviderContext.builder().commandRegistry(commandRegistry).version("1.2.3").dependencies(deps)
                .build();
    }

    private OrcaProviderDependencies.Builder baseDeps() {
        return OrcaProviderDependencies.builder().subagentRegistry(mock(SubagentRegistry.class))
                .skillRegistry(mock(SkillRegistry.class));
    }

    private List<SystemCommand> capture() {
        ArgumentCaptor<SystemCommand> captor = ArgumentCaptor.forClass(SystemCommand.class);
        verify(registry, org.mockito.Mockito.atLeastOnce()).registerSystemCommand(captor.capture());
        return new ArrayList<>(captor.getAllValues());
    }

    @Test
    void shouldRejectNullArguments() {
        OrcaCommandProviderContext ctx = context(baseDeps().build());

        assertThatNullPointerException().isThrownBy(() -> provider.registerCommands(null, ctx))
                .withMessageContaining("registry");
        assertThatNullPointerException().isThrownBy(() -> provider.registerCommands(registry, null))
                .withMessageContaining("context");
    }

    @Test
    void shouldRejectMissingCommandRegistryInContext() {
        OrcaCommandProviderContext ctx = OrcaCommandProviderContext.builder().version("1.0.0")
                .dependencies(OrcaProviderDependencies.builder().build()).build();

        assertThatNullPointerException().isThrownBy(() -> provider.registerCommands(registry, ctx))
                .withMessageContaining("commandRegistry");
    }

    @Test
    void shouldRejectMissingVersionInContext() {
        OrcaCommandProviderContext ctx = OrcaCommandProviderContext.builder().commandRegistry(commandRegistry)
                .dependencies(OrcaProviderDependencies.builder().build()).build();

        assertThatNullPointerException().isThrownBy(() -> provider.registerCommands(registry, ctx))
                .withMessageContaining("version");
    }

    @Test
    void shouldRegisterCoreCommandsWithoutOptionalCollaborators() {
        provider.registerCommands(registry, context(baseDeps().build()));

        List<SystemCommand> registered = capture();
        assertThat(registered).extracting(Object::getClass).containsExactly(HelpCommand.class, VersionCommand.class,
                ClearCommand.class, CommandListCommand.class, AgentListCommand.class, SkillListCommand.class);
        assertThat(registered).noneMatch(c -> c instanceof CompactCommand);
        assertThat(registered).noneMatch(c -> c instanceof PendingTurnsCommand);
        assertThat(registered).noneMatch(c -> c instanceof DenyTurnCommand);
        assertThat(registered).noneMatch(c -> c instanceof ApproveTurnCommand);
    }

    @Test
    void shouldNotRegisterCompactWhenAnyCollaboratorMissing() {
        // env missing
        OrcaProviderDependencies deps = baseDeps().compactionEngine(mock(CompactionEngine.class))
                .compactionGuard(mock(CompactionGuard.class)).hookRegistry(mock(HookRegistry.class))
                .hookExecutionManager(mock(HookExecutionManager.class)).build();

        provider.registerCommands(registry, context(deps));

        assertThat(capture()).noneMatch(c -> c instanceof CompactCommand);
    }

    @Test
    void shouldRegisterCompactWhenAllCollaboratorsPresent() {
        OrcaProviderDependencies deps = baseDeps().compactionEngine(mock(CompactionEngine.class))
                .compactionGuard(mock(CompactionGuard.class)).hookRegistry(mock(HookRegistry.class))
                .hookExecutionManager(mock(HookExecutionManager.class)).environment(mock(Environment.class)).build();

        provider.registerCommands(registry, context(deps));

        assertThat(capture()).anyMatch(c -> c instanceof CompactCommand);
    }

    @Test
    void shouldRegisterPendingAndDenyButNotApproveWhenAgentApprovalStoreMissing() {
        OrcaProviderDependencies deps = baseDeps().pendingTurnRegistry(mock(PendingTurnRegistry.class)).build();

        provider.registerCommands(registry, context(deps));

        List<SystemCommand> registered = capture();
        assertThat(registered).anyMatch(c -> c instanceof PendingTurnsCommand);
        assertThat(registered).anyMatch(c -> c instanceof DenyTurnCommand);
        assertThat(registered).noneMatch(c -> c instanceof ApproveTurnCommand);
    }

    @Test
    void shouldRegisterApproveWhenBothPendingRegistryAndApprovalStorePresent() {
        OrcaProviderDependencies deps = baseDeps().pendingTurnRegistry(mock(PendingTurnRegistry.class))
                .agentApprovalStore(mock(AgentApprovalStore.class)).build();

        provider.registerCommands(registry, context(deps));

        List<SystemCommand> registered = capture();
        assertThat(registered).anyMatch(c -> c instanceof PendingTurnsCommand);
        assertThat(registered).anyMatch(c -> c instanceof DenyTurnCommand);
        assertThat(registered).anyMatch(c -> c instanceof ApproveTurnCommand);
    }

    @Test
    void shouldNotRegisterPendingCommandsWhenPendingRegistryMissing() {
        OrcaProviderDependencies deps = baseDeps().agentApprovalStore(mock(AgentApprovalStore.class)).build();

        provider.registerCommands(registry, context(deps));

        List<SystemCommand> registered = capture();
        assertThat(registered).noneMatch(c -> c instanceof PendingTurnsCommand);
        assertThat(registered).noneMatch(c -> c instanceof DenyTurnCommand);
        assertThat(registered).noneMatch(c -> c instanceof ApproveTurnCommand);
        // /revoke must survive here: without a PendingTurnRegistry approvals can still be granted inline by a
        // SkillApprovalChannel, so the user would otherwise have no way to take them back.
        assertThat(registered).anyMatch(c -> c instanceof RevokeApprovalsCommand);
    }

    @Test
    void shouldNotRegisterRevokeWhenApprovalStoreMissing() {
        OrcaProviderDependencies deps = baseDeps().pendingTurnRegistry(mock(PendingTurnRegistry.class)).build();

        provider.registerCommands(registry, context(deps));

        assertThat(capture()).noneMatch(c -> c instanceof RevokeApprovalsCommand);
    }

    @Test
    void shouldRegisterAllOptionalCommandsWhenEverythingPresent() {
        OrcaProviderDependencies deps = baseDeps().compactionEngine(mock(CompactionEngine.class))
                .compactionGuard(mock(CompactionGuard.class)).hookRegistry(mock(HookRegistry.class))
                .hookExecutionManager(mock(HookExecutionManager.class)).environment(mock(Environment.class))
                .pendingTurnRegistry(mock(PendingTurnRegistry.class)).agentApprovalStore(mock(AgentApprovalStore.class))
                .rewakeService(mock(RewakeService.class)).build();

        provider.registerCommands(registry, context(deps));

        List<SystemCommand> registered = capture();
        assertThat(registered).extracting(Object::getClass).contains(HelpCommand.class, VersionCommand.class,
                ClearCommand.class, CommandListCommand.class, AgentListCommand.class, SkillListCommand.class,
                CompactCommand.class, PendingTurnsCommand.class, DenyTurnCommand.class, ApproveTurnCommand.class,
                RevokeApprovalsCommand.class, RewakeListCommand.class);
    }

    @Test
    void shouldNotRegisterRewakeListWhenRewakeServiceMissing() {
        provider.registerCommands(registry, context(baseDeps().build()));

        assertThat(capture()).noneMatch(c -> c instanceof RewakeListCommand);
    }

    @Test
    void shouldNotRegisterRewakeListWhenRewakeServiceIsNoop() {
        OrcaProviderDependencies deps = baseDeps().rewakeService(RewakeService.NOOP).build();

        provider.registerCommands(registry, context(deps));

        assertThat(capture()).noneMatch(c -> c instanceof RewakeListCommand);
    }

    @Test
    void shouldRegisterRewakeListWhenRewakeServicePresent() {
        OrcaProviderDependencies deps = baseDeps().rewakeService(mock(RewakeService.class)).build();

        provider.registerCommands(registry, context(deps));

        assertThat(capture()).anyMatch(c -> c instanceof RewakeListCommand);
    }

    /**
     * Pins the Phase 4 wiring end to end: a {@link SessionApprovalStore} placed in the dependencies must reach the
     * registered {@code /clear}. A type-only assertion would pass even if the store were dropped on the way through
     * {@link OrcaCommandProviderContext}, leaving approvals alive across a session reset.
     */
    @Test
    void shouldWireTheConversationApprovalStoreIntoClear() {
        SessionApprovalStore sessionApprovals = mock(SessionApprovalStore.class);
        OrcaProviderDependencies deps = baseDeps().sessionApprovalStore(sessionApprovals).build();
        SessionId sessionId = SessionId.of("conv-1");

        provider.registerCommands(registry, context(deps));

        ClearCommand clear = capture().stream().filter(ClearCommand.class::isInstance).map(ClearCommand.class::cast)
                .findFirst().orElseThrow();
        clear.execute(commandExecutionContext(clear), DirectCommandExecutionRequest.builder().arguments("")
                .previousSnapshot(SessionSnapshot.of(sessionId)).build());

        verify(sessionApprovals).invalidate(sessionId);
    }

    /**
     * Same check for {@code /revoke}, whose default scope moved from the agent to the session in Phase 4 — the
     * narrow path only exists when the store actually arrives.
     */
    @Test
    void shouldWireTheConversationApprovalStoreIntoRevoke() {
        SessionApprovalStore sessionApprovals = mock(SessionApprovalStore.class);
        OrcaProviderDependencies deps = baseDeps().sessionApprovalStore(sessionApprovals)
                .agentApprovalStore(mock(AgentApprovalStore.class)).build();
        SessionId sessionId = SessionId.of("conv-1");

        provider.registerCommands(registry, context(deps));

        RevokeApprovalsCommand revoke = capture().stream().filter(RevokeApprovalsCommand.class::isInstance)
                .map(RevokeApprovalsCommand.class::cast).findFirst().orElseThrow();
        revoke.execute(CommandExecutionContext.builder().command(revoke)
                .defaultModel(LlmModel.builder().name("test").build()).toolRegistry(new DefaultToolRegistry())
                .transcriptBuffer(new TranscriptBuffer(sessionId)).build(), DirectCommandExecutionRequest.of(""));

        verify(sessionApprovals).invalidate(sessionId);
    }

    private CommandExecutionContext commandExecutionContext(SystemCommand command) {
        return CommandExecutionContext.builder().command(command).defaultModel(LlmModel.builder().name("test").build())
                .toolRegistry(new DefaultToolRegistry()).build();
    }
}
