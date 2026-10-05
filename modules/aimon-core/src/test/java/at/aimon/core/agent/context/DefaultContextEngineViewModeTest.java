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
                .build();
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
            // The last message is input the model has not answered: it is neither summarized nor in the span.
            assertThat(buffer.getViewState().getSummarySpan()).hasValueSatisfying(span -> {
                assertThat(span.getRange()).isEqualTo(SeqRange.of(0, 2));
                assertThat(span.getSummaryText()).isEqualTo("SUMMARY");
                assertThat(span.getTrigger()).isEqualTo("AUTO");
                assertThat(span.getMessagesSummarized()).isEqualTo(2);
            });
            final List<Message> view = decision.getView().getMessages();
            assertThat(view).hasSize(3);
            assertThat(view.get(0).getContent()).startsWith(CompactBoundary.BOUNDARY_OPEN_PREFIX);
            assertThat(view.get(1).getContent()).contains("SUMMARY");
            assertThat(view.get(2)).isSameAs(logBefore.get(2));
            assertThat(decision.getViewSizeBefore()).hasValue(3);
            assertThat(summarizer.summarized).singleElement()
                    .satisfies(request -> assertThat(request.getMessages()).isEqualTo(logBefore.subList(0, 2)));
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
            // boundary, summary, the unanswered input, then what the hook appended to the log.
            assertThat(decision.getView().getMessages()).hasSize(4);
            assertThat(decision.getView().getMessages().get(3).getContent()).isEqualTo("re-attached file");
        }

        @Test
        void aSecondCompactionAbsorbsTheFirstSpan() {
            fillPastTheAutoThreshold();
            final DefaultContextEngine engine = engine();
            engine.prepare(request());
            buffer.addAssistantMessage("answer");
            // Short enough to fit under the blocking limit beside the new summary: with 4600 characters the view
            // would stay at the limit after this compaction, and the whole view would be summarized instead.
            buffer.addUserMessage("y".repeat(4000));

            final ContextDecision decision = engine.prepare(request());

            assertThat(decision.getAction()).isEqualTo(CompactionDecision.Action.COMPACT);
            // The first span was [0, 2); the second widens it over the input the model has since answered and its
            // answer, and stops before the new unanswered input.
            assertThat(buffer.getViewState().getSummarySpan())
                    .hasValueSatisfying(span -> assertThat(span.getRange()).isEqualTo(SeqRange.of(0, 4)));
            assertThat(summarizer.summarized.get(1).getMessages().get(0).getContent())
                    .as("the previous markers are summarized as ordinary messages, as in place")
                    .startsWith(CompactBoundary.BOUNDARY_OPEN_PREFIX);
            assertThat(summarizer.summarized.get(1).getMessages()).hasSize(4);
            assertThat(decision.getView().getMessages()).hasSize(3);
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

    /**
     * What the model has not answered yet — the tool results after its last call, or the input after its last reply —
     * is left out of the summary span (context-engine §13.10, SL-6).
     */
    @Nested
    class Unanswered {

        private static final String BIG = "x".repeat(4300);
        private static final String OVER_BLOCKING = "x".repeat(6000);
        private static final int BLOCKING_LIMIT = TINY.getBlockingLimit();

        private int tokensOf(ContextDecision decision) {
            return estimator.estimate(buffer.getSystemPrompt(), decision.getView().getMessages());
        }

        private void callAndResult(String id, String result) {
            buffer.addMessage(Message.assistant("", List.of(ToolUse.of(id, "Read", Map.of()))));
            buffer.addMessage(Message.toolUseResults(List.of(ToolUseResult.success(id, result))));
        }

        @Test
        void aToolResultOverTheAutoThresholdIsNotFoldedIntoTheSummaryBeforeTheModelReadsIt() {
            buffer.addUserMessage("read the report");
            buffer.addAssistantMessage("on it");
            callAndResult("t1", BIG);
            final List<Message> log = buffer.getMessages();

            final ContextDecision decision = engine().prepare(request());

            assertThat(decision.getAction()).isEqualTo(CompactionDecision.Action.COMPACT);
            assertThat(buffer.getViewState().getSummarySpan()).hasValueSatisfying(span -> {
                assertThat(span.getRange()).isEqualTo(SeqRange.of(0, 2));
                assertThat(span.getMessagesSummarized()).isEqualTo(2);
            });
            assertThat(summarizer.summarized).singleElement()
                    .satisfies(request -> assertThat(request.getMessages()).isEqualTo(log.subList(0, 2)));
            final List<Message> view = decision.getView().getMessages();
            assertThat(view).hasSize(4);
            assertThat(view.get(0).getContent()).startsWith(CompactBoundary.BOUNDARY_OPEN_PREFIX);
            // The call and its result reach the model as the same instances the log holds, the result whole.
            assertThat(view.get(2)).isSameAs(log.get(2));
            assertThat(view.get(3)).isSameAs(log.get(3));
            assertThat(decision.getViewSizeBefore()).hasValue(4);
        }

        @Test
        void theCutNeverSeparatesACallFromItsResult() {
            buffer.addUserMessage("go");
            callAndResult("t1", "small");
            callAndResult("t2", BIG);

            final ContextDecision decision = engine().prepare(request());

            assertThat(decision.getAction()).isEqualTo(CompactionDecision.Action.COMPACT);
            // The unanswered part is the t2 result alone; the cut falls before the message that made the t2 call.
            assertThat(buffer.getViewState().getSummarySpan())
                    .hasValueSatisfying(span -> assertThat(span.getRange()).isEqualTo(SeqRange.of(0, 3)));
            final List<Message> view = decision.getView().getMessages();
            final List<String> called = new ArrayList<>();
            final List<String> answered = new ArrayList<>();
            for (Message message : view) {
                message.getToolUses().forEach(use -> called.add(use.getId()));
                message.getToolUseResults().forEach(result -> answered.add(result.getToolUseId()));
            }
            assertThat(called).containsExactly("t2");
            assertThat(answered).containsExactly("t2");
            // And the summarized part holds the t1 pair whole.
            final List<String> summarizedIds = new ArrayList<>();
            for (Message message : summarizer.summarized.get(0).getMessages()) {
                message.getToolUses().forEach(use -> summarizedIds.add("call:" + use.getId()));
                message.getToolUseResults().forEach(result -> summarizedIds.add("result:" + result.getToolUseId()));
            }
            assertThat(summarizedIds).containsExactly("call:t1", "result:t1");
        }

        @Test
        void whenOnlyTheUnansweredPartIsLeftNothingIsCompactedAndNothingIsReportedAsOne() {
            buffer.addUserMessage("go");
            buffer.addAssistantMessage("on it");
            callAndResult("t1", BIG);
            final DefaultContextEngine engine = engine();
            assertThat(engine.prepare(request()).getAction()).isEqualTo(CompactionDecision.Action.COMPACT);
            final long versionAfterFirst = buffer.getVersion();

            // The same view comes back each time the model has still not answered: no second summary call, no
            // hook, no breaker failure, no span change.
            for (int i = 0; i < DefaultCompactionGuard.DEFAULT_MAX_CONSECUTIVE_FAILURES + 2; i++) {
                final ContextDecision again = engine.prepare(request());

                assertThat(again.getAction()).isEqualTo(CompactionDecision.Action.WARN);
                assertThat(again.getReason()).contains("nothing to compact");
                assertThat(again.getCompactionMetadata()).isEmpty();
                assertThat(again.getView().getMessages()).hasSize(4);
            }
            assertThat(summarizer.summarized).hasSize(1);
            assertThat(summarizer.installed).hasSize(1);
            assertThat(failures.get(buffer.getSessionId())).isZero();
            assertThat(buffer.getVersion()).isEqualTo(versionAfterFirst);
            assertThat(buffer.getViewState().getSummarySpan())
                    .hasValueSatisfying(span -> assertThat(span.getRange()).isEqualTo(SeqRange.of(0, 2)));
        }

        @Test
        void aFirstInputOverTheAutoThresholdIsSentAsItIs() {
            buffer.addUserMessage(BIG + "x".repeat(300));

            final ContextDecision decision = engine().prepare(request());

            assertThat(decision.getAction()).isEqualTo(CompactionDecision.Action.WARN);
            assertThat(summarizer.summarized).isEmpty();
            assertThat(buffer.getViewState().isEmpty()).isTrue();
            assertThat(decision.getView().getMessages()).isEqualTo(buffer.getMessages());
            assertThat(failures.get(buffer.getSessionId())).isZero();
        }

        @Test
        void onceTheModelHasAnsweredTheResultIsAbsorbedLikeAnythingElse() {
            buffer.addUserMessage("go");
            buffer.addAssistantMessage("on it");
            callAndResult("t1", BIG);
            final DefaultContextEngine engine = engine();
            engine.prepare(request());
            buffer.addAssistantMessage("the report says so");
            buffer.addUserMessage("thanks, and now?");

            final ContextDecision decision = engine.prepare(request());

            assertThat(decision.getAction()).isEqualTo(CompactionDecision.Action.COMPACT);
            assertThat(buffer.getViewState().getSummarySpan())
                    .hasValueSatisfying(span -> assertThat(span.getRange()).isEqualTo(SeqRange.of(0, 5)));
            assertThat(decision.getView().getMessages()).hasSize(3);
            assertThat(decision.getView().getMessages().get(2).getContent()).isEqualTo("thanks, and now?");
        }

        @Test
        void aBudgetForcedPassLeavesTheUnansweredPartOutToo() {
            buffer.addUserMessage("go");
            buffer.addAssistantMessage("on it");
            buffer.addUserMessage("x".repeat(4000));
            final ContextRequest forced = ContextRequest.builder().transcriptBuffer(buffer).model(MODEL)
                    .hookRegistry(new DefaultHookRegistry()).budgetForced(true).build();

            final ContextDecision decision = engine().prepare(forced);

            assertThat(decision.getAction()).isEqualTo(CompactionDecision.Action.COMPACT);
            assertThat(buffer.getViewState().getSummarySpan())
                    .hasValueSatisfying(span -> assertThat(span.getRange()).isEqualTo(SeqRange.of(0, 2)));
        }

        @Test
        void atTheBlockingLimitWhatPrecedesTheUnansweredPartIsAbsorbedWhenThatBringsTheViewUnderTheLimit() {
            buffer.addUserMessage(BIG);
            buffer.addAssistantMessage("on it");
            buffer.addUserMessage("x".repeat(1000));
            final List<Message> log = buffer.getMessages();

            final ContextDecision decision = engine().prepare(request());

            assertThat(decision.getAction()).isEqualTo(CompactionDecision.Action.COMPACT);
            assertThat(decision.getReason()).isEqualTo("blocking-limit forced compaction");
            assertThat(buffer.getViewState().getSummarySpan())
                    .hasValueSatisfying(span -> assertThat(span.getRange()).isEqualTo(SeqRange.of(0, 2)));
            assertThat(decision.getView().getMessages()).hasSize(3);
            assertThat(decision.getView().getMessages().get(2)).isSameAs(log.get(2));
            assertThat(tokensOf(decision)).isLessThan(BLOCKING_LIMIT);
            assertThat(summarizer.summarized).hasSize(1);
            assertThat(decision.getCompactionMetadata())
                    .hasValueSatisfying(metadata -> assertThat(metadata.isOverBlockingLimit()).isFalse());
        }

        /**
         * The executor asks once per iteration and sends what it is given: a blocking-limit compaction that summarized
         * one small message and left a tool result over the limit would be reported as a success and then rejected by
         * the provider, with nothing recovery may drop.
         */
        @Test
        void atTheBlockingLimitAnUnansweredResultOverTheLimitIsSummarizedWithWhatPrecedesItInOnePass() {
            buffer.addUserMessage("read the report");
            callAndResult("t1", OVER_BLOCKING);
            final List<Message> log = buffer.getMessages();

            final ContextDecision decision = engine().prepare(request());

            assertThat(decision.getAction()).isEqualTo(CompactionDecision.Action.COMPACT);
            assertThat(tokensOf(decision)).as("a forced compaction reported as a success fits under the limit")
                    .isLessThan(BLOCKING_LIMIT);
            assertThat(summarizer.summarized).as("one summary call, not one for the prefix and one for the rest")
                    .singleElement().satisfies(request -> assertThat(request.getMessages()).isEqualTo(log));
            assertThat(buffer.getViewState().getSummarySpan()).hasValueSatisfying(span -> {
                assertThat(span.getRange()).isEqualTo(SeqRange.of(0, 3));
                assertThat(span.getMessagesSummarized()).isEqualTo(3);
            });
            assertThat(decision.getView().getMessages()).hasSize(2);
            assertThat(decision.getReason()).isEqualTo("blocking-limit forced compaction");
            assertThat(buffer.getMessages()).as("the log still holds the result").isEqualTo(log);
        }

        @Test
        void atTheBlockingLimitAPastedInputOverTheLimitAfterEarlierConversationIsSummarizedInOnePass() {
            buffer.addUserMessage("go");
            buffer.addAssistantMessage("on it");
            buffer.addUserMessage(OVER_BLOCKING);

            final ContextDecision decision = engine().prepare(request());

            assertThat(decision.getAction()).isEqualTo(CompactionDecision.Action.COMPACT);
            assertThat(tokensOf(decision)).isLessThan(BLOCKING_LIMIT);
            assertThat(buffer.getViewState().getSummarySpan())
                    .hasValueSatisfying(span -> assertThat(span.getRange()).isEqualTo(SeqRange.of(0, 3)));
            assertThat(decision.getView().getMessages()).hasSize(2);
            assertThat(summarizer.summarized).hasSize(1);
        }

        @Test
        void atTheBlockingLimitWithNothingBeforeTheUnansweredPartItIsSummarizedAsTheLastResort() {
            buffer.addUserMessage(OVER_BLOCKING);
            final DefaultContextEngine engine = engine();

            // One prepare, as the executor makes: the request cannot be sent as it is, so the whole view is summarized.
            final ContextDecision decision = engine.prepare(request());

            assertThat(decision.getAction()).isEqualTo(CompactionDecision.Action.COMPACT);
            assertThat(buffer.getViewState().getSummarySpan())
                    .hasValueSatisfying(span -> assertThat(span.getRange()).isEqualTo(SeqRange.of(0, 1)));
            assertThat(decision.getView().getMessages()).hasSize(2);
            assertThat(tokensOf(decision)).isLessThan(BLOCKING_LIMIT);
            // Once, not per iteration.
            assertThat(engine.prepare(request()).getAction()).isEqualTo(CompactionDecision.Action.NONE);
            assertThat(summarizer.summarized).hasSize(1);
        }

        @Test
        void whenTheSummaryOfThePrefixPutsTheViewBackOverTheLimitTheWholeViewIsSummarizedInTheSamePrepare() {
            // The unanswered input alone is under the limit (about 1290 of 1400), so the prefix is summarized first;
            // the summary that comes back is long enough to put the view over it again.
            buffer.addUserMessage("x".repeat(600));
            buffer.addAssistantMessage("on it");
            buffer.addUserMessage("x".repeat(4500));
            summarizer.text = "S".repeat(800);

            final ContextDecision decision = engine().prepare(request());

            assertThat(decision.getAction()).isEqualTo(CompactionDecision.Action.COMPACT);
            assertThat(summarizer.summarized).hasSize(2);
            assertThat(summarizer.summarized.get(0).getMessages()).hasSize(2);
            assertThat(summarizer.summarized.get(1).getMessages()).as("markers and the unanswered input").hasSize(3);
            assertThat(buffer.getViewState().getSummarySpan())
                    .hasValueSatisfying(span -> assertThat(span.getRange()).isEqualTo(SeqRange.of(0, 3)));
            assertThat(decision.getView().getMessages()).hasSize(2);
            assertThat(tokensOf(decision)).isLessThan(BLOCKING_LIMIT);
            assertThat(decision.getReason()).isEqualTo("blocking-limit forced compaction");
        }

        @Test
        void whenThatSecondSummaryFailsTheFirstIsReportedAsLeavingTheViewOverTheLimit() {
            buffer.addUserMessage("x".repeat(600));
            buffer.addAssistantMessage("on it");
            buffer.addUserMessage("x".repeat(4500));
            summarizer.text = "S".repeat(800);
            summarizer.failFromCall = 2;

            final ContextDecision decision = engine().prepare(request());

            assertThat(decision.getAction()).isEqualTo(CompactionDecision.Action.COMPACT);
            assertThat(decision.getReason()).contains(DefaultCompactionGuard.STILL_OVER_BLOCKING);
            assertThat(decision.getCompactionMetadata()).hasValueSatisfying(metadata -> {
                assertThat(metadata.getBlockingLimit()).isEqualTo(BLOCKING_LIMIT);
                assertThat(metadata.isOverBlockingLimit()).isTrue();
            });
            assertThat(buffer.getViewState().getSummarySpan())
                    .hasValueSatisfying(span -> assertThat(span.getRange()).isEqualTo(SeqRange.of(0, 2)));
            assertThat(tokensOf(decision)).isGreaterThanOrEqualTo(BLOCKING_LIMIT);
        }

        @Test
        void aWholeViewSummaryThatIsItselfOverTheLimitIsNotReportedAsAPlainSuccess() {
            buffer.addUserMessage("read the report");
            callAndResult("t1", OVER_BLOCKING);
            summarizer.text = "S".repeat(6000);

            final ContextDecision decision = engine().prepare(request());

            // Nothing is left to summarize: the view goes out as it is and the decision says where it stands, the way
            // the rolling engine reports the same state.
            assertThat(decision.getAction()).isEqualTo(CompactionDecision.Action.COMPACT);
            assertThat(decision.getReason()).startsWith("blocking-limit forced compaction")
                    .contains(DefaultCompactionGuard.STILL_OVER_BLOCKING);
            assertThat(decision.getCompactionMetadata()).hasValueSatisfying(metadata -> {
                assertThat(metadata.getBlockingLimit()).isEqualTo(BLOCKING_LIMIT);
                assertThat(metadata.getPostCompactTokenCount()).isGreaterThanOrEqualTo(BLOCKING_LIMIT);
                assertThat(metadata.isOverBlockingLimit()).isTrue();
            });
            assertThat(summarizer.summarized).hasSize(1);
            assertThat(buffer.getViewState().getSummarySpan())
                    .hasValueSatisfying(span -> assertThat(span.getRange()).isEqualTo(SeqRange.of(0, 3)));
        }

        @Test
        void aFailedLastResortSummaryBlocksAndRecordsNothing() {
            buffer.addUserMessage("read the report");
            callAndResult("t1", OVER_BLOCKING);
            summarizer.fail = true;

            final ContextDecision decision = engine().prepare(request());

            assertThat(decision.getAction()).isEqualTo(CompactionDecision.Action.BLOCK);
            assertThat(decision.getReason()).contains("compaction failed at blocking limit");
            assertThat(summarizer.summarized).hasSize(1);
            assertThat(buffer.getViewState().isEmpty()).isTrue();
            assertThat(failures.get(buffer.getSessionId())).isEqualTo(1);
            assertThat(decision.getView().getMessages()).isEqualTo(buffer.getMessages());
        }

        @Test
        void aViewWhoseEndSplitsAToolPairIsNotSummarizedWholeAndNoSummaryCallIsSpentFindingOut() {
            // Two parallel calls, one result: the view cannot end a span here, and nothing precedes the call.
            buffer.addMessage(Message.assistant("",
                    List.of(ToolUse.of("t1", "Read", Map.of()), ToolUse.of("t2", "Read", Map.of()))));
            buffer.addMessage(Message.toolUseResults(List.of(ToolUseResult.success("t1", OVER_BLOCKING))));

            final ContextDecision decision = engine().prepare(request());

            assertThat(decision.getAction()).isEqualTo(CompactionDecision.Action.BLOCK);
            assertThat(decision.getReason()).contains("tool call");
            assertThat(summarizer.summarized).isEmpty();
            assertThat(summarizer.installed).isEmpty();
            assertThat(buffer.getViewState().isEmpty()).isTrue();
        }

        @Test
        void aSmallPrefixBeforeAPairThatCannotBeClosedIsNotSpentOn() {
            buffer.addUserMessage("go");
            buffer.addMessage(Message.assistant("",
                    List.of(ToolUse.of("t1", "Read", Map.of()), ToolUse.of("t2", "Read", Map.of()))));
            buffer.addMessage(Message.toolUseResults(List.of(ToolUseResult.success("t1", OVER_BLOCKING))));

            final ContextDecision decision = engine().prepare(request());

            // Summarizing "go" cannot bring the view under the limit, and the whole view cannot be a span.
            assertThat(decision.getAction()).isEqualTo(CompactionDecision.Action.BLOCK);
            assertThat(summarizer.summarized).isEmpty();
            assertThat(buffer.getViewState().isEmpty()).isTrue();
        }

        @Test
        void aManualCompactionOfAnInterruptedTurnStopsBeforeTheUnansweredInput() {
            buffer.addUserMessage("hello");
            buffer.addAssistantMessage("hi");
            buffer.addUserMessage("the question the interrupted turn never answered");

            final CompactionResult result = engine().compactNow(request(), null);

            assertThat(result.isSuccess()).isTrue();
            assertThat(buffer.getViewState().getSummarySpan())
                    .hasValueSatisfying(span -> assertThat(span.getRange()).isEqualTo(SeqRange.of(0, 2)));
            final List<Message> view = ViewProjection.of(buffer.getLogState()).getMessages();
            assertThat(view).hasSize(3);
            assertThat(view.get(2).getContent()).isEqualTo("the question the interrupted turn never answered");
        }

        @Test
        void aManualCompactionOfAViewThatIsOnlyUnansweredInputFails() {
            buffer.addUserMessage("the only message");
            failures.recordFailure(buffer.getSessionId());

            final CompactionResult result = engine().compactNow(request(), null);

            assertThat(result.isFailure()).isTrue();
            assertThat(result.getError())
                    .hasValueSatisfying(error -> assertThat(error).hasMessageContaining("nothing to compact"));
            assertThat(summarizer.summarized).isEmpty();
            assertThat(summarizer.installed).isEmpty();
            assertThat(buffer.getViewState().isEmpty()).isTrue();
            assertThat(failures.get(buffer.getSessionId())).as("a no-op does not reset the breaker").isEqualTo(1);
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
            buffer.addMessage(Message.assistant("read it"));
            buffer.addUserMessage("latest");
            final DefaultContextEngine engine = DefaultContextEngine.builder().compactionGuard(guard)
                    .compactionEngine(summarizer)
                    .recoveryStrategy((messages, error) -> PromptSizeRecoveryDecision
                            .retry(List.of(messages.get(0), messages.get(3), messages.get(4)), "dropped the pair"))
                    .build();

            assertThat(engine.recover(request(), new LlmPromptTooLongException("too long"))).isPresent();
            assertThat(buffer.getViewState().getDroppedRanges()).containsExactly(SeqRange.of(1, 3));
        }

        @Test
        void aToolResultTheModelHasNotAnsweredIsNeverDropped() {
            buffer.addUserMessage("q");
            buffer.addMessage(Message.assistant("", List.of(ToolUse.of("t1", "Read", Map.of()))));
            buffer.addMessage(Message.toolUseResults(List.of(ToolUseResult.success("t1", "body"))));
            buffer.addUserMessage("latest");
            final DefaultContextEngine engine = DefaultContextEngine.builder().compactionGuard(guard)
                    .compactionEngine(summarizer).recoveryStrategy((messages, error) -> PromptSizeRecoveryDecision
                            .retry(List.of(messages.get(0), messages.get(3)), "dropped the pair"))
                    .build();

            assertThat(engine.recover(request(), new LlmPromptTooLongException("too long"))).isEmpty();
            assertThat(buffer.getViewState().isEmpty()).isTrue();
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
            final CompactionGuard custom = (memory, model, hookRegistry) -> CompactionDecision.none();

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
            final CompactionGuard rewriting = (memory, model, hookRegistry) -> {
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
            // Answered, so the compaction below summarizes the long message too (an unanswered one is left verbatim).
            buffer.addAssistantMessage("answer");
            engine().prepare(request());
            assertThat(buffer.getViewState().getSummarySpan())
                    .hasValueSatisfying(span -> assertThat(span.getRange()).isEqualTo(SeqRange.of(0, 4)));
            buffer.addUserMessage("after the summary");
        }

        @Test
        void theViewIsSentAndNothingIsCompactedInPlace() {
            summarizedLog();
            final CompactionGuard rewriting = (memory, model, hookRegistry) -> {
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
            final CompactionGuard custom = (memory, model, hookRegistry) -> CompactionDecision.none();

            final CompactionResult result = inPlaceOnly(custom).compactNow(request(), null);

            assertThat(result.isFailure()).isTrue();
            assertThat(result.getError()).hasValueSatisfying(
                    error -> assertThat(error).hasMessageContaining(DefaultContextEngine.IN_PLACE_REFUSED));
            assertThat(buffer.getViewState().getSummarySpan()).isPresent();
        }

        @Test
        void recoveryDropsFromTheViewInsteadOfReplacingTheBuffer() {
            summarizedLog();
            final CompactionGuard custom = (memory, model, hookRegistry) -> CompactionDecision.none();
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
            final CompactionGuard rewriting = (memory, model, hookRegistry) -> {
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
        private String text = "SUMMARY";
        private int failFromCall = Integer.MAX_VALUE;
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
            return fail || summarized.size() >= failFromCall
                    ? CompactionResult.failure(new IllegalStateException("provider down"), metadata)
                    : CompactionResult.success(text, metadata);
        }

        @Override
        public void summaryInstalled(SummaryRequest request, CompactionResult result,
                TranscriptBuffer transcriptBuffer) {
            installed.add(result);
            onInstalled.accept(transcriptBuffer);
        }
    }
}
