package at.aimon.core.subagent.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.Environment;
import at.aimon.core.agent.ExecutionId;
import at.aimon.core.agent.interrupt.NoopCancellationSignal;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.DefaultToolExecutionManager;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolContextEnricher;
import at.aimon.core.agent.tool.ToolContextEnrichmentInfo;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.base.Principal;
import at.aimon.core.hook.DefaultHookExecutionManager;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.memory.PeerView;
import at.aimon.core.memory.Workspace;
import at.aimon.core.subagent.Subagent;
import at.aimon.core.subagent.SubagentContent;
import at.aimon.core.subagent.SubagentMetadata;
import at.aimon.core.tools.memory.MemoryRecallTool;
import at.aimon.core.tools.memory.MemoryToolContextEnricher;

/**
 * Guards the identity a {@link ToolContextEnricher} is handed on a subagent fork.
 *
 * <p>
 * A fork has no session, so an enricher keying durable state on the fork's own identity reaches something that by
 * construction was never written — the concrete symptom being a memory recall that missed 100% of the time inside a
 * fork. The fork used to paper over this by minting a {@link SessionId} for itself, which merely made the wrong id
 * look like a right one; since Stage 6-4 it reports an {@link ExecutionId} and the invoking session beside it, and
 * lets the enricher choose. These tests pin all three halves: that the fork describes itself as sessionless, that the
 * executor threads the invoking id through, and that the memory enricher resolves invoker-first end to end.
 */
@DisplayName("DefaultSubagentExecutor tool-context enrichment")
class DefaultSubagentExecutorEnrichmentTest {

    private static final Workspace WS = Workspace.builder().id("ws-1").build();
    private static final PeerView ALICE = PeerView.of(WS, Principal.user("alice", "Alice"));

    @Test
    @DisplayName("an enricher sees the invoking session beside the fork's own run id")
    void enricherSeesBothConversationIds() {
        final SessionId userSession = SessionId.generate();
        final CapturingEnricher enricher = new CapturingEnricher();

        runFork(userSession, List.of(enricher), new DefaultToolRegistry());

        assertThat(enricher.info).isNotNull();
        assertThat(enricher.info.getInvokingSessionId()).contains(userSession);
        assertThat(enricher.info.getSessionId()).as("a fork is nobody's session, not even its own").isEmpty();
        assertThat(enricher.info.getExecutionId()).as("the fork keeps a run identity of its own").isPresent();
        assertThat(enricher.info.getExecutionId().orElseThrow().value()).isNotEqualTo(userSession.value())
                .startsWith("subagent:recaller:");
    }

    @Test
    @DisplayName("a run nobody asked for has no invoking session and still identifies itself")
    void uninvokedRunHasNoInvokingConversation() {
        final CapturingEnricher enricher = new CapturingEnricher();

        runFork(null, List.of(enricher), new DefaultToolRegistry());

        assertThat(enricher.info.getInvokingSessionId()).isEmpty();
        assertThat(enricher.info.getSessionId()).isEmpty();
        assertThat(enricher.info.getExecutionId()).isPresent();
    }

    /**
     * Two forks of the same subagent must not land in one bucket — hence the random tail on the execution id, and hence
     * this test rather than a bare {@code isPresent()} on one run.
     */
    @Test
    @DisplayName("each fork gets its own run id")
    void everyForkGetsADistinctRunId() {
        final CapturingEnricher first = new CapturingEnricher();
        final CapturingEnricher second = new CapturingEnricher();

        runFork(null, List.of(first), new DefaultToolRegistry());
        runFork(null, List.of(second), new DefaultToolRegistry());

        assertThat(first.info.getExecutionId()).isNotEqualTo(second.info.getExecutionId());
    }

    /**
     * The memory key is dropped rather than filled with the fork's own identity: absent, {@code MemoryRecall} matches
     * across sessions, which beats looking up an id nothing ever wrote under.
     */
    @Test
    @DisplayName("an uninvoked fork publishes no memory session id at all")
    void uninvokedForkPublishesNoMemorySessionId() {
        final DefaultToolRegistry registry = new DefaultToolRegistry();
        final ContextCapturingTool probe = new ContextCapturingTool();
        registry.register(probe);

        runFork(null, List.of(new MemoryToolContextEnricher(WS, ALICE)), registry);

        assertThat(probe.captured).isNotNull();
        assertThat(probe.captured.get(MemoryRecallTool.SESSION_ID_KEY)).isEmpty();
    }

    @Test
    @DisplayName("memory recall inside a fork is keyed on the conversation memory was written under")
    void memorySessionIdFollowsTheInvoker() {
        final SessionId userSession = SessionId.generate();
        final DefaultToolRegistry registry = new DefaultToolRegistry();
        final ContextCapturingTool probe = new ContextCapturingTool();
        registry.register(probe);

        runFork(userSession, List.of(new MemoryToolContextEnricher(WS, ALICE)), registry);

        assertThat(probe.captured).isNotNull();
        assertThat(probe.captured.get(MemoryRecallTool.SESSION_ID_KEY))
                .as("memory is only ever written under a user-facing conversation id, never under a fork's")
                .contains(userSession.value());
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private SubagentExecutionResult runFork(SessionId invokingSessionId, List<ToolContextEnricher> enrichers,
            DefaultToolRegistry toolRegistry) {
        final ScriptedLlmClient llm = new ScriptedLlmClient();
        if (toolRegistry.findByName(ContextCapturingTool.TOOL_NAME).isPresent()) {
            llm.responses.add(
                    LlmResponse.of("", List.of(ToolUse.of("t1", ContextCapturingTool.TOOL_NAME, Map.of("ping", "1")))));
        }
        llm.responses.add(LlmResponse.text("done"));

        final SubagentExecutionContext context = SubagentExecutionContext.builder()
                .agentRuntimeId(AgentRuntimeId.of("agent:test-1")).subagent(subagent())
                .defaultModel(LlmModel.builder().name("gpt-4").build()).toolRegistry(toolRegistry)
                .hookRegistry(new DefaultHookRegistry()).environment(Environment.createDefault())
                .toolContextEnrichers(enrichers).parentCancellationSignal(NoopCancellationSignal.INSTANCE).build();

        final SubagentExecutionRequest request = SubagentExecutionRequest.builder().taskId("task-1").goal("recall it")
                .invokingSessionId(invokingSessionId).build();

        return new DefaultSubagentExecutor(llm, new DefaultToolExecutionManager(), new DefaultHookExecutionManager())
                .execute(context, request);
    }

    private Subagent subagent() {
        return Subagent.of("recaller", SubagentMetadata.builder().description("d").maxIterations(5).build(),
                SubagentContent.of("you are recaller"));
    }

    /** Records the info handed to it; the executor builds exactly one per execution. */
    private static final class CapturingEnricher implements ToolContextEnricher {
        private ToolContextEnrichmentInfo info;

        @Override
        public void enrich(ToolContext.Builder builder, ToolContextEnrichmentInfo info) {
            this.info = info;
        }
    }

    /** Captures the assembled {@link ToolContext} — the only way to observe what the enrichers actually wrote. */
    private static final class ContextCapturingTool extends AbstractTool {
        private static final String TOOL_NAME = "Probe";

        private ToolContext captured;

        ContextCapturingTool() {
            super(TOOL_NAME, "captures the tool context", Map.of("type", "object", "properties", Map.of()));
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext context) {
            captured = context;
            return ToolResult.success("ok");
        }
    }

    /** Minimal scriptable LLM client: hands back queued responses, then a terminal text. */
    private static final class ScriptedLlmClient implements LlmClient {
        private final Deque<LlmResponse> responses = new ArrayDeque<>();

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig) {
            return sendMessage(systemPrompt, messages, tools, modelConfig, LlmCallMetadata.empty());
        }

        @Override
        public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                LlmModel modelConfig, LlmCallMetadata metadata) {
            return responses.isEmpty() ? LlmResponse.text("done") : responses.poll();
        }

        @Override
        public String getProviderName() {
            return "Scripted";
        }

    }
}
