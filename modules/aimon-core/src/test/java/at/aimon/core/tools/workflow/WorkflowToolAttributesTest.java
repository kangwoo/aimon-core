package at.aimon.core.tools.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
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
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.base.UserLocale;
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
import at.aimon.core.subagent.SubagentExecutionManager;
import at.aimon.core.subagent.SubagentRegistry;
import at.aimon.core.subagent.behavior.InMemorySubagentBehaviorRegistry;
import at.aimon.core.subagent.execution.DefaultSubagentExecutor;
import at.aimon.core.subagent.execution.SubagentExecutor;
import at.aimon.core.tools.ToolContextKeys;

/**
 * EE-42: the built-in {@link WorkflowTool} steps take the attributes of the subagent registered under their role's
 * lookup name, so an execution environment provider can place them.
 */
@DisplayName("WorkflowTool — built-in steps carry the attributes registered for their role (EE-42)")
class WorkflowToolAttributesTest {

    private static final Map<String, String> JUDGE_ATTRIBUTES = Map.of("sandbox.slot", "judge");

    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        pool = Executors.newCachedThreadPool();
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    @Test
    @DisplayName("judge_panel: the judge steps carry the registered 'workflow-judge' attributes; unregistered roles none")
    void judgeStepsCarryRegisteredAttributes() {
        final InMemorySubagentRegistry registry = new InMemorySubagentRegistry();
        registry.register(roleDefinition(WorkflowTool.ROLE_JUDGE, JUDGE_ATTRIBUTES));
        final Map<String, Map<String, String>> seen = new ConcurrentHashMap<>();

        final ToolResult result = newTool(registry, recordingBehaviors(seen)).execute(
                ToolInput.of(Map.of("prompt", "how?", "strategy", "judge_panel", "perspectives", "a,b")),
                context(null));

        assertThat(result.isSuccess()).as(result.getContent()).isTrue();
        assertThat(seen.get("workflow:judge")).isEqualTo(JUDGE_ATTRIBUTES);
        assertThat(seen.get("workflow:candidate:a")).isEmpty();
        assertThat(seen.get("workflow:candidate:b")).isEmpty();
        assertThat(seen.get("workflow:synthesizer")).isEmpty();
    }

    @Test
    @DisplayName("a registry that throws leaves the steps without attributes instead of failing the workflow")
    void failingRegistryMeansNoAttributes() {
        final SubagentRegistry registry = mock(SubagentRegistry.class);
        when(registry.getSubagent(WorkflowTool.ROLE_SKEPTIC)).thenThrow(new IllegalStateException("registry down"));
        when(registry.getAllSubagents()).thenReturn(List.of());
        final Map<String, Map<String, String>> seen = new ConcurrentHashMap<>();

        final ToolResult result = newTool(registry, recordingBehaviors(seen)).execute(
                ToolInput.of(Map.of("prompt", "the sky is green", "strategy", "adversarial_verify")), context(null));

        assertThat(result.isSuccess()).as(result.getContent()).isTrue();
        assertThat(seen.get("workflow:skeptic")).isEmpty();
    }

    @Test
    @DisplayName("role attributes are resolved once per run: a registry change mid-fan-out does not split siblings")
    @SuppressWarnings("unchecked")
    void roleAttributesResolvedOncePerRun() {
        final SubagentRegistry registry = mock(SubagentRegistry.class);
        // Each lookup answers differently, standing in for a registry reloaded between sibling thunks.
        when(registry.getSubagent(WorkflowTool.ROLE_PERSPECTIVE)).thenReturn(
                Optional.of(roleDefinition(WorkflowTool.ROLE_PERSPECTIVE, Map.of("sandbox.slot", "first"))),
                Optional.of(roleDefinition(WorkflowTool.ROLE_PERSPECTIVE, Map.of("sandbox.slot", "second"))),
                Optional.of(roleDefinition(WorkflowTool.ROLE_PERSPECTIVE, Map.of("sandbox.slot", "third"))));
        when(registry.getAllSubagents()).thenReturn(List.of());
        final Map<String, Map<String, String>> seen = new ConcurrentHashMap<>();
        final InMemorySubagentBehaviorRegistry behaviors = new InMemorySubagentBehaviorRegistry();
        for (String angle : List.of("a", "b", "c")) {
            final String name = "workflow:perspective:" + angle;
            behaviors.register(name, (c, r, s) -> {
                seen.put(name, c.getSubagent().getMetadata().getAttributes());
                return s.success(angle + " analysis");
            });
        }

        final ToolResult result = newTool(registry, behaviors).execute(
                ToolInput.of(Map.of("prompt", "ship?", "perspectives", "a,b,c", "synthesize", Boolean.FALSE)),
                context(null));

        assertThat(result.isSuccess()).as(result.getContent()).isTrue();
        assertThat(seen).hasSize(3).allSatisfy(
                (name, attributes) -> assertThat(attributes).as(name).isEqualTo(Map.of("sandbox.slot", "first")));
        verify(registry, times(1)).getSubagent(WorkflowTool.ROLE_PERSPECTIVE);
    }

    @Test
    @DisplayName("through the real fork executor, each perspective's EnvironmentRequest carries the role's attributes")
    void environmentRequestCarriesRoleAttributes() {
        final InMemorySubagentRegistry registry = new InMemorySubagentRegistry();
        final Map<String, String> perspectiveAttributes = Map.of("sandbox.slot", "analysis");
        registry.register(roleDefinition(WorkflowTool.ROLE_PERSPECTIVE, perspectiveAttributes));
        final List<EnvironmentRequest> requests = new CopyOnWriteArrayList<>();
        final ExecutionEnvironmentProvider provider = request -> {
            requests.add(request);
            return UnavailableExecutionEnvironment.of("not needed");
        };
        final SubagentExecutor executor = new DefaultSubagentExecutor(new DoneLlmClient(),
                new DefaultToolExecutionManager(), new DefaultHookExecutionManager());
        final WorkflowTool tool = new WorkflowTool(LlmModel.builder().name("gpt-4").build(), registry,
                new DefaultToolRegistry(), new DefaultHookRegistry(), UserLocale.createDefault(),
                new DefaultSubagentExecutionManager(executor, pool), List.of());

        final ToolResult result = tool.execute(
                ToolInput.of(Map.of("prompt", "ship?", "perspectives", "risk,cost", "synthesize", Boolean.FALSE)),
                context(provider));

        assertThat(result.isSuccess()).as(result.getContent()).isTrue();
        assertThat(requests).hasSize(2).allSatisfy(request -> {
            assertThat(request.fork()).isPresent();
            assertThat(request.definitionAttributes()).isEqualTo(perspectiveAttributes);
        });
        assertThat(requests).extracting(request -> request.fork().get().name())
                .containsExactlyInAnyOrder("workflow:perspective:risk", "workflow:perspective:cost");
    }

    private WorkflowTool newTool(SubagentRegistry registry, InMemorySubagentBehaviorRegistry behaviors) {
        final SubagentExecutionManager manager = new DefaultSubagentExecutionManager(mock(SubagentExecutor.class), pool,
                null, behaviors);
        return new WorkflowTool(LlmModel.builder().name("gpt-4").build(), registry, new DefaultToolRegistry(),
                new DefaultHookRegistry(), UserLocale.createDefault(), manager, List.of());
    }

    /** Code behaviors for every built-in step name the tests reach, each recording its subagent's attributes. */
    private static InMemorySubagentBehaviorRegistry recordingBehaviors(Map<String, Map<String, String>> seen) {
        final InMemorySubagentBehaviorRegistry behaviors = new InMemorySubagentBehaviorRegistry();
        for (String name : List.of("workflow:candidate:a", "workflow:candidate:b", "workflow:synthesizer")) {
            behaviors.register(name, (c, r, s) -> {
                seen.put(name, c.getSubagent().getMetadata().getAttributes());
                return s.success(name + " answer");
            });
        }
        behaviors.register("workflow:judge", (c, r, s) -> {
            seen.put("workflow:judge", c.getSubagent().getMetadata().getAttributes());
            return s.success("{\"score\": 5}");
        });
        behaviors.register("workflow:skeptic", (c, r, s) -> {
            seen.put("workflow:skeptic", c.getSubagent().getMetadata().getAttributes());
            return s.success("{\"refuted\": false}");
        });
        return behaviors;
    }

    private static Subagent roleDefinition(String role, Map<String, String> attributes) {
        return Subagent.builder().name(role).systemPrompt("placement only").attributes(attributes).build();
    }

    private static ToolContext context(ExecutionEnvironmentProvider provider) {
        final ToolContext.Builder builder = ToolContext.builder().put(ToolContextKeys.AGENT_RUNTIME_ID,
                AgentRuntimeId.of("agent:test"));
        Optional.ofNullable(provider).ifPresent(p -> builder.put(ToolContextKeys.EXECUTION_ENVIRONMENT_PROVIDER, p));
        return builder.build();
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
