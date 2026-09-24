package at.aimon.core.agent.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.Environment;
import at.aimon.core.agent.compact.CompactBoundary;
import at.aimon.core.agent.compact.CompactionDecision;
import at.aimon.core.agent.compact.CompactionEngine;
import at.aimon.core.agent.compact.CompactionGuard;
import at.aimon.core.agent.compact.CompactionMetadata;
import at.aimon.core.agent.compact.CompactionRequest;
import at.aimon.core.agent.compact.CompactionResult;
import at.aimon.core.agent.compact.CompactionTrigger;
import at.aimon.core.agent.compact.DefaultCompactionGuard;
import at.aimon.core.agent.compact.DefaultPromptSizeRecoveryStrategy;
import at.aimon.core.agent.compact.InMemoryCompactionFailureStore;
import at.aimon.core.agent.compact.PromptSizeRecoveryDecision;
import at.aimon.core.agent.compact.SummaryRequest;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.transcript.LogOrigin;
import at.aimon.core.agent.session.transcript.SeqRange;
import at.aimon.core.agent.session.transcript.SessionLogFormat;
import at.aimon.core.agent.session.transcript.TranscriptBuffer;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.llm.InMemoryModelContextWindowRegistry;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ModelContextLimits;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;
import at.aimon.core.llm.exception.LlmPromptTooLongException;
import at.aimon.core.llm.token.HeuristicTokenEstimator;

/**
 * {@link DefaultContextEngine} over a version-2 log: the same decisions as the in-place mode, recorded in the view
 * state instead of written over the log.
 */
@SuppressWarnings("deprecation") // the fallback cases wire a CompactionGuard on purpose
class DefaultContextEngineViewModeTest {

    private static final LlmModel MODEL = LlmModel.builder().name("tiny").build();

    /** Auto-compact at 1200 estimated tokens, blocking at 1400. */
    private static final ModelContextLimits TINY = ModelContextLimits.builder().contextWindow(2000)
            .reservedOutputTokens(500).autoCompactBuffer(300).warningBuffer(200).blockingBuffer(100).build();

    private final HeuristicTokenEstimator estimator = new HeuristicTokenEstimator();
    private final InMemoryCompactionFailureStore failures = new InMemoryCompactionFailureStore();
    private SummarizingEngine summarizer;
    private DefaultCompactionGuard guard;
    private TranscriptBuffer buffer;

    @BeforeEach
    void setUp() {
        summarizer = new SummarizingEngine();
        guard = new DefaultCompactionGuard(summarizer,
                InMemoryModelContextWindowRegistry.builder().defaultLimits(TINY).build(), estimator,
                DefaultCompactionGuard.DEFAULT_MAX_CONSECUTIVE_FAILURES,
                DefaultCompactionGuard.DEFAULT_MAX_TRACKED_SESSIONS, failures);
        buffer = new TranscriptBuffer(SessionId.of("s-1"), "system prompt");
        buffer.requireFormat(SessionLogFormat.V2);
    }

    private DefaultContextEngine engine() {
        return DefaultContextEngine.builder().compactionGuard(guard).compactionEngine(summarizer)
                .recoveryStrategy(new DefaultPromptSizeRecoveryStrategy()).tokenEstimator(estimator)
                .writeFormat(SessionLogFormat.V2).build();
    }

    private ContextRequest request() {
        return ContextRequest.builder().transcriptBuffer(buffer).model(MODEL).hookRegistry(new DefaultHookRegistry())
                .environment(Environment.createDefault()).build();
    }

    private void fillPastTheAutoThreshold() {
        buffer.addUserMessage("first");
        buffer.addAssistantMessage("one");
        buffer.addUserMessage("x".repeat(4300));
    }

    @Nested
    class Prepare {

        @Test
        void underTheThresholdTheViewIsTheLog() {
            buffer.addUserMessage("hello");

            final ContextDecision decision = engine().prepare(request());

            assertThat(decision.getAction()).isEqualTo(CompactionDecision.Action.NONE);
            assertThat(decision.getView().getMessages()).isEqualTo(buffer.getMessages());
            assertThat(decision.getViewSizeBefore()).hasValue(1);
            assertThat(summarizer.summarized).isEmpty();
        }

        @Test
        void aCompactionRecordsASpanAndLeavesTheLogAlone() {
            fillPastTheAutoThreshold();
            final List<Message> logBefore = buffer.getMessages();
            final long versionBefore = buffer.getVersion();

            final ContextDecision decision = engine().prepare(request());

            assertThat(decision.getAction()).isEqualTo(CompactionDecision.Action.COMPACT);
            assertThat(buffer.getMessages()).as("the log is append-only").isEqualTo(logBefore);
            assertThat(buffer.getVersion()).as("a view state change is a mutation to persist")
                    .isGreaterThan(versionBefore);
            assertThat(buffer.getViewState().getSummarySpan()).hasValueSatisfying(span -> {
                assertThat(span.getRange()).isEqualTo(SeqRange.of(0, 3));
                assertThat(span.getSummaryText()).isEqualTo("SUMMARY");
                assertThat(span.getTrigger()).isEqualTo("AUTO");
                assertThat(span.getMessagesSummarized()).isEqualTo(3);
            });
            final List<Message> view = decision.getView().getMessages();
            assertThat(view).hasSize(2);
            assertThat(view.get(0).getContent()).startsWith(CompactBoundary.BOUNDARY_OPEN_PREFIX);
            assertThat(view.get(1).getContent()).contains("SUMMARY");
            assertThat(decision.getViewSizeBefore()).hasValue(3);
            assertThat(summarizer.summarized).singleElement()
                    .satisfies(request -> assertThat(request.getMessages()).isEqualTo(logBefore));
            assertThat(decision.getCompactionMetadata())
                    .hasValueSatisfying(metadata -> assertThat(metadata.getPostCompactTokenCount()).isPositive());
        }

        @Test
        void postCompactHooksAreReportedTheInstalledStateAndTheirAppendsJoinTheView() {
            fillPastTheAutoThreshold();
            summarizer.onInstalled = installedIn -> installedIn.addMessage(Message.user("re-attached file"),
                    LogOrigin.SYNTHETIC);

            final ContextDecision decision = engine().prepare(request());

            assertThat(summarizer.installed).hasSize(1);
            assertThat(decision.getView().getMessages()).hasSize(3);
            assertThat(decision.getView().getMessages().get(2).getContent()).isEqualTo("re-attached file");
        }

        @Test
        void aSecondCompactionAbsorbsTheFirstSpan() {
            fillPastTheAutoThreshold();
            final DefaultContextEngine engine = engine();
            engine.prepare(request());
            buffer.addAssistantMessage("answer");
            buffer.addUserMessage("y".repeat(4600));

            final ContextDecision decision = engine.prepare(request());

            assertThat(decision.getAction()).isEqualTo(CompactionDecision.Action.COMPACT);
            assertThat(buffer.getViewState().getSummarySpan())
                    .hasValueSatisfying(span -> assertThat(span.getRange()).isEqualTo(SeqRange.of(0, 5)));
            assertThat(summarizer.summarized.get(1).getMessages().get(0).getContent())
                    .as("the previous markers are summarized as ordinary messages, as in place")
                    .startsWith(CompactBoundary.BOUNDARY_OPEN_PREFIX);
            assertThat(buffer.getMessages()).hasSize(5);
        }

        @Test
        void aFailedSummaryLeavesTheViewStateAloneAndCountsTowardTheBreaker() {
            fillPastTheAutoThreshold();
            summarizer.fail = true;

            final ContextDecision decision = engine().prepare(request());

            assertThat(decision.getAction()).isEqualTo(CompactionDecision.Action.COMPACT);
            assertThat(buffer.getViewState().isEmpty()).isTrue();
            assertThat(failures.get(buffer.getSessionId())).isEqualTo(1);
            assertThat(decision.getView().getMessages()).isEqualTo(buffer.getMessages());
        }

        @Test
        void aTurnInFlightWithAnUnansweredCallIsNotCompacted() {
            fillPastTheAutoThreshold();
            buffer.addMessage(Message.assistant("", List.of(ToolUse.of("t1", "Read", Map.of()))));

            final ContextDecision decision = engine().prepare(request());

            assertThat(decision.getAction()).isNotEqualTo(CompactionDecision.Action.COMPACT);
            assertThat(buffer.getViewState().isEmpty()).isTrue();
        }
    }

    @Nested
    class Recover {

        @Test
        void theOldestUserMessageIsDroppedFromTheViewNotTheLog() {
            buffer.addUserMessage("oldest");
            buffer.addAssistantMessage("one");
            buffer.addUserMessage("latest");
            final List<Message> logBefore = buffer.getMessages();

            final ContextView recovered = engine().recover(request(), new LlmPromptTooLongException("too long"))
                    .orElseThrow();

            assertThat(recovered.getMessages()).extracting(Message::getContent).containsExactly("one", "latest");
            assertThat(buffer.getMessages()).isEqualTo(logBefore);
            assertThat(buffer.getViewState().getDroppedRanges()).containsExactly(SeqRange.of(0, 1));
        }

        @Test
        void aStrategyThatRewritesAMessageIsRefused() {
            buffer.addUserMessage("oldest");
            buffer.addUserMessage("latest");
            final DefaultContextEngine engine = DefaultContextEngine.builder().compactionGuard(guard)
                    .compactionEngine(summarizer).recoveryStrategy((messages, error) -> PromptSizeRecoveryDecision
                            .retry(List.of(Message.user("truncated"), messages.get(1)), "truncated"))
                    .build();

            assertThat(engine.recover(request(), new LlmPromptTooLongException("too long"))).isEmpty();
            assertThat(buffer.getViewState().isEmpty()).isTrue();
        }

        @Test
        void aToolPairDroppedTogetherIsOneLegalRange() {
            buffer.addUserMessage("q");
            buffer.addMessage(Message.assistant("", List.of(ToolUse.of("t1", "Read", Map.of()))));
            buffer.addMessage(Message.toolUseResults(List.of(ToolUseResult.success("t1", "body"))));
            buffer.addUserMessage("latest");
            final DefaultContextEngine engine = DefaultContextEngine.builder().compactionGuard(guard)
                    .compactionEngine(summarizer).recoveryStrategy((messages, error) -> PromptSizeRecoveryDecision
                            .retry(List.of(messages.get(0), messages.get(3)), "dropped the pair"))
                    .build();

            assertThat(engine.recover(request(), new LlmPromptTooLongException("too long"))).isPresent();
            assertThat(buffer.getViewState().getDroppedRanges()).containsExactly(SeqRange.of(1, 3));
        }

        @Test
        void halfAToolPairIsRefused() {
            buffer.addUserMessage("q");
            buffer.addMessage(Message.assistant("", List.of(ToolUse.of("t1", "Read", Map.of()))));
            buffer.addMessage(Message.toolUseResults(List.of(ToolUseResult.success("t1", "body"))));
            buffer.addUserMessage("latest");
            final DefaultContextEngine engine = DefaultContextEngine.builder().compactionGuard(guard)
                    .compactionEngine(summarizer)
                    .recoveryStrategy((messages, error) -> PromptSizeRecoveryDecision
                            .retry(List.of(messages.get(0), messages.get(2), messages.get(3)), "dropped a call"))
                    .build();

            assertThat(engine.recover(request(), new LlmPromptTooLongException("too long"))).isEmpty();
            assertThat(buffer.getViewState().isEmpty()).isTrue();
        }

        @Test
        void droppingACompactionMarkerIsRefused() {
            fillPastTheAutoThreshold();
            final DefaultContextEngine engine = DefaultContextEngine.builder().compactionGuard(guard)
                    .compactionEngine(summarizer).tokenEstimator(estimator).recoveryStrategy((messages,
                            error) -> PromptSizeRecoveryDecision.retry(messages.subList(1, 2), "dropped the boundary"))
                    .build();
            engine.prepare(request());

            assertThat(engine.recover(request(), new LlmPromptTooLongException("too long"))).isEmpty();
            assertThat(buffer.getViewState().getDroppedRanges()).isEmpty();
        }
    }

    @Nested
    class CompactNow {

        @Test
        void aManualCompactionRecordsASpanAndResetsTheBreaker() {
            buffer.addUserMessage("hello");
            buffer.addAssistantMessage("hi");
            failures.recordFailure(buffer.getSessionId());

            final CompactionResult result = engine().compactNow(request(), "keep the names");

            assertThat(result.isSuccess()).isTrue();
            assertThat(summarizer.summarized).singleElement().satisfies(request -> {
                assertThat(request.getTrigger()).isEqualTo(CompactionTrigger.MANUAL);
                assertThat(request.getCustomInstructions()).contains("keep the names");
            });
            assertThat(buffer.getViewState().getSummarySpan()).isPresent();
            assertThat(buffer.getMessages()).hasSize(2);
            assertThat(failures.get(buffer.getSessionId())).isZero();
        }

        @Test
        void aNullSummaryIsACleanFailureNotAnNpe() {
            buffer.addUserMessage("hello");
            buffer.addAssistantMessage("hi");
            summarizer.returnNull = true;

            final CompactionResult result = engine().compactNow(request(), null);

            assertThat(result).isNotNull();
            assertThat(result.isFailure()).isTrue();
            assertThat(result.getError())
                    .hasValueSatisfying(error -> assertThat(error).hasMessageContaining("summarize() returned null"));
            assertThat(result.getMetadata().getTrigger()).isEqualTo(CompactionTrigger.MANUAL);
            assertThat(buffer.getViewState().getSummarySpan()).isEmpty();
        }

        @Test
        void anEmptyViewIsNotCompacted() {
            final CompactionResult result = engine().compactNow(request(), null);

            assertThat(result.isFailure()).isTrue();
            assertThat(summarizer.summarized).isEmpty();
        }
    }

    @Nested
    class WhatViewModeNeeds {

        @Test
        void aVersionTwoNodeRefusesACustomGuard() {
            final CompactionGuard custom = (memory, model, hookRegistry, environment) -> CompactionDecision.none();

            assertThatThrownBy(() -> DefaultContextEngine.builder().compactionGuard(custom).compactionEngine(summarizer)
                    .writeFormat(SessionLogFormat.V2).build()).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("version-1 write mode");
        }

        @Test
        void aVersionTwoNodeRefusesAnEngineThatCannotSummarize() {
            final CompactionEngine compactOnly = request -> CompactionResult.success("s", metadata());

            assertThatThrownBy(() -> DefaultContextEngine.builder().compactionEngine(compactOnly)
                    .writeFormat(SessionLogFormat.V2).build()).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("summarize");
        }

        @Test
        void aVersionOneNodeWithACustomGuardCompactsAnUpgradedRecordInPlace() {
            fillPastTheAutoThreshold();
            final CompactionGuard rewriting = (memory, model, hookRegistry, environment) -> {
                memory.replaceWith(List.of(Message.user("rewritten")));
                return CompactionDecision.compact(CompactionResult.success("s", metadata()), "custom", 1, 2);
            };
            final DefaultContextEngine engine = DefaultContextEngine.builder().compactionGuard(rewriting).build();

            final ContextDecision decision = engine.prepare(request());

            assertThat(decision.getView().getMessages()).extracting(Message::getContent).containsExactly("rewritten");
            assertThat(buffer.getFormat()).as("the record stays version 2 (sticky)").isEqualTo(SessionLogFormat.V2);
        }
    }

    /**
     * The in-place fallback meeting a log that already carries a view: a version-1 node whose engine cannot keep the
     * log append-only, serving a record another node compacted in view mode.
     */
    @Nested
    class InPlaceFallbackOverAView {

        private DefaultContextEngine inPlaceOnly(CompactionGuard guard) {
            return DefaultContextEngine.builder().compactionGuard(guard)
                    .recoveryStrategy(new DefaultPromptSizeRecoveryStrategy()).compactionEngine(summarizer)
                    .tokenEstimator(estimator).build();
        }

        private void summarizedLog() {
            fillPastTheAutoThreshold();
            engine().prepare(request());
            assertThat(buffer.getViewState().getSummarySpan()).isPresent();
            buffer.addUserMessage("after the summary");
        }

        @Test
        void theViewIsSentAndNothingIsCompactedInPlace() {
            summarizedLog();
            final CompactionGuard rewriting = (memory, model, hookRegistry, environment) -> {
                throw new AssertionError("the guard must not be asked to rewrite a log that carries a view");
            };
            final List<Message> logBefore = buffer.getMessages();

            final ContextDecision decision = inPlaceOnly(rewriting).prepare(request());

            assertThat(decision.getAction()).isEqualTo(CompactionDecision.Action.NONE);
            assertThat(decision.getReason()).isEqualTo(DefaultContextEngine.IN_PLACE_REFUSED);
            assertThat(decision.getView().getMessages()).as("the projected view, not the raw entries")
                    .isEqualTo(ViewProjection.of(buffer.getLogState()).getMessages());
            assertThat(decision.getView().getMessages()).extracting(Message::getContent)
                    .doesNotContain("x".repeat(4300));
            assertThat(buffer.getMessages()).isEqualTo(logBefore);
            assertThat(buffer.getViewState().getSummarySpan()).as("the span survives").isPresent();
        }

        @Test
        void compactNowFailsRatherThanErasingTheSpan() {
            summarizedLog();
            final CompactionGuard custom = (memory, model, hookRegistry, environment) -> CompactionDecision.none();

            final CompactionResult result = inPlaceOnly(custom).compactNow(request(), null);

            assertThat(result.isFailure()).isTrue();
            assertThat(result.getError()).hasValueSatisfying(
                    error -> assertThat(error).hasMessageContaining(DefaultContextEngine.IN_PLACE_REFUSED));
            assertThat(buffer.getViewState().getSummarySpan()).isPresent();
        }

        @Test
        void recoveryDropsFromTheViewInsteadOfReplacingTheBuffer() {
            summarizedLog();
            final CompactionGuard custom = (memory, model, hookRegistry, environment) -> CompactionDecision.none();
            final List<Message> logBefore = buffer.getMessages();

            final var recovered = inPlaceOnly(custom).recover(request(), new LlmPromptTooLongException("too long"));

            assertThat(buffer.getMessages()).as("the log is not rewritten").isEqualTo(logBefore);
            assertThat(buffer.getViewState().getSummarySpan()).isPresent();
            recovered.ifPresent(view -> assertThat(view.getMessages())
                    .isEqualTo(ViewProjection.of(buffer.getLogState()).getMessages()));
        }

        @Test
        void aVersionTwoLogWithoutAViewIsStillCompactedInPlace() {
            fillPastTheAutoThreshold();
            final CompactionGuard rewriting = (memory, model, hookRegistry, environment) -> {
                memory.replaceWith(List.of(Message.user("rewritten")));
                return CompactionDecision.compact(CompactionResult.success("s", metadata()), "custom", 1, 2);
            };

            final ContextDecision decision = inPlaceOnly(rewriting).prepare(request());

            assertThat(decision.getView().getMessages()).extracting(Message::getContent).containsExactly("rewritten");
        }

        @Test
        void passthroughSendsTheViewOfAVersionTwoLog() {
            summarizedLog();

            final ContextDecision decision = ContextEngine.passthrough().prepare(request());

            assertThat(decision.getView().getMessages())
                    .isEqualTo(ViewProjection.of(buffer.getLogState()).getMessages());
        }
    }

    private static CompactionMetadata metadata() {
        final Instant now = Instant.now();
        return CompactionMetadata.builder().trigger(CompactionTrigger.AUTO).startedAt(now).completedAt(now).build();
    }

    /** Summarizes by returning a fixed text; records every summarize and installed notification. */
    private static final class SummarizingEngine implements CompactionEngine {

        private final List<SummaryRequest> summarized = new ArrayList<>();
        private final List<CompactionResult> installed = new ArrayList<>();
        private boolean fail;
        private boolean returnNull;
        private java.util.function.Consumer<TranscriptBuffer> onInstalled = memory -> {
        };

        @Override
        public CompactionResult compact(CompactionRequest request) {
            throw new AssertionError("view mode must not rewrite the transcript");
        }

        @Override
        public boolean supportsSummarize() {
            return true;
        }

        @Override
        public CompactionResult summarize(SummaryRequest request) {
            summarized.add(request);
            if (returnNull) {
                return null;
            }
            final Instant now = Instant.now();
            final CompactionMetadata metadata = CompactionMetadata.builder().trigger(request.getTrigger())
                    .preCompactTokenCount(100).messagesSummarized(request.getMessages().size()).startedAt(now)
                    .completedAt(now).build();
            return fail
                    ? CompactionResult.failure(new IllegalStateException("provider down"), metadata)
                    : CompactionResult.success("SUMMARY", metadata);
        }

        @Override
        public void summaryInstalled(SummaryRequest request, CompactionResult result,
                TranscriptBuffer transcriptBuffer) {
            installed.add(result);
            onInstalled.accept(transcriptBuffer);
        }
    }
}
