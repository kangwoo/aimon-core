package at.aimon.core.agent.compact;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.Environment;
import at.aimon.core.agent.ExecutionId;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.transcript.TranscriptBuffer;
import at.aimon.core.hook.DefaultHookExecutionManager;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.HookEventType;
import at.aimon.core.hook.HookRegistry;
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
import at.aimon.core.llm.ModelContextWindowRegistry;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.token.HeuristicTokenEstimator;
import at.aimon.core.llm.token.TokenEstimator;

/**
 * Pins who a compaction says it is, for the two kinds of run that reach the engine.
 *
 * <p>
 * A subagent fork has no session, but {@link TranscriptBuffer} is typed on {@link SessionId}, so the fork labels its
 * buffer with its own {@link ExecutionId} wrapped in one. The engine used to read that label and export it as the
 * PreCompact session id, which made a fork indistinguishable from a user session to every hook — the same lie the
 * rewake replay path was fixed for. The fix is a request-level identity channel, so these tests assert on the
 * identity fields of the {@link PreCompactContext} a hook actually observes:
 *
 * <ul>
 * <li>fork — execution id present, session id empty;
 * <li>session turn — session id present and unchanged, execution id empty.
 * </ul>
 */
class DefaultCompactionEngineRunIdentityTest {

    private DefaultHookRegistry hookRegistry;
    private DefaultHookExecutionManager hookExecutionManager;
    private Environment environment;
    private DefaultCompactionEngine engine;
    private CapturingPreCompactHook capturedPreCompact;

    @BeforeEach
    void setUp() {
        hookRegistry = new DefaultHookRegistry();
        hookExecutionManager = new DefaultHookExecutionManager();
        environment = Environment.createDefault();
        capturedPreCompact = new CapturingPreCompactHook();
        hookRegistry.register(HookEventType.PRE_COMPACT, capturedPreCompact);
        engine = DefaultCompactionEngine.withDefaults(new StubSummaryClient(), new HeuristicTokenEstimator(),
                hookExecutionManager);
    }

    @Test
    void forkCompactionNamesTheExecutionAndClaimsNoSession() {
        ExecutionId executionId = ExecutionId.generate("subagent:researcher");
        // Exactly how DefaultSubagentExecutor labels a fork's buffer: the run id wrapped in a SessionId.
        TranscriptBuffer memory = memoryLabelled(SessionId.of(executionId.value()));

        CompactionResult result = engine.compact(baseRequest(memory).executionId(executionId).build());

        assertThat(result.isSuccess()).isTrue();
        PreCompactContext seen = capturedPreCompact.require();
        assertThat(seen.getExecutionId()).hasValue(executionId);
        // The honest answer for a run with no session — and what AIMON_SESSION_ID exports.
        assertThat(seen.getSessionIdValue()).isEmpty();
    }

    @Test
    void forkCompactionNeverLeaksTheTranscriptLabelAsASessionId() {
        ExecutionId executionId = ExecutionId.generate("subagent:researcher");
        TranscriptBuffer memory = memoryLabelled(SessionId.of(executionId.value()));

        engine.compact(baseRequest(memory).executionId(executionId).build());

        // Guards the specific regression: the label and the run id are the same string, so an implementation that
        // exported the label would still pass an "is not blank" assertion while telling the hook it had a session.
        assertThat(capturedPreCompact.require().getSessionIdValue()).isNotEqualTo(executionId.value());
    }

    @Test
    void sessionTurnCompactionKeepsExportingTheSessionId() {
        SessionId sessionId = SessionId.generate();
        TranscriptBuffer memory = memoryLabelled(sessionId);

        CompactionResult result = engine.compact(baseRequest(memory).build());

        assertThat(result.isSuccess()).isTrue();
        PreCompactContext seen = capturedPreCompact.require();
        assertThat(seen.getSessionIdValue()).isEqualTo(sessionId.toString());
        assertThat(seen.getExecutionId()).isEmpty();
    }

    @Test
    void guardForwardsTheRunIdentityItWasGiven() {
        RecordingEngine recordingEngine = new RecordingEngine();
        DefaultCompactionGuard guard = newGuard(recordingEngine);
        ExecutionId executionId = ExecutionId.generate("subagent:researcher");
        TranscriptBuffer memory = memoryLabelled(SessionId.of(executionId.value()));

        CompactionDecision decision = guard.maybeCompact(memory, model(), hookRegistry, environment, executionId);

        assertThat(decision.getAction()).isEqualTo(CompactionDecision.Action.COMPACT);
        assertThat(recordingEngine.require().getExecutionId()).hasValue(executionId);
    }

    @Test
    void guardLeavesTheRunIdentityAbsentOnTheSessionPath() {
        RecordingEngine recordingEngine = new RecordingEngine();
        DefaultCompactionGuard guard = newGuard(recordingEngine);

        CompactionDecision decision = guard.maybeCompact(memoryLabelled(SessionId.generate()), model(), hookRegistry,
                environment);

        assertThat(decision.getAction()).isEqualTo(CompactionDecision.Action.COMPACT);
        assertThat(recordingEngine.require().getExecutionId()).isEmpty();
    }

    @Test
    void guardForceCompactAlsoForwardsTheRunIdentity() {
        RecordingEngine recordingEngine = new RecordingEngine();
        DefaultCompactionGuard guard = newGuard(recordingEngine);
        ExecutionId executionId = ExecutionId.generate("subagent:researcher");
        TranscriptBuffer memory = memoryLabelled(SessionId.of(executionId.value()));

        CompactionDecision decision = guard.forceCompact(memory, model(), hookRegistry, environment, executionId);

        assertThat(decision.getAction()).isEqualTo(CompactionDecision.Action.COMPACT);
        assertThat(recordingEngine.require().getExecutionId()).hasValue(executionId);
    }

    /**
     * SPI compatibility: the {@link ExecutionId} overloads are {@code default} methods delegating to the four-argument
     * contract, so a downstream guard that implements only that contract keeps working when called through the new
     * arity. It loses the honest identity (the engine falls back to the transcript label, as every guard did before
     * the overload existed) but it neither fails to compile nor changes behaviour.
     */
    @Test
    void guardImplementingOnlyTheFourArgContractStillAnswersTheExecutionIdOverload() {
        LegacyFourArgGuard legacy = new LegacyFourArgGuard();
        TranscriptBuffer memory = memoryLabelled(SessionId.generate());

        CompactionDecision decision = legacy.maybeCompact(memory, model(), hookRegistry, environment,
                ExecutionId.generate("subagent:researcher"));

        assertThat(decision.getAction()).isEqualTo(CompactionDecision.Action.NONE);
        assertThat(legacy.calls).isEqualTo(1);
    }

    /**
     * The same for {@code forceCompact}, which delegates to the four-argument {@code forceCompact}, not maybeCompact.
     */
    @Test
    void guardImplementingOnlyTheFourArgContractStillAnswersTheForceCompactOverload() {
        LegacyFourArgGuard legacy = new LegacyFourArgGuard();
        TranscriptBuffer memory = memoryLabelled(SessionId.generate());

        CompactionDecision decision = legacy.forceCompact(memory, model(), hookRegistry, environment,
                ExecutionId.generate("subagent:researcher"));

        assertThat(decision.getAction()).isEqualTo(CompactionDecision.Action.NONE);
        assertThat(legacy.calls).isEqualTo(1);
    }

    private CompactionRequest.Builder baseRequest(TranscriptBuffer memory) {
        return CompactionRequest.builder().transcriptBuffer(memory).trigger(CompactionTrigger.AUTO).model(model())
                .hookRegistry(hookRegistry).environment(environment);
    }

    /**
     * Guard whose estimator always lands above the auto-compact threshold, so {@code maybeCompact} always reaches the
     * engine and the recorded request is the one under test.
     */
    private static DefaultCompactionGuard newGuard(CompactionEngine engine) {
        final ModelContextWindowRegistry registry = InMemoryModelContextWindowRegistry.builder()
                .defaultLimits(ModelContextLimits.builder().contextWindow(10_000).reservedOutputTokens(1_000)
                        .autoCompactBuffer(2_000).warningBuffer(1_000).blockingBuffer(500).build())
                .build();
        // 7_500 sits in the auto-compact band [7_000, 8_500) — compaction happens, blocking-limit forcing does not.
        return new DefaultCompactionGuard(engine, registry, new FixedTokenEstimator(7_500));
    }

    private static LlmModel model() {
        return LlmModel.builder().name("test-model").build();
    }

    private static TranscriptBuffer memoryLabelled(SessionId label) {
        TranscriptBuffer memory = new TranscriptBuffer(label);
        memory.addUserMessage("hello");
        memory.addAssistantMessage("hi");
        return memory;
    }

    /** Captures the single {@link PreCompactContext} the engine fires. */
    private static final class CapturingPreCompactHook implements PreCompactHook {
        private final AtomicReference<PreCompactContext> last = new AtomicReference<>();

        @Override
        public HookResult execute(PreCompactContext context) {
            last.set(context);
            return HookResult.success();
        }

        PreCompactContext require() {
            final PreCompactContext context = last.get();
            assertThat(context).as("PreCompactHook was never fired").isNotNull();
            return context;
        }
    }

    /** Captures the {@link CompactionRequest} the guard builds, and reports success so the guard is satisfied. */
    private static final class RecordingEngine implements CompactionEngine {
        private final AtomicReference<CompactionRequest> last = new AtomicReference<>();

        @Override
        public CompactionResult compact(CompactionRequest request) {
            last.set(request);
            final CompactionMetadata metadata = CompactionMetadata.builder().trigger(request.getTrigger())
                    .startedAt(Instant.now()).completedAt(Instant.now()).build();
            return CompactionResult.success("stub", metadata);
        }

        CompactionRequest require() {
            final CompactionRequest request = last.get();
            assertThat(request).as("CompactionEngine was never invoked").isNotNull();
            return request;
        }
    }

    /** Stands in for a downstream guard written before the {@link ExecutionId} overloads existed. */
    private static final class LegacyFourArgGuard implements CompactionGuard {
        private int calls;

        @Override
        public CompactionDecision maybeCompact(TranscriptBuffer memory, LlmModel model, HookRegistry hookRegistry,
                Environment environment) {
            calls++;
            return CompactionDecision.none("legacy guard");
        }
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

    /** Minimal LLM stub: the engine wiring is under test, not the provider. */
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
