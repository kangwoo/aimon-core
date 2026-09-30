package at.aimon.core.subagent.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.Environment;
import at.aimon.core.agent.compact.CompactionDecision;
import at.aimon.core.agent.compact.CompactionResult;
import at.aimon.core.agent.context.ContextDecision;
import at.aimon.core.agent.context.ContextEngine;
import at.aimon.core.agent.context.ContextRequest;
import at.aimon.core.agent.context.ContextView;
import at.aimon.core.agent.interrupt.NoopCancellationSignal;
import at.aimon.core.agent.session.transcript.TranscriptBuffer;
import at.aimon.core.agent.tool.DefaultToolExecutionManager;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.hook.DefaultHookExecutionManager;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.exception.LlmPromptTooLongException;
import at.aimon.core.llm.invoke.LlmCallGateway;
import at.aimon.core.subagent.Subagent;
import at.aimon.core.subagent.SubagentContent;
import at.aimon.core.subagent.SubagentMetadata;

/**
 * Pins a fork to an injected {@link ContextEngine}: the fork's LLM calls are sent the engine's view, the engine is told
 * the fork's execution id, and a blocking decision ends the fork.
 */
class DefaultSubagentExecutorContextEngineTest {

    private static final Message VIEW_MARKER = Message.user("[the engine's view]");

    @Test
    void aForkIsSentTheInjectedEnginesViewAndIdentifiesByItsExecutionId() {
        final RecordingClient llm = new RecordingClient();
        final RecordingEngine engine = new RecordingEngine(CompactionDecision.Action.NONE);

        final SubagentExecutionResult result = run(llm, engine);

        assertThat(result.isSuccess()).isTrue();
        assertThat(llm.sent).containsExactly(List.of(VIEW_MARKER));
        final ContextRequest seen = engine.requests.get(0);
        assertThat(seen.getCaller().getExecutionId()).hasValueSatisfying(
                id -> assertThat(seen.getTranscriptBuffer().getSessionId().value()).isEqualTo(id.value()));
        assertThat(seen.getHookRegistry()).isPresent();
        assertThat(seen.getEnvironment()).isPresent();
    }

    @Test
    void aBlockingDecisionEndsTheForkWithoutCallingTheProvider() {
        final RecordingClient llm = new RecordingClient();

        final SubagentExecutionResult result = run(llm, new RecordingEngine(CompactionDecision.Action.BLOCK));

        assertThat(result.isSuccess()).isFalse();
        assertThat(llm.sent).isEmpty();
    }

    @Test
    void theDefaultForkSendsItsTranscriptAsItIs() {
        final RecordingClient llm = new RecordingClient();

        final SubagentExecutionResult result = new DefaultSubagentExecutor(llm, new DefaultToolExecutionManager(),
                new DefaultHookExecutionManager()).execute(context(), request());

        assertThat(result.isSuccess()).isTrue();
        assertThat(llm.sent).hasSize(1);
        assertThat(llm.sent.get(0)).extracting(Message::getContent).anyMatch(c -> c.contains("do the thing"));
    }

    private static SubagentExecutionResult run(RecordingClient llm, ContextEngine engine) {
        return new DefaultSubagentExecutor(LlmCallGateway.<TranscriptBuffer>withDefaultRetry(llm),
                new DefaultToolExecutionManager(), new DefaultHookExecutionManager(), engine)
                .execute(context(), request());
    }

    private static SubagentExecutionContext context() {
        return SubagentExecutionContext.builder().agentRuntimeId(AgentRuntimeId.of("agent:test-1"))
                .subagent(Subagent.of("worker", SubagentMetadata.builder().description("d").maxIterations(5).build(),
                        SubagentContent.of("you are worker")))
                .defaultModel(LlmModel.builder().name("gpt-4").build()).toolRegistry(new DefaultToolRegistry())
                .hookRegistry(new DefaultHookRegistry()).environment(Environment.createDefault())
                .parentCancellationSignal(NoopCancellationSignal.INSTANCE).build();
    }

    private static SubagentExecutionRequest request() {
        return SubagentExecutionRequest.builder().taskId("task-1").goal("do the thing").build();
    }

    /** Hands back a fixed view with the configured action and records every request. */
    private static final class RecordingEngine implements ContextEngine {
        final List<ContextRequest> requests = new CopyOnWriteArrayList<>();
        private final CompactionDecision.Action action;

        RecordingEngine(CompactionDecision.Action action) {
            this.action = action;
        }

        @Override
        public ContextDecision prepare(ContextRequest request) {
            requests.add(request);
            return ContextDecision.builder().view(ContextView.of(List.of(VIEW_MARKER))).action(action)
                    .reason("scripted").build();
        }

        @Override
        public Optional<ContextView> recover(ContextRequest request, LlmPromptTooLongException error) {
            return Optional.empty();
        }

        @Override
        public CompactionResult compactNow(ContextRequest request, String instructions) {
            throw new AssertionError("not called");
        }
    }

    /** Records every message list it is sent and answers with a terminal text. */
    private static final class RecordingClient implements LlmClient {
        final List<List<Message>> sent = new CopyOnWriteArrayList<>();

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            return sendMessage(systemPrompt, messages, tools, modelConfig, LlmCallMetadata.empty());
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig, LlmCallMetadata metadata) {
            sent.add(List.copyOf(messages));
            return LlmResponse.text("done");
        }

        @Override
        public String getProviderName() {
            return "Recording";
        }
    }
}
