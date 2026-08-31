package at.aimon.core.agent.impl.orca.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.AgentExecutionResult;
import at.aimon.core.agent.SubmitOptions;
import at.aimon.core.agent.budget.CompletionReason;
import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.agent.session.DefaultLiveSession;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.SubmitOutcome;
import at.aimon.core.agent.session.TurnId;

/**
 * L3 — interrupting a running turn, and the id that decides <b>which</b> turn gets interrupted.
 *
 * <p>
 * This is the first class in the suite to go through a {@link DefaultLiveSession} rather than driving the executor
 * directly, because turn targeting does not exist below that line: the executor knows only the one
 * {@code InterruptCoordinator} of the turn it is running, and has no way to answer "is this the turn the caller
 * meant?".
 * That question is answered by {@link DefaultLiveSession#interrupt(TurnId, InterruptReason)} comparing against the
 * {@link DefaultLiveSession#currentTurnId() active turn id}, so the assertions here have to be made against the handle.
 *
 * <h2>Why the drop case is the test that matters</h2>
 *
 * <p>
 * {@link #anInterruptAimedAtTheActiveTurnEndsIt} on its own would stay green if the {@code TurnId} comparison were
 * deleted and every interrupt forwarded unconditionally — which is precisely the regression worth catching, because it
 * is invisible until a slow user cancels a turn that has already settled and kills its successor instead.
 * {@link #anInterruptAimedAtADifferentTurnIsDropped} is the test that fails in that world, and the two are only
 * meaningful as a pair: the first proves the mechanism fires, the second proves it aims.
 *
 * <h2>Why a gate rather than a sleep</h2>
 *
 * <p>
 * An interrupt is only observable while a turn is in flight, and a turn here lasts microseconds. {@link GateTool} parks
 * the turn <em>inside</em> a tool call and hands the test two separate moments — arrival and release — so the interrupt
 * lands at a known point rather than at whatever point a {@code sleep} happened to leave the loop in. Its own javadoc
 * argues why a {@code CyclicBarrier} cannot serve. Once released, the parked tool returns normally and the loop reaches
 * its iteration-tail cancellation check: that check, not the death of the tool, is what ends the turn — the gate is
 * deliberately {@code NON_INTERRUPTIBLE} so nothing else can.
 */
@DisplayName("RT-IT-L3: interrupting a turn, and aiming the interrupt at the right one")
class TurnInterruptIntegrationTest {

    /** Bounds every stage join so a turn that never settles fails the test instead of hanging the build. */
    private static final int JOIN_TIMEOUT_SECONDS = 20;

    @TempDir
    Path tempDir;

    private OrcaRuntimeItSupport support;
    private ScriptedLlmClient llm;
    private GateTool gate;
    private OrcaRuntimeItSupport.Node node;

    @BeforeEach
    void setUp() {
        support = new OrcaRuntimeItSupport(tempDir);
        llm = new ScriptedLlmClient();
        gate = new GateTool();
        node = support.newNode("agent-a", llm,
                OrcaRuntimeItSupport.options().extraToolProvider(GateTool.provider(gate)));
    }

    @AfterEach
    void tearDown() {
        // Unblocks anything still parked before teardown, so a failed assertion cannot leave a turn holding the gate
        // for the full timeout.
        gate.release();
        support.close();
    }

    /** Park in the gate, then answer — two model calls when the turn is allowed to finish. */
    private void scriptGateThenAnswer(SessionId sessionId) {
        llm.script(sessionId.value(), ScriptedLlmClient.callTool(GateTool.TOOL_NAME, Map.of()),
                ScriptedLlmClient.text("done"));
    }

    private CompletableFuture<AgentExecutionResult> submit(DefaultLiveSession session, String input) {
        final SubmitOutcome outcome = session.offerAsync(input, SubmitOptions.empty(), event -> {
        });
        assertThat(outcome.getKind()).isEqualTo(SubmitOutcome.Kind.EXECUTED);
        return outcome.getResultStage().orElseThrow().toCompletableFuture();
    }

    private static AgentExecutionResult await(CompletableFuture<AgentExecutionResult> future) {
        try {
            return future.get(JOIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new IllegalStateException("turn did not settle within " + JOIN_TIMEOUT_SECONDS + "s", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("turn failed exceptionally", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while awaiting the turn", e);
        }
    }

    /**
     * The control the two targeting tests lean on: the same gated script, nothing interrupted, both calls made.
     *
     * <p>
     * Without it, "the interrupt was dropped" and "the gate never let the turn resume" would look identical.
     */
    @Test
    @DisplayName("a turn parked in a tool resumes and completes when nothing interrupts it")
    void aParkedTurnCompletesWhenNothingInterruptsIt() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        scriptGateThenAnswer(sessionId);
        final DefaultLiveSession session = node.openLiveSession(sessionId);

        final CompletableFuture<AgentExecutionResult> future = submit(session, "go");
        gate.awaitArrival();
        gate.release();
        final AgentExecutionResult result = await(future);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getFinalAnswer()).isEqualTo("done");
        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.COMPLETED);
        assertThat(llm.callCount(sessionId.value())).isEqualTo(2);
    }

    /**
     * The mechanism fires. The interrupt lands while the turn is parked; the gate is released so the tool returns
     * normally, and the loop's iteration-tail check ends the turn before the second model call goes out.
     */
    @Test
    @DisplayName("an interrupt aimed at the active turn ends it before the next model call")
    void anInterruptAimedAtTheActiveTurnEndsIt() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        scriptGateThenAnswer(sessionId);
        final DefaultLiveSession session = node.openLiveSession(sessionId);

        final CompletableFuture<AgentExecutionResult> future = submit(session, "go");
        gate.awaitArrival();
        final TurnId active = session.currentTurnId().orElseThrow();
        session.interrupt(active, InterruptReason.USER_SIGINT);
        gate.release();
        final AgentExecutionResult result = await(future);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.INTERRUPTED);
        assertThat(result.getErrorMessage()).isEqualTo("Execution interrupted");
        // The second entry of the script was never consumed: the interrupt cost the model a call.
        assertThat(llm.callCount(sessionId.value())).isEqualTo(1);
    }

    /**
     * The mechanism aims. Same setup, same parked turn, an interrupt for a turn id that is not the active one — and the
     * turn finishes as if nothing had happened.
     *
     * <p>
     * A fresh {@link TurnId#generate()} stands in for the realistic case: a cancel issued against a turn that has
     * already settled, arriving while its successor runs. Forwarding it would kill an innocent turn, and no assertion
     * in
     * {@link #anInterruptAimedAtTheActiveTurnEndsIt} would notice.
     */
    @Test
    @DisplayName("an interrupt aimed at a different turn is dropped and the active turn finishes")
    void anInterruptAimedAtADifferentTurnIsDropped() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        scriptGateThenAnswer(sessionId);
        final DefaultLiveSession session = node.openLiveSession(sessionId);

        final CompletableFuture<AgentExecutionResult> future = submit(session, "go");
        gate.awaitArrival();
        final TurnId active = session.currentTurnId().orElseThrow();
        final TurnId stale = TurnId.generate();
        assertThat(stale).isNotEqualTo(active);
        session.interrupt(stale, InterruptReason.USER_SIGINT);
        gate.release();
        final AgentExecutionResult result = await(future);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getFinalAnswer()).isEqualTo("done");
        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.COMPLETED);
        assertThat(llm.callCount(sessionId.value())).isEqualTo(2);
    }

    /**
     * The untargeted overload has no such guard by design — "cancel whatever is running" is what a Ctrl-C means — so it
     * ends the parked turn without the caller naming it.
     *
     * <p>
     * Pinning this is what stops {@link #anInterruptAimedAtADifferentTurnIsDropped} from being satisfiable by an
     * interrupt path that is simply broken: the same session, the same parked turn, no turn id, and the turn does die.
     */
    @Test
    @DisplayName("the untargeted interrupt ends whatever turn is running")
    void anUntargetedInterruptEndsTheActiveTurn() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        scriptGateThenAnswer(sessionId);
        final DefaultLiveSession session = node.openLiveSession(sessionId);

        final CompletableFuture<AgentExecutionResult> future = submit(session, "go");
        gate.awaitArrival();
        session.interrupt(InterruptReason.USER_SIGINT);
        gate.release();
        final AgentExecutionResult result = await(future);

        assertThat(result.getCompletionReason()).isEqualTo(CompletionReason.INTERRUPTED);
        assertThat(llm.callCount(sessionId.value())).isEqualTo(1);
    }

    /**
     * An interrupt that arrives when the session is idle must not be remembered. A latched trip would end the next turn
     * the session ran — the same "kills an innocent turn" failure as a mis-targeted one, arriving through a different
     * door.
     */
    @Test
    @DisplayName("an interrupt on an idle session is discarded and does not poison the next turn")
    void anInterruptOnAnIdleSessionDoesNotAffectTheNextTurn() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        scriptGateThenAnswer(sessionId);
        final DefaultLiveSession session = node.openLiveSession(sessionId);

        assertThat(session.currentTurnId()).isEmpty();
        session.interrupt(TurnId.generate(), InterruptReason.USER_SIGINT);
        session.interrupt(InterruptReason.USER_SIGINT);

        gate.release();
        final AgentExecutionResult result = await(submit(session, "go"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getFinalAnswer()).isEqualTo("done");
        assertThat(llm.callCount(sessionId.value())).isEqualTo(2);
    }

    /**
     * The id is published for exactly the duration of the turn. Both halves matter: unpublished, a targeted interrupt
     * could never match; left behind, the <em>next</em> caller's stale id would match and cancel a turn it does not
     * name.
     */
    @Test
    @DisplayName("the active turn id is published while the turn runs and cleared when it settles")
    void theActiveTurnIdIsPublishedOnlyForTheDurationOfTheTurn() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        scriptGateThenAnswer(sessionId);
        final DefaultLiveSession session = node.openLiveSession(sessionId);

        assertThat(session.currentTurnId()).isEmpty();

        final CompletableFuture<AgentExecutionResult> future = submit(session, "go");
        gate.awaitArrival();
        assertThat(session.currentTurnId()).isPresent();

        gate.release();
        await(future);

        assertThat(session.currentTurnId()).isEmpty();
    }
}
