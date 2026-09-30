package at.aimon.core.agent.context;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.Environment;
import at.aimon.core.agent.compact.CompactionEngine;
import at.aimon.core.agent.compact.CompactionResult;
import at.aimon.core.agent.compact.DefaultCompactionEngine;
import at.aimon.core.agent.compact.DefaultCompactionGuard;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.transcript.SessionLogFormat;
import at.aimon.core.agent.session.transcript.TranscriptBuffer;
import at.aimon.core.hook.DefaultHookExecutionManager;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.llm.InMemoryModelContextWindowRegistry;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.Role;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;
import at.aimon.core.llm.token.HeuristicTokenEstimator;

/**
 * The summary request {@link DefaultContextEngine} sends through the real {@link DefaultCompactionEngine}, in both
 * modes: it must end on the user side. Between turns a conversation ends on the assistant's final answer, and a
 * request ending there is a prefill of a finished answer — Anthropic answers it with no content blocks at all, which is
 * how {@code /compact} in view mode first failed against a real provider.
 */
@SuppressWarnings("deprecation") // the default engine's guard is part of what is under test
@DisplayName("DefaultContextEngine - the summary request ends on the user side")
class DefaultContextEngineSummaryRequestTest {

    private static final LlmModel MODEL = LlmModel.builder().name("test-model").build();

    private final HeuristicTokenEstimator estimator = new HeuristicTokenEstimator();
    private SummaryCalls calls;
    private CompactionEngine compactionEngine;

    @BeforeEach
    void setUp() {
        calls = new SummaryCalls();
        compactionEngine = DefaultCompactionEngine.withDefaults(calls, estimator, new DefaultHookExecutionManager());
    }

    private DefaultContextEngine engine(SessionLogFormat writeFormat) {
        final DefaultCompactionGuard guard = new DefaultCompactionGuard(compactionEngine,
                InMemoryModelContextWindowRegistry.withDefaults(), estimator);
        return DefaultContextEngine.builder().compactionGuard(guard).compactionEngine(compactionEngine)
                .tokenEstimator(estimator).writeFormat(writeFormat).build();
    }

    private static ContextRequest request(TranscriptBuffer buffer) {
        return ContextRequest.builder().transcriptBuffer(buffer).systemPrompt("system prompt").model(MODEL)
                .hookRegistry(new DefaultHookRegistry()).environment(Environment.createDefault()).build();
    }

    private static TranscriptBuffer twoCompletedTurns(SessionLogFormat format) {
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

    @Test
    @DisplayName("view mode: /compact after completed turns closes the request with a user message")
    void viewModeCompactNowEndsOnTheUserSide() {
        final TranscriptBuffer buffer = twoCompletedTurns(SessionLogFormat.V2);

        final CompactionResult result = engine(SessionLogFormat.V2).compactNow(request(buffer), null);

        assertThat(result.isSuccess()).isTrue();
        assertThat(buffer.getViewState().getSummarySpan()).as("the summary went to the view").isPresent();
        assertThat(calls.lastRoles).containsExactly(Role.USER);
        final List<Message> sent = calls.lastInput;
        assertThat(sent).hasSize(5);
        assertThat(sent.get(4).getContent()).isEqualTo(DefaultCompactionEngine.SUMMARIZE_NOTE);
        assertThat(buffer.getMessages()).as("the note is sent, never logged").hasSize(4)
                .noneMatch(message -> DefaultCompactionEngine.SUMMARIZE_NOTE.equals(message.getContent()));
    }

    @Test
    @DisplayName("in place (version 1): /compact after completed turns closes the request with a user message")
    void inPlaceCompactNowEndsOnTheUserSide() {
        final TranscriptBuffer buffer = twoCompletedTurns(SessionLogFormat.V1);

        final CompactionResult result = engine(SessionLogFormat.V1).compactNow(request(buffer), null);

        assertThat(result.isSuccess()).isTrue();
        assertThat(buffer.getMessages()).as("rewritten in place to boundary + summary").hasSize(2)
                .noneMatch(message -> DefaultCompactionEngine.SUMMARIZE_NOTE.equals(message.getContent()));
        assertThat(calls.lastRoles).containsExactly(Role.USER);
        assertThat(calls.lastInput.get(calls.lastInput.size() - 1).getContent())
                .isEqualTo(DefaultCompactionEngine.SUMMARIZE_NOTE);
    }

    @Test
    @DisplayName("a request already ending on tool results is sent as it is")
    void aRequestEndingOnToolResultsGetsNoNote() {
        final TranscriptBuffer buffer = new TranscriptBuffer(SessionId.generate(), "system prompt");
        buffer.requireFormat(SessionLogFormat.V2);
        buffer.addUserMessage("fetch the report");
        buffer.addMessage(Message.assistant("", List.of(ToolUse.of("t-1", "fetch_report", Map.of()))));
        buffer.addMessage(Message.toolUseResults(List.of(ToolUseResult.success("t-1", "report text"))));

        final CompactionResult result = engine(SessionLogFormat.V2).compactNow(request(buffer), null);

        assertThat(result.isSuccess()).isTrue();
        assertThat(calls.lastRoles).containsExactly(Role.TOOL);
        assertThat(calls.lastInput).hasSize(3);
    }

    /** Answers every summary call and records the role its input ends on. */
    private static final class SummaryCalls implements LlmClient {

        private final List<Role> lastRoles = new CopyOnWriteArrayList<>();
        private volatile List<Message> lastInput = List.of();

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            lastInput = List.copyOf(messages);
            lastRoles.add(messages.get(messages.size() - 1).getRole());
            return LlmResponse.text("Summary: the codeword is PELICAN; the meeting moved to Tuesday.");
        }

        @Override
        public String getProviderName() {
            return "Recording";
        }
    }
}
