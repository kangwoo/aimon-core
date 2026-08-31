package at.aimon.core.agent.impl.orca;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.session.store.InMemorySessionRecordStore;
import at.aimon.core.agent.session.transcript.DefaultTranscriptManager;
import at.aimon.core.agent.session.transcript.TranscriptBuffer;
import at.aimon.core.agent.session.transcript.TranscriptManager;
import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.DefaultToolExecutionManager;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.agent.tool.SideEffectLevel;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolExecutionManager;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.agent.tool.execution.ToolExecutionResult;
import at.aimon.core.agent.tool.schema.SchemaValidationMode;
import at.aimon.core.command.CommandExecutionManager;
import at.aimon.core.command.DefaultCommandExecutionManager;
import at.aimon.core.hook.DefaultHookExecutionManager;
import at.aimon.core.hook.HookExecutionManager;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.exception.LlmOverloadedException;
import at.aimon.core.llm.invoke.LlmCallGateway;
import at.aimon.core.llm.retry.LlmFallbackPolicy;
import at.aimon.core.llm.retry.LlmRetryPolicy;
import at.aimon.core.subagent.DefaultSubagentExecutionManager;
import at.aimon.core.subagent.SubagentExecutionManager;

@DisplayName("OrcaAgentExecutorFactory Tests")
class OrcaAgentExecutorFactoryTest {

    private OrcaAgentExecutorFactory factory;
    private MockLlmClient mockLlmClient;

    @BeforeEach
    void setUp() {
        factory = new OrcaAgentExecutorFactory();
        mockLlmClient = new MockLlmClient();
    }

    @Test
    @DisplayName("Should create OrcaAgentExecutor with default managers")
    void testCreate_WithDefaults_ExecutorCreated() {
        OrcaAgentExecutor executor = factory.create(mockLlmClient, transcriptManager());

        assertThat(executor).isNotNull();
        assertThat(executor.getLlmClient()).isEqualTo(mockLlmClient);
    }

    @Test
    @DisplayName("Should create OrcaAgentExecutor with custom managers")
    void testCreate_WithCustomManagers_ExecutorCreatedWithCustomManagers() {
        TranscriptManager transcriptManager = new DefaultTranscriptManager(new InMemorySessionRecordStore());;
        ToolExecutionManager customToolManager = new DefaultToolExecutionManager();
        HookExecutionManager customHookManager = new DefaultHookExecutionManager();
        CommandExecutionManager customCommandManager = new DefaultCommandExecutionManager(mockLlmClient);
        SubagentExecutionManager customSubagentManager = new DefaultSubagentExecutionManager(mockLlmClient,
                customToolManager, customHookManager);

        OrcaAgentExecutor executor = factory.create(mockLlmClient, transcriptManager, customToolManager,
                customHookManager, customCommandManager, customSubagentManager);

        assertThat(executor).isNotNull();
        assertThat(executor.getLlmClient()).isEqualTo(mockLlmClient);
    }

    @Test
    @DisplayName("Default executor observes a schema violation rather than rejecting it")
    void testCreate_WithoutSchemaValidationMode_WarnsAndStillExecutes() {
        OrcaAgentExecutor executor = factory.create(mockLlmClient, transcriptManager());

        ToolExecutionResult result = callWithAnUndeclaredParameter(executor.getToolExecutionManager());

        assertThat(result.isError()).isFalse();
        assertThat(result.getContent()).isEqualTo("ran");
    }

    @Test
    @DisplayName("withSchemaValidationMode reaches the executor that runs the tools")
    void testCreate_WithEnforceSchemaValidationMode_RejectsTheCall() {
        // The mode is only worth having if setting it on the factory changes what a tool call does. Asserting on the
        // call rather than on a field is what makes this a wiring test: an unread setter would still pass the latter.
        OrcaAgentExecutor executor = factory.withSchemaValidationMode(SchemaValidationMode.ENFORCE)
                .create(mockLlmClient, transcriptManager());

        ToolExecutionResult result = callWithAnUndeclaredParameter(executor.getToolExecutionManager());

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("Unknown parameter 'recursive'")
                .contains("The tool was not executed.");
    }

    private static TranscriptManager transcriptManager() {
        return new DefaultTranscriptManager(new InMemorySessionRecordStore());
    }

    /** Runs one call carrying a parameter the tool's schema does not declare, through the given manager. */
    private ToolExecutionResult callWithAnUndeclaredParameter(ToolExecutionManager manager) {
        ToolRegistry registry = new DefaultToolRegistry();
        registry.register(new StrictSchemaTool());

        return manager.execute(
                ToolUse.of("call-1", StrictSchemaTool.TOOL_NAME, Map.of("path", "/tmp/x", "recursive", true)),
                ToolContext.empty(), registry, List.of());
    }

    @Test
    @DisplayName("Should reject null LLM client in create method")
    void testCreate_NullLlmClient_ThrowsException() {
        assertThatThrownBy(() -> factory.create(null, transcriptManager())).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("LLM client cannot be null");
    }

    @Test
    @DisplayName("Should reject null LLM client in create with custom managers")
    void testCreate_NullLlmClientWithCustomManagers_ThrowsException() {
        TranscriptManager transcriptManager = new DefaultTranscriptManager(new InMemorySessionRecordStore());;
        ToolExecutionManager toolManager = new DefaultToolExecutionManager();
        HookExecutionManager hookManager = new DefaultHookExecutionManager();
        CommandExecutionManager commandManager = new DefaultCommandExecutionManager(mockLlmClient);
        SubagentExecutionManager subagentManager = new DefaultSubagentExecutionManager(mockLlmClient, toolManager,
                hookManager);

        assertThatThrownBy(() -> factory.create(null, transcriptManager, toolManager, hookManager, commandManager,
                subagentManager)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("LLM client cannot be null");
    }

    @Test
    @DisplayName("Should reject null tool execution manager")
    void testCreate_NullToolExecutionManager_ThrowsException() {
        TranscriptManager transcriptManager = new DefaultTranscriptManager(new InMemorySessionRecordStore());;
        HookExecutionManager hookManager = new DefaultHookExecutionManager();
        CommandExecutionManager commandManager = new DefaultCommandExecutionManager(mockLlmClient);
        SubagentExecutionManager subagentManager = new DefaultSubagentExecutionManager(mockLlmClient,
                new DefaultToolExecutionManager(), hookManager);

        assertThatThrownBy(() -> factory.create(mockLlmClient, transcriptManager, null, hookManager, commandManager,
                subagentManager)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Tool execution manager cannot be null");
    }

    @Test
    @DisplayName("Should reject null hook execution manager")
    void testCreate_NullHookExecutionManager_ThrowsException() {
        TranscriptManager transcriptManager = new DefaultTranscriptManager(new InMemorySessionRecordStore());;
        ToolExecutionManager toolManager = new DefaultToolExecutionManager();
        CommandExecutionManager commandManager = new DefaultCommandExecutionManager(mockLlmClient);
        SubagentExecutionManager subagentManager = new DefaultSubagentExecutionManager(mockLlmClient, toolManager,
                new DefaultHookExecutionManager());

        assertThatThrownBy(() -> factory.create(mockLlmClient, transcriptManager, toolManager, null, commandManager,
                subagentManager)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Hook execution manager cannot be null");
    }

    @Test
    @DisplayName("Should reject null command execution manager")
    void testCreate_NullCommandExecutionManager_ThrowsException() {
        TranscriptManager transcriptManager = new DefaultTranscriptManager(new InMemorySessionRecordStore());;
        ToolExecutionManager toolManager = new DefaultToolExecutionManager();
        HookExecutionManager hookManager = new DefaultHookExecutionManager();
        SubagentExecutionManager subagentManager = new DefaultSubagentExecutionManager(mockLlmClient, toolManager,
                hookManager);

        assertThatThrownBy(
                () -> factory.create(mockLlmClient, transcriptManager, toolManager, hookManager, null, subagentManager))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Command execution manager cannot be null");
    }

    @Test
    @DisplayName("Should reject null subagent execution manager")
    void testCreate_NullSubagentExecutionManager_ThrowsException() {
        TranscriptManager transcriptManager = new DefaultTranscriptManager(new InMemorySessionRecordStore());;
        ToolExecutionManager toolManager = new DefaultToolExecutionManager();
        HookExecutionManager hookManager = new DefaultHookExecutionManager();
        CommandExecutionManager commandManager = new DefaultCommandExecutionManager(mockLlmClient);

        assertThatThrownBy(
                () -> factory.create(mockLlmClient, transcriptManager, toolManager, hookManager, commandManager, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Subagent execution manager cannot be null");
    }

    @Test
    @DisplayName("Should create multiple executors independently")
    void testCreate_MultipleExecutors_CreatedIndependently() {
        OrcaAgentExecutor executor1 = factory.create(mockLlmClient, transcriptManager());
        OrcaAgentExecutor executor2 = factory.create(mockLlmClient, transcriptManager());

        assertThat(executor1).isNotNull();
        assertThat(executor2).isNotNull();
        assertThat(executor1).isNotSameAs(executor2);
    }

    @Test
    @DisplayName("Should allow factory subclassing for custom default managers")
    void testCreate_CustomFactorySubclass_UsesCustomDefaults() {
        CustomOrcaAgentExecutorFactory customFactory = new CustomOrcaAgentExecutorFactory();
        OrcaAgentExecutor executor = customFactory.create(mockLlmClient, transcriptManager());

        assertThat(executor).isNotNull();
        assertThat(executor.getLlmClient()).isEqualTo(mockLlmClient);
    }

    @Test
    @DisplayName("Factory without gateway override builds a default pass-through gateway around the supplied client")
    void testCreate_WithoutGatewayOverride_UsesDefaultPassThroughGateway() {
        OrcaAgentExecutor executor = factory.create(mockLlmClient, transcriptManager());

        // The default gateway must wrap the supplied client and be wired as the executor's gateway.
        LlmCallGateway<TranscriptBuffer> gateway = executor.getGateway();
        assertThat(gateway).isNotNull();
        assertThat(gateway.getClient()).isSameAs(mockLlmClient);
        // Legacy accessor still resolves to the same wrapped client.
        assertThat(executor.getLlmClient()).isSameAs(mockLlmClient);
    }

    @Test
    @DisplayName("Factory with gateway override honors the custom gateway and exposes it on the executor")
    void testCreate_WithGatewayOverride_UsesCustomGateway() {
        LlmCallGateway<TranscriptBuffer> customGateway = LlmCallGateway.<TranscriptBuffer>builder()
                .client(mockLlmClient).retryPolicy(LlmRetryPolicy.defaultPolicy())
                .fallbackPolicy(LlmFallbackPolicy.none()).build();

        OrcaAgentExecutorFactory overriddenFactory = new OrcaAgentExecutorFactory().withGateway(customGateway);
        OrcaAgentExecutor executor = overriddenFactory.create(mockLlmClient, transcriptManager());

        // The executor must use the exact gateway instance supplied, not a factory-built default.
        assertThat(executor.getGateway()).isSameAs(customGateway);
        assertThat(executor.getLlmClient()).isSameAs(mockLlmClient);
    }

    @Test
    @DisplayName("Fallback policy override is wired into the factory-built default gateway")
    void testCreate_WithFallbackPolicy_WiresIntoDefaultGateway() {
        LlmModel primary = LlmModel.builder().name("primary").build();
        LlmModel secondary = LlmModel.builder().name("secondary").build();
        LlmFallbackPolicy policy = LlmFallbackPolicy.builder().fallbackChain(List.of(primary, secondary))
                .activatingExceptions(Set.of(LlmOverloadedException.class)).consecutiveFailureThreshold(3).build();

        OrcaAgentExecutorFactory configured = new OrcaAgentExecutorFactory().withFallbackPolicy(policy);
        OrcaAgentExecutor executor = configured.create(mockLlmClient, transcriptManager());

        // The default gateway must carry the configured fallback policy verbatim.
        assertThat(executor.getGateway().getFallbackPolicy()).isSameAs(policy);
    }

    @Test
    @DisplayName("Without a fallback policy the default gateway performs no model fallback")
    void testCreate_WithoutFallbackPolicy_DefaultsToNoFallback() {
        OrcaAgentExecutor executor = factory.create(mockLlmClient, transcriptManager());

        assertThat(executor.getGateway().getFallbackPolicy()).isEqualTo(LlmFallbackPolicy.none());
    }

    @Test
    @DisplayName("An explicit gateway override wins and the fallback policy knob is ignored")
    void testCreate_WithGatewayOverrideAndFallbackPolicy_GatewayWins() {
        LlmModel primary = LlmModel.builder().name("primary").build();
        LlmModel secondary = LlmModel.builder().name("secondary").build();
        LlmFallbackPolicy ignored = LlmFallbackPolicy.builder().fallbackChain(List.of(primary, secondary))
                .activatingExceptions(Set.of(LlmOverloadedException.class)).consecutiveFailureThreshold(3).build();
        LlmCallGateway<TranscriptBuffer> customGateway = LlmCallGateway.<TranscriptBuffer>builder()
                .client(mockLlmClient).retryPolicy(LlmRetryPolicy.defaultPolicy())
                .fallbackPolicy(LlmFallbackPolicy.none()).build();

        OrcaAgentExecutorFactory configured = new OrcaAgentExecutorFactory().withGateway(customGateway)
                .withFallbackPolicy(ignored);
        OrcaAgentExecutor executor = configured.create(mockLlmClient, transcriptManager());

        // resolveGateway returns the explicit gateway verbatim; the fallback policy field is never consulted.
        assertThat(executor.getGateway()).isSameAs(customGateway);
        assertThat(executor.getGateway().getFallbackPolicy()).isEqualTo(LlmFallbackPolicy.none());
    }

    // Mock implementations

    /**
     * Mock LlmClient for testing purposes.
     */
    private static class MockLlmClient implements LlmClient {
        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel model) {
            return LlmResponse.text("Mock response");
        }

        @Override
        public String getProviderName() {
            return "Mock Provider";
        }

    }

    @Test
    @DisplayName("The command manager is handed the executor's own tool manager, so the ceiling reaches slash skills")
    void testCreate_CommandManagerSharesTheExecutorsToolExecutionManager() {
        final AtomicReference<ToolExecutionManager> forTheExecutor = new AtomicReference<>();
        final AtomicReference<ToolExecutionManager> forTheCommandPath = new AtomicReference<>();

        final OrcaAgentExecutorFactory capturing = new OrcaAgentExecutorFactory() {
            @Override
            protected ToolExecutionManager createDefaultToolExecutionManager() {
                final ToolExecutionManager manager = super.createDefaultToolExecutionManager();
                forTheExecutor.set(manager);
                return manager;
            }

            @Override
            protected CommandExecutionManager createDefaultCommandExecutionManager(LlmClient llmClient,
                    ToolExecutionManager toolExecutionManager) {
                forTheCommandPath.set(toolExecutionManager);
                return super.createDefaultCommandExecutionManager(llmClient, toolExecutionManager);
            }
        };

        capturing.withMaxSideEffectLevel(SideEffectLevel.READ_ONLY).create(mockLlmClient,
                new DefaultTranscriptManager(new InMemorySessionRecordStore()));

        // Same instance, not merely same ceiling: a second manager is a second setting, and a setting that can be
        // forgotten is how a user-invoked skill came to outrank the agent that invoked it.
        assertThat(forTheCommandPath.get()).isSameAs(forTheExecutor.get());
        assertThat(forTheCommandPath.get().getMaxSideEffectLevel()).isEqualTo(SideEffectLevel.READ_ONLY);
    }

    /** A tool whose schema opts into strictness, so an undeclared parameter is something the gate can see. */
    private static class StrictSchemaTool extends AbstractTool {

        static final String TOOL_NAME = "Strict";

        StrictSchemaTool() {
            super(TOOL_NAME, "Accepts exactly one declared parameter",
                    Map.of("type", "object", "additionalProperties", false, "properties",
                            Map.of("path", Map.of("type", "string", "description", "A path")), "required",
                            List.of("path")));
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext context) {
            return ToolResult.success("ran");
        }
    }

    /**
     * Custom factory subclass to test extensibility.
     */
    private static class CustomOrcaAgentExecutorFactory extends OrcaAgentExecutorFactory {
        @Override
        protected ToolExecutionManager createDefaultToolExecutionManager() {
            // Could return custom implementation if needed
            return super.createDefaultToolExecutionManager();
        }

        @Override
        protected HookExecutionManager createDefaultHookExecutionManager() {
            // Could return custom implementation if needed
            return super.createDefaultHookExecutionManager();
        }

        @Override
        protected CommandExecutionManager createDefaultCommandExecutionManager(LlmClient llmClient,
                ToolExecutionManager toolExecutionManager) {
            // Could return custom implementation if needed
            return super.createDefaultCommandExecutionManager(llmClient, toolExecutionManager);
        }

        @Override
        protected SubagentExecutionManager createDefaultSubagentExecutionManager(LlmClient llmClient,
                ToolExecutionManager toolExecutionManager, HookExecutionManager hookExecutionManager) {
            // Could return custom implementation if needed
            return super.createDefaultSubagentExecutionManager(llmClient, toolExecutionManager, hookExecutionManager);
        }
    }
}
