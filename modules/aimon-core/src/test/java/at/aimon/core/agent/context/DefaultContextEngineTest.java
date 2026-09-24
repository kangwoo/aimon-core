package at.aimon.core.agent.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.Environment;
import at.aimon.core.agent.ExecutionId;
import at.aimon.core.agent.compact.CompactionDecision;
import at.aimon.core.agent.compact.CompactionEngine;
import at.aimon.core.agent.compact.CompactionGuard;
import at.aimon.core.agent.compact.CompactionMetadata;
import at.aimon.core.agent.compact.CompactionRequest;
import at.aimon.core.agent.compact.CompactionResult;
import at.aimon.core.agent.compact.CompactionTrigger;
import at.aimon.core.agent.compact.PromptSizeRecoveryDecision;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.transcript.TranscriptBuffer;
import at.aimon.core.base.Principal;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.exception.LlmPromptTooLongException;
import at.aimon.core.llm.token.TokenEstimator;

/**
 * Pins {@link DefaultContextEngine} as a pure router: each entry reaches the collaborator that decided it before the
 * engine existed, with the same arguments, and the view it hands back is the buffer as that collaborator left it.
 */
class DefaultContextEngineTest {

    private static final LlmModel MODEL = LlmModel.builder().name("test-model").build();

    private TranscriptBuffer buffer;
    private HookRegistry hookRegistry;
    private Environment environment;

    @BeforeEach
    void setUp() {
        buffer = new TranscriptBuffer(SessionId.of("s-1"), "system prompt");
        buffer.addUserMessage("first");
        buffer.addAssistantMessage("one");
        buffer.addUserMessage("second");
        hookRegistry = new DefaultHookRegistry();
        environment = Environment.createDefault();
    }

    private ContextRequest.Builder request() {
        return ContextRequest.builder().transcriptBuffer(buffer).model(MODEL).hookRegistry(hookRegistry)
                .environment(environment);
    }

    @Nested
    class Prepare {

        @Test
        void routesAnOrdinaryCallToMaybeCompact() {
            final RecordingGuard guard = new RecordingGuard(CompactionDecision.none());
            final DefaultContextEngine engine = DefaultContextEngine.builder().compactionGuard(guard).build();

            final ContextDecision decision = engine.prepare(request().build());

            assertThat(guard.calls).containsExactly("maybeCompact");
            assertThat(guard.lastExecutionId.get()).isNull();
            assertThat(decision.getAction()).isEqualTo(CompactionDecision.Action.NONE);
            assertThat(decision.getView().getMessages()).isEqualTo(buffer.getMessages());
        }

        @Test
        void routesABudgetForcedCallToForceCompact() {
            final RecordingGuard guard = new RecordingGuard(CompactionDecision.none());
            final DefaultContextEngine engine = DefaultContextEngine.builder().compactionGuard(guard).build();

            engine.prepare(request().budgetForced(true).build());

            assertThat(guard.calls).containsExactly("forceCompact");
        }

        @Test
        void handsASessionLessCallersExecutionIdToTheGuard() {
            final RecordingGuard guard = new RecordingGuard(CompactionDecision.none());
            final DefaultContextEngine engine = DefaultContextEngine.builder().compactionGuard(guard).build();
            final ExecutionId executionId = ExecutionId.generate("subagent:researcher");
            final ContextCaller fork = ContextCaller.builder().executionId(executionId).build();

            engine.prepare(request().caller(fork).build());
            engine.prepare(request().caller(fork).budgetForced(true).build());

            assertThat(guard.calls).containsExactly("maybeCompact+id", "forceCompact+id");
            assertThat(guard.lastExecutionId.get()).isEqualTo(executionId);
        }

        @Test
        void theViewIsTheBufferAsTheGuardLeftIt() {
            final List<Message> compacted = List.of(Message.user("[summary]"));
            final CompactionResult success = CompactionResult.success("summary", metadata(CompactionTrigger.AUTO));
            final CompactionGuard rewriting = new RecordingGuard(
                    CompactionDecision.compact(success, "auto", 900, 1000)) {
                @Override
                protected void onCall(TranscriptBuffer memory) {
                    memory.replaceWith(compacted);
                }
            };
            final DefaultContextEngine engine = DefaultContextEngine.builder().compactionGuard(rewriting).build();

            final ContextDecision decision = engine.prepare(request().build());

            assertThat(decision.getAction()).isEqualTo(CompactionDecision.Action.COMPACT);
            assertThat(decision.getView().getMessages()).isEqualTo(compacted);
            assertThat(decision.getCompactionMetadata()).hasValue(success.getMetadata());
            assertThat(decision.getEstimatedTokens()).isEqualTo(900);
            assertThat(decision.getBlockingLimit()).isEqualTo(1000);
        }

        @Test
        void aBlockCarriesTheNumbersTheCallerBuildsItsExceptionFrom() {
            final DefaultContextEngine engine = DefaultContextEngine.builder()
                    .compactionGuard(new RecordingGuard(CompactionDecision.block("too big", 1200, 1000))).build();

            final ContextDecision decision = engine.prepare(request().build());

            assertThat(decision.getAction()).isEqualTo(CompactionDecision.Action.BLOCK);
            assertThat(decision.getReason()).isEqualTo("too big");
            assertThat(decision.getEstimatedTokens()).isEqualTo(1200);
            assertThat(decision.getBlockingLimit()).isEqualTo(1000);
            assertThat(decision.getCompactionMetadata()).isEmpty();
        }

        @Test
        void estimatesTheViewWithTheRequestsSystemPromptWhenAnEstimatorIsWired() {
            final AtomicReference<String> seenPrompt = new AtomicReference<>();
            final DefaultContextEngine engine = DefaultContextEngine.builder()
                    .tokenEstimator(new FixedTokenEstimator(321, seenPrompt)).build();

            final ContextDecision decision = engine.prepare(request().build());

            assertThat(decision.getView().getEstimatedTokens()).isEqualTo(321);
            assertThat(seenPrompt.get()).isEqualTo("system prompt");
        }

        @Test
        void leavesTheViewUnestimatedWithoutAnEstimator() {
            assertThat(DefaultContextEngine.builder().build().prepare(request().build()).getView().getEstimatedTokens())
                    .isZero();
        }

        @Test
        void refusesARequestWithoutTheHookPlumbingACompactionNeeds() {
            final DefaultContextEngine engine = DefaultContextEngine.builder().build();

            assertThatThrownBy(() -> engine.prepare(
                    ContextRequest.builder().transcriptBuffer(buffer).model(MODEL).environment(environment).build()))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("HookRegistry");
            assertThatThrownBy(() -> engine.prepare(
                    ContextRequest.builder().transcriptBuffer(buffer).model(MODEL).hookRegistry(hookRegistry).build()))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Environment");
        }
    }

    @Nested
    class Recover {

        @Test
        void installsTheShortenedListAndReturnsItAsTheView() {
            final List<Message> shortened = List.of(Message.user("second"));
            final DefaultContextEngine engine = DefaultContextEngine.builder()
                    .recoveryStrategy((messages, error) -> PromptSizeRecoveryDecision.retry(shortened, "dropped"))
                    .build();

            final ContextView view = engine.recover(request().build(), promptTooLong()).orElseThrow();

            assertThat(view.getMessages()).isEqualTo(shortened);
            assertThat(buffer.getMessages()).as("v1 mode rewrites the transcript in place").isEqualTo(shortened);
        }

        @Test
        void handsTheStrategyTheTranscriptItIsAboutToShorten() {
            final List<Message> before = buffer.getMessages();
            final AtomicReference<List<Message>> seen = new AtomicReference<>();
            final DefaultContextEngine engine = DefaultContextEngine.builder().recoveryStrategy((messages, error) -> {
                seen.set(messages);
                return PromptSizeRecoveryDecision.none("nothing droppable");
            }).build();

            engine.recover(request().build(), promptTooLong());

            assertThat(seen.get()).isEqualTo(before);
        }

        @Test
        void returnsEmptyAndLeavesTheTranscriptAloneWhenTheStrategyDeclines() {
            final List<Message> before = buffer.getMessages();
            final DefaultContextEngine engine = DefaultContextEngine.builder()
                    .recoveryStrategy((messages, error) -> PromptSizeRecoveryDecision.none("nothing droppable"))
                    .build();

            assertThat(engine.recover(request().build(), promptTooLong())).isEmpty();
            assertThat(buffer.getMessages()).isEqualTo(before);
        }

        @Test
        void defaultsToNoRecovery() {
            assertThat(DefaultContextEngine.builder().build().recover(request().build(), promptTooLong())).isEmpty();
        }
    }

    @Nested
    class CompactNow {

        @Test
        void runsAManualCompactionCarryingTheCallersAttribution() {
            final RecordingCompactionEngine compactionEngine = new RecordingCompactionEngine(true);
            final DefaultContextEngine engine = DefaultContextEngine.builder().compactionEngine(compactionEngine)
                    .build();
            final LlmCallMetadata callMetadata = LlmCallMetadata.builder().component("compact-command")
                    .principal(Principal.user("u-1")).build();

            final CompactionResult result = engine.compactNow(request().callMetadata(callMetadata).build(),
                    "focus on the api");

            assertThat(result.isSuccess()).isTrue();
            final CompactionRequest seen = compactionEngine.last.get();
            assertThat(seen.getTrigger()).isEqualTo(CompactionTrigger.MANUAL);
            assertThat(seen.getTranscriptBuffer()).isSameAs(buffer);
            assertThat(seen.getModel()).isSameAs(MODEL);
            assertThat(seen.getHookRegistry()).isSameAs(hookRegistry);
            assertThat(seen.getEnvironment()).isSameAs(environment);
            assertThat(seen.getCustomInstructions()).hasValue("focus on the api");
            assertThat(seen.getCallMetadata()).hasValue(callMetadata);
            assertThat(seen.getExecutionId()).isEmpty();
        }

        @Test
        void resetsTheCircuitBreakerOnSuccessOnly() {
            final RecordingGuard guard = new RecordingGuard(CompactionDecision.none());

            DefaultContextEngine.builder().compactionGuard(guard).compactionEngine(new RecordingCompactionEngine(false))
                    .build().compactNow(request().build(), null);
            assertThat(guard.resetSessionId.get()).isNull();

            DefaultContextEngine.builder().compactionGuard(guard).compactionEngine(new RecordingCompactionEngine(true))
                    .build().compactNow(request().build(), null);
            assertThat(guard.resetSessionId.get()).isEqualTo(buffer.getSessionId());
        }

        @Test
        void letsAnEngineExceptionReachTheCaller() {
            final CompactionEngine throwing = request -> {
                throw new IllegalStateException("boom");
            };
            final DefaultContextEngine engine = DefaultContextEngine.builder().compactionEngine(throwing).build();

            assertThatThrownBy(() -> engine.compactNow(request().build(), null)).hasMessage("boom");
        }

        @Test
        void failsWithoutACompactionEngine() {
            final CompactionResult result = DefaultContextEngine.builder().build().compactNow(request().build(), null);

            assertThat(result.isFailure()).isTrue();
            assertThat(result.getMetadata().getTrigger()).isEqualTo(CompactionTrigger.MANUAL);
        }
    }

    @Test
    void passthroughSendsTheTranscriptAsItIsAndNeverShrinksIt() {
        final ContextEngine passthrough = ContextEngine.passthrough();
        final ContextRequest bare = ContextRequest.builder().transcriptBuffer(buffer).model(MODEL).build();

        final ContextDecision decision = passthrough.prepare(bare);

        assertThat(decision.getAction()).isEqualTo(CompactionDecision.Action.NONE);
        assertThat(decision.getView().getMessages()).isEqualTo(buffer.getMessages());
        assertThat(passthrough.recover(bare, promptTooLong())).isEmpty();
        assertThat(passthrough.compactNow(bare, null).isFailure()).isTrue();
        assertThat(ContextEngine.passthrough()).isSameAs(passthrough);
    }

    @Test
    void requestDefaultsItsSystemPromptAndCallerFromTheBuffer() {
        final ContextRequest bare = ContextRequest.builder().transcriptBuffer(buffer).model(MODEL).build();

        assertThat(bare.getSystemPrompt()).isEqualTo("system prompt");
        assertThat(bare.getCaller()).isSameAs(ContextCaller.session());
        assertThat(bare.getCaller().getExecutionId()).isEmpty();
        assertThat(bare.getHookRegistry()).isEmpty();
        assertThat(bare.isBudgetForced()).isFalse();
    }

    @Test
    void viewCopiesItsMessages() {
        final List<Message> source = new ArrayList<>(List.of(Message.user("a")));
        final ContextView view = ContextView.of(source);
        source.add(Message.user("b"));

        assertThat(view.getMessages()).hasSize(1);
        assertThatThrownBy(() -> ContextView.of(source, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    private static LlmPromptTooLongException promptTooLong() {
        return new LlmPromptTooLongException("prompt is too long");
    }

    private static CompactionMetadata metadata(CompactionTrigger trigger) {
        final Instant now = Instant.now();
        return CompactionMetadata.builder().trigger(trigger).startedAt(now).completedAt(now).build();
    }

    /** Records which guard entry was taken and returns a canned decision. */
    private static class RecordingGuard implements CompactionGuard {
        final List<String> calls = new ArrayList<>();
        final AtomicReference<ExecutionId> lastExecutionId = new AtomicReference<>();
        final AtomicReference<SessionId> resetSessionId = new AtomicReference<>();
        private final CompactionDecision decision;

        RecordingGuard(CompactionDecision decision) {
            this.decision = decision;
        }

        protected void onCall(TranscriptBuffer memory) {
        }

        @Override
        public CompactionDecision maybeCompact(TranscriptBuffer memory, LlmModel model, HookRegistry hookRegistry,
                Environment environment) {
            calls.add("maybeCompact");
            onCall(memory);
            return decision;
        }

        @Override
        public CompactionDecision maybeCompact(TranscriptBuffer memory, LlmModel model, HookRegistry hookRegistry,
                Environment environment, ExecutionId executionId) {
            calls.add("maybeCompact+id");
            lastExecutionId.set(executionId);
            onCall(memory);
            return decision;
        }

        @Override
        public CompactionDecision forceCompact(TranscriptBuffer memory, LlmModel model, HookRegistry hookRegistry,
                Environment environment) {
            calls.add("forceCompact");
            onCall(memory);
            return decision;
        }

        @Override
        public CompactionDecision forceCompact(TranscriptBuffer memory, LlmModel model, HookRegistry hookRegistry,
                Environment environment, ExecutionId executionId) {
            calls.add("forceCompact+id");
            lastExecutionId.set(executionId);
            onCall(memory);
            return decision;
        }

        @Override
        public void recordExternalSuccess(SessionId sessionId) {
            resetSessionId.set(sessionId);
        }
    }

    /** Captures the request and reports the configured outcome. */
    private static final class RecordingCompactionEngine implements CompactionEngine {
        final AtomicReference<CompactionRequest> last = new AtomicReference<>();
        private final boolean succeed;

        RecordingCompactionEngine(boolean succeed) {
            this.succeed = succeed;
        }

        @Override
        public CompactionResult compact(CompactionRequest request) {
            last.set(request);
            return succeed
                    ? CompactionResult.success("summary", metadata(request.getTrigger()))
                    : CompactionResult.failure(new IllegalStateException("down"), metadata(request.getTrigger()));
        }
    }

    /** Returns a fixed estimate and records the system prompt it was asked about. */
    private static final class FixedTokenEstimator implements TokenEstimator {
        private final int estimate;
        private final AtomicReference<String> seenPrompt;

        FixedTokenEstimator(int estimate, AtomicReference<String> seenPrompt) {
            this.estimate = estimate;
            this.seenPrompt = seenPrompt;
        }

        @Override
        public int estimate(String systemPrompt, List<Message> messages) {
            seenPrompt.set(systemPrompt);
            return estimate;
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
}
