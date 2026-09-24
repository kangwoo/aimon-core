package at.aimon.core.agent.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.Environment;
import at.aimon.core.agent.compact.CompactBoundary;
import at.aimon.core.agent.compact.CompactionContendedException;
import at.aimon.core.agent.compact.CompactionDecision;
import at.aimon.core.agent.compact.CompactionEngine;
import at.aimon.core.agent.compact.CompactionKind;
import at.aimon.core.agent.compact.CompactionMetadata;
import at.aimon.core.agent.compact.CompactionRequest;
import at.aimon.core.agent.compact.CompactionResult;
import at.aimon.core.agent.compact.CompactionTrigger;
import at.aimon.core.agent.compact.DefaultPromptSizeRecoveryStrategy;
import at.aimon.core.agent.compact.InMemoryCompactionFailureStore;
import at.aimon.core.agent.compact.SummaryRequest;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.store.SegmentId;
import at.aimon.core.agent.session.transcript.LogOrigin;
import at.aimon.core.agent.session.transcript.SeqRange;
import at.aimon.core.agent.session.transcript.SessionLogFormat;
import at.aimon.core.agent.session.transcript.SessionLogManifestEntry;
import at.aimon.core.agent.session.transcript.SummarySpan;
import at.aimon.core.agent.session.transcript.TranscriptBuffer;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.llm.InMemoryModelContextWindowRegistry;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ModelContextLimits;
import at.aimon.core.llm.Role;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;
import at.aimon.core.llm.exception.LlmPromptTooLongException;
import at.aimon.core.llm.token.TokenEstimator;

/**
 * {@link RollingContextEngine}: head and tail verbatim, one widening summary span, prune before summary, and the
 * fallbacks to the default engine (context-engine §5).
 *
 * <p>
 * Sizes use a one-character-per-token estimator. With the limits below the effective window is 1000, so: rolling auto
 * 600, warning 500, blocking 950; summary budget 80, tail budget 200 (half: 100), minimum tail 50, head cap 50.
 */
@SuppressWarnings("deprecation") // the version-1 fallback reaches the deprecated compact() on purpose
class RollingContextEngineTest {

    private static final LlmModel MODEL = LlmModel.builder().name("tiny").build();

    private static final ModelContextLimits LIMITS = ModelContextLimits.builder().contextWindow(1200)
            .reservedOutputTokens(200).autoCompactBuffer(100).warningBuffer(100).blockingBuffer(50).build();

    private final CharEstimator estimator = new CharEstimator();
    private final InMemoryCompactionFailureStore failures = new InMemoryCompactionFailureStore();
    private SummarizingEngine summarizer;
    private TranscriptBuffer buffer;

    @BeforeEach
    void setUp() {
        summarizer = new SummarizingEngine();
        buffer = new TranscriptBuffer(SessionId.of("s-1"));
        buffer.requireFormat(SessionLogFormat.V2);
    }

    private RollingContextEngine engine() {
        return RollingContextEngine.builder().compactionEngine(summarizer)
                .modelContextWindowRegistry(InMemoryModelContextWindowRegistry.builder().defaultLimits(LIMITS).build())
                .tokenEstimator(estimator).failureStore(failures)
                .recoveryStrategy(new DefaultPromptSizeRecoveryStrategy()).build();
    }

    private ContextRequest request() {
        return request(null, false);
    }

    private ContextRequest request(String systemPrompt, boolean budgetForced) {
        return ContextRequest.builder().transcriptBuffer(buffer).systemPrompt(systemPrompt).model(MODEL)
                .hookRegistry(new DefaultHookRegistry()).environment(Environment.createDefault())
                .budgetForced(budgetForced).build();
    }

    /** seq 0 = "goal", then {@code count} messages of {@code size} characters, alternating assistant / user. */
    private void conversation(int count, int size) {
        buffer.addUserMessage("goal");
        for (int i = 1; i <= count; i++) {
            if (i % 2 == 1) {
                buffer.addAssistantMessage("a".repeat(size));
            } else {
                buffer.addUserMessage("u".repeat(size));
            }
        }
    }

    private int viewSize() {
        return estimator.estimate(null, ViewProjection.of(buffer.getLogState()).getMessages());
    }

    @Nested
    class Rolling {

        @Test
        void underTheThresholdNothingHappens() {
            conversation(4, 60);

            final ContextDecision decision = engine().prepare(request());

            assertThat(decision.getAction()).isEqualTo(CompactionDecision.Action.NONE);
            assertThat(decision.getView().getMessages()).isEqualTo(buffer.getMessages());
            assertThat(summarizer.summarized).isEmpty();
        }

        @Test
        void theMiddleIsSummarizedWhileHeadAndTailStayVerbatim() {
            conversation(10, 60); // 604 tokens: past rolling auto (600), far from the default engine's 900
            final List<Message> logBefore = buffer.getMessages();

            final ContextDecision decision = engine().prepare(request());

            assertThat(decision.getAction()).isEqualTo(CompactionDecision.Action.COMPACT);
            assertThat(buffer.getMessages()).as("the log is append-only").isEqualTo(logBefore);
            // tail budget 200 over 60-token messages: seqs 8..10 (180), and seq 8 is a user message
            assertThat(buffer.getViewState().getSummarySpan())
                    .hasValueSatisfying(span -> assertThat(span.getRange()).isEqualTo(SeqRange.of(1, 8)));
            final List<Message> view = decision.getView().getMessages();
            assertThat(view).hasSize(6);
            assertThat(view.get(0)).as("the head").isSameAs(logBefore.get(0));
            assertThat(view.get(1).getContent()).startsWith(CompactBoundary.BOUNDARY_OPEN_PREFIX);
            assertThat(view.get(2).getContent()).contains("SUMMARY-1");
            assertThat(view.subList(3, 6)).as("the tail").isEqualTo(logBefore.subList(8, 11));

            final SummaryRequest summary = summarizer.summarized.get(0);
            assertThat(summary.isRolling()).isTrue();
            assertThat(summary.getPreviousSummary()).isEmpty();
            assertThat(summary.getTargetSummaryTokens()).isEqualTo(80);
            assertThat(summary.getTrigger()).isEqualTo(CompactionTrigger.AUTO);
            assertThat(summary.getMessages()).hasSize(9);
            assertThat(summary.getMessages().get(0).getContent())
                    .as("an absorbed range starting with an assistant message gets a user message in front")
                    .isEqualTo(RollingContextEngine.CONTINUATION_NOTE);
            assertThat(summary.getMessages().subList(1, 8)).isEqualTo(logBefore.subList(1, 8));
            assertThat(summary.getMessages().get(8)).as("and one after, since it ends with an assistant message")
                    .satisfies(last -> {
                        assertThat(last.getRole()).isEqualTo(Role.USER);
                        assertThat(last.getContent()).isEqualTo(RollingContextEngine.SUMMARIZE_NOTE);
                    });

            assertThat(decision.getCompactionMetadata()).hasValueSatisfying(metadata -> {
                assertThat(metadata.getKind()).isEqualTo(CompactionKind.ROLLING);
                assertThat(metadata.getAbsorbedFromSeq()).hasValue(1);
                assertThat(metadata.getAbsorbedToSeq()).hasValue(8);
                assertThat(metadata.getHeadTokens()).isEqualTo(4);
                assertThat(metadata.getTailTokens()).isEqualTo(180);
                assertThat(metadata.getSpanTokens()).isPositive();
                assertThat(metadata.getSummaryTokens()).isEqualTo("SUMMARY-1".length());
            });
            assertThat(summarizer.installed).hasSize(1);
        }

        @Test
        void anAssistantMessageStartIsALegalTail() {
            conversation(12, 50); // tail 200 exactly at seq 9, an assistant message; no user cut within ±10%

            engine().prepare(request());

            assertThat(buffer.getViewState().getSummarySpan())
                    .hasValueSatisfying(span -> assertThat(span.getToSeq()).isEqualTo(9));
            assertThat(buffer.getMessages().get(9).getRole()).isEqualTo(Role.ASSISTANT);
        }

        @Test
        void theTailSnapsToAUserMessageWithinTenPercentOfTheBudget() {
            buffer.addUserMessage("goal");
            for (int i = 0; i < 3; i++) {
                buffer.addUserMessage("u".repeat(100));
                buffer.addAssistantMessage("a".repeat(100));
            }
            buffer.addUserMessage("u".repeat(10)); // seq 7: tail from here is 205
            buffer.addAssistantMessage("a".repeat(195)); // seq 8: tail from here is 195, the budget's own choice

            engine().prepare(request());

            assertThat(buffer.getViewState().getSummarySpan())
                    .hasValueSatisfying(span -> assertThat(span.getToSeq()).isEqualTo(7));
        }

        @Test
        void aSecondCompactionUpdatesThePreviousSummaryAndWidensTheSpan() {
            conversation(10, 60);
            final RollingContextEngine engine = engine();
            engine.prepare(request());
            int next = 0;
            while (viewSize() < 610) {
                buffer.addUserMessage("n".repeat(60) + next++);
            }

            final ContextDecision decision = engine.prepare(request());

            assertThat(decision.getAction()).isEqualTo(CompactionDecision.Action.COMPACT);
            final SummaryRequest second = summarizer.summarized.get(1);
            assertThat(second.getPreviousSummary()).hasValue("SUMMARY-1");
            assertThat(second.getMessages().get(0)).as("only what the span newly absorbs: from its old end on")
                    .isSameAs(buffer.getMessages().get(8));
            assertThat(buffer.getViewState().getSummarySpan()).hasValueSatisfying(span -> {
                assertThat(span.getFromSeq()).as("the span keeps its start").isEqualTo(1);
                assertThat(span.getToSeq()).isGreaterThan(8);
                assertThat(span.getSummaryText()).isEqualTo("SUMMARY-2");
                assertThat(span.getMessagesSummarized()).isEqualTo(7 + second.getMessages().stream()
                        .filter(m -> !RollingContextEngine.SUMMARIZE_NOTE.equals(m.getContent())).count());
            });
        }

        @Test
        void aSummaryRequestEndingWithAUserMessageGetsNoClosingNote() {
            buffer.addUserMessage("goal");
            for (int i = 1; i <= 10; i++) {
                // Every message a user one, so the absorbed range cannot end with an assistant message.
                buffer.addUserMessage("u".repeat(60));
            }

            engine().prepare(request());

            final List<Message> sent = summarizer.summarized.get(0).getMessages();
            assertThat(sent.get(sent.size() - 1).getContent()).isNotEqualTo(RollingContextEngine.SUMMARIZE_NOTE);
            assertThat(sent).extracting(Message::getRole).containsOnly(Role.USER);
        }

        @Test
        void aSummaryRequestEndingWithToolResultsGetsNoClosingNote() {
            buffer.addUserMessage("goal");
            for (int i = 0; i < 4; i++) {
                buffer.addMessage(Message.assistant("", List.of(ToolUse.of("t" + i, "Read", Map.of()))));
                buffer.addMessage(Message.toolUseResults(List.of(ToolUseResult.success("t" + i, "r".repeat(100)))));
            }
            buffer.addUserMessage("u".repeat(200));

            engine().prepare(request());

            final List<Message> sent = summarizer.summarized.get(0).getMessages();
            assertThat(sent.get(sent.size() - 1).getRole()).as("tool results are sent in the user's role")
                    .isEqualTo(Role.TOOL);
        }

        @Test
        void aBudgetForcedCallCompactsFromTheWarningBand() {
            conversation(9, 60); // 544: over warning (500), under rolling auto (600)

            assertThat(engine().prepare(request()).getAction()).isEqualTo(CompactionDecision.Action.WARN);
            assertThat(engine().prepare(request(null, true)).getAction()).isEqualTo(CompactionDecision.Action.COMPACT);
        }
    }

    @Nested
    class Head {

        @Test
        void syntheticEntriesBeforeTheFirstUserMessageBelongToTheHead() {
            buffer.addMessage(Message.user("<system-reminder>" + "r".repeat(100) + "</system-reminder>"),
                    LogOrigin.SYNTHETIC);
            buffer.addUserMessage("goal");
            for (int i = 0; i < 10; i++) {
                buffer.addMessage(i % 2 == 0 ? Message.assistant("a".repeat(60)) : Message.user("u".repeat(60)));
            }

            engine().prepare(request());

            assertThat(buffer.getViewState().getSummarySpan())
                    .hasValueSatisfying(span -> assertThat(span.getFromSeq()).isEqualTo(2));
        }

        @Test
        void aHeadOverItsCapIsEmpty() {
            buffer.addUserMessage("g".repeat(60)); // over the 50-token head cap
            for (int i = 1; i <= 10; i++) {
                buffer.addMessage(i % 2 == 1 ? Message.assistant("a".repeat(60)) : Message.user("u".repeat(60)));
            }

            engine().prepare(request());

            assertThat(buffer.getViewState().getSummarySpan())
                    .hasValueSatisfying(span -> assertThat(span.getFromSeq()).isZero());
        }

        @Test
        void aSealedRangeBeforeTheFirstCarriedUserMessageEmptiesTheHead() {
            // The real first user message was dropped by a recovery and then sealed: it is no longer carried, and the
            // first carried user message is a later one. That one must not become the head.
            buffer.addUserMessage("goal");
            buffer.addAssistantMessage("a".repeat(10));
            buffer.addUserMessage("later");
            for (int i = 0; i < 10; i++) {
                buffer.addMessage(i % 2 == 0 ? Message.assistant("a".repeat(60)) : Message.user("u".repeat(60)));
            }
            buffer.dropFromView(0, 2);
            buffer.seal(SessionLogManifestEntry.builder().fromSeq(0).toSeq(2).segmentId(SegmentId.generate())
                    .contentHash("sha256:x").entryCount(2).build());

            engine().prepare(request());

            assertThat(buffer.getViewState().getSummarySpan()).hasValueSatisfying(
                    span -> assertThat(span.getFromSeq()).as("no head: the span starts at the floor").isZero());
        }

        @Test
        void aDroppedButCarriedFirstUserMessageStillAnchorsTheHead() {
            // Dropped but not sealed: the log still carries it, so the head is anchored on it by seq.
            buffer.addUserMessage("goal");
            buffer.addAssistantMessage("a".repeat(10));
            buffer.addUserMessage("later");
            for (int i = 0; i < 10; i++) {
                buffer.addMessage(i % 2 == 0 ? Message.assistant("a".repeat(60)) : Message.user("u".repeat(60)));
            }
            buffer.dropFromView(0, 1);

            engine().prepare(request());

            assertThat(buffer.getViewState().getSummarySpan())
                    .hasValueSatisfying(span -> assertThat(span.getFromSeq()).isEqualTo(1));
        }

        @Test
        void aLogMigratedFromVersionOneHasNoHead() {
            buffer.addUserMessage(CompactBoundary.BOUNDARY_OPEN_PREFIX + "old]] compacted before the upgrade");
            buffer.addUserMessage("old summary");
            for (int i = 0; i < 10; i++) {
                buffer.addMessage(i % 2 == 0 ? Message.assistant("a".repeat(60)) : Message.user("u".repeat(60)));
            }

            engine().prepare(request());

            assertThat(buffer.getViewState().getSummarySpan())
                    .hasValueSatisfying(span -> assertThat(span.getFromSeq()).isZero());
        }
    }

    @Nested
    class Prune {

        @Test
        void largeToolResultsAreElidedWhenThatAloneIsEnough() {
            buffer.addUserMessage("goal");
            buffer.addMessage(Message.assistant("", List.of(ToolUse.of("t1", "Read", Map.of()))));
            buffer.addMessage(Message.toolUseResults(List.of(ToolUseResult.success("t1", "x".repeat(700)))));
            buffer.addAssistantMessage("a".repeat(60));
            buffer.addUserMessage("u".repeat(60));
            buffer.addAssistantMessage("a".repeat(60));
            buffer.addUserMessage("u".repeat(60));
            final List<Message> logBefore = buffer.getMessages();

            final ContextDecision decision = engine().prepare(request());

            assertThat(decision.getAction()).isEqualTo(CompactionDecision.Action.COMPACT);
            assertThat(decision.getCompactionMetadata())
                    .hasValueSatisfying(metadata -> assertThat(metadata.getKind()).isEqualTo(CompactionKind.PRUNE));
            assertThat(summarizer.summarized).as("no summary needed").isEmpty();
            assertThat(buffer.getViewState().getElisions()).containsOnlyKeys(2L);
            assertThat(decision.getView().getMessages().get(2).getToolUseResults()).singleElement()
                    .satisfies(result -> {
                        assertThat(result.getToolUseId()).isEqualTo("t1");
                        assertThat(result.getContent()).isEqualTo("[tool result elided: seq=2]");
                    });
            assertThat(buffer.getMessages()).as("the original stays in the log").isEqualTo(logBefore);
        }

        @Test
        void aPrunedResultReachesTheSummaryAsItsOriginal() {
            final String fact = "IMPORTANT-FACT " + "x".repeat(700);
            buffer.addUserMessage("goal");
            buffer.addMessage(Message.assistant("", List.of(ToolUse.of("t1", "Read", Map.of()))));
            buffer.addMessage(Message.toolUseResults(List.of(ToolUseResult.success("t1", fact))));
            buffer.addAssistantMessage("a".repeat(60));
            buffer.addUserMessage("u".repeat(60));
            buffer.addAssistantMessage("a".repeat(60));
            buffer.addUserMessage("u".repeat(60));
            final RollingContextEngine engine = engine();
            engine.prepare(request());
            assertThat(buffer.getViewState().getElisions()).containsOnlyKeys(2L);

            for (int i = 0; i < 8; i++) {
                if (i % 2 == 0) {
                    buffer.addAssistantMessage("a".repeat(100));
                } else {
                    buffer.addUserMessage("u".repeat(100));
                }
            }
            engine.prepare(request());

            assertThat(summarizer.summarized).hasSize(1);
            assertThat(summarizer.summarized.get(0).getMessages()).flatExtracting(Message::getToolUseResults)
                    .extracting(ToolUseResult::getContent).as("context-engine §5.4: the originals, not the view")
                    .containsExactly(fact);
        }
    }

    @Nested
    class Retreat {

        private void heldSpanWithSummary(int summaryLength) {
            buffer.addUserMessage("goal");
            buffer.addAssistantMessage("a");
            buffer.summarizeView(SummarySpan.builder().fromSeq(1).toSeq(2).summaryText("s".repeat(summaryLength))
                    .boundaryId("b-1").trigger("AUTO").build());
        }

        @Test
        void whenNoCutCanHelpTheEngineWarnsInsteadOfCompacting() {
            heldSpanWithSummary(520);
            assertThat(viewSize()).isBetween(600, 949);

            final ContextDecision decision = engine().prepare(request());

            assertThat(decision.getAction()).isEqualTo(CompactionDecision.Action.WARN);
            assertThat(decision.getCompactionMetadata())
                    .hasValueSatisfying(metadata -> assertThat(metadata.getKind()).isEqualTo(CompactionKind.FALLBACK));
            assertThat(summarizer.summarized).isEmpty();
        }

        @Test
        void atTheBlockingLimitTheHeadIsAbsorbedToo() {
            heldSpanWithSummary(900);
            assertThat(viewSize()).isGreaterThanOrEqualTo(950);

            final ContextDecision decision = engine().prepare(request());

            assertThat(decision.getAction()).isEqualTo(CompactionDecision.Action.COMPACT);
            assertThat(buffer.getViewState().getSummarySpan())
                    .hasValueSatisfying(span -> assertThat(span.getRange()).isEqualTo(SeqRange.of(0, 2)));
            final SummaryRequest summary = summarizer.summarized.get(0);
            assertThat(summary.getPreviousSummary()).hasValue("s".repeat(900));
            assertThat(summary.getMessages()).extracting(Message::getContent).containsExactly("goal");
        }
    }

    @Nested
    class Fallback {

        @Test
        void aModelThatCannotSustainRollingIsCompactedAsTheDefaultEngineDoes() {
            conversation(9, 60); // 544, plus a 400-token system prompt: 944, past the default engine's auto (900)
            final String systemPrompt = "p".repeat(400); // system + head + 80 + 50 = 534 >= warning 500

            final ContextDecision decision = engine().prepare(request(systemPrompt, false));

            assertThat(decision.getAction()).isEqualTo(CompactionDecision.Action.COMPACT);
            assertThat(summarizer.summarized).singleElement()
                    .satisfies(summary -> assertThat(summary.isRolling()).isFalse());
            assertThat(buffer.getViewState().getSummarySpan())
                    .hasValueSatisfying(span -> assertThat(span.getFromSeq()).as("a full compaction").isZero());
        }

        @Test
        void aVersionOneLogIsCompactedInPlace() {
            buffer = new TranscriptBuffer(SessionId.of("s-v1"));
            conversation(16, 60);

            engine().prepare(request());

            assertThat(summarizer.compacted).isEqualTo(1);
            assertThat(summarizer.summarized).isEmpty();
        }

        @Test
        void recoveryIsTheDefaultEnginesDrop() {
            conversation(4, 10);

            final var recovered = engine().recover(request(), new LlmPromptTooLongException("too long"));

            assertThat(recovered).isPresent();
            assertThat(buffer.getViewState().getDroppedRanges()).containsExactly(SeqRange.of(0, 1));
        }
    }

    @Nested
    class CircuitBreaker {

        @Test
        void consecutiveFailuresStopAutoCompaction() {
            summarizer.fail = true;
            conversation(10, 60);
            final RollingContextEngine engine = engine();

            for (int i = 0; i < 3; i++) {
                assertThat(engine.prepare(request()).getAction()).isEqualTo(CompactionDecision.Action.COMPACT);
            }
            final ContextDecision fourth = engine.prepare(request());

            assertThat(fourth.getAction()).isEqualTo(CompactionDecision.Action.NONE);
            assertThat(fourth.getReason()).contains("circuit breaker");
            assertThat(summarizer.summarized).hasSize(3);
        }
    }

    @Nested
    class ThrowingEngine {

        @Test
        void aSummarizeThatThrowsIsAFailedCompactionCountedByTheBreaker() {
            summarizer.throwOnSummarize = true;
            conversation(10, 60);
            final RollingContextEngine engine = engine();

            for (int i = 0; i < 3; i++) {
                final ContextDecision decision = engine.prepare(request());
                assertThat(decision.getAction()).isEqualTo(CompactionDecision.Action.COMPACT);
            }
            assertThat(failures.get(buffer.getSessionId())).isEqualTo(3);
            assertThat(engine.prepare(request()).getReason()).contains("circuit breaker");
            assertThat(buffer.getViewState().getSummarySpan()).isEmpty();
        }

        @Test
        void aSummaryInstalledThatThrowsKeepsTheSpanAndReportsAFailure() {
            summarizer.throwOnInstall = true;
            conversation(10, 60);

            final ContextDecision decision = engine().prepare(request());

            assertThat(decision.getAction()).isEqualTo(CompactionDecision.Action.COMPACT);
            assertThat(decision.getReason()).contains("failed");
            assertThat(buffer.getViewState().getSummarySpan()).as("the span was recorded before the hook ran")
                    .isPresent();
            assertThat(decision.getView().getMessages()).as("and the view already shows it")
                    .anySatisfy(m -> assertThat(m.getContent()).contains("SUMMARY-1"));
            assertThat(failures.get(buffer.getSessionId())).isEqualTo(1);
        }

        @Test
        void compactNowReturnsAFailureInsteadOfThrowing() {
            summarizer.throwOnSummarize = true;
            conversation(6, 60);

            final CompactionResult result = engine().compactNow(request(), null);

            assertThat(result.isFailure()).isTrue();
            assertThat(result.getError()).containsInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    class CompactNow {

        @Test
        void compactsBelowTheThresholdWithTheUsersInstructions() {
            conversation(6, 60);
            failures.recordFailure(buffer.getSessionId());

            final CompactionResult result = engine().compactNow(request(), "keep the numbers");

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getMetadata().getKind()).isEqualTo(CompactionKind.ROLLING);
            assertThat(summarizer.summarized).singleElement().satisfies(summary -> {
                assertThat(summary.getTrigger()).isEqualTo(CompactionTrigger.MANUAL);
                assertThat(summary.getCustomInstructions()).hasValue("keep the numbers");
            });
            assertThat(buffer.getViewState().getSummarySpan()).isPresent();
            assertThat(failures.get(buffer.getSessionId())).as("the breaker is reset").isZero();
        }

        @Test
        void aConcurrentCompactionMakesItFailWithContention() throws Exception {
            conversation(10, 60);
            final RollingContextEngine engine = engine();
            final AtomicReference<CompactionResult> concurrent = new AtomicReference<>();
            summarizer.duringSummarize = () -> {
                try {
                    concurrent.set(CompletableFuture.supplyAsync(() -> engine.compactNow(request(), null)).get(5,
                            TimeUnit.SECONDS));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            };

            engine.prepare(request());

            assertThat(concurrent.get().isFailure()).isTrue();
            assertThat(concurrent.get().getError()).containsInstanceOf(CompactionContendedException.class);
        }

        @Test
        void anEmptyViewIsAFailureNotAnLlmCall() {
            final CompactionResult result = engine().compactNow(request(), null);

            assertThat(result.isFailure()).isTrue();
            assertThat(summarizer.summarized).isEmpty();
        }
    }

    @Nested
    class Construction {

        @Test
        void theVersionOneWriteModeIsRefused() {
            assertThatThrownBy(() -> RollingContextEngine.builder().compactionEngine(summarizer)
                    .modelContextWindowRegistry(InMemoryModelContextWindowRegistry.withDefaults())
                    .tokenEstimator(estimator).writeFormat(SessionLogFormat.V1).build())
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("version-1");
        }

        @Test
        void anEngineThatCannotSummarizeIsRefused() {
            final CompactionEngine legacy = request -> {
                throw new AssertionError();
            };

            assertThatThrownBy(() -> RollingContextEngine.builder().compactionEngine(legacy)
                    .modelContextWindowRegistry(InMemoryModelContextWindowRegistry.withDefaults())
                    .tokenEstimator(estimator).build()).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("summarize");
        }

        @Test
        void ratiosOutsideZeroToOneAreRefused() {
            assertThatThrownBy(() -> RollingContextEngine.builder().compactionEngine(summarizer)
                    .modelContextWindowRegistry(InMemoryModelContextWindowRegistry.withDefaults())
                    .tokenEstimator(estimator).tailTokenRatio(1.5).build())
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    /** One token per character of text, tool input and tool result; nothing for structure. */
    private static final class CharEstimator implements TokenEstimator {

        @Override
        public int estimate(String systemPrompt, List<Message> messages) {
            int total = estimateText(systemPrompt);
            for (Message message : messages) {
                total += estimateMessage(message);
            }
            return total;
        }

        @Override
        public int estimateMessage(Message message) {
            int total = estimateText(message.getContent());
            for (ToolUse toolUse : message.getToolUses()) {
                total += toolUse.getName().length();
            }
            for (ToolUseResult result : message.getToolUseResults()) {
                total += estimateText(result.getContent());
            }
            return total;
        }

        @Override
        public int estimateText(String text) {
            return text == null ? 0 : text.length();
        }
    }

    private static final class SummarizingEngine implements CompactionEngine {

        private final List<SummaryRequest> summarized = new ArrayList<>();
        private final List<CompactionResult> installed = new ArrayList<>();
        private int compacted;
        private boolean fail;
        private boolean throwOnSummarize;
        private boolean throwOnInstall;
        private Runnable duringSummarize = () -> {
        };

        @Override
        public CompactionResult compact(CompactionRequest request) {
            compacted++;
            final Instant now = Instant.now();
            return CompactionResult.failure(new IllegalStateException("in place"),
                    CompactionMetadata.builder().trigger(request.getTrigger()).startedAt(now).completedAt(now).build());
        }

        @Override
        public boolean supportsSummarize() {
            return true;
        }

        @Override
        public CompactionResult summarize(SummaryRequest request) {
            summarized.add(request);
            if (throwOnSummarize) {
                throw new IllegalStateException("custom engine blew up");
            }
            duringSummarize.run();
            final Instant now = Instant.now();
            final CompactionMetadata metadata = CompactionMetadata.builder().trigger(request.getTrigger())
                    .preCompactTokenCount(100).messagesSummarized(request.getMessages().size()).startedAt(now)
                    .completedAt(now).build();
            return fail
                    ? CompactionResult.failure(new IllegalStateException("provider down"), metadata)
                    : CompactionResult.success("SUMMARY-" + summarized.size(), metadata);
        }

        @Override
        public void summaryInstalled(SummaryRequest request, CompactionResult result,
                TranscriptBuffer transcriptBuffer) {
            installed.add(result);
            if (throwOnInstall) {
                throw new IllegalStateException("PostCompact hook blew up");
            }
        }
    }
}
