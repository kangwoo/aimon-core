package at.aimon.session.testkit;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import at.aimon.core.agent.AgentExecutionResult;
import at.aimon.core.agent.SubmitOptions;
import at.aimon.core.agent.artifact.FileArtifact;
import at.aimon.core.agent.budget.CompletionReason;
import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.agent.session.LiveSession;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.SubmitOutcome;
import at.aimon.core.agent.stream.AgentExecutionEvent;

/**
 * Controllable {@link LiveSession} double the multi-node contract suite drives, whatever the backend underneath.
 *
 * <p>
 * {@link #submitAsync(String, SubmitOptions, Consumer)} returns an unresolved future, so a test can observe what the
 * router did with a turn before letting that turn finish; {@link #completeCurrentTurn(AgentExecutionResult)} releases
 * it. Nothing here knows about Redis, Postgres or MongoDB — it sits on the router's near side, which is why one copy
 * can serve every backend.
 *
 * <p>
 * There were three copies of this class, one per backend module, identical but for the sentence in each javadoc
 * saying it mirrored the others.
 */
public final class RecordingTestSession implements LiveSession {

    private final SessionId sessionId;
    private final AtomicReference<CompletableFuture<AgentExecutionResult>> current = new AtomicReference<>();
    private final AtomicReference<Consumer<AgentExecutionEvent>> currentListener = new AtomicReference<>();
    private final ConcurrentLinkedQueue<String> submittedInputs = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<InterruptReason> interrupts = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final CountDownLatch turnStarted = new CountDownLatch(1);
    private final CountDownLatch interrupted = new CountDownLatch(1);

    public RecordingTestSession(SessionId sessionId) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
    }

    @Override
    public SessionId getSessionId() {
        return sessionId;
    }

    @Override
    public AgentExecutionResult submit(String input, SubmitOptions submitOptions) {
        return submitAsync(input, submitOptions, e -> {
        }).toCompletableFuture().join();
    }

    @Override
    public CompletionStage<AgentExecutionResult> submitAsync(String input, SubmitOptions submitOptions,
            Consumer<AgentExecutionEvent> listener) {
        Objects.requireNonNull(input, "input must not be null");
        Objects.requireNonNull(submitOptions, "submitOptions must not be null");
        if (closed.get()) {
            throw new IllegalStateException("session closed");
        }
        submittedInputs.add(input);
        currentListener.set(listener);
        final CompletableFuture<AgentExecutionResult> f = new CompletableFuture<>();
        if (!current.compareAndSet(null, f)) {
            throw new IllegalStateException("test session already has a turn in flight");
        }
        turnStarted.countDown();
        return f;
    }

    /**
     * Emits an event to the in-flight turn's listener (the manager's {@code SessionEventRelay}) so multi-node tests can
     * drive the cross-node {@code EVENT} relay path. No-op when no turn is in flight.
     *
     * @param event
     *            the event to relay (must not be null)
     */
    public void emitEvent(AgentExecutionEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        final Consumer<AgentExecutionEvent> listener = currentListener.get();
        if (listener != null) {
            listener.accept(event);
        }
    }

    @Override
    public SubmitOutcome offerAsync(String input, SubmitOptions submitOptions, Consumer<AgentExecutionEvent> listener) {
        return SubmitOutcome.executed(submitAsync(input, submitOptions, listener));
    }

    @Override
    public void interrupt(InterruptReason reason) {
        Objects.requireNonNull(reason, "reason must not be null");
        interrupts.add(reason);
        interrupted.countDown();
    }

    @Override
    public void close() {
        closed.set(true);
    }

    public void completeCurrentTurn(AgentExecutionResult result) {
        final CompletableFuture<AgentExecutionResult> f = current.getAndSet(null);
        if (f == null) {
            throw new IllegalStateException("no turn in flight");
        }
        f.complete(result);
    }

    /**
     * Completes the in-flight turn, tolerating the window where {@link #submitAsync} has already recorded the input but
     * has not yet installed that turn's future. Retrying is safer than sleeping a fixed amount and cannot
     * double-complete: {@link #completeCurrentTurn} swaps the future out before it checks it for null, so a losing
     * attempt observes null and throws without touching a turn.
     *
     * @param result
     *            the terminal result to publish (must not be null)
     * @param millis
     *            how long to keep retrying
     * @throws InterruptedException
     *             if interrupted while waiting
     */
    public void completeWhenReady(AgentExecutionResult result, long millis) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + millis;
        while (true) {
            try {
                completeCurrentTurn(result);
                return;
            } catch (IllegalStateException noTurnYet) {
                if (System.currentTimeMillis() >= deadline) {
                    throw noTurnYet;
                }
                Thread.sleep(10);
            }
        }
    }

    public boolean awaitTurnStarted(long millis) throws InterruptedException {
        return turnStarted.await(millis, TimeUnit.MILLISECONDS);
    }

    /**
     * Waits until the manager has submitted at least {@code count} turns to this session — the second one being the
     * message it drained from the shared inbox on another node's behalf.
     *
     * @param count
     *            the number of submitted turns to wait for
     * @param millis
     *            how long to wait
     * @return {@code true} if the count was reached within the window
     * @throws InterruptedException
     *             if interrupted while waiting
     */
    public boolean awaitSubmittedInputs(int count, long millis) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + millis;
        while (submittedInputs.size() < count) {
            if (System.currentTimeMillis() >= deadline) {
                return false;
            }
            Thread.sleep(10);
        }
        return true;
    }

    public boolean awaitInterrupt(long millis) throws InterruptedException {
        return interrupted.await(millis, TimeUnit.MILLISECONDS);
    }

    public List<String> submittedInputs() {
        return List.copyOf(submittedInputs);
    }

    public List<InterruptReason> recordedInterrupts() {
        return List.copyOf(interrupts);
    }

    public boolean isClosed() {
        return closed.get();
    }

    public static AgentExecutionResult ok(String finalAnswer) {
        return new SimpleResult(true, finalAnswer);
    }

    private static final class SimpleResult implements AgentExecutionResult {
        private final boolean success;
        private final String finalAnswer;

        SimpleResult(boolean success, String finalAnswer) {
            this.success = success;
            this.finalAnswer = finalAnswer;
        }

        @Override
        public boolean isSuccess() {
            return success;
        }

        @Override
        public String getFinalAnswer() {
            return finalAnswer;
        }

        @Override
        public String getErrorMessage() {
            return null;
        }

        @Override
        public List<FileArtifact> getArtifacts() {
            return List.of();
        }

        @Override
        public CompletionReason getCompletionReason() {
            return success ? CompletionReason.COMPLETED : CompletionReason.ERROR;
        }
    }
}
