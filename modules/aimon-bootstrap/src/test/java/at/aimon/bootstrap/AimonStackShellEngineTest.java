package at.aimon.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import at.aimon.bootstrap.spec.AgentSpec;
import at.aimon.bootstrap.spec.LlmSpec;
import at.aimon.bootstrap.spec.ToolSpec;
import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.impl.AgentBundle;
import at.aimon.core.agent.impl.orca.OrcaAgentRuntime;
import at.aimon.core.agent.tool.ProgrammaticToolSettings;
import at.aimon.core.agent.tool.ShellEngineToolScope;
import at.aimon.core.agent.tool.ToolDescriptor;
import at.aimon.core.agent.tool.ToolOfferContext;
import at.aimon.core.environment.EnvironmentDescriptor;
import at.aimon.core.environment.EnvironmentIsolation;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.shell.ShellFeature;
import at.aimon.core.tools.code.CodeOfferKeys;
import at.aimon.core.tools.code.ExecuteCodeTool;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * The shell engine through {@code aimon-bootstrap} (shell doc §12.2, design §5.2): with {@code programmaticEnabled}
 * the stack discovers the Python engine {@code aimon-core} registers, its one {@code ExecuteCode} offers Python on a
 * sandboxed environment and nothing on a host one, and the application's {@code shell-engine-tool-scope} reaches it.
 */
@DisplayName("AimonStack — the shell engine")
class AimonStackShellEngineTest {

    private static final LlmClient STUB_LLM = new LlmClient() {
        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public String getProviderName() {
            return "stub";
        }
    };

    private static AimonStackSpec spec(Path workspace, String agent, ToolSpec tools) {
        return AimonStackSpec.builder().workspaceRoot(workspace.toString()).llm(LlmSpec.of(STUB_LLM))
                .agent(AgentSpec.of(AgentBundle.builder()
                        .agent(DefaultAgent.builder().name(agent).systemPrompt("You are a test agent.").maxIterations(5)
                                .programmatic(ProgrammaticToolSettings.builder().tools(List.of("Read")).build())
                                .build())
                        .build()))
                .tools(tools).build();
    }

    private static ToolOfferContext offer(OrcaAgentRuntime runtime, EnvironmentIsolation isolation) {
        return ToolOfferContext.builder().registry(runtime.getToolRegistry())
                .programmatic(runtime.getAgent().getMetadata().getProgrammatic())
                .put(CodeOfferKeys.ENVIRONMENT_DESCRIPTOR,
                        EnvironmentDescriptor.builder().workingDirectory("/workspace").isolation(isolation)
                                .runtime(EnvironmentDescriptor.RUNTIME_PYTHON3).build())
                .put(CodeOfferKeys.SHELL_FEATURES,
                        Set.of(ShellFeature.STREAMING, ShellFeature.RUN_DIRECTORY, ShellFeature.ISOLATED_EXECUTION))
                .put(CodeOfferKeys.RUNTIME_FILE_SYSTEM, true).put(CodeOfferKeys.BASH_SETTINGS_UNCONSTRAINED, true)
                .build();
    }

    @Test
    @DisplayName("programmaticEnabled discovers the Python engine: offered on a sandbox, not on the host, one warning")
    void discoveredPythonEngine(@TempDir Path workspace) {
        final List<String> warnings = captureWarnings(() -> {
            try (AimonStack stack = AimonStackBuilder
                    .build(spec(workspace, "ops-py", ToolSpec.builder().programmaticEnabled(true).build()))) {
                final OrcaAgentRuntime runtime = stack.agentRuntimeRegistry().get(AgentRuntimeId.fromName("ops-py"))
                        .map(OrcaAgentRuntime.class::cast).orElseThrow();
                assertThat(runtime.getToolRegistry().findAll()).filteredOn(ExecuteCodeTool.class::isInstance)
                        .hasSize(1);
                final ExecuteCodeTool tool = (ExecuteCodeTool) runtime.getToolRegistry()
                        .findByName(ExecuteCodeTool.TOOL_NAME).orElseThrow();
                final ToolDescriptor sandboxed = tool.describe(offer(runtime, EnvironmentIsolation.SANDBOXED));
                assertThat(sandboxed.isOfferable()).isTrue();
                assertThat(sandboxed.getApprovalVia()).hasValue("ExecuteCode (Python)");
                assertThat(tool.describe(offer(runtime, EnvironmentIsolation.HOST)).isOfferable())
                        .as("no JavaScript engine on this class path, and Python never runs on the host").isFalse();
            }
        });
        assertThat(warnings).filteredOn(w -> w.contains("no JavaScript engine")).hasSize(1);
        assertThat(warnings).noneMatch(w -> w.contains("unrestricted"));
    }

    @Test
    @DisplayName("shell-engine-tool-scope unrestricted is warned at startup")
    void unrestrictedWarned(@TempDir Path workspace) {
        final ToolSpec tools = ToolSpec.builder().programmaticEnabled(true)
                .shellEngineToolScope(ShellEngineToolScope.UNRESTRICTED).build();
        assertThat(tools.getShellEngineToolScope()).isEqualTo(ShellEngineToolScope.UNRESTRICTED);
        final List<String> warnings = captureWarnings(() -> {
            try (AimonStack stack = AimonStackBuilder.build(spec(workspace, "ops-wide", tools))) {
                assertThat(stack).isNotNull();
            }
        });
        assertThat(warnings).filteredOn(w -> w.contains("shell-engine-tool-scope is unrestricted")).hasSize(1);
    }

    @Test
    @DisplayName("the default scope is environment")
    void defaultScope() {
        assertThat(ToolSpec.defaults().getShellEngineToolScope()).isEqualTo(ShellEngineToolScope.ENVIRONMENT);
    }

    private static List<String> captureWarnings(Runnable action) {
        final Logger logger = (Logger) LoggerFactory.getLogger("at.aimon");
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            action.run();
        } finally {
            logger.detachAppender(appender);
        }
        return appender.list.stream().filter(e -> e.getLevel() == Level.WARN).map(ILoggingEvent::getFormattedMessage)
                .toList();
    }
}
