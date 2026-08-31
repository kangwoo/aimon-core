package at.aimon.core.agent.impl.orca.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
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
import at.aimon.core.agent.input.ImageInput;
import at.aimon.core.agent.input.MultimodalInput;
import at.aimon.core.agent.input.TextInput;
import at.aimon.core.agent.input.UserInput;
import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.agent.session.DefaultLiveSession;
import at.aimon.core.agent.session.RewoundTurn;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.SubmitOutcome;
import at.aimon.core.agent.session.store.SessionRecordView;
import at.aimon.core.base.Principal;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.content.ImageContentBlock;

/**
 * L3 — retrying an interrupted turn, end to end through a {@link DefaultLiveSession}.
 *
 * <p>
 * Retrying is not the same as asking again, and the difference is the whole subject here. An interrupted turn leaves a
 * partial trail in the history — the user message, the assistant output produced before the stop, the tool results
 * including the ones filled in as skipped — and submitting the same request on top of it would ask the model to redo
 * work in a history that says it already half-did it. So the assertions are as much about what the transcript stops
 * containing as about the second attempt succeeding.
 *
 * <h2>Why the "clean history" assertion is the one that matters</h2>
 *
 * <p>
 * {@link #aRetriedTurnCompletes} alone would stay green if the rewind were deleted and the retry were a plain
 * resubmission — which is precisely the regression worth catching, because it is invisible in the result and shows up
 * only later as a model contradicting itself.
 * {@link #aRetryLeavesNoTraceOfTheInterruptedAttempt} is the test that fails in that world.
 *
 * <h2>Why the re-open case is here</h2>
 *
 * <p>
 * The rewind point is persisted rather than kept on the handle, and {@link #aSessionCanBeRetriedAfterBeingReopened} is
 * what that decision buys. An interrupt is exactly the moment a process is most likely to go away — a SIGINT, an
 * eviction, a node handing the session over — so a retry that only worked while the original handle lived would fail
 * precisely when it was most wanted.
 */
@DisplayName("RT-IT-L3: retrying a turn that was interrupted")
class TurnRetryIntegrationTest {

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

    private CompletableFuture<AgentExecutionResult> submit(DefaultLiveSession session, UserInput input) {
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

    /** Runs a turn that parks in the gate and stops it there, leaving the session with something to retry. */
    private void interruptAGatedTurn(DefaultLiveSession session, String input) {
        gate.rearm();
        final CompletableFuture<AgentExecutionResult> future = submit(session, input);
        gate.awaitArrival();
        session.interrupt(InterruptReason.USER_SIGINT);
        gate.release();
        assertThat(await(future).getCompletionReason()).isEqualTo(CompletionReason.INTERRUPTED);
    }

    private SessionRecordView storedRecord(SessionId sessionId) {
        return node.recordStore().load(sessionId).orElseThrow();
    }

    /**
     * The control every other test here leans on: what an uninterrupted turn leaves behind, and that it leaves nothing
     * to retry.
     */
    @Test
    @DisplayName("a turn that was never interrupted has nothing to retry")
    void aCompletedTurnIsNotRetryable() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        scriptGateThenAnswer(sessionId);
        final DefaultLiveSession session = node.openLiveSession(sessionId);

        final CompletableFuture<AgentExecutionResult> future = submit(session, "go");
        gate.awaitArrival();
        gate.release();
        assertThat(await(future).getCompletionReason()).isEqualTo(CompletionReason.COMPLETED);

        assertThat(storedRecord(sessionId).getRewindPoint()).isEmpty();
        assertThat(session.retryLastTurn()).isEmpty();
    }

    /** The mechanism fires: the second attempt runs the same request and this time it finishes. */
    @Test
    @DisplayName("an interrupted turn can be retried and completes")
    void aRetriedTurnCompletes() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        scriptGateThenAnswer(sessionId);
        final DefaultLiveSession session = node.openLiveSession(sessionId);

        interruptAGatedTurn(session, "go");
        assertThat(storedRecord(sessionId).getRewindPoint()).isPresent();

        // The second attempt answers straight away rather than parking again — the point under test is the rewind,
        // and a turn that does not touch the gate keeps the parking count at one per test.
        llm.script(sessionId.value(), ScriptedLlmClient.text("done"));

        assertThat(session.retryLastTurn().orElseThrow().getCompletionReason()).isEqualTo(CompletionReason.COMPLETED);
        assertThat(storedRecord(sessionId).getRewindPoint()).as("a completed retry is not itself retryable").isEmpty();
    }

    /**
     * The mechanism rewinds. The history after a successful retry has to look like the history of a session whose
     * first attempt never happened — not like one carrying a stopped turn followed by a second run of the same
     * request.
     */
    @Test
    @DisplayName("a retry leaves no trace of the interrupted attempt in the history")
    void aRetryLeavesNoTraceOfTheInterruptedAttempt() {
        // The reference: the same request answered in one go, on a session that was never interrupted.
        final SessionId cleanRun = OrcaRuntimeItSupport.newSession();
        llm.script(cleanRun.value(), ScriptedLlmClient.text("done"));
        await(submit(node.openLiveSession(cleanRun), "go"));
        final int messagesAfterOneCleanTurn = storedRecord(cleanRun).getMessages().size();

        final SessionId retried = OrcaRuntimeItSupport.newSession();
        scriptGateThenAnswer(retried);
        final DefaultLiveSession session = node.openLiveSession(retried);
        interruptAGatedTurn(session, "go");
        assertThat(storedRecord(retried).getMessages()).as("the stopped attempt did leave a trail behind")
                .hasSizeGreaterThan(messagesAfterOneCleanTurn);

        llm.script(retried.value(), ScriptedLlmClient.text("done"));
        session.retryLastTurn().orElseThrow();

        assertThat(storedRecord(retried).getMessages()).as("the stopped attempt must have been taken back out")
                .hasSize(messagesAfterOneCleanTurn);
    }

    /**
     * A rewind concurrent with a running turn is refused rather than quietly undone.
     *
     * <p>
     * It cannot work: the running turn holds its own copy of the history and writes it back when it ends, putting the
     * trail straight back. A caller who got an empty {@code Optional} or a silently reverted rewind would have no way
     * to tell that from a session with nothing to retry.
     */
    @Test
    @DisplayName("rewinding while a turn is running is refused")
    void rewindingDuringATurnIsRefused() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        scriptGateThenAnswer(sessionId);
        final DefaultLiveSession session = node.openLiveSession(sessionId);

        gate.rearm();
        final CompletableFuture<AgentExecutionResult> turn = submit(session, "go");
        gate.awaitArrival();

        assertThatThrownBy(session::rewindLastTurn).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("while a turn is running");

        gate.release();
        await(turn);
    }

    /**
     * What persisting the rewind point buys: the handle that ran the interrupted turn is gone, and the session is
     * still retryable when it is opened again.
     */
    @Test
    @DisplayName("a session interrupted under one handle can be retried under the next")
    void aSessionCanBeRetriedAfterBeingReopened() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        scriptGateThenAnswer(sessionId);
        final DefaultLiveSession first = node.openLiveSession(sessionId);
        interruptAGatedTurn(first, "go");
        first.close();

        final DefaultLiveSession reopened = node.openLiveSession(sessionId);
        llm.script(sessionId.value(), ScriptedLlmClient.text("done"));

        assertThat(reopened.retryLastTurn().orElseThrow().getCompletionReason()).isEqualTo(CompletionReason.COMPLETED);
    }

    /**
     * A retry that is itself stopped has to leave the session in the state it found it in, not one turn deeper.
     * Otherwise repeated attempts would each strand a trail, and the history would grow a copy of the request per
     * interrupt.
     */
    @Test
    @DisplayName("a retry that is interrupted again stays retryable, without stacking trails")
    void aRetryThatIsInterruptedAgainIsStillRetryable() {
        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        scriptGateThenAnswer(sessionId);
        final DefaultLiveSession session = node.openLiveSession(sessionId);

        interruptAGatedTurn(session, "go");
        final int afterFirstStop = storedRecord(sessionId).getMessages().size();

        scriptGateThenAnswer(sessionId);
        gate.rearm();
        final CompletableFuture<AgentExecutionResult> retry = CompletableFuture
                .supplyAsync(() -> session.retryLastTurn().orElseThrow());
        gate.awaitArrival();
        session.interrupt(InterruptReason.USER_SIGINT);
        gate.release();
        assertThat(await(retry).getCompletionReason()).isEqualTo(CompletionReason.INTERRUPTED);

        assertThat(storedRecord(sessionId).getRewindPoint()).isPresent();
        assertThat(storedRecord(sessionId).getMessages()).as("two stopped attempts must not leave two trails")
                .hasSize(afterFirstStop);
    }

    /**
     * The reason the rewind point keeps the request rather than the message built from it.
     *
     * <p>
     * An image has no text of its own — the message carrying it renders as {@code [Image: image/png, N bytes]} — so a
     * retry that read the input back out of the transcript could only submit that placeholder, which is a different
     * turn asking a different question. Keeping the {@link UserInput} means the second attempt puts the same bytes in
     * front of the model as the first one did.
     */
    @Test
    @DisplayName("a turn started by an image replays as the image, not as a description of it")
    void anInterruptedMultimodalTurnIsRetriedAsItself() {
        final byte[] png = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
        final UserInput ask = MultimodalInput.of(TextInput.of("what is in this screenshot?"),
                ImageInput.of(png, "image/png"));

        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        scriptGateThenAnswer(sessionId);
        final DefaultLiveSession session = node.openLiveSession(sessionId);

        gate.rearm();
        final CompletableFuture<AgentExecutionResult> future = submit(session, ask);
        gate.awaitArrival();
        session.interrupt(InterruptReason.USER_SIGINT);
        gate.release();
        assertThat(await(future).getCompletionReason()).isEqualTo(CompletionReason.INTERRUPTED);

        assertThat(storedRecord(sessionId).getRewindPoint().orElseThrow().getUserInput())
                .as("the request itself is what is kept, image bytes and all").isEqualTo(ask);

        llm.script(sessionId.value(), ScriptedLlmClient.text("done"));
        assertThat(session.retryLastTurn().orElseThrow().getCompletionReason()).isEqualTo(CompletionReason.COMPLETED);

        assertThat(imageBytesInHistory(sessionId)).as("the retried turn sent the image again, not a placeholder")
                .containsExactly(png);
    }

    /**
     * The other half of "the same turn again": who submitted it, and under what per-turn context.
     *
     * <p>
     * The principal reaches tool context and the memory request, so a retry that dropped it would run the same words
     * as a different caller — a quieter failure than replaying an image as its placeholder, and the same kind. The
     * no-argument {@code retryLastTurn()} therefore resubmits under the options the turn originally carried.
     */
    @Test
    @DisplayName("a retry runs under the options the original turn was submitted with")
    void aRetryKeepsTheOptionsTheTurnWasSubmittedUnder() {
        final SubmitOptions original = SubmitOptions.builder().principal(Principal.user("operator-7"))
                .systemPromptVariables(Map.of("tenant", "acme")).build();

        final SessionId sessionId = OrcaRuntimeItSupport.newSession();
        scriptGateThenAnswer(sessionId);
        final DefaultLiveSession session = node.openLiveSession(sessionId);

        gate.rearm();
        final SubmitOutcome outcome = session.offerAsync(TextInput.of("summarise the incident"), original, event -> {
        });
        gate.awaitArrival();
        session.interrupt(InterruptReason.USER_SIGINT);
        gate.release();
        assertThat(await(outcome.getResultStage().orElseThrow().toCompletableFuture()).getCompletionReason())
                .isEqualTo(CompletionReason.INTERRUPTED);

        assertThat(storedRecord(sessionId).getRewindPoint().orElseThrow().getSubmitOptions())
                .as("the options are recorded with the turn, not left behind").isEqualTo(original);

        llm.script(sessionId.value(), ScriptedLlmClient.text("done"));
        final RewoundTurn rewound = session.rewindLastTurn().orElseThrow();

        assertThat(rewound.getSubmitOptions()).as("and handed back for the second attempt").isEqualTo(original);
    }

    /** Every image the stored history carries, in order — the retried turn's included. */
    private List<byte[]> imageBytesInHistory(SessionId sessionId) {
        return storedRecord(sessionId).getMessages().stream().map(Message::getContentBlocks).flatMap(List::stream)
                .filter(ImageContentBlock.class::isInstance).map(ImageContentBlock.class::cast)
                .map(ImageContentBlock::getData).toList();
    }
}
