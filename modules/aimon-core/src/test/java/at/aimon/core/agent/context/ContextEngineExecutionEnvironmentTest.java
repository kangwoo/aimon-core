package at.aimon.core.agent.context;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.ExecutionId;
import at.aimon.core.agent.compact.CompactionDecision;
import at.aimon.core.agent.compact.CompactionEngine;
import at.aimon.core.agent.compact.CompactionGuard;
import at.aimon.core.agent.compact.CompactionGuardRequest;
import at.aimon.core.agent.compact.CompactionRequest;
import at.aimon.core.agent.compact.CompactionResult;
import at.aimon.core.agent.compact.CompactionTrigger;
import at.aimon.core.agent.compact.DefaultCompactionEngine;
import at.aimon.core.agent.compact.DefaultCompactionGuard;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.transcript.SessionLogFormat;
import at.aimon.core.agent.session.transcript.TranscriptBuffer;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.environment.TestExecutionEnvironments;
import at.aimon.core.hook.DefaultHookExecutionManager;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.HookEventType;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.hook.event.PostCompactContext;
import at.aimon.core.hook.event.PostCompactHook;
import at.aimon.core.hook.event.PreCompactContext;
import at.aimon.core.hook.event.PreCompactHook;
import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.llm.InMemoryModelContextWindowRegistry;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ModelContextLimits;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.token.TokenEstimator;

/**
 * EE-9: the execution environment a caller puts on a {@link ContextRequest} reaches the PreCompact / PostCompact hooks
 * a compaction fires, on every route through {@link DefaultContextEngine} and the real {@link DefaultCompactionEngine}:
 * AUTO in place (through the guard), MANUAL in place, and both in view mode (summarize + summaryInstalled).
 */
@SuppressWarnings("deprecation") // the guard and the in-place engine entry are part of what is under test
@DisplayName("Compaction hooks carry the ContextRequest's execution environment")
class ContextEngineExecutionEnvironmentTest {

    private static final LlmModel MODEL = LlmModel.builder().name("test-model").build();

    private final ExecutionEnvironment executionEnvironment = TestExecutionEnvironments.builder()
            .workingDirectory("/workspace").build();
    private DefaultHookRegistry hookRegistry;
    private final AtomicReference<PreCompactContext> pre = new AtomicReference<>();
    private final AtomicReference<PostCompactContext> post = new AtomicReference<>();
    private CompactionEngine compactionEngine;

    @BeforeEach
    void setUp() {
        hookRegistry = new DefaultHookRegistry();
        hookRegistry.register(HookEventType.PRE_COMPACT, (PreCompactHook) context -> {
            pre.set(context);
            return HookResult.success();
        });
        hookRegistry.register(HookEventType.POST_COMPACT, (PostCompactHook) context -> {
            post.set(context);
            return HookResult.success();
        });
        // 7_500 sits in the auto-compact band [7_000, 8_500): prepare() always compacts, never by the blocking limit.
        compactionEngine = DefaultCompactionEngine.withDefaults(new StubSummaryClient(), new FixedTokenEstimator(7_500),
                new DefaultHookExecutionManager());
    }

    private DefaultContextEngine engine(SessionLogFormat writeFormat) {
        final DefaultCompactionGuard guard = new DefaultCompactionGuard(compactionEngine,
                InMemoryModelContextWindowRegistry.builder()
                        .defaultLimits(ModelContextLimits.builder().contextWindow(10_000).reservedOutputTokens(1_000)
                                .autoCompactBuffer(2_000).warningBuffer(1_000).blockingBuffer(500).build())
                        .build(),
                new FixedTokenEstimator(7_500));
        return DefaultContextEngine.builder().compactionGuard(guard).compactionEngine(compactionEngine)
                .tokenEstimator(new FixedTokenEstimator(7_500)).writeFormat(writeFormat).build();
    }

    private ContextRequest.Builder request(TranscriptBuffer buffer) {
        return ContextRequest.builder().transcriptBuffer(buffer).systemPrompt("system prompt").model(MODEL)
                .hookRegistry(hookRegistry);
    }

    private static TranscriptBuffer conversation(SessionLogFormat format) {
        final TranscriptBuffer buffer = new TranscriptBuffer(SessionId.generate(), "system prompt");
        if (format == SessionLogFormat.V2) {
            buffer.requireFormat(SessionLogFormat.V2);
        }
        buffer.addUserMessage("Remember this codeword for later: PELICAN.");
        buffer.addAssistantMessage("ok");
        buffer.addUserMessage("Note 1: the meeting moved to Tuesday.");
        buffer.addAssistantMessage("noted");
        return buffer;
    }

    private void assertBothHooksSaw(ExecutionEnvironment expected) {
        assertThat(pre.get()).as("PreCompact fired").isNotNull();
        assertThat(post.get()).as("PostCompact fired").isNotNull();
        assertThat(pre.get().getExecutionEnvironment().orElseThrow()).isSameAs(expected);
        assertThat(post.get().getExecutionEnvironment().orElseThrow()).isSameAs(expected);
        assertThat(pre.get().getEnvironmentDescriptor()).contains(expected.descriptor());
    }

    @Test
    @DisplayName("AUTO, in place: prepare() -> guard -> engine.compact()")
    void autoInPlace() {
        final ContextDecision decision = engine(SessionLogFormat.V1)
                .prepare(request(conversation(SessionLogFormat.V1)).executionEnvironment(executionEnvironment).build());

        assertThat(decision.getAction()).isEqualTo(CompactionDecision.Action.COMPACT);
        assertBothHooksSaw(executionEnvironment);
    }

    @Test
    @DisplayName("AUTO, in place, for a session-less execution: the execution id still travels with the environment")
    void autoInPlaceForAFork() {
        final ExecutionId executionId = ExecutionId.generate("subagent:researcher");
        final TranscriptBuffer buffer = new TranscriptBuffer(SessionId.of(executionId.value()), "system prompt");
        buffer.addUserMessage("hello");
        buffer.addAssistantMessage("hi");

        engine(SessionLogFormat.V1).prepare(request(buffer).executionEnvironment(executionEnvironment)
                .caller(ContextCaller.builder().executionId(executionId).build()).build());

        assertBothHooksSaw(executionEnvironment);
        assertThat(pre.get().getExecutionId()).hasValue(executionId);
    }

    @Test
    @DisplayName("MANUAL, in place: compactNow() -> engine.compact()")
    void manualInPlace() {
        final CompactionResult result = engine(SessionLogFormat.V1).compactNow(
                request(conversation(SessionLogFormat.V1)).executionEnvironment(executionEnvironment).build(), null);

        assertThat(result.isSuccess()).isTrue();
        assertBothHooksSaw(executionEnvironment);
    }

    @Test
    @DisplayName("MANUAL, view mode: compactNow() -> engine.summarize() + summaryInstalled()")
    void manualViewMode() {
        final TranscriptBuffer buffer = conversation(SessionLogFormat.V2);

        final CompactionResult result = engine(SessionLogFormat.V2)
                .compactNow(request(buffer).executionEnvironment(executionEnvironment).build(), null);

        assertThat(result.isSuccess()).isTrue();
        assertThat(buffer.getViewState().getSummarySpan()).as("the summary went to the view").isPresent();
        assertBothHooksSaw(executionEnvironment);
    }

    @Test
    @DisplayName("AUTO, view mode: prepare() -> guard rules -> engine.summarize() + summaryInstalled()")
    void autoViewMode() {
        final ContextDecision decision = engine(SessionLogFormat.V2)
                .prepare(request(conversation(SessionLogFormat.V2)).executionEnvironment(executionEnvironment).build());

        assertThat(decision.getAction()).isEqualTo(CompactionDecision.Action.COMPACT);
        assertBothHooksSaw(executionEnvironment);
    }

    @Test
    @DisplayName("a request without an execution environment leaves the hook contexts empty")
    void noEnvironmentOnTheRequest() {
        engine(SessionLogFormat.V1).prepare(request(conversation(SessionLogFormat.V1)).build());

        assertThat(pre.get().getExecutionEnvironment()).isEmpty();
        assertThat(post.get().getExecutionEnvironment()).isEmpty();
    }

    @Test
    @DisplayName("a guard written against the positional methods still runs, and drops the environment")
    void aPositionalOnlyGuardDropsTheEnvironment() {
        // The documented cost of not overriding maybeCompact(CompactionGuardRequest): the hooks fire without an
        // environment. The guard itself keeps working through the default delegation.
        final CompactionGuard positionalOnly = new CompactionGuard() {
            @Override
            public CompactionDecision maybeCompact(TranscriptBuffer memory, LlmModel model, HookRegistry registry) {
                return CompactionDecision.compact(
                        compactionEngine.compact(CompactionRequest.builder().transcriptBuffer(memory)
                                .trigger(CompactionTrigger.AUTO).model(model).hookRegistry(registry).build()),
                        "positional guard", 0, 0);
            }
        };
        final DefaultContextEngine engine = DefaultContextEngine.builder().compactionGuard(positionalOnly).build();

        final ContextDecision decision = engine
                .prepare(request(conversation(SessionLogFormat.V1)).executionEnvironment(executionEnvironment).build());

        assertThat(decision.getAction()).isEqualTo(CompactionDecision.Action.COMPACT);
        assertThat(pre.get().getExecutionEnvironment()).isEmpty();
    }

    @Test
    @DisplayName("DefaultCompactionGuard: only the request-object entry point carries the environment")
    void defaultGuardCarriesTheEnvironmentOnlyThroughTheRequestObject() {
        final DefaultCompactionGuard guard = (DefaultCompactionGuard) engine(SessionLogFormat.V1).getCompactionGuard();

        guard.maybeCompact(CompactionGuardRequest.builder().transcriptBuffer(conversation(SessionLogFormat.V1))
                .model(MODEL).hookRegistry(hookRegistry).executionEnvironment(executionEnvironment).build());
        assertThat(pre.get().getExecutionEnvironment().orElseThrow()).isSameAs(executionEnvironment);

        pre.set(null);
        guard.maybeCompact(conversation(SessionLogFormat.V1), MODEL, hookRegistry);
        assertThat(pre.get().getExecutionEnvironment()).isEmpty();
    }

    /** Returns a fixed estimate regardless of content, so a threshold band can be targeted precisely. */
    private static final class FixedTokenEstimator implements TokenEstimator {
        private final int fixedEstimate;

        FixedTokenEstimator(int fixedEstimate) {
            this.fixedEstimate = fixedEstimate;
        }

        @Override
        public int estimate(String systemPrompt, List<Message> messages) {
            return fixedEstimate;
        }

        @Override
        public int estimateMessage(Message message) {
            return 0;
        }

        @Override
        public int estimateText(String text) {
            return 0;
        }
    }

    /** Minimal LLM stub: the wiring is under test, not the provider. */
    private static final class StubSummaryClient implements LlmClient {

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            return LlmResponse.text("compacted summary");
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig, LlmCallMetadata metadata) {
            return sendMessage(systemPrompt, messages, tools, modelConfig);
        }

        @Override
        public String getProviderName() {
            return "stub";
        }
    }
}
