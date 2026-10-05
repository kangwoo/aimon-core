package at.aimon.workflow.graaljs;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.tool.DefaultToolExecutionManager;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.environment.EnvironmentRequest;
import at.aimon.core.environment.ExecutionEnvironmentProvider;
import at.aimon.core.environment.UnavailableExecutionEnvironment;
import at.aimon.core.hook.DefaultHookExecutionManager;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.subagent.DefaultSubagentExecutionManager;
import at.aimon.core.subagent.InMemorySubagentRegistry;
import at.aimon.core.subagent.Subagent;
import at.aimon.core.subagent.SubagentExecutionEnvironment;
import at.aimon.core.subagent.execution.DefaultSubagentExecutor;
import at.aimon.core.workflow.RunId;
import at.aimon.core.workflow.WorkflowRunner;
import at.aimon.core.workflow.WorkflowRunnerOptions;
import at.aimon.core.workflow.WorkflowRunners;

/**
 * EE-42 end to end: a JS step's attributes reach the {@link EnvironmentRequest} a provider sees. Unlike
 * {@link AbstractGraalJsRunTest}, whose mocked manager never builds a request, this drives the <b>real</b> execution
 * manager and fork executor over an LLM that answers "done" at once.
 */
@DisplayName("GraalJS workflow steps — EnvironmentRequest carries the step's attributes (EE-42)")
class GraalJsEnvironmentRequestTest {

    private GraalJsEngineHolder engines;
    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        engines = GraalJsEngineHolder.create();
        pool = Executors.newCachedThreadPool();
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
        engines.close();
    }

    @Test
    @DisplayName("agent() gets the registered definition's attributes; parallel() descriptors each get their own")
    void environmentRequestsCarryStepAttributes() {
        final String js = "agent({ agentType: 'builder', goal: 'compile' });\n" + "await parallel([\n"
                + "  { agentType: 'reviewer', goal: 'r', attributes: { sandbox: { profile: 'ro' } } },\n"
                + "  { agentType: 'builder', goal: 't', attributes: { 'sandbox.profile': 'rw' } },\n" + "]);\n"
                + "return 'done';";

        final List<EnvironmentRequest> requests = run(js);

        assertThat(requests).hasSize(3).allSatisfy(request -> assertThat(request.fork()).isPresent());
        assertThat(requests).extracting(request -> request.fork().get().name() + " " + request.definitionAttributes())
                .containsExactlyInAnyOrder("graaljs:builder {sandbox.slot=build}",
                        "graaljs:reviewer {sandbox.profile=ro}",
                        "graaljs:builder {sandbox.slot=build, sandbox.profile=rw}");
    }

    @Test
    @DisplayName("pipeline() stage descriptors carry their attributes, registered ones included, for every item")
    void pipelineStagesCarryAttributes() {
        final String js = "await pipeline(['x', 'y'],\n"
                + "  (prev, item) => ({ agentType: 'finder', goal: 'find ' + item,"
                + " attributes: { sandbox: { slot: 'ro' } } }),\n"
                + "  (prev, item) => ({ agentType: 'builder', goal: 'fix ' + item, attributes: { gpu: true } })\n"
                + ");\n" + "return 'done';";

        final List<EnvironmentRequest> requests = run(js);

        assertThat(requests).hasSize(4).allSatisfy(request -> assertThat(request.fork()).isPresent());
        assertThat(requests).extracting(request -> request.fork().get().name() + " " + request.definitionAttributes())
                .containsExactlyInAnyOrder("graaljs:finder {sandbox.slot=ro}", "graaljs:finder {sandbox.slot=ro}",
                        "graaljs:builder {sandbox.slot=build, gpu=true}",
                        "graaljs:builder {sandbox.slot=build, gpu=true}");
    }

    /**
     * Runs {@code js} over a registry holding {@code builder} ({@code sandbox.slot: build}), with the keys these
     * scripts set allowed (EE-45), and returns the requests the provider saw.
     */
    private List<EnvironmentRequest> run(String js) {
        final InMemorySubagentRegistry registry = new InMemorySubagentRegistry();
        registry.register(Subagent.builder().name("builder").systemPrompt("unused")
                .attributes(Map.of("sandbox.slot", "build")).build());
        final List<EnvironmentRequest> requests = new CopyOnWriteArrayList<>();
        final ExecutionEnvironmentProvider provider = request -> {
            requests.add(request);
            return UnavailableExecutionEnvironment.of("not needed");
        };
        final GraalJsWorkflowScript script = new GraalJsWorkflowScript(js, Map.of(), JsSandboxConfig.defaults(),
                engines, SubagentResolver.inline(registry, List.of("sandbox.profile", "sandbox.slot", "gpu")), null);
        final DefaultSubagentExecutionManager manager = new DefaultSubagentExecutionManager(new DefaultSubagentExecutor(
                new DoneLlmClient(), new DefaultToolExecutionManager(), new DefaultHookExecutionManager()), pool);
        try (WorkflowRunner runner = WorkflowRunners.create(manager, env(registry, provider),
                WorkflowRunnerOptions.defaults())) {
            assertThat(runner.run(script, RunId.from("ee42-run"))).isEqualTo("done");
        }
        return requests;
    }

    private static SubagentExecutionEnvironment env(InMemorySubagentRegistry registry,
            ExecutionEnvironmentProvider provider) {
        return SubagentExecutionEnvironment.builder().agentRuntimeId(AgentRuntimeId.of("agent:test"))
                .subagentRegistry(registry).toolRegistry(new DefaultToolRegistry())
                .hookRegistry(new DefaultHookRegistry()).defaultModel(LlmModel.builder().name("gpt-4").build())
                .executionEnvironmentProvider(provider).build();
    }

    /** An LLM that answers every call with a final "done". */
    private static final class DoneLlmClient implements LlmClient {

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            return LlmResponse.text("done");
        }

        @Override
        public String getProviderName() {
            return "Stub";
        }
    }
}
