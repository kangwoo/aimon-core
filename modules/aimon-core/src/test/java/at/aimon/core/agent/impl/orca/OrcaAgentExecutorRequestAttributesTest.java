package at.aimon.core.agent.impl.orca;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.SubmitOptions;
import at.aimon.core.agent.impl.orca.OrcaAgentExecutorTestSupport.ConcurrentScriptedLlmClient;
import at.aimon.core.agent.session.DefaultLiveSession;
import at.aimon.core.agent.session.LiveSessionOptions;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolContextEnricher;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.command.DefaultCommandRegistry;
import at.aimon.core.environment.EnvironmentRequest;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.environment.ExecutionEnvironmentProvider;
import at.aimon.core.environment.TestExecutionEnvironments;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.skill.DefaultSkillRegistry;
import at.aimon.core.subagent.DefaultSubagentRegistry;
import at.aimon.core.tools.ToolContextKeys;

/**
 * A main turn's {@code environment.*} execution attributes reach its environment provider, prefix removed, as
 * {@link EnvironmentRequest#requestAttributes()} (issue #252).
 */
@DisplayName("OrcaAgentExecutor environment request attributes")
class OrcaAgentExecutorRequestAttributesTest {

    @TempDir
    Path tempDir;

    private OrcaAgentExecutorTestSupport support;
    private ConcurrentScriptedLlmClient llm;
    private OrcaAgentExecutor executor;
    private RecordingProvider provider;
    private ContextCapturingTool tool;

    @BeforeEach
    void setUp() {
        support = new OrcaAgentExecutorTestSupport(tempDir);
        llm = new ConcurrentScriptedLlmClient();
        executor = support.newExecutor(llm);
        provider = new RecordingProvider(TestExecutionEnvironments.provider(support.fileSystem()));
        tool = new ContextCapturingTool();
    }

    @Test
    @DisplayName("a turn submitted through a live session hands the provider the prefix-stripped environment.* subset")
    void submitOptionsReachTheProvider() {
        final SessionId sessionId = SessionId.of("conv-attrs");
        llm.script(sessionId.value(), LlmResponse.text("done"));
        final DefaultLiveSession session = new DefaultLiveSession(sessionId, runtime(List.of()), executor,
                LiveSessionOptions.defaults());
        try {
            session.submit("hi", SubmitOptions.builder().executionAttribute("environment.sandbox.workspace", "ws-7")
                    .executionAttribute("feature.flag", true).build());
        } finally {
            session.close();
        }

        assertThat(provider.requests).singleElement()
                .satisfies(request -> assertThat(request.requestAttributes())
                        .containsExactly(Map.entry("sandbox.workspace", "ws-7")))
                .satisfies(
                        request -> assertThat(request.toString()).contains("sandbox.workspace").doesNotContain("ws-7"));
    }

    @Test
    @DisplayName("a turn without environment.* attributes sends an empty map")
    void noPrefixedAttributesMeansEmpty() {
        final SessionId sessionId = SessionId.of("conv-none");
        llm.script(sessionId.value(), LlmResponse.text("done"));

        executor.execute(runtime(List.of()), OrcaAgentExecutionRequest.builder().userInput("hi").sessionId(sessionId)
                .executionAttributes(Map.of("feature.flag", "on")).build());

        assertThat(provider.requests).singleElement()
                .satisfies(request -> assertThat(request.requestAttributes()).isEmpty());
    }

    @Test
    @DisplayName("a non-String environment.* value skips the provider; tools see an unavailable environment naming the key")
    void nonStringValueMakesTheEnvironmentUnavailable() {
        final SessionId sessionId = SessionId.of("conv-bad");
        llm.script(sessionId.value(), OrcaAgentExecutorTestSupport.toolCall("t1", ContextCapturingTool.NAME),
                LlmResponse.text("done"));

        final OrcaAgentExecutionResult result = executor.execute(runtime(List.of()),
                OrcaAgentExecutionRequest.builder().userInput("hi").sessionId(sessionId)
                        .executionAttributes(Map.of("environment.sandbox.refuse-unattached", true)).build());

        assertThat(provider.requests).isEmpty();
        assertThat(result.isSuccess()).isTrue();
        final ExecutionEnvironment environment = tool.captured.get().get(ToolContextKeys.EXECUTION_ENVIRONMENT)
                .orElseThrow();
        assertThat(environment.descriptor().notes()).hasValueSatisfying(notes -> assertThat(notes)
                .contains("environment.sandbox.refuse-unattached").contains("java.lang.Boolean"));
    }

    @Test
    @DisplayName("an enricher rewriting the tool context's execution attributes cannot change what the provider saw")
    void enrichersCannotChangeTheRequest() {
        final SessionId sessionId = SessionId.of("conv-enricher");
        llm.script(sessionId.value(), OrcaAgentExecutorTestSupport.toolCall("t1", ContextCapturingTool.NAME),
                LlmResponse.text("done"));
        final ToolContextEnricher rewriting = (builder, info) -> builder.put(ToolContextKeys.EXECUTION_ATTRIBUTES_KEY,
                Map.of("environment.sandbox.workspace", "ws-other"));

        executor.execute(runtime(List.of(rewriting)), OrcaAgentExecutionRequest.builder().userInput("hi")
                .sessionId(sessionId).executionAttributes(Map.of("environment.sandbox.workspace", "ws-7")).build());

        // The request is built from the execution request before any tool context exists, and only once per turn.
        assertThat(provider.requests).singleElement().satisfies(request -> assertThat(request.requestAttributes())
                .containsExactly(Map.entry("sandbox.workspace", "ws-7")));
        assertThat(tool.captured.get()).isNotNull();
    }

    private OrcaAgentRuntime runtime(List<ToolContextEnricher> enrichers) {
        return OrcaAgentRuntime.builder().id(AgentRuntimeId.of("agent:attrs"))
                .agent(DefaultAgent.builder().name("TestAgent").maxIterations(5).systemPrompt("You are a test agent")
                        .build())
                .toolRegistry(OrcaAgentExecutorTestSupport.registryOf(tool)).hookRegistry(new DefaultHookRegistry())
                .commandRegistry(new DefaultCommandRegistry(support.fileSystem(), ".aimon/commands"))
                .subagentRegistry(new DefaultSubagentRegistry(support.fileSystem(), ".aimon/agents"))
                .skillRegistry(new DefaultSkillRegistry(support.fileSystem(), ".aimon/skills"))
                .controlFileSystem(support.fileSystem()).executionEnvironmentProvider(provider)
                .toolContextEnrichers(enrichers).build();
    }

    /** Records every request, then answers as the delegate does. */
    private static final class RecordingProvider implements ExecutionEnvironmentProvider {
        private final ExecutionEnvironmentProvider delegate;
        private final List<EnvironmentRequest> requests = new CopyOnWriteArrayList<>();

        RecordingProvider(ExecutionEnvironmentProvider delegate) {
            this.delegate = delegate;
        }

        @Override
        public ExecutionEnvironment resolve(EnvironmentRequest request) {
            requests.add(request);
            return delegate.resolve(request);
        }
    }

    /** Captures the tool context it runs with. */
    private static final class ContextCapturingTool extends AbstractTool {
        static final String NAME = "Capture";
        private final AtomicReference<ToolContext> captured = new AtomicReference<>();

        ContextCapturingTool() {
            super(NAME, "test tool", Map.of("type", "object", "properties", Map.of()));
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext context) {
            captured.set(context);
            return ToolResult.success("ok");
        }
    }
}
