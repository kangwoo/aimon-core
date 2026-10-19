package at.aimon.core.subagent.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.compact.CompactionEngine;
import at.aimon.core.agent.compact.CompactionResult;
import at.aimon.core.agent.compact.DefaultCompactionEngine;
import at.aimon.core.agent.compact.DefaultCompactionGuard;
import at.aimon.core.agent.context.ContextDecision;
import at.aimon.core.agent.context.ContextEngine;
import at.aimon.core.agent.context.ContextRequest;
import at.aimon.core.agent.context.ContextView;
import at.aimon.core.agent.context.DefaultContextEngine;
import at.aimon.core.agent.interrupt.CancellationSignal;
import at.aimon.core.agent.interrupt.DefaultInterruptCoordinator;
import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.agent.prompt.SystemPromptParts;
import at.aimon.core.agent.session.transcript.TranscriptBuffer;
import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.ConcurrencyBehavior;
import at.aimon.core.agent.tool.DefaultParallelToolDispatcher;
import at.aimon.core.agent.tool.DefaultToolExecutionManager;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.agent.tool.ToolConcurrencyConfig;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.hook.DefaultHookExecutionManager;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.llm.InMemoryModelContextWindowRegistry;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmCancellation;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ModelContextLimits;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.exception.LlmCallCancelledException;
import at.aimon.core.llm.exception.LlmClientException;
import at.aimon.core.llm.exception.LlmPromptTooLongException;
import at.aimon.core.llm.invoke.LlmCallGateway;
import at.aimon.core.llm.token.TokenEstimator;
import at.aimon.core.subagent.Subagent;
import at.aimon.core.subagent.SubagentContent;
import at.aimon.core.subagent.SubagentMetadata;
import at.aimon.core.tools.ToolContextKeys;
import at.aimon.core.tracing.SpanContext;
import at.aimon.core.tracing.SpanExporter;
import at.aimon.core.tracing.SpanStatus;
import at.aimon.core.tracing.SpanType;
import at.aimon.core.tracing.TraceSpan;
import at.aimon.core.tracing.Tracer;
import at.aimon.core.tracing.impl.DefaultTracer;
import at.aimon.core.tracing.impl.InMemoryTraceSpanStore;
import at.aimon.core.tracing.impl.TracingLlmClient;

@DisplayName("DefaultSubagentExecutor tracing — ITERATION/TOOL spans under the caller's span (TRACE-01)")
class DefaultSubagentExecutorTracingTest {

    private static final String SESSION = "s1";

    private InMemoryTraceSpanStore store;
    private Tracer tracer;
    private ScriptedLlmClient llm;
    /** The span the caller handed the fork (stands in for the SUBAGENT span the manager opens). */
    private Tracer.Span seedSpan;
    private LlmCallMetadata seededMetadata;

    @BeforeEach
    void setUp() {
        store = new InMemoryTraceSpanStore();
        tracer = new DefaultTracer(store, SpanExporter.noop());
        llm = new ScriptedLlmClient();
        final Tracer.Span root = tracer.startRoot(SESSION, SpanType.TURN, "turn", null);
        seedSpan = tracer.startChild(root.context(), SpanType.SUBAGENT, "subagent:explorer", null);
        seededMetadata = seedSpan.enrich(LlmCallMetadata.builder().traceId(SESSION).build());
    }

    @Test
    @DisplayName("each iteration is an ITERATION span under the caller's span; LLM and TOOL spans nest under it")
    void iterationsNestUnderCallerAndOwnLlmAndToolSpans() {
        final DefaultToolRegistry registry = new DefaultToolRegistry();
        registry.register(new CapturingTool("Probe", false));
        llm.responses.add(LlmResponse.of("", List.of(ToolUse.of("p1", "Probe", Map.of())), TokenUsage.of(3, 2, 5)));
        llm.responses.add(LlmResponse.text("done"));

        final SubagentExecutionResult result = tracedExecutor().execute(context(registry), request(seededMetadata));

        assertThat(result.isSuccess()).isTrue();
        final List<TraceSpan> iterations = spansOf(SpanType.ITERATION);
        assertThat(iterations).hasSize(2);
        assertThat(iterations).allSatisfy(s -> {
            assertThat(s.getParentSpanId()).contains(seedSpan.context().getSpanId());
            assertThat(s.getAttributes()).containsEntry("invokerType", "SUBAGENT").containsEntry("subagent",
                    "explorer");
            assertThat(s.getStatus()).isEqualTo(SpanStatus.OK);
        });
        final TraceSpan first = iterationNamed("iteration#1");
        final TraceSpan second = iterationNamed("iteration#2");

        final List<TraceSpan> llmSpans = spansOf(SpanType.LLM);
        assertThat(llmSpans).hasSize(2);
        assertThat(llmSpans).extracting(s -> s.getParentSpanId().orElseThrow())
                .containsExactlyInAnyOrder(first.getSpanId(), second.getSpanId());

        final List<TraceSpan> toolSpans = spansOf(SpanType.TOOL);
        assertThat(toolSpans).hasSize(1);
        assertThat(toolSpans.get(0).getName()).isEqualTo("Probe");
        assertThat(toolSpans.get(0).getParentSpanId()).contains(first.getSpanId());
        assertThat(toolSpans.get(0).getAttributes()).containsEntry("invokerType", "SUBAGENT");

        // The fork stays in the caller's trace.
        assertThat(store.bySession(SESSION))
                .allSatisfy(s -> assertThat(s.getTraceId()).isEqualTo(seedSpan.context().getTraceId()));
    }

    @Test
    @DisplayName("the tool receives metadata pointing at its own TOOL span, so a Task it runs nests under the call")
    void toolReceivesMetadataRotatedToItsToolSpan() {
        final DefaultToolRegistry registry = new DefaultToolRegistry();
        final CapturingTool probe = new CapturingTool("Probe", false);
        registry.register(probe);
        llm.responses.add(LlmResponse.of("", List.of(ToolUse.of("p1", "Probe", Map.of()))));
        llm.responses.add(LlmResponse.text("done"));

        tracedExecutor().execute(context(registry), request(seededMetadata));

        final TraceSpan toolSpan = spansOf(SpanType.TOOL).get(0);
        assertThat(probe.received).hasSize(1);
        final SpanContext parentSeenByTool = SpanContext.readFrom(probe.received.get(0)).orElseThrow();
        assertThat(parentSeenByTool.getSpanId()).isEqualTo(toolSpan.getSpanId());
    }

    @Test
    @DisplayName("parallel CONCURRENT_SAFE tools each get a TOOL span under the same ITERATION")
    void parallelToolsShareTheIterationParent() {
        final DefaultToolRegistry registry = new DefaultToolRegistry();
        final CapturingTool a = new CapturingTool("A", true);
        final CapturingTool b = new CapturingTool("B", true);
        registry.register(a);
        registry.register(b);
        llm.responses
                .add(LlmResponse.of("", List.of(ToolUse.of("a1", "A", Map.of()), ToolUse.of("b1", "B", Map.of()))));
        llm.responses.add(LlmResponse.text("done"));

        final DefaultSubagentExecutor executor = tracedExecutor()
                .withParallelToolDispatcher(new DefaultParallelToolDispatcher(ToolConcurrencyConfig.enabled(2)));
        executor.execute(context(registry), request(seededMetadata));

        final TraceSpan first = iterationNamed("iteration#1");
        final List<TraceSpan> toolSpans = spansOf(SpanType.TOOL);
        assertThat(toolSpans).extracting(TraceSpan::getName).containsExactlyInAnyOrder("A", "B");
        assertThat(toolSpans).allSatisfy(s -> assertThat(s.getParentSpanId()).contains(first.getSpanId()));
        // Each worker saw its own call's span, not its sibling's.
        assertThat(SpanContext.readFrom(a.received.get(0)).orElseThrow().getSpanId())
                .isEqualTo(toolSpanNamed("A").getSpanId());
        assertThat(SpanContext.readFrom(b.received.get(0)).orElseThrow().getSpanId())
                .isEqualTo(toolSpanNamed("B").getSpanId());
    }

    @Test
    @DisplayName("metadata naming no parent span produces no span at all (no orphans)")
    void unseededMetadataProducesNoSpans() {
        final DefaultToolRegistry registry = new DefaultToolRegistry();
        registry.register(new CapturingTool("Probe", false));
        llm.responses.add(LlmResponse.of("", List.of(ToolUse.of("p1", "Probe", Map.of()))));
        llm.responses.add(LlmResponse.text("done"));

        final SubagentExecutionResult result = tracedExecutor().execute(context(registry),
                request(LlmCallMetadata.builder().traceId("s-unseeded").build()));

        assertThat(result.isSuccess()).isTrue();
        assertThat(store.bySession("s-unseeded")).isEmpty();
        // Only the seed's own trace exists, and nothing was added to it.
        assertThat(store.bySession(SESSION)).isEmpty();
    }

    @Test
    @DisplayName("with the no-op tracer the metadata is never rotated: the LLM call and the tool see the caller's")
    void noopTracerLeavesMetadataUntouched() {
        final DefaultToolRegistry registry = new DefaultToolRegistry();
        final CapturingTool probe = new CapturingTool("Probe", false);
        registry.register(probe);
        llm.responses.add(LlmResponse.of("", List.of(ToolUse.of("p1", "Probe", Map.of()))));
        llm.responses.add(LlmResponse.text("done"));

        // Tracer left at the default no-op.
        new DefaultSubagentExecutor(llm, new DefaultToolExecutionManager(), new DefaultHookExecutionManager())
                .execute(context(registry), request(seededMetadata));

        // Both still point at the caller's span, and are the very same instance (no per-iteration rebuild).
        assertThat(probe.received.get(0)).isSameAs(llm.metadata.get(0));
        assertThat(SpanContext.readFrom(probe.received.get(0)).orElseThrow().getSpanId())
                .isEqualTo(seedSpan.context().getSpanId());
    }

    @Test
    @DisplayName("a throwing tracer never breaks the fork (fail-safe)")
    void throwingTracerIsFailSafe() {
        final DefaultToolRegistry registry = new DefaultToolRegistry();
        registry.register(new CapturingTool("Probe", false));
        llm.responses.add(LlmResponse.of("", List.of(ToolUse.of("p1", "Probe", Map.of()))));
        llm.responses.add(LlmResponse.text("done"));

        final SubagentExecutionResult result = new DefaultSubagentExecutor(llm, new DefaultToolExecutionManager(),
                new DefaultHookExecutionManager()).withTracer(new ThrowingTracer())
                .execute(context(registry), request(seededMetadata));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getFinalAnswer()).isEqualTo("done");
    }

    @Test
    @DisplayName("a span whose enrich throws keeps the caller's metadata and never breaks the fork")
    void enrichFailureKeepsParentMetadata() {
        final DefaultToolRegistry registry = new DefaultToolRegistry();
        final CapturingTool probe = new CapturingTool("Probe", false);
        registry.register(probe);
        llm.responses.add(LlmResponse.of("", List.of(ToolUse.of("p1", "Probe", Map.of()))));
        llm.responses.add(LlmResponse.text("done"));

        final SubagentExecutionResult result = new DefaultSubagentExecutor(llm, new DefaultToolExecutionManager(),
                new DefaultHookExecutionManager()).withTracer(new EnrichFailingTracer(tracer))
                .execute(context(registry), request(seededMetadata));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getFinalAnswer()).isEqualTo("done");
        // Rotation failed, so what runs downstream attaches one level up — to the caller's span.
        assertThat(SpanContext.readFrom(probe.received.get(0)).orElseThrow().getSpanId())
                .isEqualTo(seedSpan.context().getSpanId());
    }

    @Test
    @DisplayName("an iteration whose LLM call fails ends ERROR and is still closed")
    void failingIterationEndsError() {
        llm.failure = new LlmClientException("provider down");

        final SubagentExecutionResult result = tracedExecutor().execute(context(new DefaultToolRegistry()),
                request(seededMetadata));

        assertThat(result.isSuccess()).isFalse();
        final TraceSpan iteration = iterationNamed("iteration#1");
        assertThat(iteration.getStatus()).isEqualTo(SpanStatus.ERROR);
        assertThat(iteration.getEndTime()).isPresent();
    }

    @Test
    @DisplayName("an iteration cut short by a cancellation ends INTERRUPTED, not ERROR")
    void cancelledIterationEndsInterrupted() {
        llm.failure = new LlmCallCancelledException("aborted in flight");

        final SubagentExecutionResult result = tracedExecutor().execute(context(new DefaultToolRegistry()),
                request(seededMetadata));

        assertThat(result.getCompletionReason()).hasToString("INTERRUPTED");
        assertThat(iterationNamed("iteration#1").getStatus()).isEqualTo(SpanStatus.INTERRUPTED);
    }

    @Test
    @DisplayName("a cancel landing after an iteration produced its final answer does not relabel it INTERRUPTED")
    void lateCancelKeepsCompletedIterationOk() {
        final DefaultInterruptCoordinator parent = new DefaultInterruptCoordinator();
        // The cancel arrives while the final answer is in flight: the iteration still completes normally.
        llm.onCall = () -> parent.requestInterrupt(InterruptReason.USER_SIGINT);
        llm.responses.add(LlmResponse.text("done"));

        final SubagentExecutionResult result = tracedExecutor()
                .execute(context(new DefaultToolRegistry(), parent.getSignal()), request(seededMetadata));

        assertThat(result.getFinalAnswer()).isEqualTo("done");
        assertThat(iterationNamed("iteration#1").getStatus()).isEqualTo(SpanStatus.OK);
    }

    @Test
    @DisplayName("an iteration ended by the tail cancellation check is INTERRUPTED")
    void tailCancelEndsIterationInterrupted() {
        final DefaultInterruptCoordinator parent = new DefaultInterruptCoordinator();
        final DefaultToolRegistry registry = new DefaultToolRegistry();
        registry.register(new CapturingTool("Probe", false));
        llm.onCall = () -> parent.requestInterrupt(InterruptReason.USER_SIGINT);
        llm.responses.add(LlmResponse.of("", List.of(ToolUse.of("p1", "Probe", Map.of()))));

        final SubagentExecutionResult result = tracedExecutor().execute(context(registry, parent.getSignal()),
                request(seededMetadata));

        assertThat(result.getCompletionReason()).hasToString("INTERRUPTED");
        assertThat(iterationNamed("iteration#1").getStatus()).isEqualTo(SpanStatus.INTERRUPTED);
    }

    @Test
    @DisplayName("ST-5: the compaction summary call is an LLM span under the iteration, filed under the caller's group key")
    void compactionSummaryCallNestsUnderTheIteration() {
        llm.responses.add(LlmResponse.text("the summary"));
        llm.responses.add(LlmResponse.text("done"));
        final LlmClient traced = new TracingLlmClient(llm, tracer);
        final DefaultSubagentExecutor executor = new DefaultSubagentExecutor(
                LlmCallGateway.<TranscriptBuffer>withDefaultRetry(traced), new DefaultToolExecutionManager(),
                new DefaultHookExecutionManager(), compactingEngine(traced)).withTracer(tracer);

        final SubagentExecutionResult result = executor.execute(context(new DefaultToolRegistry()),
                request(seededMetadata));

        assertThat(result.isSuccess()).isTrue();
        final TraceSpan iteration = iterationNamed("iteration#1");
        System.out.println(
                "DBG meta=" + llm.metadata + " spans=" + store.bySession(SESSION) + " all=" + result.getFinalAnswer());
        final List<TraceSpan> llmSpans = spansOf(SpanType.LLM);
        assertThat(llmSpans).hasSize(2)
                .allSatisfy(s -> assertThat(s.getParentSpanId()).contains(iteration.getSpanId()));
        final TraceSpan summary = llmSpans.stream()
                .filter(s -> LlmCallMetadata.Feature.COMPACTION.equals(s.getAttributes().get("feature"))).findFirst()
                .orElseThrow();
        // Same group key as the iteration — the caller's session, not the fork's transcript label.
        assertThat(summary.getSessionId()).isEqualTo(iteration.getSessionId()).isEqualTo(SESSION);

        // The usage attribution stays the engine's: the carrier brought only the span position.
        final LlmCallMetadata summaryCall = llm.metadata.stream()
                .filter(m -> m.getFeature().filter(LlmCallMetadata.Feature.COMPACTION::equals).isPresent()).findFirst()
                .orElseThrow();
        assertThat(summaryCall.getComponent()).contains("compaction-engine");
        assertThat(summaryCall.getTraceId()).contains(SESSION);
    }

    @Test
    @DisplayName("ST-5: tracing off, the fork's context request carries no call metadata")
    void compactionRequestCarriesNoMetadataWhenTracingOff() {
        llm.responses.add(LlmResponse.text("done"));
        final List<ContextRequest> requests = new CopyOnWriteArrayList<>();
        final DefaultSubagentExecutor executor = new DefaultSubagentExecutor(
                LlmCallGateway.<TranscriptBuffer>withDefaultRetry(llm), new DefaultToolExecutionManager(),
                new DefaultHookExecutionManager(), capturingEngine(requests));

        executor.execute(context(new DefaultToolRegistry()),
                request(LlmCallMetadata.builder().traceId(SESSION).build()));

        assertThat(requests).isNotEmpty().allSatisfy(r -> assertThat(r.getCallMetadata()).isEmpty());
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /** The default engine with every estimate fixed in the auto-compact band, so iteration 1 compacts. */
    private static ContextEngine compactingEngine(LlmClient client) {
        final TokenEstimator fixed = new TokenEstimator() {
            @Override
            public int estimate(String systemPrompt, List<Message> messages) {
                return 7_500;
            }

            @Override
            public int estimateMessage(Message message) {
                return 0;
            }

            @Override
            public int estimateText(String text) {
                return 0;
            }
        };
        final CompactionEngine compactionEngine = DefaultCompactionEngine.withDefaults(client, fixed,
                new DefaultHookExecutionManager());
        final DefaultCompactionGuard guard = new DefaultCompactionGuard(compactionEngine,
                InMemoryModelContextWindowRegistry.builder()
                        .defaultLimits(ModelContextLimits.builder().contextWindow(10_000).reservedOutputTokens(1_000)
                                .autoCompactRatio(0.8).warningBuffer(1_000).blockingBuffer(500).build())
                        .build(),
                fixed);
        return DefaultContextEngine.builder().compactionGuard(guard).compactionEngine(compactionEngine)
                .tokenEstimator(fixed).build();
    }

    /** Records every request and answers with the transcript unchanged. */
    private static ContextEngine capturingEngine(List<ContextRequest> requests) {
        return new ContextEngine() {
            @Override
            public ContextDecision prepare(ContextRequest request) {
                requests.add(request);
                return ContextEngine.passthrough().prepare(request);
            }

            @Override
            public Optional<ContextView> recover(ContextRequest request, LlmPromptTooLongException error) {
                return Optional.empty();
            }

            @Override
            public CompactionResult compactNow(ContextRequest request, String instructions) {
                throw new UnsupportedOperationException("not under test");
            }
        };
    }

    private DefaultSubagentExecutor tracedExecutor() {
        return new DefaultSubagentExecutor(new TracingLlmClient(llm, tracer), new DefaultToolExecutionManager(),
                new DefaultHookExecutionManager()).withTracer(tracer);
    }

    private List<TraceSpan> spansOf(SpanType type) {
        return store.bySession(SESSION).stream().filter(s -> s.getType() == type).toList();
    }

    private TraceSpan iterationNamed(String name) {
        return spansOf(SpanType.ITERATION).stream().filter(s -> s.getName().equals(name)).findFirst().orElseThrow();
    }

    private TraceSpan toolSpanNamed(String name) {
        return spansOf(SpanType.TOOL).stream().filter(s -> s.getName().equals(name)).findFirst().orElseThrow();
    }

    private static SubagentExecutionContext context(DefaultToolRegistry registry) {
        return contextBuilder(registry).build();
    }

    private static SubagentExecutionContext context(DefaultToolRegistry registry, CancellationSignal parentSignal) {
        return contextBuilder(registry).parentCancellationSignal(parentSignal).build();
    }

    private static SubagentExecutionContext.Builder contextBuilder(DefaultToolRegistry registry) {
        final Subagent subagent = Subagent.of("explorer",
                SubagentMetadata.builder().description("d").maxIterations(5).build(), SubagentContent.of("explore"));
        return SubagentExecutionContext.builder().agentRuntimeId(AgentRuntimeId.of("agent:test-1")).subagent(subagent)
                .defaultModel(LlmModel.builder().name("gpt-4").build()).toolRegistry(registry)
                .hookRegistry(new DefaultHookRegistry());
    }

    private static SubagentExecutionRequest request(LlmCallMetadata metadata) {
        return SubagentExecutionRequest.builder().taskId("task-1").goal("explore").llmCallMetadata(metadata).build();
    }

    /** Records the LLM call metadata each call carried and the ToolContext metadata the tool received. */
    private static final class CapturingTool extends AbstractTool {
        private final boolean concurrent;
        private final List<LlmCallMetadata> received = new CopyOnWriteArrayList<>();

        CapturingTool(String name, boolean concurrent) {
            super(name, "capturing tool", Map.of("type", "object", "properties", Map.of(), "required", List.of()));
            this.concurrent = concurrent;
        }

        @Override
        public ConcurrencyBehavior getConcurrencyBehavior() {
            return concurrent ? ConcurrencyBehavior.CONCURRENT_SAFE : super.getConcurrencyBehavior();
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext context) {
            context.get(ToolContextKeys.LLM_CALL_METADATA_KEY).ifPresent(received::add);
            return ToolResult.success("ok");
        }
    }

    /** Returns scripted responses in order and records the metadata of every call; may fail every call. */
    private static final class ScriptedLlmClient implements LlmClient {
        private final List<LlmResponse> responses = Collections.synchronizedList(new ArrayList<>());
        private final List<LlmCallMetadata> metadata = new CopyOnWriteArrayList<>();
        private RuntimeException failure;
        /** Runs inside every call, before it answers. */
        private Runnable onCall = () -> {
        };

        @Override
        public LlmResponse sendMessage(SystemPromptParts systemPromptParts, List<Message> messages,
                List<ToolDefinition> tools, LlmModel modelConfig, LlmCallMetadata callMetadata,
                LlmCancellation cancellation) {
            metadata.add(callMetadata);
            onCall.run();
            if (failure != null) {
                throw failure;
            }
            return responses.isEmpty() ? LlmResponse.text("unexpected-extra-call") : responses.remove(0);
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            return sendMessage(SystemPromptParts.empty(), messages, tools, modelConfig, LlmCallMetadata.empty(),
                    LlmCancellation.none());
        }

        @Override
        public String getProviderName() {
            return "Scripted";
        }
    }

    /** Starts real spans whose {@code enrich} throws. */
    private static final class EnrichFailingTracer implements Tracer {
        private final Tracer delegate;

        EnrichFailingTracer(Tracer delegate) {
            this.delegate = delegate;
        }

        @Override
        public Span startRoot(String sessionId, SpanType type, String name, Map<String, Object> inputs) {
            return wrap(delegate.startRoot(sessionId, type, name, inputs));
        }

        @Override
        public Span startChild(SpanContext parent, SpanType type, String name, Map<String, Object> inputs) {
            return wrap(delegate.startChild(parent, type, name, inputs));
        }

        private static Span wrap(Span span) {
            return new Span() {
                @Override
                public SpanContext context() {
                    return span.context();
                }

                @Override
                public LlmCallMetadata enrich(LlmCallMetadata base) {
                    throw new IllegalStateException("enrich down");
                }

                @Override
                public void setOutputs(Object outputs) {
                    span.setOutputs(outputs);
                }

                @Override
                public void setTokenUsage(TokenUsage usage) {
                    span.setTokenUsage(usage);
                }

                @Override
                public void setModel(String model) {
                    span.setModel(model);
                }

                @Override
                public void setAttribute(String key, String value) {
                    span.setAttribute(key, value);
                }

                @Override
                public void error(Throwable t) {
                    span.error(t);
                }

                @Override
                public void error(String message) {
                    span.error(message);
                }

                @Override
                public void interrupted() {
                    span.interrupted();
                }

                @Override
                public void close() {
                    span.close();
                }
            };
        }
    }

    /** A tracer whose every method throws. */
    private static final class ThrowingTracer implements Tracer {
        @Override
        public Span startRoot(String sessionId, SpanType type, String name, Map<String, Object> inputs) {
            throw new IllegalStateException("tracer down");
        }

        @Override
        public Span startChild(SpanContext parent, SpanType type, String name, Map<String, Object> inputs) {
            throw new IllegalStateException("tracer down");
        }
    }
}
