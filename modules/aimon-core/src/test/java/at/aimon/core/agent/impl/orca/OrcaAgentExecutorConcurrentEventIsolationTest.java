package at.aimon.core.agent.impl.orca;

import static at.aimon.core.agent.impl.orca.OrcaAgentExecutorTestSupport.toolCall;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.impl.orca.OrcaAgentExecutorTestSupport.BarrierTool;
import at.aimon.core.agent.impl.orca.OrcaAgentExecutorTestSupport.ConcurrentScriptedLlmClient;
import at.aimon.core.agent.stream.ToolUseStarted;
import at.aimon.core.llm.LlmResponse;

/**
 * Locks in the per-turn event-sink isolation contract (design note "H1"): when two sessions of the <b>same</b>
 * agent-scoped executor run concurrently, each {@code executeAsync} listener observes only its own turn's events. The
 * listener registered by {@code executeAsync} is attached to that execution's own {@code ExecutionScope} sink — not the
 * shared executor-level emitter — so a tool started in session A must never surface in session B's listener.
 *
 * <p>
 * The two turns are pinned together on a shared {@link CyclicBarrier} inside their tools, so the assertion runs only
 * after both turns are provably in-flight at the same instant. If the executor leaked events across turns (e.g. by
 * dispatching to a shared sink), session B's {@link ToolUseStarted} stream would contain session A's
 * {@code tool_use} id and the isolation assertion would fail.
 */
@DisplayName("OrcaAgentExecutor concurrent event-sink isolation (H1)")
class OrcaAgentExecutorConcurrentEventIsolationTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("each executeAsync listener sees only its own turn's ToolUseStarted events")
    void perTurnEventListenersDoNotCrossContaminate() {
        final OrcaAgentExecutorTestSupport support = new OrcaAgentExecutorTestSupport(tempDir);

        // One shared rendezvous proves both turns are genuinely in-flight together; each turn calls its own tool.
        final CyclicBarrier barrier = new CyclicBarrier(2);
        final BarrierTool gateA = new BarrierTool("gateA", barrier);
        final BarrierTool gateB = new BarrierTool("gateB", barrier);

        final ConcurrentScriptedLlmClient client = new ConcurrentScriptedLlmClient();
        client.script("conv-A", toolCall("ua-1", "gateA")).fallback("conv-A", LlmResponse.text("done-A"));
        client.script("conv-B", toolCall("ub-1", "gateB")).fallback("conv-B", LlmResponse.text("done-B"));

        final OrcaAgentExecutor executor = support.newExecutor(client);
        final OrcaAgentRuntime context = support.newContext("agent:evt-iso", gateA, gateB);

        final List<String> startedA = new CopyOnWriteArrayList<>();
        final List<String> startedB = new CopyOnWriteArrayList<>();

        final CompletableFuture<OrcaAgentExecutionResult> futureA = executor
                .executeAsync(context, OrcaAgentExecutorTestSupport.request("conv-A"), event -> {
                    if (event instanceof ToolUseStarted started) {
                        startedA.add(started.getToolUseId());
                    }
                }).toCompletableFuture();
        final CompletableFuture<OrcaAgentExecutionResult> futureB = executor
                .executeAsync(context, OrcaAgentExecutorTestSupport.request("conv-B"), event -> {
                    if (event instanceof ToolUseStarted started) {
                        startedB.add(started.getToolUseId());
                    }
                }).toCompletableFuture();

        final OrcaAgentExecutionResult resultA = futureA.join();
        final OrcaAgentExecutionResult resultB = futureB.join();

        assertThat(gateA.rendezvoused && gateB.rendezvoused).as("both turns must be in-flight together (rendezvous)")
                .isTrue();
        assertThat(resultA.isSuccess()).isTrue();
        assertThat(resultB.isSuccess()).isTrue();
        assertThat(resultA.getFinalAnswer()).isEqualTo("done-A");
        assertThat(resultB.getFinalAnswer()).isEqualTo("done-B");

        assertThat(startedA).as("conversation A's listener sees only A's tool start").containsExactly("ua-1");
        assertThat(startedB).as("conversation B's listener sees only B's tool start").containsExactly("ub-1");
    }
}
