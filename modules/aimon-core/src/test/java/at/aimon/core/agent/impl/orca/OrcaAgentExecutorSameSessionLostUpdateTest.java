package at.aimon.core.agent.impl.orca;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.impl.orca.OrcaAgentExecutorTestSupport.ConcurrentScriptedLlmClient;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.llm.LlmResponse;

/**
 * Characterization test for the <b>unsupported</b> case of two turns racing on the <b>same</b> {@code sessionId}
 * at
 * once. The supported concurrency model is different sessions of the same agent; running two turns against one
 * session is a caller error. This test does not endorse that usage — it pins down that the executor
 * <i>tolerates</i>
 * it rather than corrupting global state: both turns are pinned together at the LLM-call boundary (proving genuine
 * overlap), and the contract we lock in is that each turn completes without a thrown exception or deadlock and returns
 * its own well-formed result (a deterministic final answer plus at least its own user+assistant pair in the returned
 * history). Which turn's write ultimately wins on the shared memory is deliberately left unspecified — this test
 * asserts
 * tolerance, not a particular last-writer-wins ordering.
 *
 * <p>
 * If a future change made concurrent same-session turns throw or deadlock, this test would fail and force an
 * explicit decision about the contract rather than letting the behavior drift silently.
 */
@DisplayName("OrcaAgentExecutor same-conversation concurrent turns (last-writer-wins characterization)")
class OrcaAgentExecutorSameSessionLostUpdateTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("two turns on one sessionId both complete cleanly with a well-formed result")
    void concurrentSameConversationTurnsAreTolerated() {
        final OrcaAgentExecutorTestSupport support = new OrcaAgentExecutorTestSupport(tempDir);

        // Stateless response for the shared id: every call returns "done", so the final answer is deterministic
        // regardless of how the two turns interleave on the shared session memory.
        final ConcurrentScriptedLlmClient client = new ConcurrentScriptedLlmClient();
        client.repeat("conv-shared", LlmResponse.text("done"));

        // Rendezvous both turns inside the LLM client so they provably overlap on the one session. Exactly two
        // calls happen (one terminal text response per turn), so a barrier of two always clears.
        final CyclicBarrier barrier = new CyclicBarrier(2);
        final AtomicInteger rendezvoused = new AtomicInteger();
        client.onEachCall((sessionId, messages) -> {
            try {
                barrier.await(5, TimeUnit.SECONDS);
                rendezvoused.incrementAndGet();
            } catch (TimeoutException | BrokenBarrierException ignored) {
                // Not overlapping: the turns still return "done"; the rendezvous assertion will surface it.
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        final OrcaAgentExecutor executor = support.newExecutor(client);
        final OrcaAgentRuntime context = support.newContext("agent:same-conv");

        final CompletableFuture<OrcaAgentExecutionResult> futureA = executor
                .executeAsync(context, OrcaAgentExecutorTestSupport.request(SessionId.of("conv-shared")), event -> {
                }).toCompletableFuture();
        final CompletableFuture<OrcaAgentExecutionResult> futureB = executor
                .executeAsync(context, OrcaAgentExecutorTestSupport.request(SessionId.of("conv-shared")), event -> {
                }).toCompletableFuture();

        final List<Throwable> errors = new CopyOnWriteArrayList<>();
        OrcaAgentExecutionResult resultA = null;
        OrcaAgentExecutionResult resultB = null;
        try {
            resultA = futureA.join();
        } catch (Throwable t) {
            errors.add(t);
        }
        try {
            resultB = futureB.join();
        } catch (Throwable t) {
            errors.add(t);
        }

        assertThat(rendezvoused.get()).as("both turns provably overlapped at the LLM-call boundary").isEqualTo(2);
        assertThat(errors).as("concurrent same-conversation turns must not throw").isEmpty();
        assertThat(resultA).isNotNull();
        assertThat(resultB).isNotNull();
        assertThat(resultA.getFinalAnswer()).isEqualTo("done");
        assertThat(resultB.getFinalAnswer()).isEqualTo("done");
        // Each turn's own result carries a well-formed history: append-only shared memory holds at least that turn's
        // user message plus its assistant response (>= 2), never a corrupted/empty view — regardless of interleaving.
        assertThat(resultA.getConversationHistory()).hasSizeGreaterThanOrEqualTo(2);
        assertThat(resultB.getConversationHistory()).hasSizeGreaterThanOrEqualTo(2);
    }
}
