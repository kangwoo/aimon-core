package at.aimon.cli.factory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.bootstrap.AimonStack;
import at.aimon.bootstrap.AimonStackBuilder;
import at.aimon.bootstrap.AimonStackSpec;
import at.aimon.bootstrap.spec.AgentSpec;
import at.aimon.bootstrap.spec.FileSystemSpec;
import at.aimon.bootstrap.spec.LlmSpec;
import at.aimon.bootstrap.spec.ToolSpec;
import at.aimon.cli.config.CliConfig;
import at.aimon.cli.config.CliSettings;
import at.aimon.cli.config.ProgrammaticConfig;
import at.aimon.cli.tool.GraalJsWorkflowToolProvider;
import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.impl.AgentBundle;
import at.aimon.core.agent.impl.orca.OrcaAgentRuntime;
import at.aimon.core.agent.tool.ToolDescriptor;
import at.aimon.core.agent.tool.ToolOfferContext;
import at.aimon.core.filesystem.impl.local.LocalFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystemConfig;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.programmatic.CodeExecutionLimits;
import at.aimon.core.tools.code.ExecuteCodeTool;
import at.aimon.core.tools.code.ExecuteCodeToolProvider;
import at.aimon.graaljs.GraalJsEngineHolder;

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

        final ToolSpec spec = factory.buildToolSpec(config, fileSystem, engines);

        assertThat(spec.resolveProviders()).anyMatch(GraalJsWorkflowToolProvider.class::isInstance);
    }

    @Test
    @DisplayName("enables the workflow runner without the built-in Workflow tool")
    void enablesRunnerWithoutBuiltInWorkflowTool() {
        settings.setEnableWorkflowJs(true);

        final ToolSpec spec = factory.buildToolSpec(config, fileSystem, mock(GraalJsEngineHolder.class));

        assertThat(spec.isWorkflowRunnerEnabled()).isTrue();
        assertThat(spec.isWorkflowToolEnabled()).isFalse();
    }

    @Test
    @DisplayName("omits the WorkflowJs provider when no GraalJS engine holder is wired")
    void omitsWorkflowJsProviderWhenEnginesNull() {
        final ToolSpec spec = factory.buildToolSpec(config, fileSystem, null);

        assertThat(spec.resolveProviders()).noneMatch(GraalJsWorkflowToolProvider.class::isInstance);
        assertThat(spec.isWorkflowRunnerEnabled()).isFalse();
    }

    @Test
    @DisplayName("programmatic.enableJs alone registers ExecuteCode over the shared holder, without WorkflowJs")
    void programmaticEnableJsRegistersExecuteCode() {
        final ProgrammaticConfig programmatic = new ProgrammaticConfig();
        programmatic.setEnableJs(true);
        when(config.getProgrammaticConfig()).thenReturn(programmatic);

        final ToolSpec spec = factory.buildToolSpec(config, fileSystem, mock(GraalJsEngineHolder.class));

        // The engine goes to the stack, which is the one owner of the ExecuteCode provider (review-2).
        assertThat(spec.isProgrammaticEnabled()).isTrue();
        assertThat(spec.getCodeExecutionEngines()).hasValueSatisfying(engines -> assertThat(engines).singleElement()
                .satisfies(engine -> assertThat(engine.language()).isEqualTo("javascript")));
        assertThat(spec.resolveProviders()).noneMatch(ExecuteCodeToolProvider.class::isInstance)
                .noneMatch(GraalJsWorkflowToolProvider.class::isInstance);
    }

    @Test
    @DisplayName("programmatic.enableJs through the real stack: ExecuteCode registered once, and not offered to an"
            + " agent that opens nothing to code")
    void programmaticEnableJsThroughTheStack(@TempDir Path workspace) {
        final ProgrammaticConfig programmatic = new ProgrammaticConfig();
        programmatic.setEnableJs(true);
        when(config.getProgrammaticConfig()).thenReturn(programmatic);
        final LocalFileSystem realFileSystem = new LocalFileSystem(new LocalFileSystemConfig(workspace.toString()));
        realFileSystem.initialize();
        final ToolSpec tools = factory.buildToolSpec(config, realFileSystem, mock(GraalJsEngineHolder.class));
        final LlmClient stub = new LlmClient() {
            @Override
            public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> defs,
                    LlmModel modelConfig) {
                throw new UnsupportedOperationException("stub");
            }

            @Override
            public String getProviderName() {
                return "stub";
            }
        };
        final AgentBundle bundle = AgentBundle.builder().agent(DefaultAgent.builder().name("cli-no-code")
                .systemPrompt("You are a test agent.").maxIterations(5).build()).build();

        try (AimonStack stack = AimonStackBuilder.build(
                AimonStackSpec.builder().llm(LlmSpec.of(stub)).fileSystem(FileSystemSpec.supplied(realFileSystem))
                        .agent(AgentSpec.of(bundle)).tools(tools).build())) {
            final OrcaAgentRuntime runtime = stack.runtime(stack.primaryRuntimeId()).orElseThrow();
            assertThat(runtime.getToolRegistry().findAll()).filteredOn(ExecuteCodeTool.class::isInstance).hasSize(1);
            // The offer as the main loop computes it for this agent, whose definition sets no programmatic-tools.
            final List<String> offered = ToolOfferContext.builder().registry(runtime.getToolRegistry())
                    .programmatic(runtime.getAgent().getMetadata().getProgrammatic()).build().offer().stream()
                    .map(ToolDescriptor::getName).toList();
            assertThat(offered).isNotEmpty().doesNotContain(ExecuteCodeTool.TOOL_NAME);
        }
    }

    @Test
    @DisplayName("programmatic.enableJs off: the spec carries no engine and the CLI adds no ExecuteCode provider")
    void programmaticOffLeavesItToTheStack() {
        final ToolSpec spec = factory.buildToolSpec(config, fileSystem, null);

        assertThat(spec.isProgrammaticEnabled()).isFalse();
        assertThat(spec.getCodeExecutionEngines()).isEmpty();
        assertThat(spec.resolveProviders()).noneMatch(ExecuteCodeToolProvider.class::isInstance);
    }

    @Test
    @DisplayName("programmatic limits bind onto the core limits, unset keys at their defaults")
    void programmaticLimits() {
        final ProgrammaticConfig.Limits limits = new ProgrammaticConfig.Limits();
        limits.setWallClockSeconds(30);
        limits.setMaxNestedCalls(50);
        final ProgrammaticConfig programmatic = new ProgrammaticConfig();
        programmatic.setLimits(limits);

        final CodeExecutionLimits bound = programmatic.toLimits();

        assertThat(bound.getWallClock()).isEqualTo(Duration.ofSeconds(30));
        assertThat(bound.getMaxNestedCalls()).isEqualTo(50);
        assertThat(bound.getMaxConcurrentCalls()).isEqualTo(CodeExecutionLimits.defaults().getMaxConcurrentCalls());
        assertThat(new ProgrammaticConfig().toLimits()).isEqualTo(CodeExecutionLimits.defaults());
    }
}
