package at.aimon.core.agent.impl.orca;

import static at.aimon.core.agent.impl.orca.OrcaAgentExecutorTestSupport.toolBatch;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.impl.orca.OrcaAgentExecutorTestSupport.ConcurrencyProbeTool;
import at.aimon.core.agent.impl.orca.OrcaAgentExecutorTestSupport.ConcurrentScriptedLlmClient;
import at.aimon.core.agent.tool.DefaultParallelToolDispatcher;
import at.aimon.core.agent.tool.ToolConcurrencyConfig;
import at.aimon.core.llm.LlmResponse;

/**
 * Locks in that the executor-scoped {@link DefaultParallelToolDispatcher} enforces a <b>global</b> concurrency ceiling
 * that holds across turns, not merely within a single batch. The dispatcher is an agent-scoped singleton shared by
 * every
 * session on the executor, so when two sessions each fire a batch of {@code CONCURRENT_SAFE} tools at the
 * same
 * time, the total number of tools executing simultaneously across both batches must never exceed the configured
 * {@code maxConcurrency}.
 *
 * <p>
 * Six probe tools (three per session) all report into one shared high-water-mark counter. With a pool cap of two,
 * six tools want to run but at most two ever do — the {@code <= 2} assertion is the ceiling invariant; the {@code >= 2}
 * assertion proves the pool actually parallelizes rather than silently serializing.
 *
 * <p>
 * Both turns are rendezvoused at their first LLM call via a {@link CountDownLatch}, so neither turn can dispatch (and
 * finish) its batch before the other has started: the two batches provably contend on the one shared pool <b>at the
 * same time</b>, which is what makes this a cross-turn ceiling test rather than two back-to-back single-batch runs.
 */
@DisplayName("OrcaAgentExecutor shared dispatcher global concurrency ceiling across turns")
class OrcaAgentExecutorConcurrentDispatcherCeilingTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("two concurrent batches share one pool: simultaneous tool executions never exceed maxConcurrency")
    void sharedPoolCeilingHoldsAcrossConcurrentTurns() {
        final int maxConcurrency = 2;
        final OrcaAgentExecutorTestSupport support = new OrcaAgentExecutorTestSupport(tempDir);

        // One shared high-water-mark across BOTH sessions' probe tools.
        final AtomicInteger active = new AtomicInteger();
        final AtomicInteger maxObserved = new AtomicInteger();
        final long holdMillis = 150L;

        final ConcurrencyProbeTool[] tools = {new ConcurrencyProbeTool("pa0", active, maxObserved, holdMillis),
                new ConcurrencyProbeTool("pa1", active, maxObserved, holdMillis),
                new ConcurrencyProbeTool("pa2", active, maxObserved, holdMillis),
                new ConcurrencyProbeTool("pb0", active, maxObserved, holdMillis),
                new ConcurrencyProbeTool("pb1", active, maxObserved, holdMillis),
                new ConcurrencyProbeTool("pb2", active, maxObserved, holdMillis)};

        final ConcurrentScriptedLlmClient client = new ConcurrentScriptedLlmClient();
        client.script("conv-A", toolBatch("pa", 3)).fallback("conv-A", LlmResponse.text("done-A"));
        client.script("conv-B", toolBatch("pb", 3)).fallback("conv-B", LlmResponse.text("done-B"));

        // Pin both turns together at their first LLM call so their batches dispatch concurrently onto the shared pool.
        // A CountDownLatch (not a CyclicBarrier) is used so the later iter-2 calls fall straight through instead of
        // waiting for a second rendezvous; every turn makes at least one call, so the latch always reaches zero and
        // this can never deadlock.
        final CountDownLatch bothTurnsInFlight = new CountDownLatch(2);
        client.onEachCall((sessionId, messages) -> {
            bothTurnsInFlight.countDown();
            try {
                bothTurnsInFlight.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        final OrcaAgentExecutor executor = support.newExecutor(client);
        // The enabled dispatcher lazily owns a fixed thread pool; close it in finally so its (daemon) worker
        // threads do not linger for the rest of the suite.
        final DefaultParallelToolDispatcher parallelDispatcher = new DefaultParallelToolDispatcher(
                ToolConcurrencyConfig.enabled(maxConcurrency));
        executor.parallelToolDispatcher = parallelDispatcher;
        final OrcaAgentRuntime context = support.newContext("agent:ceiling", tools);

        try {
            final CompletableFuture<OrcaAgentExecutionResult> futureA = executor
                    .executeAsync(context, OrcaAgentExecutorTestSupport.request("conv-A"), event -> {
                    }).toCompletableFuture();
            final CompletableFuture<OrcaAgentExecutionResult> futureB = executor
                    .executeAsync(context, OrcaAgentExecutorTestSupport.request("conv-B"), event -> {
                    }).toCompletableFuture();

            final OrcaAgentExecutionResult resultA = futureA.join();
            final OrcaAgentExecutionResult resultB = futureB.join();

            assertThat(resultA.isSuccess()).isTrue();
            assertThat(resultB.isSuccess()).isTrue();
            assertThat(maxObserved.get()).as("global pool ceiling must never be exceeded across concurrent turns")
                    .isLessThanOrEqualTo(maxConcurrency);
            assertThat(maxObserved.get()).as("the shared pool actually parallelizes up to the cap")
                    .isGreaterThanOrEqualTo(2);
            assertThat(active.get()).as("all probes released").isZero();
        } finally {
            executor.parallelToolDispatcher = DefaultParallelToolDispatcher.sequential();
            parallelDispatcher.close();
        }
    }
}
