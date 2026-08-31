package at.aimon.core.agent.impl.orca;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import at.aimon.core.agent.AgentContent;
import at.aimon.core.agent.Environment;
import at.aimon.core.agent.prompt.Staticness;
import at.aimon.core.agent.prompt.SystemPromptPart;
import at.aimon.core.agent.prompt.SystemPromptParts;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.store.InMemorySessionRecordStore;
import at.aimon.core.agent.session.transcript.DefaultTranscriptManager;
import at.aimon.core.agent.session.transcript.TranscriptBuffer;
import at.aimon.core.agent.tool.DefaultToolExecutionManager;
import at.aimon.core.base.Principal;
import at.aimon.core.command.DefaultCommandExecutionManager;
import at.aimon.core.hook.DefaultHookExecutionManager;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.invoke.LlmCallGateway;
import at.aimon.core.llm.retry.LlmFallbackPolicy;
import at.aimon.core.llm.retry.LlmRetryPolicy;
import at.aimon.core.memory.MemoryContextProvider;
import at.aimon.core.memory.MemoryContextRequest;
import at.aimon.core.subagent.DefaultSubagentExecutionManager;

/**
 * SK-MEM Stage 9 regression: confirms the optional {@link MemoryContextProvider} pluggable into
 * {@link OrcaAgentExecutor} contributes a system prompt part on every turn (when present) and is invisible to the
 * concatenated prompt when absent.
 *
 * <p>
 * Most cases invoke {@code buildSystemPromptParts} directly so they do not depend on the LLM/ReAct loop — they are
 * structural checks on the prompt assembly path that the auto-injection wiring traverses. The last case runs a real
 * turn
 * instead, because what it checks — that the <b>executing turn's</b> session and caller reach the provider — is
 * precisely what the direct seam cannot show.
 */
@DisplayName("OrcaAgentExecutor system prompt — memory auto-injection")
class OrcaAgentExecutorMemoryInjectionTest {

    private static final String MEMORY_KIND = "memory";

    private OrcaAgentExecutor newExecutor(MemoryContextProvider provider) {
        final LlmClient client = Mockito.mock(LlmClient.class);
        return newExecutor(provider, client);
    }

    private OrcaAgentExecutor newExecutor(MemoryContextProvider provider, LlmClient client) {
        final DefaultToolExecutionManager toolManager = new DefaultToolExecutionManager();
        final DefaultHookExecutionManager hookManager = new DefaultHookExecutionManager();
        final DefaultCommandExecutionManager commandManager = new DefaultCommandExecutionManager(client);
        final DefaultSubagentExecutionManager subagentManager = new DefaultSubagentExecutionManager(client, toolManager,
                hookManager);
        final LlmCallGateway<TranscriptBuffer> gateway = LlmCallGateway.<TranscriptBuffer>builder().client(client)
                .retryPolicy(LlmRetryPolicy.defaultPolicy()).fallbackPolicy(LlmFallbackPolicy.none()).build();
        return OrcaAgentExecutor.builder().gateway(gateway)
                .transcriptManager(new DefaultTranscriptManager(new InMemorySessionRecordStore()))
                .toolExecutionManager(toolManager).hookExecutionManager(hookManager)
                .commandExecutionManager(commandManager).subagentExecutionManager(subagentManager)
                .memoryContextProvider(provider).build();
    }

    private AgentContent helloContent() {
        return AgentContent.builder().systemPrompt("Hello").build();
    }

    private Environment env() {
        return Environment.builder().workingDirectory("/x").platform("p").osVersion("o")
                .timeZone(ZoneId.systemDefault()).build();
    }

    @Test
    @DisplayName("memory provider absent → prompt parts shape unchanged from legacy non-memory path")
    void noProvider_promptUnchanged() {
        final OrcaAgentExecutor executor = newExecutor(null);

        final SystemPromptParts parts = executor.buildSystemPromptParts(helloContent(), Map.of(), env());

        // Same shape as OrcaAgentExecutorSystemPromptTest's two-part golden: agent-content + environment, no memory.
        final List<SystemPromptPart> list = parts.parts();
        assertThat(list).hasSize(2);
        assertThat(list).extracting(SystemPromptPart::getKind).containsExactly("agent-content", "environment");
        assertThat(parts.concatenated()).doesNotContain("Known about");
    }

    @Test
    @DisplayName("memory provider returns part → appended after environment with kind=memory and DYNAMIC staticness")
    void providerReturnsPart_appendedAfterEnvironment() {
        final SystemPromptPart memoryPart = SystemPromptPart.builder()
                .content("Known about ws-1:USER:alice\nscope: local\nSummary:\n alice prefers tea")
                .staticness(Staticness.DYNAMIC).kind(MEMORY_KIND).build();
        final OrcaAgentExecutor executor = newExecutor(request -> Optional.of(memoryPart));

        final SystemPromptParts parts = executor.buildSystemPromptParts(helloContent(), Map.of(), env());

        final List<SystemPromptPart> list = parts.parts();
        assertThat(list).hasSize(3);
        assertThat(list).extracting(SystemPromptPart::getKind).containsExactly("agent-content", "environment",
                MEMORY_KIND);
        assertThat(list.get(2).getStaticness()).isEqualTo(Staticness.DYNAMIC);
        assertThat(parts.concatenated()).contains("Known about ws-1:USER:alice").contains("alice prefers tea");
    }

    @Test
    @DisplayName("provider returns empty → no memory part added")
    void providerReturnsEmpty_noPartAdded() {
        final OrcaAgentExecutor executor = newExecutor(request -> Optional.empty());

        final SystemPromptParts parts = executor.buildSystemPromptParts(helloContent(), Map.of(), env());

        assertThat(parts.parts()).extracting(SystemPromptPart::getKind).doesNotContain(MEMORY_KIND);
    }

    @Test
    @DisplayName("provider throws → memory part skipped, other parts still produced")
    void providerThrows_skippedGracefully() {
        final OrcaAgentExecutor executor = newExecutor(request -> {
            throw new IllegalStateException("boom");
        });

        // The executor catches RuntimeException from the provider and proceeds; otherwise a transient memory-store
        // failure would block every turn.
        final SystemPromptParts parts = executor.buildSystemPromptParts(helloContent(), Map.of(), env());

        assertThat(parts.parts()).hasSize(2);
        assertThat(parts.parts()).extracting(SystemPromptPart::getKind).containsExactly("agent-content", "environment");
    }

    @Test
    @DisplayName("each turn hands the provider its own session and caller, not the ones baked in at construction")
    void turnIdentityReachesProvider(@TempDir Path tempDir) throws Exception {
        // The executor is built once and agent-scoped, so if it did not pass the executing turn's identity down, every
        // session would read the same peer's memory. Two turns through ONE executor is the only way to see that.
        final List<MemoryContextRequest> seen = new CopyOnWriteArrayList<>();
        final OrcaAgentExecutorTestSupport support = new OrcaAgentExecutorTestSupport(tempDir);
        final OrcaAgentExecutorTestSupport.ConcurrentScriptedLlmClient client = new OrcaAgentExecutorTestSupport.ConcurrentScriptedLlmClient();
        final OrcaAgentExecutor executor = newExecutor(request -> {
            seen.add(request);
            return Optional.empty();
        }, client);
        final OrcaAgentRuntime runtime = support.newContext();

        final Principal alice = Principal.user("alice", "Alice");
        executor.execute(runtime, OrcaAgentExecutionRequest.builder().userInput("hi").sessionId(SessionId.of("sess-A"))
                .principal(alice).build());
        executor.execute(runtime,
                OrcaAgentExecutionRequest.builder().userInput("hi").sessionId(SessionId.of("sess-B")).build());

        assertThat(seen).hasSize(2);
        assertThat(seen.get(0).getSessionId()).contains(SessionId.of("sess-A"));
        assertThat(seen.get(0).getPrincipal()).contains(alice);
        assertThat(seen.get(1).getSessionId()).contains(SessionId.of("sess-B"));
        // Not "the previous turn's caller" — an unidentified turn must arrive unidentified, or the resolver behind it
        // cannot tell an anonymous execution from Alice's.
        assertThat(seen.get(1).getPrincipal()).isEmpty();
    }
}
