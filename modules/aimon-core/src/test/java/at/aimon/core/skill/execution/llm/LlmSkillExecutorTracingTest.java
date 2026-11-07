package at.aimon.core.skill.execution.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.ExecutionId;
import at.aimon.core.agent.tool.DataReach;
import at.aimon.core.agent.tool.DefaultToolExecutionManager;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.agent.tool.DestructiveBehavior;
import at.aimon.core.agent.tool.ProgrammaticEngineChoice;
import at.aimon.core.agent.tool.ProgrammaticToolSettings;
import at.aimon.core.agent.tool.SideEffectLevel;
import at.aimon.core.agent.tool.Tool;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.usage.LlmUsageEvent;
import at.aimon.core.llm.usage.LlmUsageRecorder;
import at.aimon.core.llm.usage.MeteringLlmClient;
import at.aimon.core.programmatic.CodeExecutionEngine;
import at.aimon.core.programmatic.CodeExecutionLimits;
import at.aimon.core.programmatic.CodeExecutionOutcome;
import at.aimon.core.programmatic.CodeExecutionRequest;
import at.aimon.core.programmatic.CodeExecutionStatus;
import at.aimon.core.programmatic.EngineAvailability;
import at.aimon.core.programmatic.OfferFacts;
import at.aimon.core.programmatic.ToolCallBridge;
import at.aimon.core.programmatic.ToolGrade;
import at.aimon.core.skill.Skill;
import at.aimon.core.skill.SkillContent;
import at.aimon.core.skill.SkillMetadata;
import at.aimon.core.skill.execution.SkillExecutionContext;
import at.aimon.core.skill.execution.SkillExecutionRequest;
import at.aimon.core.skill.execution.SkillExecutionResult;
import at.aimon.core.skill.render.DefaultSkillContentRenderer;
import at.aimon.core.toolinvocation.ToolCallDispatchers;
import at.aimon.core.tools.ToolContextKeys;
import at.aimon.core.tools.ToolSpans;
import at.aimon.core.tools.code.ExecuteCodeTool;
import at.aimon.core.tracing.SpanContext;
import at.aimon.core.tracing.SpanExporter;
import at.aimon.core.tracing.SpanStatus;
import at.aimon.core.tracing.SpanType;
import at.aimon.core.tracing.Spans;
import at.aimon.core.tracing.TraceSpan;
import at.aimon.core.tracing.Tracer;
import at.aimon.core.tracing.impl.DefaultTracer;
import at.aimon.core.tracing.impl.InMemoryTraceSpanStore;

/**
 * TC-23: a slash skill's loop records {@code ITERATION} spans under the COMMAND span its caller's metadata names, one
 * {@code TOOL} span per call under its iteration, and nests an {@code ExecuteCode} program's calls under that call's
 * span; its LLM calls carry the iteration's span position and nothing else.
 */
@DisplayName("LlmSkillExecutor — ITERATION and TOOL spans (TC-23)")
class LlmSkillExecutorTracingTest {

    private static final String SESSION = "sess-skill-trace";

    private final InMemoryTraceSpanStore store = new InMemoryTraceSpanStore();
    private final Tracer tracer = new DefaultTracer(store, SpanExporter.noop());

    @Test
    @DisplayName("COMMAND → ITERATION#0..#2, a TOOL span under each tool iteration, attributes and the LLM carrier")
    void iterationAndToolSpans() {
        final ScriptedLlm llm = new ScriptedLlm(LlmResponse.of("", List.of(ToolUse.of("t-1", "Read", Map.of()))),
                LlmResponse.of("", List.of(ToolUse.of("t-2", "Read", Map.of()))), LlmResponse.text("done"));
        final Tracer.Span command = Spans.startRootSafely(tracer, SESSION, SpanType.COMMAND, "/probe", null);

        final SkillExecutionResult result = run(llm, skill("Read", 5, ProgrammaticToolSettings.none()),
                tracedContext(command, registry()));
        command.close();

        assertThat(result.isSuccess()).isTrue();
        final List<TraceSpan> spans = store.bySession(SESSION);
        final List<TraceSpan> iterations = ofType(spans, SpanType.ITERATION);
        assertThat(iterations).extracting(TraceSpan::getName).containsExactly("iteration#0", "iteration#1",
                "iteration#2");
        assertThat(iterations).allSatisfy(span -> {
            assertThat(span.getParentSpanId()).contains(command.context().getSpanId());
            assertThat(span.getAttributes()).containsEntry("invokerType", "MAIN_AGENT").containsEntry("skill", "probe");
            assertThat(span.getStatus()).isEqualTo(SpanStatus.OK);
        });
        final List<TraceSpan> tools = ofType(spans, SpanType.TOOL);
        assertThat(tools).hasSize(2);
        assertThat(tools.get(0).getParentSpanId()).contains(iterations.get(0).getSpanId());
        assertThat(tools.get(1).getParentSpanId()).contains(iterations.get(1).getSpanId());
        assertThat(tools).allSatisfy(
                span -> assertThat(span.getAttributes()).containsEntry(ToolSpans.PATH, ToolSpans.PATH_DIRECT)
                        .containsEntry("skill", "probe").containsEntry("invokerType", "MAIN_AGENT"));

        // Each LLM call carries the span carrier of its iteration and no attribution.
        assertThat(llm.metadata).hasSize(3);
        for (int i = 0; i < 3; i++) {
            final LlmCallMetadata metadata = llm.metadata.get(i);
            assertThat(metadata.getTags()).containsOnlyKeys(SpanContext.TAG_TRACE_ID, SpanContext.TAG_PARENT_SPAN_ID)
                    .containsEntry(SpanContext.TAG_PARENT_SPAN_ID, iterations.get(i).getSpanId());
            assertThat(metadata.getComponent()).isEmpty();
            assertThat(metadata.getFeature()).isEmpty();
            assertThat(metadata.getPrincipal()).isEmpty();
        }
    }

    @Test
    @DisplayName("an ExecuteCode call's nested calls nest under its TOOL span")
    void executeCodeNestedSpansUnderItsToolSpan() {
        final ScriptedLlm llm = new ScriptedLlm(
                LlmResponse.of("", List.of(ToolUse.of("t-1", ExecuteCodeTool.TOOL_NAME, Map.of("code", "go")))),
                LlmResponse.text("done"));
        final ToolRegistry registry = registry();
        registry.register(new ExecuteCodeTool(List.of(new CallingEngine(EngineAvailability.available())),
                CodeExecutionLimits.defaults()));
        final Tracer.Span command = Spans.startRootSafely(tracer, SESSION, SpanType.COMMAND, "/probe", null);

        final SkillExecutionResult result = run(llm, skill("Read ExecuteCode", 5, readJs()),
                ptcContext(tracedContext(command, registry)));
        command.close();

        assertThat(result.isSuccess()).as(result.getResponse()).isTrue();
        final List<TraceSpan> tools = ofType(store.bySession(SESSION), SpanType.TOOL);
        final TraceSpan executeCode = tools.stream().filter(span -> span.getName().equals(ExecuteCodeTool.TOOL_NAME))
                .findFirst().orElseThrow();
        final TraceSpan nestedRead = tools.stream().filter(span -> span.getName().equals("Read")).findFirst()
                .orElseThrow();
        assertThat(nestedRead.getParentSpanId()).contains(executeCode.getSpanId());
        assertThat(executeCode.getAttributes()).containsEntry("skill", "probe");
    }

    @Test
    @DisplayName("ptc.unavailable_reason is set on each iteration when the skill opens tools to code but no engine runs")
    void unavailableReasonOnIterations() {
        final ScriptedLlm llm = new ScriptedLlm(LlmResponse.of("", List.of(ToolUse.of("t-1", "Read", Map.of()))),
                LlmResponse.text("done"));
        final ToolRegistry registry = registry();
        registry.register(
                new ExecuteCodeTool(List.of(new CallingEngine(EngineAvailability.unavailable("no engine here"))),
                        CodeExecutionLimits.defaults()));
        final Tracer.Span command = Spans.startRootSafely(tracer, SESSION, SpanType.COMMAND, "/probe", null);

        run(llm, skill("Read ExecuteCode", 5, readJs()), ptcContext(tracedContext(command, registry)));
        command.close();

        final List<TraceSpan> iterations = ofType(store.bySession(SESSION), SpanType.ITERATION);
        assertThat(iterations).hasSize(2)
                .allSatisfy(span -> assertThat(span.getAttributes()).containsKey("ptc.unavailable_reason"));
    }

    @Test
    @DisplayName("max-iterations ends the last iteration span in error")
    void maxIterationsEndsTheLastIterationInError() {
        final ScriptedLlm llm = new ScriptedLlm(LlmResponse.of("", List.of(ToolUse.of("t-1", "Read", Map.of()))),
                LlmResponse.of("", List.of(ToolUse.of("t-2", "Read", Map.of()))));
        final Tracer.Span command = Spans.startRootSafely(tracer, SESSION, SpanType.COMMAND, "/probe", null);

        final SkillExecutionResult result = run(llm, skill("Read", 1, ProgrammaticToolSettings.none()),
                tracedContext(command, registry()));
        command.close();

        assertThat(result.isFailure()).isTrue();
        final List<TraceSpan> iterations = ofType(store.bySession(SESSION), SpanType.ITERATION);
        assertThat(iterations).hasSize(2);
        assertThat(iterations.get(0).getStatus()).isEqualTo(SpanStatus.OK);
        assertThat(iterations.get(1).getStatus()).isEqualTo(SpanStatus.ERROR);
    }

    @Test
    @DisplayName("tracing off opens nothing and the LLM call carries the empty metadata instance")
    void tracingOffOpensNothing() {
        final ScriptedLlm llm = new ScriptedLlm(LlmResponse.of("", List.of(ToolUse.of("t-1", "Read", Map.of()))),
                LlmResponse.text("done"));
        final ToolRegistry registry = registry();

        final SkillExecutionResult result = run(llm, skill("Read", 5, ProgrammaticToolSettings.none()),
                ToolContext.builder()
                        .put(ToolContextKeys.TOOL_CALL_DISPATCHER,
                                ToolCallDispatchers.ungated(new DefaultToolExecutionManager(), registry))
                        .build(),
                registry);

        assertThat(result.isSuccess()).isTrue();
        assertThat(store.bySession(SESSION)).isEmpty();
        assertThat(llm.metadata).allSatisfy(metadata -> assertThat(metadata).isSameAs(LlmCallMetadata.empty()));
    }

    @Test
    @DisplayName("metering records the same attribution as the four-argument call did, apart from the span tags")
    void meteringAttributionUnchangedApartFromSpanTags() {
        final List<LlmUsageEvent> events = new CopyOnWriteArrayList<>();
        final LlmUsageRecorder recorder = new LlmUsageRecorder() {
            @Override
            public void record(String provider, String model, at.aimon.core.llm.TokenUsage usage,
                    LlmCallMetadata metadata) {
                // the event form below is the one read
            }

            @Override
            public void record(LlmUsageEvent event) {
                events.add(event);
            }
        };
        final ScriptedLlm llm = new ScriptedLlm(LlmResponse.text("done"));
        final Tracer.Span command = Spans.startRootSafely(tracer, SESSION, SpanType.COMMAND, "/probe", null);

        final ToolRegistry registry = registry();
        final SkillExecutionResult result = runWith(new MeteringLlmClient(llm, recorder),
                skill("Read", 5, ProgrammaticToolSettings.none()), tracedContext(command, registry), registry);
        command.close();

        assertThat(result.isSuccess()).as(result.getResponse()).isTrue();
        assertThat(events).hasSize(1);
        final LlmCallMetadata recorded = events.get(0).getMetadata();
        assertThat(recorded.getComponent()).isEmpty();
        assertThat(recorded.getFeature()).isEmpty();
        assertThat(recorded.getPrincipal()).isEmpty();
        assertThat(recorded.getParentComponent()).isEmpty();
        assertThat(recorded.getTags()).containsOnlyKeys(SpanContext.TAG_TRACE_ID, SpanContext.TAG_PARENT_SPAN_ID);
    }

    // ---------------------------------------------------------------- fixture

    private SkillExecutionResult run(ScriptedLlm llm, Skill skill, ToolContext context) {
        return run(llm, skill, context, registryOf(context));
    }

    private SkillExecutionResult run(ScriptedLlm llm, Skill skill, ToolContext context, ToolRegistry registry) {
        return runWith(llm, skill, context, registry);
    }

    private static SkillExecutionResult runWith(LlmClient llm, Skill skill, ToolContext context,
            ToolRegistry registry) {
        final LlmSkillExecutor executor = new LlmSkillExecutor(llm, new DefaultSkillContentRenderer(),
                new DefaultToolExecutionManager());
        final SkillExecutionContext skillContext = SkillExecutionContext.builder().skill(skill)
                .defaultModel(LlmModel.builder().build()).toolRegistry(registry)
                .executionId(ExecutionId.generate("skill:test")).toolContext(context).build();
        return executor.execute(skillContext,
                SkillExecutionRequest.builder().rawArguments("").arguments(List.of()).build());
    }

    /** The registry the context's dispatcher was built over (kept beside it for {@link #run}). */
    private final Map<ToolContext, ToolRegistry> registries = new java.util.IdentityHashMap<>();

    private ToolRegistry registryOf(ToolContext context) {
        return registries.get(context);
    }

    private ToolContext tracedContext(Tracer.Span command, ToolRegistry registry) {
        final ToolContext context = ToolContext.builder()
                .put(ToolContextKeys.TOOL_CALL_DISPATCHER,
                        ToolCallDispatchers.ungated(new DefaultToolExecutionManager(), registry, readJs()))
                .put(ToolContextKeys.TRACER, tracer)
                .put(ToolContextKeys.LLM_CALL_METADATA_KEY, Spans.enrichSafely(command,
                        LlmCallMetadata.builder().traceId(SESSION).component("command").feature("slash").build()))
                .build();
        registries.put(context, registry);
        return context;
    }

    private ToolContext ptcContext(ToolContext traced) {
        final ToolContext context = ToolContext.builder().putAll(traced.getContext())
                .put(ToolContextKeys.CALLER_PROGRAMMATIC, readJs()).build();
        registries.put(context, registries.get(traced));
        return context;
    }

    private static ProgrammaticToolSettings readJs() {
        return ProgrammaticToolSettings.builder().tools(List.of("Read")).engine(ProgrammaticEngineChoice.JAVASCRIPT)
                .build();
    }

    private static ToolRegistry registry() {
        final DefaultToolRegistry registry = new DefaultToolRegistry();
        registry.register(new ReadTool());
        return registry;
    }

    private static Skill skill(String allowedTools, int maxIterations, ProgrammaticToolSettings programmatic) {
        return Skill.builder().name("probe")
                .metadata(SkillMetadata.builder().name("probe").description("probe").allowedTools(allowedTools)
                        .maxIterations(maxIterations).programmatic(programmatic).build())
                .content(SkillContent.of("Probe.")).build();
    }

    private static List<TraceSpan> ofType(List<TraceSpan> spans, SpanType type) {
        return spans.stream().filter(span -> span.getType() == type)
                .sorted(java.util.Comparator.comparing(TraceSpan::getStartTime)).toList();
    }

    /** Replays responses in order and records the metadata each call carried. */
    private static final class ScriptedLlm implements LlmClient {
        private final List<LlmResponse> responses;
        private final List<LlmCallMetadata> metadata = new ArrayList<>();

        ScriptedLlm(LlmResponse... responses) {
            this.responses = new ArrayList<>(List.of(responses));
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            throw new AssertionError("the skill loop must call the metadata overload");
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig, LlmCallMetadata callMetadata) {
            metadata.add(callMetadata);
            return responses.isEmpty() ? LlmResponse.text("extra") : responses.remove(0);
        }

        @Override
        public String getProviderName() {
            return "Scripted";
        }
    }

    /** A read-only, code-callable tool. */
    private static final class ReadTool implements Tool {
        @Override
        public ToolDefinition getDefinition() {
            return ToolDefinition.of("Read", "Read a file", Map.of("type", "object", "properties", Map.of()));
        }

        @Override
        public SideEffectLevel getSideEffectLevel() {
            return SideEffectLevel.READ_ONLY;
        }

        @Override
        public DataReach dataReach() {
            return DataReach.ENVIRONMENT;
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext context) {
            return ToolResult.success("read ok");
        }
    }

    /** A JavaScript engine whose program calls Read once through the bridge. */
    private static final class CallingEngine implements CodeExecutionEngine {
        private final EngineAvailability availability;

        CallingEngine(EngineAvailability availability) {
            this.availability = availability;
        }

        @Override
        public String language() {
            return JAVASCRIPT;
        }

        @Override
        public EngineAvailability staticAvailability(OfferFacts facts) {
            return availability;
        }

        @Override
        public ToolGrade intrinsicGrade() {
            return ToolGrade.builder().sideEffectLevel(SideEffectLevel.READ_ONLY)
                    .destructiveBehavior(DestructiveBehavior.NON_DESTRUCTIVE).dataReach(DataReach.ENVIRONMENT)
                    .approvalDelegated(true).build();
        }

        @Override
        public CodeExecutionOutcome execute(CodeExecutionRequest request, ToolCallBridge bridge, ToolContext context) {
            bridge.callAsync("Read", Map.of()).join();
            return CodeExecutionOutcome.builder().status(CodeExecutionStatus.COMPLETED).stdout("ran").build();
        }
    }
}
