package at.aimon.core.agent.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import at.aimon.core.agent.compact.CompactionDecision;
import at.aimon.core.agent.compact.CompactionMetadata;
import at.aimon.core.agent.compact.DefaultCompactionEngine;
import at.aimon.core.agent.compact.DefaultCompactionGuard;
import at.aimon.core.agent.compact.DefaultPromptSizeRecoveryStrategy;
import at.aimon.core.agent.compact.InMemoryCompactionFailureStore;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.transcript.SessionLogFormat;
import at.aimon.core.agent.session.transcript.TranscriptBuffer;
import at.aimon.core.hook.DefaultHookExecutionManager;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.HookEventType;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.hook.event.PostCompactHook;
import at.aimon.core.hook.event.PreCompactHook;
import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.llm.InMemoryModelContextWindowRegistry;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ModelContextLimits;
import at.aimon.core.llm.token.HeuristicTokenEstimator;

/**
 * What the hooks see when the blocking-limit compaction has to summarize twice in one {@code prepare}: the real
 * compaction engine, real hook dispatch, a scripted model.
 *
 * <p>
 * The pass is two summary calls, and each is a compaction to the hooks: PreCompact fires before each (its feedback is
 * that call's instructions, its AUTO block is that call's veto) and PostCompact after each (what it appends is part
 * of the view the limit is then held against). What the caller gets back is one record for the pass.
 */
@DisplayName("DefaultContextEngine: the two-summary blocking-limit pass, as hooks see it")
class DefaultContextEngineTwoSummaryPassHooksTest {

    private static final LlmModel MODEL = LlmModel.builder().name("tiny").build();

    /** Auto-compact at 1200 estimated tokens, blocking at 1400. */
    private static final ModelContextLimits TINY = ModelContextLimits.builder().contextWindow(2000)
            .reservedOutputTokens(500).autoCompactBuffer(300).warningBuffer(200).blockingBuffer(100).build();

    private static final String ATTACHMENT = "re-attached file";

    @Test
    @SuppressWarnings("unchecked")
    void bothSummariesFireTheHooks_andTheCallerGetsOneRecordForThePass() throws Exception {
        final HeuristicTokenEstimator estimator = new HeuristicTokenEstimator();
        final LlmClient llm = mock(LlmClient.class);
        // The first summary is long enough to leave the view at the limit; the second is short.
        when(llm.sendMessage(anyString(), anyList(), anyList(), any(LlmModel.class), any(LlmCallMetadata.class)))
                .thenReturn(LlmResponse.of("S".repeat(800), List.of()), LlmResponse.of("short summary", List.of()));

        final List<Integer> preCompactSawMessages = new ArrayList<>();
        final List<CompactionMetadata> postCompactSaw = new ArrayList<>();
        final HookRegistry registry = new DefaultHookRegistry();
        registry.register(HookEventType.PRE_COMPACT, (PreCompactHook) context -> {
            preCompactSawMessages.add(context.getMessageCount());
            return HookResult.success();
        });
        registry.register(HookEventType.POST_COMPACT, (PostCompactHook) context -> {
            postCompactSaw.add(context.getCompactionMetadata());
            context.addSyntheticMessage(Message.user(ATTACHMENT));
            return HookResult.success();
        });

        final TranscriptBuffer buffer = new TranscriptBuffer(SessionId.of("s-1"), "system prompt");
        buffer.requireFormat(SessionLogFormat.V2);
        buffer.addUserMessage("x".repeat(600));
        buffer.addAssistantMessage("on it");
        buffer.addUserMessage("x".repeat(4500));

        try (DefaultHookExecutionManager hooks = DefaultHookExecutionManager.builder().build()) {
            final DefaultCompactionEngine compactionEngine = new DefaultCompactionEngine(llm, estimator, hooks);
            final DefaultCompactionGuard guard = new DefaultCompactionGuard(compactionEngine,
                    InMemoryModelContextWindowRegistry.builder().defaultLimits(TINY).build(), estimator,
                    DefaultCompactionGuard.DEFAULT_MAX_CONSECUTIVE_FAILURES,
                    DefaultCompactionGuard.DEFAULT_MAX_TRACKED_SESSIONS, new InMemoryCompactionFailureStore());
            final DefaultContextEngine engine = DefaultContextEngine.builder().compactionGuard(guard)
                    .compactionEngine(compactionEngine).recoveryStrategy(new DefaultPromptSizeRecoveryStrategy())
                    .tokenEstimator(estimator).writeFormat(SessionLogFormat.V2).build();
            final int viewBefore = guard.estimateTokens(buffer.getSystemPrompt(), buffer.getMessages());

            final ContextDecision decision = engine.prepare(
                    ContextRequest.builder().transcriptBuffer(buffer).model(MODEL).hookRegistry(registry).build());

            assertThat(decision.getAction()).isEqualTo(CompactionDecision.Action.COMPACT);

            // Two summary calls, each with its PreCompact and its PostCompact.
            final ArgumentCaptor<List<Message>> summarized = ArgumentCaptor.forClass(List.class);
            verify(llm, org.mockito.Mockito.times(2)).sendMessage(anyString(), summarized.capture(), anyList(),
                    any(LlmModel.class), any(LlmCallMetadata.class));
            assertThat(preCompactSawMessages).as("PreCompact fires before each summary call").containsExactly(2, 4);
            assertThat(postCompactSaw).as("PostCompact fires after each summary").hasSize(2);

            // What the first PostCompact attached was summarized by the second call; the second attached it again.
            assertThat(summarized.getAllValues().get(1)).extracting(Message::getContent).contains(ATTACHMENT);
            assertThat(decision.getView().getMessages()).extracting(Message::getContent).filteredOn(ATTACHMENT::equals)
                    .hasSize(1);
            assertThat(decision.getView().getMessages()).as("boundary, summary, the one live attachment").hasSize(3);

            // One record for the pass: where it started, where it ended, and that it took two summaries.
            final CompactionMetadata reported = decision.getCompactionMetadata().orElseThrow();
            assertThat(reported.getSummaryCalls()).isEqualTo(2);
            assertThat(reported.getPreCompactTokenCount()).isEqualTo(viewBefore);
            assertThat(reported.getMessagesSummarized()).isEqualTo(3);
            assertThat(reported.getStartedAt()).isEqualTo(postCompactSaw.get(0).getStartedAt());
            assertThat(reported.getPostCompactTokenCount())
                    .isEqualTo(guard.estimateTokens(buffer.getSystemPrompt(), decision.getView().getMessages()));
            assertThat(postCompactSaw.get(0).getSummaryCalls()).isEqualTo(1);
            assertThat(postCompactSaw.get(1).getSummaryCalls()).as("the last PostCompact is shown the pass")
                    .isEqualTo(2);
            assertThat(postCompactSaw.get(1).getPreCompactTokenCount()).isEqualTo(viewBefore);
        }
    }
}
