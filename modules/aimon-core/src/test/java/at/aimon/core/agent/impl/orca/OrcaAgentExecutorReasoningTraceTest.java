package at.aimon.core.agent.impl.orca;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.impl.orca.OrcaAgentExecutorTestSupport.ConcurrentScriptedLlmClient;
import at.aimon.core.agent.impl.orca.OrcaAgentExecutorTestSupport.EchoTool;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ReasoningTrace;
import at.aimon.core.llm.Role;
import at.aimon.core.llm.StopReason;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.llm.ToolUse;

/**
 * Binds the rule that an assistant {@link Message} built out of an {@link LlmResponse} carries that response's
 * reasoning traces — at all three of this executor's sites.
 *
 * <p>
 * <strong>Why this class exists at all.</strong> The provider-side round-trip test drives {@code sendMessage}
 * directly and then <em>reproduces</em> the executor's attachment by hand. That makes it green for every possible
 * behaviour of every executor, including one that attaches nothing: it binds the provider, which is what it is for,
 * and it cannot bind a caller. These tests run the real executor and read what the <em>second</em> call was handed,
 * so each fails on exactly one dropped site.
 *
 * <p>
 * No OpenAI type appears here. The attachment is a core-side rule, so it is bound on the core side, with a
 * hand-built trace whose provider name is a stub.
 */
@DisplayName("OrcaAgentExecutor - reasoning traces travel with the assistant message")
class OrcaAgentExecutorReasoningTraceTest {

    private static final ReasoningTrace FIRST = ReasoningTrace.builder().providerName("Stub").payload("RS-1")
            .toolUseId("call_1").build();
    private static final ReasoningTrace SECOND = ReasoningTrace.builder().providerName("Stub").payload("RS-2").build();

    private OrcaAgentExecutorTestSupport support;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        support = new OrcaAgentExecutorTestSupport(tempDir);
    }

    @Test
    @DisplayName("the traces of a tool-calling turn reach the next iteration's request")
    void tracesReachTheNextIteration() {
        // Site 3 (the tool-use iteration). Fails if that site drops them: the second call's message list would carry
        // an assistant message with no traces, so the model re-derives its chain of thought every iteration -- the
        // exact cost the reasoning round trip exists to remove.
        final ConcurrentScriptedLlmClient client = new ConcurrentScriptedLlmClient().script(
                "conv-traces", LlmResponse.of("thinking", List.of(ToolUse.of("call_1", "echo", Map.of())))
                        .withReasoningTraces(List.of(FIRST)),
                LlmResponse.text("done").withReasoningTraces(List.of(SECOND)));

        support.newExecutor(client).execute(support.newContext(new EchoTool("echo")),
                OrcaAgentExecutorTestSupport.request("conv-traces"));

        final List<List<Message>> seen = client.messagesFor("conv-traces");
        assertThat(seen).hasSizeGreaterThanOrEqualTo(2);
        assertThat(assistantMessagesOf(seen.get(1)))
                .anySatisfy(message -> assertThat(message.getReasoningTraces()).containsExactly(FIRST));
    }

    @Test
    @DisplayName("the terminal turn's traces survive into the session snapshot")
    void tracesSurviveIntoTheSessionSnapshot() {
        // Site 2 (the clean terminal branch). The snapshot is what the session's NEXT user turn replays, so dropping
        // there costs the reasoning across turns rather than across iterations -- invisible to the test above.
        final ConcurrentScriptedLlmClient client = new ConcurrentScriptedLlmClient().script(
                "conv-terminal", LlmResponse.of("thinking", List.of(ToolUse.of("call_1", "echo", Map.of())))
                        .withReasoningTraces(List.of(FIRST)),
                LlmResponse.text("done").withReasoningTraces(List.of(SECOND)));

        final OrcaAgentExecutionResult result = support.newExecutor(client).execute(
                support.newContext(new EchoTool("echo")), OrcaAgentExecutorTestSupport.request("conv-terminal"));

        final List<Message> history = assistantMessagesOf(result.getSnapshot().getConversationHistory());
        assertThat(history).hasSize(2);
        assertThat(history.get(0).getReasoningTraces()).containsExactly(FIRST);
        assertThat(history.get(1).getReasoningTraces()).containsExactly(SECOND);
    }

    @Test
    @DisplayName("a turn truncated at max_tokens keeps its traces on the flagged message")
    void truncatedTerminalMessageKeepsItsTraces() {
        // Site 1 (the truncated terminal branch). The branch a reader is most likely to miss, because it builds
        // `flaggedAnswer` rather than response.getTextContent() and so does not look like the other two.
        final ConcurrentScriptedLlmClient client = new ConcurrentScriptedLlmClient().script("conv-truncated",
                LlmResponse.of("half an ans", List.of(), TokenUsage.empty(), StopReason.MAX_TOKENS)
                        .withReasoningTraces(List.of(SECOND)));

        final OrcaAgentExecutionResult result = support.newExecutor(client).execute(
                support.newContext(new EchoTool("echo")), OrcaAgentExecutorTestSupport.request("conv-truncated"));

        final List<Message> history = assistantMessagesOf(result.getSnapshot().getConversationHistory());
        assertThat(history).hasSize(1);
        assertThat(history.get(0).getContent()).contains("half an ans");
        assertThat(history.get(0).getReasoningTraces()).containsExactly(SECOND);
    }

    private static List<Message> assistantMessagesOf(List<Message> messages) {
        return messages.stream().filter(message -> message.getRole() == Role.ASSISTANT).toList();
    }
}
