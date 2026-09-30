package at.aimon.core.agent.compact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.Environment;
import at.aimon.core.agent.ExecutionId;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.hook.DefaultHookExecutionManager;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.HookEventType;
import at.aimon.core.hook.event.PostCompactContext;
import at.aimon.core.hook.event.PostCompactHook;
import at.aimon.core.hook.event.PreCompactContext;
import at.aimon.core.hook.event.PreCompactHook;
import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.token.HeuristicTokenEstimator;

/**
 * Pins {@link CompactionEngine#summarize}: the summary half of a compaction with nothing installed anywhere.
 */
class DefaultCompactionEngineSummarizeTest {

    private static final LlmModel MODEL = LlmModel.builder().name("test-model").build();

    private DefaultHookRegistry hookRegistry;
    private RecordingClient client;
    private DefaultCompactionEngine engine;

    @BeforeEach
    void setUp() {
        hookRegistry = new DefaultHookRegistry();
        client = new RecordingClient("the summary");
        engine = DefaultCompactionEngine.withDefaults(client, new HeuristicTokenEstimator(),
                new DefaultHookExecutionManager());
    }

    private SummaryRequest.Builder request(List<Message> messages) {
        return SummaryRequest.builder().messages(messages).systemPrompt("system").sessionId(SessionId.of("s-1"))
                .trigger(CompactionTrigger.AUTO).model(MODEL).hookRegistry(hookRegistry)
                .environment(Environment.createDefault());
    }

    @Test
    void theDefaultEngineAdvertisesSummarize() {
        assertThat(engine.supportsSummarize()).isTrue();
    }

    @Test
    void anEngineWrittenBeforeSummarizeReportsItCannot() {
        final CompactionEngine legacy = request -> {
            throw new AssertionError("not called");
        };

        assertThat(legacy.supportsSummarize()).isFalse();
        assertThatThrownBy(() -> legacy.summarize(request(List.of(Message.user("x"))).build()))
                .isInstanceOf(UnsupportedOperationException.class).hasMessageContaining("supportsSummarize");
    }

    @Test
    void summarizesExactlyTheGivenMessagesAndReportsTheSummary() {
        final List<Message> messages = List.of(Message.user("first"), Message.assistant("one", List.of()));

        final CompactionResult result = engine.summarize(request(messages).customInstructions("keep names").build());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getSummaryText()).hasValue("the summary");
        assertThat(client.lastMessages.get()).hasSize(3);
        assertThat(client.lastMessages.get().subList(0, 2)).isEqualTo(messages);
        assertThat(client.lastMessages.get().get(2).getContent()).isEqualTo(DefaultCompactionEngine.SUMMARIZE_NOTE);
        assertThat(client.lastSystemPrompt.get()).contains("keep names");
        assertThat(client.lastMetadata.get().getFeature()).hasValue(LlmCallMetadata.Feature.COMPACTION);
        assertThat(client.lastMetadata.get().getTraceId()).hasValue("s-1");
        assertThat(result.getMetadata().getMessagesSummarized()).isEqualTo(2);
        assertThat(result.getMetadata().getPreCompactTokenCount()).isPositive();
        assertThat(result.getMetadata().getPostCompactTokenCount()).isZero();
    }

    @Test
    void firesPreCompactWithTheCallersIdentityAndNoPostCompact() {
        final AtomicReference<PreCompactContext> pre = new AtomicReference<>();
        final List<PostCompactContext> post = new ArrayList<>();
        hookRegistry.register(HookEventType.PRE_COMPACT, (PreCompactHook) context -> {
            pre.set(context);
            return HookResult.success();
        });
        hookRegistry.register(HookEventType.POST_COMPACT, (PostCompactHook) context -> {
            post.add(context);
            return HookResult.success();
        });
        final ExecutionId executionId = ExecutionId.generate("subagent:researcher");

        engine.summarize(request(List.of(Message.user("x"))).executionId(executionId).build());

        assertThat(pre.get().getExecutionId()).hasValue(executionId);
        assertThat(pre.get().getSessionIdValue()).isEmpty();
        assertThat(post).as("PostCompact needs the installed state, which only the caller produces").isEmpty();
    }

    @Test
    void anAutoSummaryBlockedByAHookFails() {
        hookRegistry.register(HookEventType.PRE_COMPACT, (PreCompactHook) context -> HookResult.block("no"));

        final CompactionResult result = engine.summarize(request(List.of(Message.user("x"))).build());

        assertThat(result.isFailure()).isTrue();
        assertThat(result.getError()).containsInstanceOf(CompactionBlockedByHookException.class);
        assertThat(client.lastMessages.get()).isNull();
    }

    @Test
    void aBlankSummaryFails() {
        final DefaultCompactionEngine blank = DefaultCompactionEngine.withDefaults(new RecordingClient("  "),
                new HeuristicTokenEstimator(), new DefaultHookExecutionManager());

        final CompactionResult result = blank.summarize(request(List.of(Message.user("x"))).build());

        assertThat(result.isFailure()).isTrue();
        assertThat(result.getError().orElseThrow()).hasMessageContaining("empty");
    }

    @Test
    void aSummaryRequestedFromInsideTheSummaryCallIsRefusedAsReentrant() {
        // Hooks run on the hook executor's thread, out of the thread-local guard's sight; the summary call runs on the
        // caller's thread, which is where a nested compaction would actually re-enter.
        final AtomicReference<CompactionResult> nested = new AtomicReference<>();
        final AtomicReference<DefaultCompactionEngine> self = new AtomicReference<>();
        final LlmClient reentering = new RecordingClient("outer summary") {
            @Override
            public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                    LlmModel modelConfig, LlmCallMetadata metadata) {
                if (nested.get() == null) {
                    nested.set(self.get().summarize(request(List.of(Message.user("y"))).build()));
                }
                return super.sendMessage(systemPrompt, messages, tools, modelConfig, metadata);
            }
        };
        self.set(DefaultCompactionEngine.withDefaults(reentering, new HeuristicTokenEstimator(),
                new DefaultHookExecutionManager()));

        final CompactionResult outer = self.get().summarize(request(List.of(Message.user("x"))).build());

        assertThat(outer.isSuccess()).isTrue();
        assertThat(nested.get().getError()).containsInstanceOf(CompactionReentrancyException.class);
    }

    @Test
    void requestCopiesItsMessagesAndDefaultsItsSystemPrompt() {
        final List<Message> source = new ArrayList<>(List.of(Message.user("x")));
        final SummaryRequest request = SummaryRequest.builder().messages(source).sessionId(SessionId.of("s"))
                .trigger(CompactionTrigger.MANUAL).model(MODEL).hookRegistry(hookRegistry)
                .environment(Environment.createDefault()).build();
        source.add(Message.user("y"));

        assertThat(request.getMessages()).hasSize(1);
        assertThat(request.getSystemPrompt()).isEmpty();
        assertThat(request.getExecutionId()).isEmpty();
    }

    /** Records the summary call and answers with a fixed text. */
    @Test
    void aRollingSummaryAsksForTheTenSectionsAndUpdatesThePreviousSummary() {
        final CompactionResult result = engine.summarize(request(List.of(Message.user("new work"))).rolling(true)
                .previousSummary("PREVIOUS SUMMARY TEXT").targetSummaryTokens(640).build());

        assertThat(result.isSuccess()).isTrue();
        assertThat(client.lastSystemPrompt.get()).contains("Key decisions and constraints").contains("cumulative")
                .contains("Update the previous summary").contains("PREVIOUS SUMMARY TEXT").contains("about 640 tokens");
        assertThat(client.lastMessages.get()).extracting(Message::getContent).containsExactly("new work");
    }

    @Test
    void aFirstRollingSummaryHasNoPreviousSummaryToUpdate() {
        engine.summarize(request(List.of(Message.user("work"))).rolling(true).build());

        assertThat(client.lastSystemPrompt.get()).contains("Key decisions and constraints")
                .doesNotContain("Update the previous summary");
    }

    @Test
    void aPreviousSummaryWithoutRollingIsRefused() {
        assertThatThrownBy(() -> request(List.of(Message.user("x"))).previousSummary("p").build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static class RecordingClient implements LlmClient {
        final AtomicReference<List<Message>> lastMessages = new AtomicReference<>();
        final AtomicReference<String> lastSystemPrompt = new AtomicReference<>();
        final AtomicReference<LlmCallMetadata> lastMetadata = new AtomicReference<>();
        private final String answer;

        RecordingClient(String answer) {
            this.answer = answer;
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            return sendMessage(systemPrompt, messages, tools, modelConfig, LlmCallMetadata.empty());
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig, LlmCallMetadata metadata) {
            lastSystemPrompt.set(systemPrompt);
            lastMessages.set(messages);
            lastMetadata.set(metadata);
            return LlmResponse.text(answer);
        }

        @Override
        public String getProviderName() {
            return "stub";
        }
    }
}
