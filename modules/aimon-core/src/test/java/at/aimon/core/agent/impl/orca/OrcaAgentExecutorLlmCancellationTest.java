package at.aimon.core.agent.impl.orca;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.Environment;
import at.aimon.core.agent.budget.CompletionReason;
import at.aimon.core.agent.interrupt.DefaultInterruptCoordinator;
import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.agent.prompt.SystemPromptParts;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.store.InMemorySessionRecordStore;
import at.aimon.core.agent.session.transcript.DefaultTranscriptManager;
import at.aimon.core.agent.tool.DefaultToolExecutionManager;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.command.DefaultCommandExecutionManager;
import at.aimon.core.command.DefaultCommandRegistry;
import at.aimon.core.filesystem.impl.local.LocalFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystemConfig;
import at.aimon.core.hook.DefaultHookExecutionManager;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmCancellation;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.exception.LlmCallCancelledException;
import at.aimon.core.llm.streaming.LlmStreamChunk;
import at.aimon.core.llm.streaming.LlmStreamSink;
import at.aimon.core.llm.streaming.LlmStreamingOptions;
import at.aimon.core.skill.DefaultSkillRegistry;
import at.aimon.core.subagent.DefaultSubagentExecutionManager;
import at.aimon.core.subagent.DefaultSubagentRegistry;

/**
 * Verifies the executor-side translation added for cooperative LLM cancellation: when the gateway/provider raises an
 * {@link LlmCallCancelledException} (the turn signal tripped and actively aborted the HTTP call), the ReAct loop must
 * treat it as an interruption — {@link CompletionReason#INTERRUPTED}, not a generic failure — and must not issue any
 * further LLM call.
 */
@DisplayName("OrcaAgentExecutor LLM-cancellation translation")
class OrcaAgentExecutorLlmCancellationTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("a gateway LlmCallCancelledException becomes CompletionReason.INTERRUPTED (non-streaming)")
    void llmCancelledBecomesInterrupted() {
        final CancellingLlmClient llmClient = new CancellingLlmClient();

        final OrcaAgentExecutor executor = createExecutor(llmClient);
        final OrcaAgentExecutionResult result = executor.execute(createContext(), request());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.INTERRUPTED);
        // Exactly one LLM call was attempted; the cancellation was terminal, so the loop unwound instead of retrying.
        assertThat(llmClient.callCount).isEqualTo(1);
    }

    @Test
    @DisplayName("a mid-stream LlmCallCancelledException becomes INTERRUPTED and preserves the streamed prefix (streaming)")
    void streamingLlmCancelledPreservesPrefixAndInterrupts() {
        final StreamingCancelLlmClient llmClient = new StreamingCancelLlmClient();

        final OrcaAgentExecutor executor = createStreamingExecutor(llmClient);
        final OrcaAgentExecutionResult result = executor.execute(createContext(), request());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.INTERRUPTED);
        assertThat(llmClient.callCount).as("the cancellation is terminal — exactly one streaming call").isEqualTo(1);
        // The prefix streamed before the abort must survive in the session as an assistant message so the final
        // snapshot carries exactly what the user saw (tool_uses intentionally empty — a mid-stream cancel completes no
        // tool_call).
        assertThat(result.getConversationHistory())
                .anySatisfy(message -> assertThat(message.getContent()).contains(StreamingCancelLlmClient.PARTIAL));
    }

    @Test
    @DisplayName("a mid-stream cancel commits the streamed answer to the transcript and never the deliberation")
    void streamingCancelDoesNotPersistReasoningText() {
        // The failure this guards is a privacy failure, and it is reachable only through cancellation: on the happy
        // path the assistant message is built from the LlmResponse, which never carried reasoning in the first
        // place. Folding a reasoning delta into the aggregator's text buffer would put the model's private
        // deliberation into the session as its public answer, and a transcript is not recoverable.
        final ReasoningStreamingCancelLlmClient llmClient = new ReasoningStreamingCancelLlmClient();

        final OrcaAgentExecutor executor = createStreamingExecutor(llmClient);
        final OrcaAgentExecutionResult result = executor.execute(createContext(), request());

        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.INTERRUPTED);
        // 1. The answer prefix survives, both halves of it — the reasoning delta between them did not split it.
        assertThat(result.getConversationHistory())
                .anySatisfy(message -> assertThat(message.getContent()).contains("Answer part one. Answer part two."));
        // 2. ...and the deliberation is nowhere in the session. Written as a negative over the whole history rather
        // than over one message, because the failure guarded against is "it ended up SOMEWHERE persistent".
        assertThat(result.getConversationHistory())
                .allSatisfy(message -> assertThat(message.getContent()).doesNotContain(SECRET_DELIBERATION));
    }

    @Test
    @DisplayName("the same holds on the sink-checkpoint cancel arm, which duplicates the peekText/addMessage pair")
    void sinkCheckpointCancelDoesNotPersistReasoningText() {
        // invokeGatewayOnce has two catch arms that each preserve the prefix — CancelledExecutionException from the
        // sink checkpoint, and LlmCallCancelledException from the provider. They duplicate the peekText/addMessage
        // pair, so a future edit could fix one and not the other; both are pinned.
        final DefaultInterruptCoordinator coordinator = new DefaultInterruptCoordinator();
        final ReasoningStreamingCancelLlmClient llmClient = new ReasoningStreamingCancelLlmClient();
        llmClient.throwFromProvider = false;
        // Trip the turn's signal between the reasoning delta and the second text delta, so the sink's own checkpoint
        // after that delta unwinds the stream rather than the provider doing it.
        llmClient.beforeSecondTextDelta = () -> coordinator.requestInterrupt(InterruptReason.USER_SIGINT);

        final OrcaAgentExecutor executor = createStreamingExecutor(llmClient);
        executor.interruptCoordinatorFactory = () -> coordinator;
        final OrcaAgentExecutionResult result = executor.execute(createContext(), request());

        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.INTERRUPTED);
        assertThat(result.getConversationHistory())
                .anySatisfy(message -> assertThat(message.getContent()).contains("Answer part one."));
        assertThat(result.getConversationHistory())
                .allSatisfy(message -> assertThat(message.getContent()).doesNotContain(SECRET_DELIBERATION));
    }

    // ---------- helpers ----------

    private OrcaAgentExecutionRequest request() {
        return OrcaAgentExecutionRequest.builder().userInput("hi").sessionId(SessionId.generate()).build();
    }

    private OrcaAgentRuntime createContext() {
        final LocalFileSystem fileSystem = new LocalFileSystem(new LocalFileSystemConfig(tempDir.toString()));
        fileSystem.initialize();
        return OrcaAgentRuntime.builder().id(AgentRuntimeId.of("agent:test-1"))
                .agent(DefaultAgent.builder().name("TestAgent").maxIterations(5).systemPrompt("You are a test agent")
                        .build())
                .toolRegistry(new DefaultToolRegistry()).hookRegistry(new DefaultHookRegistry())
                .commandRegistry(new DefaultCommandRegistry(fileSystem, ".aimon/commands"))
                .subagentRegistry(new DefaultSubagentRegistry(fileSystem, ".aimon/agents"))
                .skillRegistry(new DefaultSkillRegistry(fileSystem, ".aimon/skills")).fileSystem(fileSystem)
                .environment(Environment.createDefault()).build();
    }

    private OrcaAgentExecutor createExecutor(LlmClient client) {
        final DefaultToolExecutionManager toolManager = new DefaultToolExecutionManager();
        final DefaultHookExecutionManager hookManager = new DefaultHookExecutionManager();
        final DefaultCommandExecutionManager commandManager = new DefaultCommandExecutionManager(client);
        final DefaultSubagentExecutionManager subagentManager = new DefaultSubagentExecutionManager(client, toolManager,
                hookManager);
        return new OrcaAgentExecutor(client, new DefaultTranscriptManager(new InMemorySessionRecordStore()),
                toolManager, hookManager, commandManager, subagentManager);
    }

    private OrcaAgentExecutor createStreamingExecutor(LlmClient client) {
        final DefaultToolExecutionManager toolManager = new DefaultToolExecutionManager();
        final DefaultHookExecutionManager hookManager = new DefaultHookExecutionManager();
        final DefaultCommandExecutionManager commandManager = new DefaultCommandExecutionManager(client);
        final DefaultSubagentExecutionManager subagentManager = new DefaultSubagentExecutionManager(client, toolManager,
                hookManager);
        return OrcaAgentExecutor.builder().llmClient(client)
                .transcriptManager(new DefaultTranscriptManager(new InMemorySessionRecordStore()))
                .toolExecutionManager(toolManager).hookExecutionManager(hookManager)
                .commandExecutionManager(commandManager).subagentExecutionManager(subagentManager).useStreaming(true)
                .build();
    }

    /**
     * LLM client that simulates a provider/gateway cancellation: the first (and only) call throws
     * {@link LlmCallCancelledException}, as the streaming/gateway path would when the turn signal actively aborts the
     * in-flight call.
     */
    private static final class CancellingLlmClient implements LlmClient {
        int callCount;

        private LlmResponse cancel() {
            callCount++;
            throw new LlmCallCancelledException("LLM call aborted by cancellation");
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            return cancel();
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig, LlmCallMetadata metadata) {
            return cancel();
        }

        @Override
        public String getProviderName() {
            return "Cancelling";
        }

    }

    /** The model's deliberation, which must not reach the session under any cancellation path. */
    private static final String SECRET_DELIBERATION = "SECRET-DELIBERATION";

    /**
     * Streaming client that interleaves a reasoning delta between two text deltas and then cancels.
     *
     * <p>
     * The interleaving is the point: a naive implementation that appended both channels to one buffer would produce
     * a transcript reading "Answer part one. SECRET-DELIBERATION Answer part two.", which passes a test asserting
     * only that the prefix survives.
     */
    private static final class ReasoningStreamingCancelLlmClient implements LlmClient {

        int callCount;

        /**
         * Which cancel arm to exercise. {@code true} aborts from the provider ({@link LlmCallCancelledException});
         * {@code false} lets {@link #beforeSecondTextDelta} trip the turn signal so the sink's own checkpoint raises
         * {@link at.aimon.core.agent.interrupt.CancelledExecutionException} on the next text delta instead.
         */
        boolean throwFromProvider = true;

        /** Run just before the second text delta, so a test can trip the signal mid-stream. */
        Runnable beforeSecondTextDelta = () -> {
        };

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            throw new UnsupportedOperationException("streaming-only fake");
        }

        @Override
        public LlmResponse sendMessageStreaming(SystemPromptParts systemPromptParts, List<Message> messages,
                List<ToolDefinition> tools, LlmModel modelConfig, LlmCallMetadata metadata, LlmStreamingOptions options,
                LlmStreamSink sink, LlmCancellation cancellation) {
            callCount++;
            sink.accept(LlmStreamChunk.textDelta(0, "Answer part one. "));
            sink.accept(LlmStreamChunk.reasoningDelta(1, SECRET_DELIBERATION));
            if (throwFromProvider) {
                sink.accept(LlmStreamChunk.textDelta(2, "Answer part two."));
                throw new LlmCallCancelledException("stream aborted mid-flight by cancellation");
            }
            // The sink checkpoints after each text delta, so tripping the signal first makes the next one raise
            // CancelledExecutionException out of the provider's stream loop — the other arm, same preservation code.
            beforeSecondTextDelta.run();
            sink.accept(LlmStreamChunk.textDelta(2, "Answer part two."));
            throw new IllegalStateException("the sink checkpoint should have unwound before this line");
        }

        @Override
        public String getProviderName() {
            return "ReasoningStreamingCancelling";
        }
    }

    /**
     * Streaming LLM client that emits one partial text delta, then aborts the stream with
     * {@link LlmCallCancelledException} — as the provider does when the turn signal actively closes the in-flight HTTP
     * stream mid-response.
     */
    private static final class StreamingCancelLlmClient implements LlmClient {
        static final String PARTIAL = "partial answer before abort";

        int callCount;

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            throw new UnsupportedOperationException("streaming-only fake");
        }

        @Override
        public LlmResponse sendMessageStreaming(SystemPromptParts systemPromptParts, List<Message> messages,
                List<ToolDefinition> tools, LlmModel modelConfig, LlmCallMetadata metadata, LlmStreamingOptions options,
                LlmStreamSink sink, LlmCancellation cancellation) {
            callCount++;
            sink.accept(LlmStreamChunk.textDelta(0, PARTIAL));
            throw new LlmCallCancelledException("stream aborted mid-flight by cancellation");
        }

        @Override
        public String getProviderName() {
            return "StreamingCancelling";
        }

    }
}
