package at.aimon.cli.factory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.bootstrap.spec.ToolSpec;
import at.aimon.cli.config.CliConfig;
import at.aimon.cli.config.CliSettings;
import at.aimon.cli.tool.GraalJsWorkflowToolProvider;
import at.aimon.core.filesystem.impl.local.LocalFileSystem;
import at.aimon.workflow.graaljs.GraalJsEngineHolder;

/**
 * Wiring tests for {@link AgentSetupFactory#buildToolSpec} covering the {@code cli.enableWorkflowJs} branch: the
 * CLI-side {@link GraalJsWorkflowToolProvider} must reach the resolved provider list only when a
 * {@link GraalJsEngineHolder} is present (i.e. the flag is on), and that flag alone must be enough to give the
 * runtime a {@code WorkflowRunner} — the GraalJS tool dispatches background runs against it even though the
 * built-in Workflow tool stays off.
 */
@DisplayName("AgentSetupFactory cli.enableWorkflowJs tool-provider wiring")
class AgentSetupFactoryGraalJsTest {

    private AgentSetupFactory factory;
    private LocalFileSystem fileSystem;
    private CliSettings settings;
    private CliConfig config;

    @BeforeEach
    void setUp() {
        factory = new AgentSetupFactory();
        fileSystem = mock(LocalFileSystem.class);

        // Real settings: isEnableWorkflow() is false and getMcpConfig() stays null, so only the WorkflowJs branch
        // under test contributes anything beyond the core defaults.
        settings = new CliSettings();
        config = mock(CliConfig.class);
        when(config.getCliSettings()).thenReturn(settings);
    }

    @Test
    @DisplayName("appends the WorkflowJs provider when the GraalJS engine holder is present")
    void appendsWorkflowJsProviderWhenEnginesPresent() {
        settings.setEnableWorkflowJs(true);
        final GraalJsEngineHolder engines = mock(GraalJsEngineHolder.class);

        final ToolSpec spec = factory.buildToolSpec(config, fileSystem, List.of(), engines);

        assertThat(spec.resolveProviders()).anyMatch(GraalJsWorkflowToolProvider.class::isInstance);
    }

    @Test
    @DisplayName("enables the workflow runner without the built-in Workflow tool")
    void enablesRunnerWithoutBuiltInWorkflowTool() {
        settings.setEnableWorkflowJs(true);

        final ToolSpec spec = factory.buildToolSpec(config, fileSystem, List.of(), mock(GraalJsEngineHolder.class));

        assertThat(spec.isWorkflowRunnerEnabled()).isTrue();
        assertThat(spec.isWorkflowToolEnabled()).isFalse();
    }

    @Test
    @DisplayName("omits the WorkflowJs provider when no GraalJS engine holder is wired")
    void omitsWorkflowJsProviderWhenEnginesNull() {
        final ToolSpec spec = factory.buildToolSpec(config, fileSystem, List.of(), null);

        assertThat(spec.resolveProviders()).noneMatch(GraalJsWorkflowToolProvider.class::isInstance);
        assertThat(spec.isWorkflowRunnerEnabled()).isFalse();
    }
}
