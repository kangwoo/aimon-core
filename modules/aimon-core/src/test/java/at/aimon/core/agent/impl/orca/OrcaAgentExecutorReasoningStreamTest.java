package at.aimon.core.agent.impl.orca;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.Environment;
import at.aimon.core.agent.prompt.SystemPromptParts;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.store.InMemorySessionRecordStore;
import at.aimon.core.agent.session.transcript.DefaultTranscriptManager;
import at.aimon.core.agent.stream.AgentExecutionEvent;
import at.aimon.core.agent.stream.AssistantReasoningDelta;
import at.aimon.core.agent.stream.AssistantTextDelta;
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
import at.aimon.core.llm.StopReason;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.streaming.LlmStreamChunk;
import at.aimon.core.llm.streaming.LlmStreamSink;
import at.aimon.core.llm.streaming.LlmStreamingOptions;
import at.aimon.core.skill.DefaultSkillRegistry;
import at.aimon.core.subagent.DefaultSubagentExecutionManager;
import at.aimon.core.subagent.DefaultSubagentRegistry;

/**
 * The one hop between a provider's {@link LlmStreamChunk.Kind#REASONING_DELTA} and the event a renderer subscribes to.
 *
 * <p>
 * <strong>Why it needed a test of its own.</strong> Both ends of the reasoning channel were pinned and the middle was
 * not: the provider mappers are covered by fixture streams and now by live calls, and
 * {@code AgentExecutionEventPayload} / the REPL formatter are covered by tests that <em>construct</em> an
 * {@link AssistantReasoningDelta} by hand. Nothing asserted that the executor turns one into the other, so a chunk
 * kind that reached the sink and was then dropped in the switch would have been green everywhere — the same
 * always-empty channel the provider half of #71 exists to rule out, one layer up.
 *
 * <p>
 * A fake client rather than a live one, deliberately: which events a server sends is a fact about the server and is
 * measured against the server, while this hop is arithmetic on chunks that have already arrived.
 */
@DisplayName("OrcaAgentExecutor - REASONING_DELTA chunks surface as AssistantReasoningDelta")
class OrcaAgentExecutorReasoningStreamTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("each reasoning chunk becomes one event, on its own index sequence and not the text one")
    void reasoningChunksBecomeReasoningEvents() {
        final ReasoningStreamingLlmClient llmClient = new ReasoningStreamingLlmClient();
        final OrcaAgentExecutor executor = createStreamingExecutor(llmClient);
        final List<AgentExecutionEvent> seen = new ArrayList<>();
        executor.addEventListener(seen::add);

        final OrcaAgentExecutionResult result = executor.execute(createContext(),
                OrcaAgentExecutionRequest.builder().userInput("hi").sessionId(SessionId.generate()).build());

        assertThat(result.isSuccess()).isTrue();

        final List<AssistantReasoningDelta> reasoning = seen.stream().filter(AssistantReasoningDelta.class::isInstance)
                .map(AssistantReasoningDelta.class::cast).toList();
        assertThat(reasoning).hasSize(2);
        assertThat(reasoning).extracting(AssistantReasoningDelta::getDelta).containsExactly("weighing ", "the options");

        // Its own sequence, starting at zero. Sharing the text channel's ordinal would make a renderer that indexes
        // by it overwrite one channel with the other.
        assertThat(reasoning).extracting(AssistantReasoningDelta::getChunkIndex).containsExactly(0, 1);
        assertThat(seen).filteredOn(AssistantTextDelta.class::isInstance).extracting("chunkIndex").containsExactly(0,
                1);

        // And the deliberation is not also published as answer text — the split that ChunkAggregator makes on its
        // side, asserted here on the side a subscriber actually sees.
        assertThat(seen).filteredOn(AssistantTextDelta.class::isInstance).extracting("delta")
                .containsExactly("Answer part one. ", "Answer part two.");
    }

    // ---------- helpers ----------

    private OrcaAgentRuntime createContext() {
        final LocalFileSystem fileSystem = new LocalFileSystem(new LocalFileSystemConfig(tempDir.toString()));
        fileSystem.initialize();
        return OrcaAgentRuntime.builder().id(AgentRuntimeId.of("agent:reasoning-stream-1"))
                .agent(DefaultAgent.builder().name("TestAgent").maxIterations(5).systemPrompt("You are a test agent")
                        .build())
                .toolRegistry(new DefaultToolRegistry()).hookRegistry(new DefaultHookRegistry())
                .commandRegistry(new DefaultCommandRegistry(fileSystem, ".aimon/commands"))
                .subagentRegistry(new DefaultSubagentRegistry(fileSystem, ".aimon/agents"))
                .skillRegistry(new DefaultSkillRegistry(fileSystem, ".aimon/skills")).fileSystem(fileSystem)
                .environment(Environment.createDefault()).build();
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

    /** Streams two text deltas with two reasoning deltas interleaved, then finishes normally. */
    private static final class ReasoningStreamingLlmClient implements LlmClient {

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            throw new UnsupportedOperationException("streaming-only fake");
        }

        @Override
        public LlmResponse sendMessageStreaming(SystemPromptParts systemPromptParts, List<Message> messages,
                List<ToolDefinition> tools, LlmModel modelConfig, LlmCallMetadata metadata, LlmStreamingOptions options,
                LlmStreamSink sink, LlmCancellation cancellation) {
            // Interleaved rather than grouped, because the two index sequences only diverge visibly when they are.
            sink.accept(LlmStreamChunk.textDelta(0, "Answer part one. "));
            sink.accept(LlmStreamChunk.reasoningDelta(1, "weighing "));
            sink.accept(LlmStreamChunk.reasoningDelta(2, "the options"));
            sink.accept(LlmStreamChunk.textDelta(3, "Answer part two."));
            final TokenUsage usage = TokenUsage.of(10, 5, 15);
            sink.accept(LlmStreamChunk.streamEnd(4, usage, java.util.Optional.of("end_turn"), StopReason.END_TURN));
            return LlmResponse.of("Answer part one. Answer part two.", List.of(), usage);
        }

        @Override
        public String getProviderName() {
            return "ReasoningStreaming";
        }
    }
}
