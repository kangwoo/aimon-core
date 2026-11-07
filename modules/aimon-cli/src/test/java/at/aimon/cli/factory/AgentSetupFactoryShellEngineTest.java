package at.aimon.cli.factory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import at.aimon.bootstrap.AimonStack;
import at.aimon.bootstrap.AimonStackBuilder;
import at.aimon.bootstrap.AimonStackSpec;
import at.aimon.bootstrap.spec.AgentSpec;
import at.aimon.bootstrap.spec.FileSystemSpec;
import at.aimon.bootstrap.spec.LlmSpec;
import at.aimon.bootstrap.spec.ToolSpec;
import at.aimon.cli.config.AgentConfig;
import at.aimon.cli.config.CliConfig;
import at.aimon.cli.config.CliSettings;
import at.aimon.cli.config.LlmProviderConfig;
import at.aimon.cli.config.ProgrammaticConfig;
import at.aimon.core.agent.ContextEngineKind;
import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.impl.AgentBundle;
import at.aimon.core.agent.impl.orca.OrcaAgentRuntime;
import at.aimon.core.agent.session.transcript.SessionLogFormat;
import at.aimon.core.agent.tool.ShellEngineToolScope;
import at.aimon.core.filesystem.impl.local.LocalFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystemConfig;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.programmatic.CodeExecutionEngine;
import at.aimon.core.programmatic.CodeExecutionLimits;
import at.aimon.core.tools.code.ExecuteCodeTool;
import at.aimon.core.tools.code.shell.ShellCodeExecutionEngine;
import at.aimon.graaljs.GraalJsEngineHolder;

/**
 * {@code programmatic.enablePython} and {@code programmatic.shellEngineToolScope}: the CLI assembles the Python
 * (shell) {@code ExecuteCode} engine beside the JavaScript one when asked, hands the stack the scope, and closes the
 * engine it owns (programmatic-tool-calling design §16, B.2).
 */
@DisplayName("AgentSetupFactory — programmatic.enablePython")
class AgentSetupFactoryShellEngineTest {

    private final List<ShellCodeExecutionEngine> engines = new ArrayList<>();
    private AgentSetupFactory factory;
    private CliConfig config;
    private ProgrammaticConfig programmatic;

    @BeforeEach
    void setUp() {
        factory = new AgentSetupFactory();
        config = mock(CliConfig.class);
        when(config.getCliSettings()).thenReturn(new CliSettings());
        programmatic = new ProgrammaticConfig();
        when(config.getProgrammaticConfig()).thenReturn(programmatic);
    }

    @AfterEach
    void closeEngines() {
        engines.forEach(ShellCodeExecutionEngine::close);
    }

    private ShellCodeExecutionEngine engine() {
        final ShellCodeExecutionEngine engine = ShellCodeExecutionEngine.create();
        engines.add(engine);
        return engine;
    }

    private static List<String> languages(ToolSpec spec) {
        return spec.getCodeExecutionEngines().orElseThrow().stream().map(CodeExecutionEngine::language).toList();
    }

    @Test
    @DisplayName("enablePython alone hands the stack the Python engine, with the limits and the scope")
    void pythonAlone() {
        programmatic.setEnablePython(true);
        final ShellCodeExecutionEngine shell = engine();

        final ToolSpec spec = factory.buildToolSpec(config, mock(LocalFileSystem.class), null, shell);

        assertThat(languages(spec)).containsExactly(CodeExecutionEngine.PYTHON);
        assertThat(spec.getCodeExecutionEngines().orElseThrow()).singleElement().isSameAs(shell);
        assertThat(spec.getProgrammaticLimits()).isEqualTo(CodeExecutionLimits.defaults());
        assertThat(spec.getShellEngineToolScope()).isEqualTo(ShellEngineToolScope.ENVIRONMENT);
    }

    @Test
    @DisplayName("enablePython with enableJs: both engines, the JavaScript one over the single shared holder")
    void bothEngines() {
        programmatic.setEnableJs(true);
        programmatic.setEnablePython(true);

        final ToolSpec spec = factory.buildToolSpec(config, mock(LocalFileSystem.class),
                mock(GraalJsEngineHolder.class), engine());

        assertThat(languages(spec)).containsExactly(CodeExecutionEngine.JAVASCRIPT, CodeExecutionEngine.PYTHON);
    }

    @Test
    @DisplayName("the defaults assemble no Python engine")
    void defaultsAssembleNoPython() {
        assertThat(new ProgrammaticConfig().isEnablePython()).isFalse();
        programmatic.setEnableJs(true);

        final ToolSpec spec = factory.buildToolSpec(config, mock(LocalFileSystem.class),
                mock(GraalJsEngineHolder.class), null);

        assertThat(languages(spec)).containsExactly(CodeExecutionEngine.JAVASCRIPT);
    }

    @Test
    @DisplayName("shellEngineToolScope: host reaches the stack's ExecuteCode provider")
    void scopeReachesTheSpec() {
        programmatic.setEnablePython(true);
        programmatic.setShellEngineToolScope("host");

        final ToolSpec spec = factory.buildToolSpec(config, mock(LocalFileSystem.class), null, engine());

        assertThat(spec.getShellEngineToolScope()).isEqualTo(ShellEngineToolScope.HOST);
    }

    @Test
    @DisplayName("a bad shellEngineToolScope fails the configuration load, naming the key")
    void badScopeFailsTheLoad() {
        final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

        assertThatThrownBy(() -> yaml.readValue("shellEngineToolScope: everywhere\n", ProgrammaticConfig.class))
                .hasStackTraceContaining("programmatic.shellEngineToolScope");
        assertThatIllegalArgumentException().isThrownBy(() -> programmatic.setShellEngineToolScope("everywhere"))
                .withMessageContaining("programmatic.shellEngineToolScope");
    }

    @Test
    @DisplayName("enablePython through the real stack: ExecuteCode registered once over the Python engine")
    void pythonThroughTheStack(@TempDir Path workspace) {
        programmatic.setEnablePython(true);
        final LocalFileSystem fileSystem = new LocalFileSystem(new LocalFileSystemConfig(workspace.toString()));
        fileSystem.initialize();
        final ToolSpec tools = factory.buildToolSpec(config, fileSystem, null, engine());
        final AgentBundle bundle = AgentBundle.builder()
                .agent(DefaultAgent.builder().name("cli-python").systemPrompt("You are a test agent.").build()).build();

        try (AimonStack stack = AimonStackBuilder.build(AimonStackSpec.builder().llm(LlmSpec.of(stub()))
                .fileSystem(FileSystemSpec.supplied(fileSystem)).agent(AgentSpec.of(bundle)).tools(tools).build())) {
            final OrcaAgentRuntime runtime = stack.runtime(stack.primaryRuntimeId()).orElseThrow();
            assertThat(runtime.getToolRegistry().findAll()).filteredOn(ExecuteCodeTool.class::isInstance).hasSize(1);
        }
    }

    @Test
    @DisplayName("the CLI closes the shell engine it owns at shutdown")
    void closedAtShutdown() {
        final List<ShellCodeExecutionEngine> created = new ArrayList<>();
        final AgentSetupFactory recording = recordingFactory(created, ContextEngineKind.DEFAULT);

        try (PrintStream ignored = silenceStdout()) {
            recording.create(cliConfig()).close();
        }

        assertThat(created).hasSize(1);
        verify(created.get(0), atLeastOnce()).close();
    }

    @Test
    @DisplayName("the CLI closes the shell engine when the stack fails to build")
    void closedWhenTheStackFails() {
        final List<ShellCodeExecutionEngine> created = new ArrayList<>();
        // A rolling agent on the version-1 log refuses to start (AgentSetupFactoryLogWriteFormatTest).
        final AgentSetupFactory recording = recordingFactory(created, ContextEngineKind.ROLLING);
        final CliConfig failing = cliConfig();
        failing.getCliSettings().setSessionLogWriteFormat(SessionLogFormat.V1);

        try (PrintStream ignored = silenceStdout()) {
            assertThatThrownBy(() -> recording.create(failing).close()).hasStackTraceContaining("rolling");
        }

        assertThat(created).hasSize(1);
        verify(created.get(0), atLeastOnce()).close();
    }

    @Test
    @DisplayName("enablePython off: the CLI creates no shell engine")
    void noEngineWhenOff() {
        final List<ShellCodeExecutionEngine> created = new ArrayList<>();
        final AgentSetupFactory recording = recordingFactory(created, ContextEngineKind.DEFAULT);
        final CliConfig off = cliConfig();
        off.getProgrammaticConfig().setEnablePython(false);

        try (PrintStream ignored = silenceStdout()) {
            recording.create(off).close();
        }

        assertThat(created).isEmpty();
        created.forEach(engine -> verify(engine, never()).close());
    }

    private static AgentSetupFactory recordingFactory(List<ShellCodeExecutionEngine> created,
            ContextEngineKind engineKind) {
        final LlmClientFactory stubModel = new LlmClientFactory() {
            @Override
            public LlmClient create(LlmProviderConfig ignored) {
                return stub();
            }
        };
        return new AgentSetupFactory(stubModel, name -> AgentBundle.builder().agent(DefaultAgent.builder().name(name)
                .systemPrompt("You are a probe.").maxIterations(3).contextEngine(engineKind).build()).build()) {
            @Override
            ShellCodeExecutionEngine newShellCodeExecutionEngine() {
                final ShellCodeExecutionEngine engine = spy(super.newShellCodeExecutionEngine());
                created.add(engine);
                return engine;
            }
        };
    }

    private static CliConfig cliConfig() {
        final LlmProviderConfig llm = new LlmProviderConfig();
        llm.setProvider("anthropic");
        // A placeholder: the stub factory builds no real client, and no turn runs here.
        llm.setApiKey("sk-ant-placeholder");
        llm.setModel("claude-sonnet-4-5");
        final AgentConfig agent = new AgentConfig();
        agent.setName("shell-probe");
        final CliSettings settings = new CliSettings();
        settings.setColorOutput(false);
        final ProgrammaticConfig programmatic = new ProgrammaticConfig();
        programmatic.setEnablePython(true);
        final CliConfig config = new CliConfig();
        config.setLlmConfig(llm);
        config.setAgentConfig(agent);
        config.setCliSettings(settings);
        config.setProgrammaticConfig(programmatic);
        return config;
    }

    /** Swallows the CLI's start-up banner for the duration of a create; restores stdout when closed. */
    private static PrintStream silenceStdout() {
        final PrintStream original = System.out;
        System.setOut(new PrintStream(new ByteArrayOutputStream()));
        return new PrintStream(original) {
            @Override
            public void close() {
                System.setOut(original);
            }
        };
    }

    private static LlmClient stub() {
        return new LlmClient() {
            @Override
            public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> defs,
                    LlmModel modelConfig) {
                return LlmResponse.text("ok");
            }

            @Override
            public String getProviderName() {
                return "stub";
            }
        };
    }
}
