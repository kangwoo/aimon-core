package at.aimon.bootstrap.assemble;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.function.Consumer;

import at.aimon.bootstrap.runtime.AgentRuntimeLease;
import at.aimon.core.agent.AgentExecutionResult;
import at.aimon.core.agent.SubmitOptions;
import at.aimon.core.agent.input.UserInput;
import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.agent.queue.QueuedInput;
import at.aimon.core.agent.session.LiveSession;
import at.aimon.core.agent.session.LiveSessionStatus;
import at.aimon.core.agent.session.RewoundTurn;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.SubmitOutcome;
import at.aimon.core.agent.session.TurnId;
import at.aimon.core.agent.stream.AgentExecutionEvent;

/**
 * A live session that holds an {@link AgentRuntimeLease} for exactly as long as it exists.
 *
 * <p>
 * A tenant runtime may be reclaimed when nothing is using it, and a cached live session is a use: it is a handle
 * on that runtime's tool registry, hook registry and MCP clients, and it can start a turn at any moment. Nothing
 * about it looks busy to a sweeper between turns, though, so without a lease the resolver would be free to close
 * a runtime that a warm handle is about to execute against. Binding the lease to the handle's lifetime makes the
 * two idle timers agree: the runtime becomes reclaimable when the last live session on it is evicted or released,
 * not while one is still cached.
 *
 * <p>
 * Every method delegates — including the ones {@link LiveSession} declares {@code default}. Inheriting a default
 * here would not fail to compile; it would quietly answer for the wrapper instead of the session, and
 * {@code status()} would report a permanently idle session while a turn was running.
 * {@code LeasedLiveSessionTest} checks that by reflection, so a method added to the interface later breaks the
 * build rather than the behaviour.
 */
final class LeasedLiveSession implements LiveSession {

    private final LiveSession delegate;
    private final AgentRuntimeLease lease;

    LeasedLiveSession(LiveSession delegate, AgentRuntimeLease lease) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.lease = Objects.requireNonNull(lease, "lease must not be null");
    }

    @Override
    public SessionId getSessionId() {
        return delegate.getSessionId();
    }

    @Override
    public LiveSessionStatus status() {
        return delegate.status();
    }

    @Override
    public AgentExecutionResult submit(String input) {
        return delegate.submit(input);
    }

    @Override
    public AgentExecutionResult submit(String input, SubmitOptions submitOptions) {
        return delegate.submit(input, submitOptions);
    }

    @Override
    public AgentExecutionResult submit(UserInput input) {
        return delegate.submit(input);
    }

    @Override
    public AgentExecutionResult submit(UserInput input, SubmitOptions submitOptions) {
        return delegate.submit(input, submitOptions);
    }

    @Override
    public CompletionStage<AgentExecutionResult> submitAsync(String input, Consumer<AgentExecutionEvent> listener) {
        return delegate.submitAsync(input, listener);
    }

    @Override
    public CompletionStage<AgentExecutionResult> submitAsync(String input, SubmitOptions submitOptions,
            Consumer<AgentExecutionEvent> listener) {
        return delegate.submitAsync(input, submitOptions, listener);
    }

    @Override
    public CompletionStage<AgentExecutionResult> submitAsync(TurnId turnId, String input, SubmitOptions submitOptions,
            Consumer<AgentExecutionEvent> listener) {
        return delegate.submitAsync(turnId, input, submitOptions, listener);
    }

    @Override
    public CompletionStage<AgentExecutionResult> submitAsync(UserInput input, SubmitOptions submitOptions,
            Consumer<AgentExecutionEvent> listener) {
        return delegate.submitAsync(input, submitOptions, listener);
    }

    @Override
    public CompletionStage<AgentExecutionResult> submitAsync(TurnId turnId, UserInput input,
            SubmitOptions submitOptions, Consumer<AgentExecutionEvent> listener) {
        return delegate.submitAsync(turnId, input, submitOptions, listener);
    }

    @Override
    public Optional<TurnId> currentTurnId() {
        return delegate.currentTurnId();
    }

    @Override
    public SubmitOutcome offerAsync(String input, Consumer<AgentExecutionEvent> listener) {
        return delegate.offerAsync(input, listener);
    }

    @Override
    public SubmitOutcome offerAsync(String input, SubmitOptions submitOptions, Consumer<AgentExecutionEvent> listener) {
        return delegate.offerAsync(input, submitOptions, listener);
    }

    @Override
    public SubmitOutcome offerAsync(UserInput input, SubmitOptions submitOptions,
            Consumer<AgentExecutionEvent> listener) {
        return delegate.offerAsync(input, submitOptions, listener);
    }

    @Override
    public void interrupt(InterruptReason reason) {
        delegate.interrupt(reason);
    }

    @Override
    public void interrupt(TurnId turnId, InterruptReason reason) {
        delegate.interrupt(turnId, reason);
    }

    @Override
    public void enqueueMidTurnInput(QueuedInput input) {
        delegate.enqueueMidTurnInput(input);
    }

    @Override
    public Optional<AgentExecutionResult> retryLastTurn() {
        return delegate.retryLastTurn();
    }

    @Override
    public Optional<AgentExecutionResult> retryLastTurn(SubmitOptions submitOptions) {
        return delegate.retryLastTurn(submitOptions);
    }

    @Override
    public Optional<RewoundTurn> rewindLastTurn() {
        return delegate.rewindLastTurn();
    }

    @Override
    public Flow.Publisher<AgentExecutionEvent> events() {
        return delegate.events();
    }

    /**
     * Closes the handle, then releases the lease.
     *
     * <p>
     * That order matters and the {@code finally} matters more: the session's own close drains and flushes, which
     * needs the runtime it was built from, and a lease that leaked because close threw would pin the runtime for
     * the life of the process — the leak this class exists to prevent.
     */
    @Override
    public void close() {
        try {
            delegate.close();
        } finally {
            lease.close();
        }
    }

    @Override
    public String toString() {
        return "LeasedLiveSession[" + delegate + " on " + lease.id() + "]";
    }
}
