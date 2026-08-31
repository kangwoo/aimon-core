package at.aimon.core.agent.impl.orca;

import static at.aimon.core.agent.impl.orca.OrcaAgentExecutorTestSupport.toolCall;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.budget.BudgetTracker;
import at.aimon.core.agent.impl.orca.OrcaAgentExecutorTestSupport.ConcurrentScriptedLlmClient;
import at.aimon.core.agent.impl.orca.OrcaAgentExecutorTestSupport.EchoTool;
import at.aimon.core.agent.impl.orca.OrcaAgentExecutorTestSupport.GateTool;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.llm.LlmResponse;

/**
 * The per-turn {@link BudgetTracker} is published to the request's budget observer so session-level callers (e.g.
 * {@code DefaultLiveSession.status()}) can read live iteration / token / elapsed counters <b>while the turn is still
 * running on another thread</b>. This test locks in that those concurrent reads are crash-free: a fleet of reader
 * threads hammers every accessor on the live tracker across several mutating iterations of the turn, and none may
 * throw.
 *
 * <p>
 * The turn is pinned inside its first tool (a {@link GateTool}) only long enough to guarantee the tracker has been
 * captured before the readers start; it then runs two further tool iterations (each of which mutates the tracker via
 * {@code recordIteration}/{@code recordTokens}) with the readers still spinning, so the reads genuinely overlap live
 * writes rather than observing a quiesced object.
 */
@DisplayName("OrcaAgentExecutor concurrent live budget-tracker reads (status seam)")
class OrcaAgentExecutorBudgetObserverConcurrencyTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("hammering the live BudgetTracker from many threads during a running turn never throws")
    void concurrentBudgetReadsAreCrashFree() throws InterruptedException {
        final OrcaAgentExecutorTestSupport support = new OrcaAgentExecutorTestSupport(tempDir);

        final CountDownLatch gateEntered = new CountDownLatch(1);
        final CountDownLatch gateRelease = new CountDownLatch(1);
        final GateTool gate = new GateTool("gate", gateEntered, gateRelease);
        final EchoTool echo = new EchoTool("echo");

        // iter1 gate (pins the turn so the tracker is provably captured), iter2/iter3 echo (mutate the tracker), done.
        final ConcurrentScriptedLlmClient client = new ConcurrentScriptedLlmClient();
        client.script("conv-1", toolCall("g", "gate"), toolCall("e1", "echo"), toolCall("e2", "echo"))
                .fallback("conv-1", LlmResponse.text("done"));

        final OrcaAgentExecutor executor = support.newExecutor(client);
        final OrcaAgentRuntime context = support.newContext("agent:budget-reads", gate, echo);

        final AtomicReference<BudgetTracker> trackerRef = new AtomicReference<>();
        final OrcaAgentExecutionRequest request = OrcaAgentExecutionRequest.builder()
                .userInput("input for " + OrcaAgentExecutorTestSupport.sessionMarker("conv-1"))
                .sessionId(SessionId.of("conv-1")).budgetObserver(trackerRef::set).build();

        final CompletableFuture<OrcaAgentExecutionResult> future = executor.executeAsync(context, request, event -> {
        }).toCompletableFuture();

        // Wait until the turn is inside the first tool: the budget observer fired at loop start, so the tracker is set.
        assertThat(gateEntered.await(5, TimeUnit.SECONDS)).as("turn reached the gate tool").isTrue();
        final BudgetTracker tracker = trackerRef.get();
        assertThat(tracker).as("budget tracker was published to the observer").isNotNull();

        final int readers = 6;
        final AtomicBoolean stop = new AtomicBoolean(false);
        final List<Throwable> failures = new CopyOnWriteArrayList<>();
        final CountDownLatch readersReady = new CountDownLatch(readers);
        final List<Thread> readerThreads = new CopyOnWriteArrayList<>();
        for (int i = 0; i < readers; i++) {
            final Thread t = new Thread(() -> {
                readersReady.countDown();
                while (!stop.get()) {
                    try {
                        tracker.iterations();
                        tracker.tokens();
                        tracker.cost();
                        tracker.elapsed();
                        tracker.check();
                        tracker.getStopReason();
                    } catch (Throwable ex) {
                        failures.add(ex);
                        return;
                    }
                }
            }, "budget-reader-" + i);
            // Daemon so a missed stop can never keep the JVM alive; the finally below is the primary cleanup.
            t.setDaemon(true);
            readerThreads.add(t);
            t.start();
        }
        assertThat(readersReady.await(5, TimeUnit.SECONDS)).as("all reader threads started").isTrue();

        // Let the turn proceed through its mutating iterations while the readers spin.
        gateRelease.countDown();
        final OrcaAgentExecutionResult result;
        try {
            result = future.join();
        } finally {
            // Always stop and join the readers even if the turn threw: otherwise they would keep spinning past the
            // test.
            stop.set(true);
            for (Thread t : readerThreads) {
                t.join(TimeUnit.SECONDS.toMillis(5));
            }
        }

        assertThat(failures).as("no concurrent read of the live budget tracker may throw").isEmpty();
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getFinalAnswer()).isEqualTo("done");
        assertThat(result.getIterationCount()).as("the turn ran multiple mutating iterations")
                .isGreaterThanOrEqualTo(3);
    }
}
