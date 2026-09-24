package at.aimon.core.agent.impl.orca.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import at.aimon.core.agent.Environment;
import at.aimon.core.agent.context.ContextEngine;
import at.aimon.core.agent.orca.OrcaProviderDependencies;
import at.aimon.core.command.CommandRegistry;
import at.aimon.core.command.MutableCommandRegistry;
import at.aimon.core.command.SystemCommand;
import at.aimon.core.command.system.CompactCommand;
import at.aimon.core.hook.HookExecutionManager;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.skill.SkillRegistry;
import at.aimon.core.subagent.SubagentRegistry;

/**
 * Pins {@code /compact} registration when the assembly supplies a {@link ContextEngine}: the engine alone is enough,
 * because it owns the summary, the transcript change and the circuit-breaker reset.
 */
class OrcaSystemCommandProviderContextEngineTest {

    private final OrcaSystemCommandProvider provider = new OrcaSystemCommandProvider();
    private final MutableCommandRegistry registry = mock(MutableCommandRegistry.class);

    @Test
    void registersCompactOverTheContextEngineWithoutABareEngineOrGuard() {
        final OrcaProviderDependencies deps = baseDeps().contextEngine(ContextEngine.passthrough())
                .hookRegistry(mock(HookRegistry.class)).hookExecutionManager(mock(HookExecutionManager.class))
                .environment(mock(Environment.class)).build();

        provider.registerCommands(registry, context(deps));

        assertThat(capture()).anyMatch(c -> c instanceof CompactCommand);
    }

    @Test
    void stillSkipsCompactWhenTheHookPlumbingIsMissing() {
        final OrcaProviderDependencies deps = baseDeps().contextEngine(ContextEngine.passthrough())
                .hookRegistry(mock(HookRegistry.class)).hookExecutionManager(mock(HookExecutionManager.class)).build();

        provider.registerCommands(registry, context(deps));

        assertThat(capture()).noneMatch(c -> c instanceof CompactCommand);
    }

    private OrcaCommandProviderContext context(OrcaProviderDependencies deps) {
        return OrcaCommandProviderContext.builder().commandRegistry(mock(CommandRegistry.class)).version("1.2.3")
                .dependencies(deps).build();
    }

    private static OrcaProviderDependencies.Builder baseDeps() {
        return OrcaProviderDependencies.builder().subagentRegistry(mock(SubagentRegistry.class))
                .skillRegistry(mock(SkillRegistry.class));
    }

    private List<SystemCommand> capture() {
        final ArgumentCaptor<SystemCommand> captor = ArgumentCaptor.forClass(SystemCommand.class);
        verify(registry, atLeastOnce()).registerSystemCommand(captor.capture());
        return captor.getAllValues();
    }
}
