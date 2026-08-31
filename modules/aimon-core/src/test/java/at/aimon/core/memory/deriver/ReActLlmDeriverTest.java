package at.aimon.core.memory.deriver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.prompt.SystemPromptParts;
import at.aimon.core.base.Principal;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;
import at.aimon.core.llm.exception.LlmClientException;
import at.aimon.core.memory.InMemoryObservationStore;
import at.aimon.core.memory.ObservationStore;
import at.aimon.core.memory.PeerView;
import at.aimon.core.memory.Workspace;
import at.aimon.core.memory.deriver.tool.DeriverMemorySearchTool;
import at.aimon.core.memory.deriver.tool.DeriverObservationCreateTool;

@DisplayName("ReActLlmDeriver")
class ReActLlmDeriverTest {

    private static final Workspace WS = Workspace.builder().id("ws-1").build();
    private static final PeerView OBSERVER = PeerView.of(WS, Principal.user("alice"));

    private StubLlmClient llm;
    private ObservationStore store;
    private ReActLlmDeriver deriver;

    @BeforeEach
    void setUp() {
        llm = new StubLlmClient();
        store = new InMemoryObservationStore();
        deriver = new ReActLlmDeriver(llm, store, "fake-model");
    }

    @Test
    @DisplayName("text-only response terminates loop with empty result")
    void textOnlyTerminates() {
        llm.enqueue(LlmResponse.of("done", List.of(), TokenUsage.of(10, 5, 15)));

        DerivationResult result = deriver.derive(ctx());

        assertThat(result.getCreated()).isEmpty();
        assertThat(result.getLlmTokensUsed()).isEqualTo(15L);
        assertThat(llm.callCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("single observation.create call persists and surfaces the observation")
    void singleObservationCreated() {
        llm.enqueue(
                LlmResponse.of("",
                        List.of(ToolUse.of("call-1", DeriverObservationCreateTool.TOOL_NAME,
                                Map.of("content", "alice prefers tea", "type", "EXPLICIT"))),
                        TokenUsage.of(10, 5, 15)));
        llm.enqueue(LlmResponse.of("done", List.of(), TokenUsage.of(5, 5, 10)));

        DerivationResult result = deriver.derive(ctx());

        assertThat(result.getCreated()).hasSize(1);
        assertThat(result.getCreated().get(0).getContent()).isEqualTo("alice prefers tea");
        assertThat(store.count(OBSERVER)).isEqualTo(1);
        assertThat(result.getLlmTokensUsed()).isEqualTo(25L);
        assertThat(llm.callCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("multiple tool calls in a single response are all dispatched")
    void multipleToolUsesInOneResponse() {
        llm.enqueue(LlmResponse.of("",
                List.of(ToolUse.of("call-1", DeriverMemorySearchTool.TOOL_NAME, Map.of("query", "tea")), ToolUse
                        .of("call-2", DeriverObservationCreateTool.TOOL_NAME, Map.of("content", "alice prefers tea"))),
                TokenUsage.of(20, 10, 30)));
        llm.enqueue(LlmResponse.of("done", List.of(), TokenUsage.empty()));

        DerivationResult result = deriver.derive(ctx());

        assertThat(result.getCreated()).hasSize(1);
        assertThat(result.getCreated().get(0).getContent()).isEqualTo("alice prefers tea");
    }

    @Test
    @DisplayName("unknown tool name yields ToolUseResult.error sent back to the model")
    void unknownToolName() {
        llm.enqueue(LlmResponse.of("", List.of(ToolUse.of("call-1", "deriver.bogus.tool", Map.of("foo", "bar"))),
                TokenUsage.empty()));
        llm.enqueue(LlmResponse.of("done", List.of(), TokenUsage.empty()));

        DerivationResult result = deriver.derive(ctx());

        assertThat(result.getCreated()).isEmpty();
        // Loop continued past the bad call rather than aborting.
        assertThat(llm.callCount()).isEqualTo(2);
        ToolUseResult firstResult = firstToolResultOf(llm.recordedMessagesOnCall(2));
        assertThat(firstResult.isError()).isTrue();
        assertThat(firstResult.getContent()).contains("Unknown tool");
    }

    @Test
    @DisplayName("tool error response is forwarded to the model as a ToolUseResult error")
    void toolErrorPropagated() {
        // Blank content forces DeriverObservationCreateTool to return ToolResult.error(...).
        llm.enqueue(LlmResponse.of("",
                List.of(ToolUse.of("call-1", DeriverObservationCreateTool.TOOL_NAME, Map.of("content", "   "))),
                TokenUsage.empty()));
        llm.enqueue(LlmResponse.of("done", List.of(), TokenUsage.empty()));

        DerivationResult result = deriver.derive(ctx());

        assertThat(result.getCreated()).isEmpty();
        assertThat(store.count(OBSERVER)).isZero();
        assertThat(firstToolResultOf(llm.recordedMessagesOnCall(2)).isError()).isTrue();
    }

    @Test
    @DisplayName("max iterations cap terminates the loop even if the model keeps calling tools")
    void maxIterationsCap() {
        ReActLlmDeriver capped = new ReActLlmDeriver(llm, store, "fake-model", /* redactionPolicy */ null, 2);
        // Always respond with a tool use to force the loop to hit the cap.
        for (int i = 0; i < 5; i++) {
            llm.enqueue(LlmResponse.of("",
                    List.of(ToolUse.of("call-" + i, DeriverMemorySearchTool.TOOL_NAME, Map.of("query", "x"))),
                    TokenUsage.of(5, 5, 10)));
        }

        DerivationResult result = capped.derive(ctx());

        assertThat(llm.callCount()).isEqualTo(2);
        assertThat(result.getLlmTokensUsed()).isEqualTo(20L);
        assertThat(result.getCreated()).isEmpty();
    }

    @Test
    @DisplayName("token budget exhaustion ends the loop after the offending iteration")
    void tokenBudgetExhausted() {
        llm.enqueue(LlmResponse.of("",
                List.of(ToolUse.of("call-1", DeriverMemorySearchTool.TOOL_NAME, Map.of("query", "x"))),
                TokenUsage.of(400, 200, 600)));
        // Should not be called because the first response already exceeds the 500 token budget.
        llm.enqueue(LlmResponse.of("",
                List.of(ToolUse.of("call-2", DeriverMemorySearchTool.TOOL_NAME, Map.of("query", "y"))),
                TokenUsage.of(10, 10, 20)));

        DerivationContext ctx = DerivationContext.builder().workspace(WS).sessionId("sess-1").observer(OBSERVER)
                .messages(List.of(Message.user("hi"))).tokenBudget(500).build();

        DerivationResult result = deriver.derive(ctx);

        assertThat(llm.callCount()).isEqualTo(1);
        assertThat(result.getLlmTokensUsed()).isEqualTo(600L);
    }

    @Test
    @DisplayName("LlmClient throwing is swallowed and partial result is returned")
    void llmThrowSwallowed() {
        llm.enqueue(LlmResponse.of("", List
                .of(ToolUse.of("call-1", DeriverObservationCreateTool.TOOL_NAME, Map.of("content", "alice likes tea"))),
                TokenUsage.of(10, 5, 15)));
        llm.enqueueThrow(new LlmClientException("boom"));

        DerivationResult result = deriver.derive(ctx());

        // The first iteration created one observation before the second iteration threw.
        assertThat(result.getCreated()).hasSize(1);
        assertThat(store.count(OBSERVER)).isEqualTo(1);
        assertThat(result.getLlmTokensUsed()).isEqualTo(15L);
    }

    @Test
    @DisplayName("first-call LlmClient failure yields fully empty result")
    void firstCallFailureEmpty() {
        llm.enqueueThrow(new LlmClientException("boom"));

        DerivationResult result = deriver.derive(ctx());

        assertThat(result.getCreated()).isEmpty();
        assertThat(result.getUpdated()).isEmpty();
        assertThat(result.getLlmTokensUsed()).isZero();
    }

    @Test
    @DisplayName("rejects illegal maxIterations")
    void rejectsIllegalMaxIterations() {
        assertThatThrownBy(() -> new ReActLlmDeriver(llm, store, "fake-model", null, 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxIterations");
    }

    @Test
    @DisplayName("rejects blank model name")
    void rejectsBlankModelName() {
        assertThatThrownBy(() -> new ReActLlmDeriver(llm, store, "  ")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("llmModelName");
    }

    private DerivationContext ctx() {
        return DerivationContext.builder().workspace(WS).sessionId("sess-1").observer(OBSERVER)
                .messages(List.of(Message.user("hello"))).build();
    }

    private static ToolUseResult firstToolResultOf(List<Message> conversation) {
        Message last = conversation.get(conversation.size() - 1);
        assertThat(last.hasToolResults()).as("last message of recorded conversation should carry tool results")
                .isTrue();
        return last.getToolUseResults().get(0);
    }

    /**
     * Marker for scripted entries the stub returns in order — either a successful response or a runtime throw.
     */
    private sealed interface ScriptEntry permits ResponseEntry, ThrowEntry {
    }

    private record ResponseEntry(LlmResponse response) implements ScriptEntry {
    }

    private record ThrowEntry(RuntimeException error) implements ScriptEntry {
    }

    /** Minimal LlmClient stub that returns scripted responses (or throws) in order. */
    private static final class StubLlmClient implements LlmClient {

        private final Deque<ScriptEntry> scripted = new ArrayDeque<>();
        private final List<List<Message>> recordedMessages = new ArrayList<>();
        private int callCount;

        void enqueue(LlmResponse response) {
            scripted.addLast(new ResponseEntry(response));
        }

        void enqueueThrow(RuntimeException error) {
            scripted.addLast(new ThrowEntry(error));
        }

        int callCount() {
            return callCount;
        }

        /** Returns the messages observed during the {@code n}-th call (1-based), as an immutable snapshot. */
        List<Message> recordedMessagesOnCall(int n) {
            if (n < 1 || n > recordedMessages.size()) {
                throw new IllegalArgumentException(
                        "call " + n + " not recorded; only " + recordedMessages.size() + " calls made");
            }
            return List.copyOf(recordedMessages.get(n - 1));
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            callCount++;
            recordedMessages.add(List.copyOf(messages));
            if (scripted.isEmpty()) {
                return LlmResponse.text("");
            }
            ScriptEntry next = scripted.pollFirst();
            if (next instanceof ThrowEntry t) {
                throw t.error();
            } else if (next instanceof ResponseEntry r) {
                return r.response();
            }
            throw new AssertionError("Unhandled ScriptEntry subtype: " + next);
        }

        @Override
        public LlmResponse sendMessage(SystemPromptParts systemPromptParts, List<Message> messages,
                List<ToolDefinition> tools, LlmModel modelConfig, LlmCallMetadata metadata) {
            return sendMessage(systemPromptParts.concatenated(), messages, tools, modelConfig);
        }

        @Override
        public String getProviderName() {
            return "stub";
        }

    }
}
