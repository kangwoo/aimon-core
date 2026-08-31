package at.aimon.core.agent.impl.orca;

import static at.aimon.core.agent.impl.orca.OrcaAgentExecutorTestSupport.toolCall;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.impl.orca.OrcaAgentExecutorTestSupport.ConcurrentScriptedLlmClient;
import at.aimon.core.agent.impl.orca.OrcaAgentExecutorTestSupport.EchoTool;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.llm.LlmResponse;

/**
 * A soak / corruption-net test: it fans a large number of distinct sessions through one agent-scoped
 * {@link OrcaAgentExecutor} on a bounded thread pool and asserts that every turn returns exactly its own scripted final
 * answer. Each session's response is keyed by {@code sessionId}, so any cross-turn state bleed — a swapped
 * scope, a shared cursor, a mis-routed response, a corrupted conversation history — would surface as a wrong or missing
 * final answer, or as a per-session call count other than the expected two.
 *
 * <p>
 * This does not assert a specific defect; it locks in that the supported mode (concurrent turns of <b>different</b>
 * sessions) stays correct under sustained parallel load.
 */
@DisplayName("OrcaAgentExecutor concurrent-turn soak (cross-turn corruption net)")
class OrcaAgentExecutorConcurrentTurnStressTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("many distinct conversations run concurrently and each returns its own final answer")
    void manyConcurrentTurnsStayIsolated() throws Exception {
        final int sessions = 48;
        final int threads = 8;
        final OrcaAgentExecutorTestSupport support = new OrcaAgentExecutorTestSupport(tempDir);

        final ConcurrentScriptedLlmClient client = new ConcurrentScriptedLlmClient();
        final List<String> ids = new ArrayList<>();
        for (int i = 0; i < sessions; i++) {
            final String id = "conv-" + i;
            ids.add(id);
            // Two LLM calls per turn: iter1 calls the shared echo tool, iter2 returns this session's unique
            // answer.
            client.script(id, toolCall("t-" + id, "echo")).fallback(id, LlmResponse.text("done-" + id));
        }

        final OrcaAgentExecutor executor = support.newExecutor(client);
        final OrcaAgentRuntime context = support.newContext("agent:soak", new EchoTool("echo"));

        final ExecutorService pool = Executors.newFixedThreadPool(threads);
        final CountDownLatch startGate = new CountDownLatch(1);
        try {
            final List<Future<OrcaAgentExecutionResult>> futures = new ArrayList<>();
            for (String id : ids) {
                futures.add(pool.submit(() -> {
                    startGate.await();
                    return executor.execute(context, OrcaAgentExecutorTestSupport.request(SessionId.of(id)));
                }));
            }

            startGate.countDown();

            for (int i = 0; i < sessions; i++) {
                final String id = ids.get(i);
                final OrcaAgentExecutionResult result = futures.get(i).get(30, TimeUnit.SECONDS);
                assertThat(result.isSuccess()).as("turn %s succeeds", id).isTrue();
                assertThat(result.getFinalAnswer()).as("turn %s returns its own answer", id).isEqualTo("done-" + id);
                assertThat(client.callCount(id)).as("turn %s routed exactly its own two LLM calls", id).isEqualTo(2);
            }
            assertThat(client.totalCalls()).as("no stray or cross-routed LLM calls").isEqualTo(2 * sessions);
        } finally {
            pool.shutdownNow();
        }
    }
}
