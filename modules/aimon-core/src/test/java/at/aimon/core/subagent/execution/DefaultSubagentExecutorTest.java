package at.aimon.core.subagent.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.Environment;
import at.aimon.core.agent.budget.ExecutionBudget;
import at.aimon.core.agent.interrupt.CancellationSignal;
import at.aimon.core.agent.interrupt.DefaultInterruptCoordinator;
import at.aimon.core.agent.interrupt.InterruptCoordinator;
import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.agent.interrupt.NoopCancellationSignal;
import at.aimon.core.agent.prompt.SystemPromptParts;
import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.DefaultToolExecutionManager;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.agent.tool.InterruptToolKeys;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolContextEnricher;
import at.aimon.core.agent.tool.ToolContextKey;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.base.Principal;
import at.aimon.core.hook.DefaultHookExecutionManager;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.knowledge.KnowledgeScope;
import at.aimon.core.knowledge.KnowledgeStore;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmCancellation;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.cost.Money;
import at.aimon.core.llm.exception.LlmCallCancelledException;
import at.aimon.core.subagent.Subagent;
import at.aimon.core.subagent.SubagentContent;
import at.aimon.core.subagent.SubagentMetadata;
import at.aimon.core.tools.ToolContextKeys;

@DisplayName("DefaultSubagentExecutor cancellation, ToolContext parity, and budget")
class DefaultSubagentExecutorTest {

    private DefaultSubagentExecutor newExecutor(LlmClient llmClient) {
        return new DefaultSubagentExecutor(llmClient, new DefaultToolExecutionManager(),
                new DefaultHookExecutionManager());
    }

    @Test
    @DisplayName("이미 취소된 parent signal 이면 LLM 호출 없이 즉시 interrupted 로 종료한다")
    void preCancelledParentSignalShortCircuits() {
        final DefaultInterruptCoordinator parent = new DefaultInterruptCoordinator();
        parent.requestInterrupt(InterruptReason.USER_SIGINT);

        final StubLlmClient llm = new StubLlmClient();
        final SubagentExecutionContext context = createContext("explorer", parent.getSignal(),
                new DefaultToolRegistry(), 5);

        final SubagentExecutionResult result = newExecutor(llm).execute(context, request("explore"));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).isEqualTo("Execution interrupted");
        assertThat(result.getIterationCount()).isZero();
        assertThat(llm.calls).isZero();
    }

    @Test
    @DisplayName("도구 실행 중 parent 가 취소되면 다음 LLM 호출 전에 interrupted 로 종료한다")
    void parentCancelDuringToolExecutionStopsBeforeNextCall() {
        final DefaultInterruptCoordinator parent = new DefaultInterruptCoordinator();
        final DefaultToolRegistry registry = new DefaultToolRegistry();
        registry.register(new TripTool(parent));

        final StubLlmClient llm = new StubLlmClient();
        llm.responses.add(LlmResponse.of("", List.of(ToolUse.of("t1", "Trip", Map.of()))));
        // A second response exists but must never be consumed because the tail cancel check fires first.
        llm.responses.add(LlmResponse.text("should-not-be-reached"));

        final SubagentExecutionContext context = createContext("explorer", parent.getSignal(), registry, 5);

        final SubagentExecutionResult result = newExecutor(llm).execute(context, request("go"));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).isEqualTo("Execution interrupted");
        assertThat(llm.calls).isEqualTo(1);
    }

    @Test
    @DisplayName("provider 가 in-flight LLM 호출을 abort 하면(LlmCallCancelledException) failure 가 아니라 interrupted 로 라우팅한다")
    void inFlightLlmCancelledRoutesToInterruptedNotFailure() {
        // §6.5 regression guard: LlmCallCancelledException extends LlmClientException. It must be caught by the
        // dedicated handler BEFORE the generic LlmClientException handler. We deliberately do NOT trip the signal, so
        // ONLY the exception-type routing can produce "interrupted" — the pre-fix code (catch LlmClientException →
        // FAILURE) would have reported "LLM client error: ...".
        final CancellationAwareLlmClient llm = new CancellationAwareLlmClient();
        llm.throwCancelled = true;

        final SubagentExecutionContext context = createContext("explorer", NoopCancellationSignal.INSTANCE,
                new DefaultToolRegistry(), 5);

        final SubagentExecutionResult result = newExecutor(llm).execute(context, request("go"));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).isEqualTo("Execution interrupted");
        assertThat(result.getErrorMessage()).doesNotContain("LLM client error");
        assertThat(llm.calls).isEqualTo(1);
    }

    @Test
    @DisplayName("실행기는 signal-backed(지원됨) LlmCancellation 을 gateway 로 전달하며, parent 취소가 in-flight abort 레버를 발화시킨다")
    void signalBackedTokenIsForwardedAndAbortsInFlightCall() {
        final DefaultInterruptCoordinator parent = new DefaultInterruptCoordinator();
        final CancellationAwareLlmClient llm = new CancellationAwareLlmClient();
        // Simulate the provider mid-call: register its abort lever, then the parent cancels (cascading into the
        // execution-scoped signal that backs the token), which must fire the registered abort; the provider then
        // unwinds by throwing LlmCallCancelledException as a real aborted HTTP stream would.
        llm.onCall = () -> parent.requestInterrupt(InterruptReason.PARENT_CANCELLED);
        llm.throwCancelled = true;

        final SubagentExecutionContext context = createContext("explorer", parent.getSignal(),
                new DefaultToolRegistry(), 5);

        final SubagentExecutionResult result = newExecutor(llm).execute(context, request("go"));

        // The token handed to the provider is a live, cancellable (supported) token — not LlmCancellation.none().
        assertThat(llm.capturedToken.get()).isNotNull();
        assertThat(llm.capturedToken.get().isSupported()).isTrue();
        // The in-flight abort lever the provider registered actually fired when the parent cancellation cascaded in.
        assertThat(llm.abortFired.get()).isEqualTo(1);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).isEqualTo("Execution interrupted");
    }

    @Test
    @DisplayName("서브에이전트 도구는 main-agent 와 동일한 ToolContext 키들을 받는다")
    void toolContextHasParityKeys() {
        final ProbeTool probe = new ProbeTool();
        final DefaultToolRegistry registry = new DefaultToolRegistry();
        registry.register(probe);

        final StubLlmClient llm = new StubLlmClient();
        llm.responses.add(LlmResponse.of("", List.of(ToolUse.of("p1", "Probe", Map.of()))));
        llm.responses.add(LlmResponse.text("done"));

        final SubagentExecutionContext context = createContext("explorer", NoopCancellationSignal.INSTANCE, registry,
                5);

        final SubagentExecutionResult result = newExecutor(llm).execute(context, request("probe"));

        assertThat(result.isSuccess()).isTrue();
        final ToolContext captured = probe.captured.get();
        assertThat(captured).isNotNull();
        assertThat(captured.get(ToolContextKeys.AGENT_RUNTIME_ID)).isPresent();
        assertThat(captured.get(ToolContextKeys.ENVIRONMENT_KEY)).isPresent();
        assertThat(captured.get(ToolContextKeys.LLM_CALL_METADATA_KEY)).isPresent();
        assertThat(captured.get(ToolContextKeys.ARTIFACT_COLLECTOR)).isPresent();
        assertThat(captured.get(ToolContextKeys.EXECUTION_ATTRIBUTES_KEY)).isPresent();
        assertThat(captured.get(InterruptToolKeys.CANCELLATION_SIGNAL)).isPresent();
        assertThat(captured.get(ToolContextKeys.CURRENT_TOOL_USE_ID_KEY)).contains("p1");
    }

    @Test
    @DisplayName("request 의 principal 은 서브에이전트 도구의 ToolContext PRINCIPAL 키로 전달된다")
    void principalIsForwardedToToolContext() {
        final ProbeTool probe = new ProbeTool();
        final DefaultToolRegistry registry = new DefaultToolRegistry();
        registry.register(probe);

        final StubLlmClient llm = new StubLlmClient();
        llm.responses.add(LlmResponse.of("", List.of(ToolUse.of("p1", "Probe", Map.of()))));
        llm.responses.add(LlmResponse.text("done"));

        final SubagentExecutionContext context = createContext("explorer", NoopCancellationSignal.INSTANCE, registry,
                5);
        final Principal principal = Principal.user("alice");
        final SubagentExecutionRequest request = SubagentExecutionRequest.builder().taskId("task-1").goal("probe")
                .principal(principal).build();

        final SubagentExecutionResult result = newExecutor(llm).execute(context, request);

        assertThat(result.isSuccess()).isTrue();
        assertThat(probe.captured.get().get(ToolContextKeys.PRINCIPAL)).contains(principal);
    }

    @Test
    @DisplayName("forward 된 knowledgeStore/Scope 는 서브에이전트 도구 ToolContext 로 주입된다")
    void knowledgeStoreAndScopeAreForwarded() {
        final ProbeTool probe = new ProbeTool();
        final DefaultToolRegistry registry = new DefaultToolRegistry();
        registry.register(probe);

        final StubLlmClient llm = new StubLlmClient();
        llm.responses.add(LlmResponse.of("", List.of(ToolUse.of("p1", "Probe", Map.of()))));
        llm.responses.add(LlmResponse.text("done"));

        final KnowledgeStore knowledgeStore = mock(KnowledgeStore.class);
        final KnowledgeScope knowledgeScope = new KnowledgeScope("parent-agent", "agent:test-1");
        final SubagentExecutionContext context = SubagentExecutionContext.builder()
                .agentRuntimeId(AgentRuntimeId.of("agent:test-1")).subagent(subagent("explorer", 5))
                .defaultModel(LlmModel.builder().name("gpt-4").build()).toolRegistry(registry)
                .hookRegistry(new DefaultHookRegistry()).environment(Environment.createDefault())
                .knowledgeStore(knowledgeStore).knowledgeScope(knowledgeScope).build();

        final SubagentExecutionResult result = newExecutor(llm).execute(context, request("probe"));

        assertThat(result.isSuccess()).isTrue();
        final ToolContext captured = probe.captured.get();
        assertThat(captured.get(ToolContextKeys.KNOWLEDGE_STORE)).contains(knowledgeStore);
        assertThat(captured.get(ToolContextKeys.KNOWLEDGE_SCOPE)).contains(knowledgeScope);
    }

    @Test
    @DisplayName("forward 된 ToolContextEnricher 는 서브에이전트 도구 호출 전에 실행되어 키를 주입한다")
    void toolContextEnrichersAreApplied() {
        final ToolContextKey<String> enrichedKey = ToolContextKey.of("test.enriched", String.class);
        final ProbeTool probe = new ProbeTool();
        final DefaultToolRegistry registry = new DefaultToolRegistry();
        registry.register(probe);

        final StubLlmClient llm = new StubLlmClient();
        llm.responses.add(LlmResponse.of("", List.of(ToolUse.of("p1", "Probe", Map.of()))));
        llm.responses.add(LlmResponse.text("done"));

        final ToolContextEnricher enricher = (builder, info) -> builder.put(enrichedKey,
                "ctx:" + info.getAgentRuntimeId().value());
        final SubagentExecutionContext context = SubagentExecutionContext.builder()
                .agentRuntimeId(AgentRuntimeId.of("agent:test-1")).subagent(subagent("explorer", 5))
                .defaultModel(LlmModel.builder().name("gpt-4").build()).toolRegistry(registry)
                .hookRegistry(new DefaultHookRegistry()).environment(Environment.createDefault())
                .toolContextEnrichers(List.of(enricher)).build();

        final SubagentExecutionResult result = newExecutor(llm).execute(context, request("probe"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(probe.captured.get().get(enrichedKey)).contains("ctx:agent:test-1");
    }

    @Test
    @DisplayName("ExecutionBudget 의 maxIterations 가 subagent maxIterations 보다 먼저 루프를 멈춘다")
    void budgetMaxIterationsStopsLoop() {
        final StubLlmClient llm = new StubLlmClient();
        llm.alwaysToolUse = true; // never terminates on its own

        final SubagentExecutionContext context = createContext("explorer", NoopCancellationSignal.INSTANCE,
                new DefaultToolRegistry(), 5);
        final SubagentExecutionRequest request = SubagentExecutionRequest.builder().taskId("t").goal("loop")
                .budget(ExecutionBudget.builder().maxIterations(1).build()).build();

        final SubagentExecutionResult result = newExecutor(llm).execute(context, request);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("stopped");
        assertThat(llm.calls).isEqualTo(1);
    }

    @Test
    @DisplayName("ExecutionBudget 의 maxCostUsd 가 in-loop 비용 집계(recordCost)로 루프를 멈춘다 (비용 미러)")
    void budgetMaxCostStopsLoop() {
        final StubLlmClient llm = new StubLlmClient();
        llm.alwaysToolUse = true; // never terminates on its own

        final SubagentExecutionContext context = createContext("explorer", NoopCancellationSignal.INSTANCE,
                new DefaultToolRegistry(), 5);
        final SubagentExecutionRequest request = SubagentExecutionRequest.builder().taskId("t").goal("loop")
                .budget(ExecutionBudget.builder().maxCostUsd(Money.usd(0.5)).build()).build();

        // Every call is priced at $1: the first call's cost already reaches the $0.50 ceiling, so the pre-iteration
        // budget check must refuse a second LLM call.
        final SubagentExecutionResult result = newExecutor(llm).withCostEstimator((model, usage) -> Money.usd(1.0))
                .execute(context, request);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("stopped");
        assertThat(llm.calls).isEqualTo(1);
    }

    @Test
    @DisplayName("실행 진행 상황(iteration/preamble/tool/final answer)이 outputSink 로 스트리밍된다")
    void streamsProgressMarkersToOutputSink() {
        final ProbeTool probe = new ProbeTool();
        final DefaultToolRegistry registry = new DefaultToolRegistry();
        registry.register(probe);

        final StubLlmClient llm = new StubLlmClient();
        llm.responses.add(LlmResponse.of("thinking about it", List.of(ToolUse.of("p1", "Probe", Map.of()))));
        llm.responses.add(LlmResponse.text("the final answer"));

        final StringBuilder captured = new StringBuilder();
        final SubagentOutputSink sink = text -> {
            synchronized (captured) {
                captured.append(text);
            }
        };
        final SubagentExecutionContext context = SubagentExecutionContext.builder()
                .agentRuntimeId(AgentRuntimeId.of("agent:test-1")).subagent(subagent("explorer", 5))
                .defaultModel(LlmModel.builder().name("gpt-4").build()).toolRegistry(registry)
                .hookRegistry(new DefaultHookRegistry()).environment(Environment.createDefault()).outputSink(sink)
                .build();

        final SubagentExecutionResult result = newExecutor(llm).execute(context, request("probe"));

        assertThat(result.isSuccess()).isTrue();
        final String streamed;
        synchronized (captured) {
            streamed = captured.toString();
        }
        assertThat(streamed).contains("[iteration 1]").contains("thinking about it").contains("→ Probe")
                .contains("← Probe [ok]").contains("[final answer]").contains("the final answer")
                .contains("[completed: SUCCESS");
    }

    @Test
    @DisplayName("outputSink 을 지정하지 않으면 NO_OP 로 안전하게 실행된다 (회귀 없음)")
    void withoutOutputSinkUsesNoOpAndSucceeds() {
        final StubLlmClient llm = new StubLlmClient();
        llm.responses.add(LlmResponse.text("done"));

        final SubagentExecutionContext context = createContext("explorer", NoopCancellationSignal.INSTANCE,
                new DefaultToolRegistry(), 5);

        final SubagentExecutionResult result = newExecutor(llm).execute(context, request("go"));

        assertThat(result.isSuccess()).isTrue();
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private SubagentExecutionContext createContext(String subagentName, CancellationSignal parentSignal,
            ToolRegistry toolRegistry, int maxIterations) {
        return SubagentExecutionContext.builder().agentRuntimeId(AgentRuntimeId.of("agent:test-1"))
                .subagent(subagent(subagentName, maxIterations)).defaultModel(LlmModel.builder().name("gpt-4").build())
                .toolRegistry(toolRegistry).hookRegistry(new DefaultHookRegistry())
                .environment(Environment.createDefault()).parentCancellationSignal(parentSignal).build();
    }

    private Subagent subagent(String subagentName, int maxIterations) {
        return Subagent.of(subagentName,
                SubagentMetadata.builder().description("d").maxIterations(maxIterations).build(),
                SubagentContent.of("you are " + subagentName));
    }

    private SubagentExecutionRequest request(String goal) {
        return SubagentExecutionRequest.builder().taskId("task-1").goal(goal).build();
    }

    private static Map<String, Object> emptySchema() {
        return Map.of("type", "object", "properties", Map.of());
    }

    /** Captures the {@link ToolContext} it is invoked with so the test can assert on injected keys. */
    private static final class ProbeTool extends AbstractTool {
        private final AtomicReference<ToolContext> captured = new AtomicReference<>();

        ProbeTool() {
            super("Probe", "Captures its tool context", emptySchema());
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext context) {
            captured.set(context);
            return ToolResult.success("ok");
        }
    }

    /** Trips the supplied (parent) coordinator when executed, simulating a parent-initiated cancel mid tool-call. */
    private static final class TripTool extends AbstractTool {
        private final InterruptCoordinator parent;

        TripTool(InterruptCoordinator parent) {
            super("Trip", "Trips the parent cancellation signal", emptySchema());
            this.parent = parent;
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext context) {
            parent.requestInterrupt(InterruptReason.USER_SIGINT);
            return ToolResult.success("tripped");
        }
    }

    /**
     * LLM client that exercises the cancellation-aware overload the way a real provider does under cancellation: it
     * captures
     * the {@link LlmCancellation} the gateway forwards, registers an abort lever via {@link LlmCancellation#onCancel},
     * and can actively abort the in-flight call by throwing {@link LlmCallCancelledException}.
     */
    private static final class CancellationAwareLlmClient implements LlmClient {
        private final AtomicReference<LlmCancellation> capturedToken = new AtomicReference<>();
        private final AtomicInteger abortFired = new AtomicInteger();
        private int calls;
        private Runnable onCall;
        private boolean throwCancelled;

        @Override
        public LlmResponse sendMessage(SystemPromptParts systemPromptParts, List<Message> messages,
                List<ToolDefinition> tools, LlmModel modelConfig, LlmCallMetadata metadata,
                LlmCancellation cancellation) {
            calls++;
            capturedToken.set(cancellation);
            // A real provider registers its SDK-specific abort lever (e.g. StreamResponse#close) here.
            cancellation.onCancel(abortFired::incrementAndGet);
            if (onCall != null) {
                onCall.run();
            }
            if (throwCancelled) {
                throw new LlmCallCancelledException("provider aborted the in-flight HTTP call");
            }
            return LlmResponse.text("done");
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            // Not exercised by these tests; the executor always uses the parts + cancellation overload above.
            return LlmResponse.text("done");
        }

        @Override
        public String getProviderName() {
            return "CancellationAwareStub";
        }

    }

    /** Minimal scriptable LLM client: returns queued responses, then a terminal text (or a tool use). */
    private static final class StubLlmClient implements LlmClient {
        private final Deque<LlmResponse> responses = new ArrayDeque<>();
        private int calls;
        private boolean alwaysToolUse;

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            return sendMessage(systemPrompt, messages, tools, modelConfig, LlmCallMetadata.empty());
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig, LlmCallMetadata metadata) {
            calls++;
            if (!responses.isEmpty()) {
                return responses.poll();
            }
            if (alwaysToolUse) {
                return LlmResponse.of("", List.of(ToolUse.of("auto-" + calls, "NoSuchTool", Map.of())));
            }
            return LlmResponse.text("done");
        }

        @Override
        public String getProviderName() {
            return "Stub";
        }

    }
}
