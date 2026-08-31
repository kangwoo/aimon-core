package at.aimon.core.agent.compact;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.Environment;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.transcript.TranscriptBuffer;
import at.aimon.core.hook.DefaultHookExecutionManager;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;
import at.aimon.core.llm.token.HeuristicTokenEstimator;

/**
 * Verifies {@link DefaultCompactionEngine} partial-compaction behavior (design §4.3).
 *
 * <p>
 * Covers: prefix-only summary preserves the tail verbatim, mid-range summary preserves both prefix and tail, the
 * summary LLM is shown only the in-range messages, range bounds are validated, and tool_use/tool_result coherency at
 * the cut points is enforced.
 */
class DefaultCompactionEnginePartialCompactionTest {

    private RecordingLlmClient llmClient;
    private DefaultHookExecutionManager hookExecutionManager;
    private HookRegistry hookRegistry;
    private Environment environment;
    private DefaultCompactionEngine engine;

    @BeforeEach
    void setUp() {
        llmClient = new RecordingLlmClient("partial summary");
        hookExecutionManager = new DefaultHookExecutionManager();
        hookRegistry = new DefaultHookRegistry();
        environment = Environment.createDefault();
        engine = DefaultCompactionEngine.withDefaults(llmClient, new HeuristicTokenEstimator(), hookExecutionManager);
    }

    @Test
    void noRangeStillCompactsFullConversation() {
        final TranscriptBuffer memory = new TranscriptBuffer(SessionId.generate());
        memory.addUserMessage("u1");
        memory.addAssistantMessage("a1");
        memory.addUserMessage("u2");
        memory.addAssistantMessage("a2");

        final CompactionResult result = engine.compact(baseRequest(memory).build());

        assertThat(result.isSuccess()).isTrue();
        assertThat(memory.getMessages()).hasSize(2); // boundary + summary
        assertThat(result.getMetadata().getMessagesSummarized()).isEqualTo(4);
        // LLM saw all 4 messages.
        assertThat(llmClient.lastMessages.get()).hasSize(4);
    }

    @Test
    void prefixCompactionKeepsTailVerbatim() {
        final TranscriptBuffer memory = new TranscriptBuffer(SessionId.generate());
        memory.addUserMessage("u1");
        memory.addAssistantMessage("a1");
        memory.addUserMessage("u2"); // kept
        memory.addAssistantMessage("a2"); // kept

        final CompactionRange range = CompactionRange.prefix(2);

        final CompactionResult result = engine.compact(baseRequest(memory).compactRange(range).build());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMetadata().getMessagesSummarized()).isEqualTo(2);
        // Resulting memory: [boundary, summary, u2, a2]
        final List<Message> after = memory.getMessages();
        assertThat(after).hasSize(4);
        assertThat(after.get(0).getContent()).contains(CompactBoundary.BOUNDARY_OPEN_PREFIX);
        assertThat(after.get(1).getContent()).contains(CompactBoundary.SUMMARY_OPEN_PREFIX).contains("partial summary");
        assertThat(after.get(2).getContent()).isEqualTo("u2");
        assertThat(after.get(3).getContent()).isEqualTo("a2");
        // LLM only saw the in-range portion.
        assertThat(llmClient.lastMessages.get()).hasSize(2);
        assertThat(llmClient.lastMessages.get().get(0).getContent()).isEqualTo("u1");
        assertThat(llmClient.lastMessages.get().get(1).getContent()).isEqualTo("a1");
    }

    @Test
    void midRangeCompactionPreservesPrefixAndTail() {
        final TranscriptBuffer memory = new TranscriptBuffer(SessionId.generate());
        memory.addUserMessage("u1"); // kept (prefix)
        memory.addAssistantMessage("a1"); // kept (prefix)
        memory.addUserMessage("u2"); // summarized
        memory.addAssistantMessage("a2"); // summarized
        memory.addUserMessage("u3"); // kept (tail)
        memory.addAssistantMessage("a3"); // kept (tail)

        final CompactionResult result = engine
                .compact(baseRequest(memory).compactRange(CompactionRange.of(2, 4)).build());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMetadata().getMessagesSummarized()).isEqualTo(2);
        final List<Message> after = memory.getMessages();
        // [u1, a1, boundary, summary, u3, a3] = 6 messages
        assertThat(after).hasSize(6);
        assertThat(after.get(0).getContent()).isEqualTo("u1");
        assertThat(after.get(1).getContent()).isEqualTo("a1");
        assertThat(after.get(2).getContent()).contains(CompactBoundary.BOUNDARY_OPEN_PREFIX);
        assertThat(after.get(3).getContent()).contains(CompactBoundary.SUMMARY_OPEN_PREFIX);
        assertThat(after.get(4).getContent()).isEqualTo("u3");
        assertThat(after.get(5).getContent()).isEqualTo("a3");
    }

    @Test
    void suffixOnlyCompactionKeepsPrefixVerbatim() {
        final TranscriptBuffer memory = new TranscriptBuffer(SessionId.generate());
        memory.addUserMessage("u1");
        memory.addAssistantMessage("a1");
        memory.addUserMessage("u2");
        memory.addAssistantMessage("a2");

        final CompactionResult result = engine
                .compact(baseRequest(memory).compactRange(CompactionRange.of(2, 4)).build());

        assertThat(result.isSuccess()).isTrue();
        final List<Message> after = memory.getMessages();
        // [u1, a1, boundary, summary]
        assertThat(after).hasSize(4);
        assertThat(after.get(0).getContent()).isEqualTo("u1");
        assertThat(after.get(1).getContent()).isEqualTo("a1");
        assertThat(after.get(2).getContent()).contains(CompactBoundary.BOUNDARY_OPEN_PREFIX);
        assertThat(after.get(3).getContent()).contains(CompactBoundary.SUMMARY_OPEN_PREFIX);
    }

    @Test
    void rangeBeyondMessageCountFailsWithoutMutatingMemory() {
        final TranscriptBuffer memory = new TranscriptBuffer(SessionId.generate());
        memory.addUserMessage("u1");
        memory.addAssistantMessage("a1");

        final CompactionResult result = engine
                .compact(baseRequest(memory).compactRange(CompactionRange.of(0, 10)).build());

        assertThat(result.isFailure()).isTrue();
        assertThat(result.getError()).get().isInstanceOf(IllegalArgumentException.class);
        // Memory untouched.
        assertThat(memory.getMessages()).hasSize(2);
        assertThat(memory.getMessages().get(0).getContent()).isEqualTo("u1");
        assertThat(memory.getMessages().get(1).getContent()).isEqualTo("a1");
        // No LLM call attempted.
        assertThat(llmClient.lastMessages.get()).isNull();
    }

    @Test
    void rangeWhoseFromIndexLandsOnToolMessageRejected() {
        // Layout: [u1, assistant(tool_use), tool_result, u2]
        // fromIndex=2 would put the prefix (u1, assistant tool_use) into the kept side with no resolving tool_result.
        final TranscriptBuffer memory = new TranscriptBuffer(SessionId.generate());
        memory.addUserMessage("u1");
        memory.addMessage(Message.assistant("calling", List.of(ToolUse.of("tu_1", "Bash", Map.of("command", "ls")))));
        memory.addMessage(Message.toolUseResults(List.of(ToolUseResult.success("tu_1", "ok"))));
        memory.addUserMessage("u2");

        final CompactionResult result = engine
                .compact(baseRequest(memory).compactRange(CompactionRange.of(2, 4)).build());

        assertThat(result.isFailure()).isTrue();
        assertThat(result.getError()).get().isInstanceOf(IllegalArgumentException.class);
        assertThat(result.getError().get().getMessage()).contains("tool_use/tool_result");
        assertThat(memory.getMessages()).hasSize(4);
    }

    @Test
    void rangeWhoseToIndexLandsOnToolMessageRejected() {
        // Layout: [u1, assistant(tool_use), tool_result, u2]
        // toIndex=2 would put assistant tool_use into the summary and leave tool_result orphaned in the tail.
        final TranscriptBuffer memory = new TranscriptBuffer(SessionId.generate());
        memory.addUserMessage("u1");
        memory.addMessage(Message.assistant("calling", List.of(ToolUse.of("tu_1", "Bash", Map.of("command", "ls")))));
        memory.addMessage(Message.toolUseResults(List.of(ToolUseResult.success("tu_1", "ok"))));
        memory.addUserMessage("u2");

        final CompactionResult result = engine
                .compact(baseRequest(memory).compactRange(CompactionRange.of(0, 2)).build());

        assertThat(result.isFailure()).isTrue();
        assertThat(result.getError()).get().isInstanceOf(IllegalArgumentException.class);
        assertThat(result.getError().get().getMessage()).contains("tool_use/tool_result");
    }

    @Test
    void rangeIncludingFullToolUseTriadIsAllowed() {
        // Layout: [u1, assistant(tool_use), tool_result, u2, a2]
        // Range [1, 3) summarizes the assistant tool_use AND its tool_result together → coherent.
        final TranscriptBuffer memory = new TranscriptBuffer(SessionId.generate());
        memory.addUserMessage("u1");
        memory.addMessage(Message.assistant("calling", List.of(ToolUse.of("tu_1", "Bash", Map.of("command", "ls")))));
        memory.addMessage(Message.toolUseResults(List.of(ToolUseResult.success("tu_1", "ok"))));
        memory.addUserMessage("u2");
        memory.addAssistantMessage("a2");

        final CompactionResult result = engine
                .compact(baseRequest(memory).compactRange(CompactionRange.of(1, 3)).build());

        assertThat(result.isSuccess()).isTrue();
        // [u1, boundary, summary, u2, a2]
        assertThat(memory.getMessages()).hasSize(5);
        assertThat(memory.getMessages().get(0).getContent()).isEqualTo("u1");
        assertThat(memory.getMessages().get(3).getContent()).isEqualTo("u2");
        assertThat(memory.getMessages().get(4).getContent()).isEqualTo("a2");
    }

    @Test
    void boundaryMetadataReflectsInRangeStatsOnly() {
        final TranscriptBuffer memory = new TranscriptBuffer(SessionId.generate());
        memory.addUserMessage("u1");
        memory.addMessage(Message.assistant("a1", List.of(ToolUse.of("tu_1", "FirstTool", Map.of()))));
        memory.addMessage(Message.toolUseResults(List.of(ToolUseResult.success("tu_1", "r1"))));
        memory.addUserMessage("u2");
        memory.addMessage(Message.assistant("a2", List.of(ToolUse.of("tu_2", "SecondTool", Map.of()))));
        memory.addMessage(Message.toolUseResults(List.of(ToolUseResult.success("tu_2", "r2"))));

        // Summarize only the second triad [3, 6); SecondTool should appear in the boundary metadata, FirstTool not.
        final CompactionResult result = engine
                .compact(baseRequest(memory).compactRange(CompactionRange.of(3, 6)).build());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMetadata().getDiscoveredToolNames()).containsExactly("SecondTool");
        // First triad preserved verbatim in the new memory.
        final List<Message> after = memory.getMessages();
        assertThat(after.get(0).getContent()).isEqualTo("u1");
        assertThat(after.get(1).getToolUses()).extracting(ToolUse::getName).containsExactly("FirstTool");
    }

    private CompactionRequest.Builder baseRequest(TranscriptBuffer memory) {
        return CompactionRequest.builder().transcriptBuffer(memory).trigger(CompactionTrigger.MANUAL)
                .model(LlmModel.builder().name("test-model").build()).hookRegistry(hookRegistry)
                .environment(environment);
    }

    private static final class RecordingLlmClient implements LlmClient {
        private final String summary;
        private final AtomicReference<List<Message>> lastMessages = new AtomicReference<>();

        RecordingLlmClient(String summary) {
            this.summary = summary;
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            throw new AssertionError("Engine should call the metadata-aware sendMessage overload");
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig, LlmCallMetadata metadata) {
            lastMessages.set(List.copyOf(messages));
            return LlmResponse.text(summary);
        }

        @Override
        public String getProviderName() {
            return "recording";
        }
    }
}
