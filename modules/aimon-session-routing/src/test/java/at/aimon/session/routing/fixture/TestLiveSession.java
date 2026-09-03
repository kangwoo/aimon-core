package at.aimon.session.routing.fixture;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
import at.aimon.core.agent.session.TurnId;
import at.aimon.core.agent.stream.AgentExecutionEvent;

/**
 * Controllable {@link LiveSession} double for WS-02 integration tests.
 *
 * <p>
 * The session is single-turn at a time — {@link #submitAsync(String, Consumer)} returns an incomplete future. Tests
 * call {@link #completeCurrentTurn(AgentExecutionResult)} (or {@link #failCurrentTurn(Throwable)}) to release it. The
 * interrupt latch and the close flag let assertions verify the manager's lifecycle behavior.
 *
 * <p>
 * Turn addressing mirrors {@code DefaultLiveSession} rather than inheriting the interface defaults: the double records
 * every {@link TurnId} it is submitted under, publishes the running one through {@link #currentTurnId()}, and drops an
 * addressed {@link #interrupt(TurnId, InterruptReason)} that names a turn other than the active one. Without that, a
 * manager-level test could not tell a correctly-addressed interrupt from one the manager broadened into a session-wide
 * stop.
 */
public final class TestLiveSession implements LiveSession {

    private final SessionId sessionId;
    private final AtomicReference<CompletableFuture<AgentExecutionResult>> current = new AtomicReference<>();
    private final AtomicReference<Consumer<AgentExecutionEvent>> currentListener = new AtomicReference<>();
    private final ConcurrentLinkedQueue<String> submittedInputs = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<SubmitOptions> submittedOptions = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<TurnId> submittedTurnIds = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<InterruptReason> interrupts = new ConcurrentLinkedQueue<>();
    private final AtomicReference<TurnId> activeTurnId = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final CountDownLatch turnStarted = new CountDownLatch(1);
    private final CountDownLatch interrupted = new CountDownLatch(1);
    private final AtomicReference<CountDownLatch> closeGate = new AtomicReference<>();
    private final CountDownLatch closeEntered = new CountDownLatch(1);
    private final AtomicReference<String> closingThreadName = new AtomicReference<>();

    public TestLiveSession(SessionId sessionId) {
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
        Objects.requireNonNull(listener, "listener must not be null");
        if (closed.get()) {
            throw new IllegalStateException("session closed");
        }
        submittedInputs.add(input);
        submittedOptions.add(submitOptions);
        currentListener.set(listener);
        final CompletableFuture<AgentExecutionResult> f = new CompletableFuture<>();
        if (!current.compareAndSet(null, f)) {
            // Tests assume one turn at a time — fail loud.
            throw new IllegalStateException("test session already has a turn in flight");
        }
        turnStarted.countDown();
        return f;
    }

    /**
     * Records {@code turnId} and publishes it as the active turn for the duration of the turn.
     *
     * <p>
     * The id is installed <em>before</em> the turn-started latch trips inside the delegate, so a test that waits on
     * that
     * latch and immediately addresses an interrupt cannot lose the race.
     */
    @Override
    public CompletionStage<AgentExecutionResult> submitAsync(TurnId turnId, String input, SubmitOptions submitOptions,
            Consumer<AgentExecutionEvent> listener) {
        Objects.requireNonNull(turnId, "turnId must not be null");
        submittedTurnIds.add(turnId);
        activeTurnId.set(turnId);
        try {
            return submitAsync(input, submitOptions, listener);
        } catch (RuntimeException e) {
            activeTurnId.compareAndSet(turnId, null);
            throw e;
        }
    }

    @Override
    public Optional<TurnId> currentTurnId() {
        return Optional.ofNullable(activeTurnId.get());
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

    /** Mirrors {@code DefaultLiveSession}: a mismatch (or an idle session) is dropped, not broadened. */
    @Override
    public void interrupt(TurnId turnId, InterruptReason reason) {
        Objects.requireNonNull(turnId, "turnId must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        if (turnId.equals(activeTurnId.get())) {
            interrupt(reason);
        }
    }

    @Override
    public void close() {
        // Recorded before the gate below parks, so a test that blocks the close can still read who is performing it.
        closingThreadName.set(Thread.currentThread().getName());
        final CountDownLatch gate = closeGate.get();
        if (gate != null) {
            closeEntered.countDown();
            try {
                // Bounded: a test that forgets to release must fail on an assertion, not hang the suite.
                gate.await(DEFAULT_AWAIT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        closed.set(true);
    }

    /**
     * Makes the next {@link #close()} park until {@link #releaseClose()}, so a test can occupy the window in which a
     * session is out of the cache but not yet gone. A real session closes over a flush, a last event or a remote
     * teardown; this makes that duration controllable rather than instantaneous.
     */
    public void blockClose() {
        closeGate.set(new CountDownLatch(1));
    }

    /** Waits up to {@link #DEFAULT_AWAIT_MS} for a parked {@link #close()} to be entered. */
    public boolean awaitCloseEntered() throws InterruptedException {
        return closeEntered.await(DEFAULT_AWAIT_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Name of the thread the most recent {@link #close()} ran on, or {@code null} before the first one.
     *
     * <p>
     * Closing a session is where a deployment's session-end hooks run, so which executor performs it is a structural
     * claim about the manager's wiring rather than an incidental detail — and the thread name is the only place that
     * claim is observable from outside.
     *
     * @return the thread name, or {@code null}
     */
    public String closingThreadName() {
        return closingThreadName.get();
    }

    /** Lets a parked {@link #close()} finish. */
    public void releaseClose() {
        final CountDownLatch gate = closeGate.get();
        if (gate != null) {
            gate.countDown();
        }
    }

    public void emit(AgentExecutionEvent event) {
        final Consumer<AgentExecutionEvent> listener = currentListener.get();
        if (listener != null) {
            listener.accept(event);
        }
    }

    public void completeCurrentTurn(AgentExecutionResult result) {
        final CompletableFuture<AgentExecutionResult> f = current.getAndSet(null);
        currentListener.set(null);
        activeTurnId.set(null);
        if (f == null) {
            throw new IllegalStateException("no turn in flight");
        }
        f.complete(result);
    }

    public void failCurrentTurn(Throwable t) {
        final CompletableFuture<AgentExecutionResult> f = current.getAndSet(null);
        currentListener.set(null);
        activeTurnId.set(null);
        if (f == null) {
            throw new IllegalStateException("no turn in flight");
        }
        f.completeExceptionally(t);
    }

    /**
     * Default upper bound for the {@code await*()} polling helpers. These are latch/poll waits that return as soon as
     * the condition holds, so this budget is only ever spent when something is genuinely wrong; it is set generously
     * (well above the ~ms the happy path needs) so a loaded CI runner cannot spuriously time out (issue #18). Prefer
     * the
     * no-arg overloads over passing a tight literal.
     */
    public static final long DEFAULT_AWAIT_MS = 5_000L;

    public boolean awaitTurnStarted(long millis) throws InterruptedException {
        return turnStarted.await(millis, TimeUnit.MILLISECONDS);
    }

    /** Waits up to {@link #DEFAULT_AWAIT_MS} for the turn to start. */
    public boolean awaitTurnStarted() throws InterruptedException {
        return awaitTurnStarted(DEFAULT_AWAIT_MS);
    }

    /**
     * Waits until a turn is in flight and therefore completable, or the deadline elapses.
     *
     * <p>
     * Unlike {@link #awaitTurnStarted()} this is not a one-shot latch, so it is the helper to use for the second and
     * later turns of a session — the latch trips once and every subsequent wait on it returns immediately, which lets a
     * test race ahead of the turn it meant to wait for. It is also tighter than {@link #awaitTurnCount(int)}: the input
     * is recorded a few instructions before the future exists, so a test that completes the turn on the count alone can
     * still find nothing in flight.
     *
     * @param millis
     *            the wall-clock budget
     * @return {@code true} when a turn became completable within the budget
     */
    public boolean awaitTurnInFlight(long millis) throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (current.get() == null) {
            if (System.nanoTime() >= deadline) {
                return false;
            }
            Thread.sleep(10);
        }
        return true;
    }

    /** Waits up to {@link #DEFAULT_AWAIT_MS} for a turn to become completable. */
    public boolean awaitTurnInFlight() throws InterruptedException {
        return awaitTurnInFlight(DEFAULT_AWAIT_MS);
    }

    /**
     * Waits until {@link #close()} has finished, or the deadline elapses.
     *
     * <p>
     * The release path closes a session from the cache's eviction listener, on a thread the test does not hold, so
     * {@link #isClosed()} read straight after a release samples a race rather than an outcome. Awaiting it is what the
     * neighbouring assertions in that test already do for every other effect they check.
     *
     * @param millis
     *            the wall-clock budget
     * @return {@code true} once the session is closed within the budget
     */
    public boolean awaitClosed(long millis) throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (!closed.get()) {
            if (System.nanoTime() >= deadline) {
                return false;
            }
            Thread.sleep(10);
        }
        return true;
    }

    /** Waits up to {@link #DEFAULT_AWAIT_MS} for {@link #close()} to finish. */
    public boolean awaitClosed() throws InterruptedException {
        return awaitClosed(DEFAULT_AWAIT_MS);
    }

    public boolean awaitInterrupt(long millis) throws InterruptedException {
        return interrupted.await(millis, TimeUnit.MILLISECONDS);
    }

    /** Waits up to {@link #DEFAULT_AWAIT_MS} for an interrupt. */
    public boolean awaitInterrupt() throws InterruptedException {
        return awaitInterrupt(DEFAULT_AWAIT_MS);
    }

    /**
     * Waits until at least {@code target} inputs have been observed by {@link #submitAsync}, or the deadline elapses.
     *
     * @param target
     *            the expected cumulative submitted-input count
     * @param millis
     *            the wall-clock budget
     * @return {@code true} when the count was reached within the budget
     */
    public boolean awaitTurnCount(int target, long millis) throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (submittedInputs.size() < target) {
            if (System.nanoTime() >= deadline) {
                return false;
            }
            Thread.sleep(10);
        }
        return true;
    }

    /** Waits up to {@link #DEFAULT_AWAIT_MS} for at least {@code target} submitted inputs. */
    public boolean awaitTurnCount(int target) throws InterruptedException {
        return awaitTurnCount(target, DEFAULT_AWAIT_MS);
    }

    public List<String> submittedInputs() {
        return List.copyOf(submittedInputs);
    }

    public List<SubmitOptions> submittedOptions() {
        return List.copyOf(submittedOptions);
    }

    /** The turn ids this session ran turns under, in submit order. */
    public List<TurnId> submittedTurnIds() {
        return List.copyOf(submittedTurnIds);
    }

    public List<InterruptReason> recordedInterrupts() {
        return List.copyOf(interrupts);
    }

    public boolean isClosed() {
        return closed.get();
    }

    public static AgentExecutionResult ok(String finalAnswer) {
        return new SimpleResult(true, finalAnswer, null);
    }

    private static final class SimpleResult implements AgentExecutionResult {
        private final boolean success;
        private final String finalAnswer;
        private final String errorMessage;

        SimpleResult(boolean success, String finalAnswer, String errorMessage) {
            this.success = success;
            this.finalAnswer = finalAnswer;
            this.errorMessage = errorMessage;
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
            return errorMessage;
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
