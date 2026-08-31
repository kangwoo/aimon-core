package at.aimon.core.agent.impl.orca;

import static at.aimon.core.agent.impl.orca.OrcaAgentExecutorTestSupport.toolCall;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.budget.BudgetTracker;
import at.aimon.core.agent.impl.orca.OrcaAgentExecutorTestSupport.BarrierTool;
import at.aimon.core.agent.impl.orca.OrcaAgentExecutorTestSupport.ConcurrentScriptedLlmClient;
import at.aimon.core.agent.interrupt.InterruptCoordinator;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.llm.LlmResponse;

/**
 * Locks in the per-turn scope isolation contract: two sessions driven through one agent-scoped
 * {@link OrcaAgentExecutor} concurrently each build their own {@code ExecutionScope}, so the per-turn
 * {@link BudgetTracker} and {@link InterruptCoordinator} published to the request observers are <b>distinct
 * instances</b>
 * — never a shared singleton. If either were shared across turns, a budget trip or interrupt on one session would
 * bleed into the other; capturing both live objects while the two turns are pinned together on a {@link CyclicBarrier}
 * and asserting reference-distinctness guards that seam.
 *
 * <p>
 * The final answers are also asserted to route back to the correct session, so this doubles as a routing-integrity
 * check: the scripted client keys responses by {@code sessionId}, and a scope mix-up would surface as a swapped or
 * duplicated final answer.
 */
@DisplayName("OrcaAgentExecutor concurrent per-turn scope isolation (budget + interrupt)")
class OrcaAgentExecutorConcurrentTurnIsolationTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("concurrent turns get distinct per-turn budget trackers and interrupt coordinators")
    void concurrentTurnsHaveIsolatedPerTurnState() {
        final OrcaAgentExecutorTestSupport support = new OrcaAgentExecutorTestSupport(tempDir);

        final CyclicBarrier barrier = new CyclicBarrier(2);
        final BarrierTool gateA = new BarrierTool("gateA", barrier);
        final BarrierTool gateB = new BarrierTool("gateB", barrier);

        final ConcurrentScriptedLlmClient client = new ConcurrentScriptedLlmClient();
        client.script("conv-A", toolCall("ua-1", "gateA")).fallback("conv-A", LlmResponse.text("done-A"));
        client.script("conv-B", toolCall("ub-1", "gateB")).fallback("conv-B", LlmResponse.text("done-B"));

        final OrcaAgentExecutor executor = support.newExecutor(client);
        final OrcaAgentRuntime context = support.newContext("agent:turn-iso", gateA, gateB);

        final AtomicReference<BudgetTracker> budgetA = new AtomicReference<>();
        final AtomicReference<BudgetTracker> budgetB = new AtomicReference<>();
        final AtomicReference<InterruptCoordinator> coordA = new AtomicReference<>();
        final AtomicReference<InterruptCoordinator> coordB = new AtomicReference<>();

        final OrcaAgentExecutionRequest requestA = OrcaAgentExecutionRequest.builder().userInput("input for conv-A")
                .sessionId(SessionId.of("conv-A")).budgetObserver(budgetA::set).interruptObserver(coordA::set).build();
        final OrcaAgentExecutionRequest requestB = OrcaAgentExecutionRequest.builder().userInput("input for conv-B")
                .sessionId(SessionId.of("conv-B")).budgetObserver(budgetB::set).interruptObserver(coordB::set).build();

        final CompletableFuture<OrcaAgentExecutionResult> futureA = executor.executeAsync(context, requestA, event -> {
        }).toCompletableFuture();
        final CompletableFuture<OrcaAgentExecutionResult> futureB = executor.executeAsync(context, requestB, event -> {
        }).toCompletableFuture();

        final OrcaAgentExecutionResult resultA = futureA.join();
        final OrcaAgentExecutionResult resultB = futureB.join();

        assertThat(gateA.rendezvoused && gateB.rendezvoused).as("both turns must overlap so both scopes are live")
                .isTrue();

        assertThat(budgetA.get()).as("turn A published its budget tracker").isNotNull();
        assertThat(budgetB.get()).as("turn B published its budget tracker").isNotNull();
        assertThat(coordA.get()).as("turn A published its interrupt coordinator").isNotNull();
        assertThat(coordB.get()).as("turn B published its interrupt coordinator").isNotNull();

        assertThat(budgetA.get()).as("each concurrent turn owns a distinct BudgetTracker").isNotSameAs(budgetB.get());
        assertThat(coordA.get()).as("each concurrent turn owns a distinct InterruptCoordinator")
                .isNotSameAs(coordB.get());

        assertThat(resultA.getFinalAnswer()).as("final answers route back to the right conversation")
                .isEqualTo("done-A");
        assertThat(resultB.getFinalAnswer()).isEqualTo("done-B");
    }
}
