package at.aimon.core.agent.session;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.function.Consumer;

import at.aimon.core.agent.AgentExecutionResult;
import at.aimon.core.agent.SubmitOptions;
import at.aimon.core.agent.input.TextInput;
import at.aimon.core.agent.input.UserInput;
import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.agent.queue.QueuedInput;
import at.aimon.core.agent.stream.AgentExecutionEvent;

/**
 * A multi-turn agent session bound to a single {@link SessionId}.
 *
 * <p>
 * {@code LiveSession} is the node-local session handle introduced by SESSION-01 of the pipeline adoption plan. Each
 * instance owns a fixed {@link SessionId}, an underlying
 * {@link at.aimon.core.agent.AgentRuntime} and the executor required to run ReAct turns against that context.
 * Callers submit user input turn by turn through {@link #submit(String)}, or through the
 * {@link at.aimon.core.agent.input.UserInput} overload of the same name when the turn is started by an image, a
 * document or a combination of both; the transcript is preserved across calls via the store wired into the underlying
 * executor, so history-aware features such as CTX-03 (transcript buffer compaction) and CQ-03 (mid-turn injection)
 * continue to work transparently.
 *
 * <h2>Lifecycle</h2>
 *
 * <ul>
 * <li><b>Open</b> — created via {@link LiveSessionFactory#open(SessionId, String, LiveSessionOptions)}.
 * <li><b>Submit</b> — each call to {@link #submit(String)} runs one ReAct turn and returns an
 * {@link AgentExecutionResult}. The session default {@link at.aimon.core.agent.budget.ExecutionBudget} supplied through
 * {@link LiveSessionOptions} is injected when the internally-built request would otherwise be unbounded.
 * <li><b>Close</b> — {@link #close()} releases <em>session-scoped</em> resources only (the mid-turn input-queue
 * subscription, the live interrupt-coordinator and budget-tracker references), then fires {@code OnSessionEnd}.
 * Anything marked {@link at.aimon.core.base.AgentScoped} or
 * {@link at.aimon.core.base.ApplicationScoped} is <b>never</b> touched — including the {@code AgentRuntime} itself,
 * its {@code McpClientManager}, and the {@code KnowledgeStore} / {@code SchedulingEngine} /
 * {@code ScheduledTaskManager} / {@code RoutineExecutor}. They outlive this session and are torn down only at
 * application shutdown or explicit agent removal. See {@code CLAUDE.md} "Scope &amp; Scheduling Lifecycle".
 * </ul>
 *
 * <h2>Thread-safety</h2>
 *
 * <p>
 * Implementations are not required to be thread-safe. Callers that wish to multiplex a single session across threads
 * must synchronize externally. Each {@code LiveSession} instance is intended to be used serially (one turn at a
 * time).
 *
 * <h2>Event streaming</h2>
 *
 * <p>
 * {@link #events()} returns a {@link Flow.Publisher} of {@link AgentExecutionEvent}s. The default implementation of
 * this interface returns an immediately-completing empty publisher; STREAM-03 will replace it with an executor-fed
 * publisher that emits iteration / tool / completion events in real time. The signature is provided now so downstream
 * code can depend on a stable API.
 *
 * @see DefaultLiveSession
 * @see LiveSessionFactory
 * @see LiveSessionOptions
 */
public interface LiveSession extends AutoCloseable {

    /**
     * Returns the {@link SessionId} bound to this session.
     *
     * <p>
     * The identifier is fixed at {@link LiveSessionFactory#open open} time and never changes for the lifetime of the
     * session. Every turn submitted via {@link #submit(String)} uses this same id, ensuring memory continuity in the
     * underlying {@code SessionRecordStore}.
     *
     * @return the session id (never null)
     */
    SessionId getSessionId();

    /**
     * Returns a point-in-time {@link LiveSessionStatus} snapshot for diagnostics, monitoring and UI display.
     *
     * <p>
     * The snapshot reports session-/turn-scoped runtime state (lifecycle phase, whether an interruptible turn is in
     * flight, mid-turn queue depth, current options) plus live per-turn progress (iterations, tokens, elapsed) when a
     * turn is running against an executor that publishes its {@link at.aimon.core.agent.budget.BudgetTracker}.
     *
     * <p>
     * <b>Best-effort, not a control gate.</b> The backing fields are read independently, so the result is not a
     * consistent atomic view; it may also be momentarily stale relative to a concurrently running turn. Callers that
     * need to decide whether a turn may start must use {@link #offerAsync(String, java.util.function.Consumer)} and
     * inspect the {@link SubmitOutcome} — reading {@code status()} and acting on it is inherently racy. The snapshot
     * also reflects only the <em>local</em> session instance (see {@link LiveSessionStatus} for the multi-instance
     * note).
     *
     * <p>
     * The default implementation returns a minimal {@link LiveSessionStatus.Phase#IDLE IDLE} status carrying only the
     * session id, so legacy / test-double {@link LiveSession} implementations remain behaviourally unchanged.
     * {@link DefaultLiveSession} overrides it to report live runtime and turn state.
     *
     * @return a status snapshot (never null)
     */
    default LiveSessionStatus status() {
        return LiveSessionStatus.builder().sessionId(getSessionId()).phase(LiveSessionStatus.Phase.IDLE).build();
    }

    /**
     * Submits one turn of user input and returns the execution result.
     *
     * <p>
     * Convenience overload equivalent to {@link #submit(String, SubmitOptions) submit(input, SubmitOptions.empty())} —
     * the executor receives no per-turn metadata, falling back to its built-in defaults.
     *
     * @param input
     *            the raw user input (must not be null; may be empty depending on the underlying executor's rules)
     * @return the result of the ReAct turn (never null)
     * @throws NullPointerException
     *             if {@code input} is null
     * @throws IllegalStateException
     *             if the session has already been closed
     */
    default AgentExecutionResult submit(String input) {
        return submit(input, SubmitOptions.empty());
    }

    /**
     * Submits one turn of user input plus per-turn {@link SubmitOptions} and returns the execution result.
     *
     * <p>
     * Implementations construct the underlying {@code AgentExecutionRequest} internally — callers supply the raw user
     * text together with the per-turn options that override executor defaults (userInfo, system prompt variables,
     * execution attributes, LLM call metadata, user-context injection). The session's default budget (from
     * {@link LiveSessionOptions#getBudget()}) is injected when the internally-built request does not already declare
     * one; when the options budget is unlimited (the default) the behavior is identical to the legacy unbounded
     * executor path.
     *
     * @param input
     *            the raw user input (must not be null; may be empty depending on the underlying executor's rules)
     * @param submitOptions
     *            the per-turn options (must not be null; use {@link SubmitOptions#empty()} for executor defaults)
     * @return the result of the ReAct turn (never null)
     * @throws NullPointerException
     *             if {@code input} or {@code submitOptions} is null
     * @throws IllegalStateException
     *             if the session has already been closed
     */
    AgentExecutionResult submit(String input, SubmitOptions submitOptions);

    /**
     * Submits one turn of {@link UserInput} and returns the execution result.
     *
     * <p>
     * Convenience overload equivalent to {@link #submit(UserInput, SubmitOptions) submit(input,
     * SubmitOptions.empty())}.
     *
     * @param input
     *            the user input (must not be null)
     * @return the result of the ReAct turn (never null)
     * @throws NullPointerException
     *             if {@code input} is null
     * @throws IllegalStateException
     *             if the session has already been closed
     */
    default AgentExecutionResult submit(UserInput input) {
        return submit(input, SubmitOptions.empty());
    }

    /**
     * Submits one turn of {@link UserInput} plus per-turn {@link SubmitOptions} and returns the execution result.
     *
     * <p>
     * <b>The general entry point.</b> Text is what a REPL prompt or an HTTP body carries, so
     * {@link #submit(String, SubmitOptions)} stays the one every session must implement; a caller with an image, a
     * document or a combination of both comes through here instead of flattening it into a description of itself.
     * The executor already speaks {@link UserInput}
     * ({@link at.aimon.core.agent.AgentExecutionRequest#getUserInput()}), so a session that overrides this converts
     * nothing on the way in.
     *
     * <p>
     * <b>The default handles text and refuses the rest.</b> A {@link TextInput} is unwrapped and handed to
     * {@link #submit(String, SubmitOptions)}, so this overload works on every session including ones written before
     * it existed — which is what keeps {@link #retryLastTurn(SubmitOptions)} working when the rewound turn happens to
     * be a text one. Anything else throws {@link UnsupportedOperationException}: saying so plainly beats degrading an
     * image to its {@code asText()} placeholder and quietly running a different turn.
     *
     * @param input
     *            the user input (must not be null)
     * @param submitOptions
     *            the per-turn options (must not be null; use {@link SubmitOptions#empty()} for executor defaults)
     * @return the result of the ReAct turn (never null)
     * @throws NullPointerException
     *             if {@code input} or {@code submitOptions} is null
     * @throws UnsupportedOperationException
     *             if the input is not text and this session accepts text only
     * @throws IllegalStateException
     *             if the session has already been closed
     */
    default AgentExecutionResult submit(UserInput input, SubmitOptions submitOptions) {
        Objects.requireNonNull(input, "input must not be null");
        Objects.requireNonNull(submitOptions, "submitOptions must not be null");
        if (input instanceof TextInput text) {
            return submit(text.getText(), submitOptions);
        }
        throw new UnsupportedOperationException(
                "This LiveSession implementation accepts text input only: " + input.getType());
    }

    /**
     * Submits one turn of user input and delivers {@link AgentExecutionEvent}s to {@code listener} as they are emitted.
     *
     * <p>
     * Convenience overload equivalent to
     * {@link #submitAsync(String, SubmitOptions, java.util.function.Consumer) submitAsync(input,
     * SubmitOptions.empty(), listener)}.
     *
     * @param input
     *            the raw user input (must not be null)
     * @param listener
     *            the event listener invoked once per emitted event (must not be null)
     * @return a stage that completes with the turn's result or exceptionally on failure (never null)
     * @throws NullPointerException
     *             if {@code input} or {@code listener} is null
     * @throws IllegalStateException
     *             if the session has already been closed
     */
    default CompletionStage<AgentExecutionResult> submitAsync(String input, Consumer<AgentExecutionEvent> listener) {
        return submitAsync(input, SubmitOptions.empty(), listener);
    }

    /**
     * Submits one turn of user input plus per-turn {@link SubmitOptions} and delivers {@link AgentExecutionEvent}s to
     * {@code listener} as they are emitted.
     *
     * <p>
     * This is the session-facade equivalent of
     * {@link at.aimon.core.agent.stream.StreamingAgentExecutor#executeAsync StreamingAgentExecutor.executeAsync}: it
     * preserves the STREAM-04 streaming behavior so callers (REPL, SDK hosts) can render iteration / tool / completion
     * events in real time without dropping the session-scoped bookkeeping (fixed {@link SessionId}, default
     * {@link at.aimon.core.agent.budget.ExecutionBudget} from options) or the per-turn metadata supplied via
     * {@code submitOptions}.
     *
     * <p>
     * <b>Listener isolation:</b> exceptions thrown from {@code listener} MUST be swallowed by the implementation; they
     * must not cancel the execution or prevent subsequent events (including the terminal one) from being delivered.
     *
     * @param input
     *            the raw user input (must not be null)
     * @param submitOptions
     *            the per-turn options (must not be null; use {@link SubmitOptions#empty()} for executor defaults)
     * @param listener
     *            the event listener invoked once per emitted event (must not be null; may receive zero events when the
     *            implementation cannot deliver streaming events)
     * @return a stage that completes with the turn's result or exceptionally on failure (never null)
     * @throws NullPointerException
     *             if {@code input}, {@code submitOptions}, or {@code listener} is null
     * @throws IllegalStateException
     *             if the session has already been closed (may be thrown synchronously before the returned stage is
     *             created, to match the contract of {@link #submit(String, SubmitOptions)})
     */
    CompletionStage<AgentExecutionResult> submitAsync(String input, SubmitOptions submitOptions,
            Consumer<AgentExecutionEvent> listener);

    /**
     * Streaming counterpart of {@link #submit(UserInput, SubmitOptions)}.
     *
     * <p>
     * Convenience overload equivalent to {@code submitAsync(TurnId.generate(), input, submitOptions, listener)}; the
     * turn's id is issued internally.
     *
     * @param input
     *            the user input (must not be null)
     * @param submitOptions
     *            the per-turn options (must not be null; use {@link SubmitOptions#empty()} for executor defaults)
     * @param listener
     *            the event listener invoked once per emitted event (must not be null)
     * @return a stage that completes with the turn's result or exceptionally on failure (never null)
     * @throws NullPointerException
     *             if any argument is null
     * @throws UnsupportedOperationException
     *             if this session accepts text input only
     * @throws IllegalStateException
     *             if the session has already been closed
     */
    default CompletionStage<AgentExecutionResult> submitAsync(UserInput input, SubmitOptions submitOptions,
            Consumer<AgentExecutionEvent> listener) {
        return submitAsync(TurnId.generate(), input, submitOptions, listener);
    }

    /**
     * Submits one turn under a caller-supplied {@link TurnId}, so the caller can address the turn it just started.
     *
     * <p>
     * Behaviourally identical to {@link #submitAsync(String, SubmitOptions, Consumer)} — the only difference is that
     * the
     * turn's identity is chosen by the caller instead of issued internally. Use this overload when the id has to be
     * known <em>before</em> the turn runs: a dispatcher that has already stamped the id onto an inbox envelope or a
     * cross-node event payload, or a caller that wants to hold the id in order to later call
     * {@link #interrupt(TurnId, InterruptReason)} on exactly this turn and no other.
     *
     * <p>
     * While the turn is in flight {@link #currentTurnId()} reports {@code turnId}.
     *
     * <p>
     * The default implementation ignores {@code turnId} and delegates to
     * {@link #submitAsync(String, SubmitOptions, Consumer)}, so legacy / test-double implementations remain
     * behaviourally unchanged (they simply cannot be addressed per turn). {@link DefaultLiveSession} overrides it to
     * publish the id through {@link #currentTurnId()}.
     *
     * @param turnId
     *            the identity to run this turn under (must not be null)
     * @param input
     *            the raw user input (must not be null)
     * @param submitOptions
     *            the per-turn options (must not be null; use {@link SubmitOptions#empty()} for executor defaults)
     * @param listener
     *            the event listener invoked once per emitted event (must not be null)
     * @return a stage that completes with the turn's result or exceptionally on failure (never null)
     * @throws NullPointerException
     *             if any argument is null
     * @throws IllegalStateException
     *             if the session has already been closed
     */
    default CompletionStage<AgentExecutionResult> submitAsync(TurnId turnId, String input, SubmitOptions submitOptions,
            Consumer<AgentExecutionEvent> listener) {
        Objects.requireNonNull(turnId, "turnId must not be null");
        return submitAsync(input, submitOptions, listener);
    }

    /**
     * Submits one turn of {@link UserInput} under a caller-supplied {@link TurnId}, with streaming events.
     *
     * <p>
     * The streaming counterpart of {@link #submit(UserInput, SubmitOptions)}, and the overload a session that
     * genuinely handles multimodal input overrides — {@link DefaultLiveSession} does, and routes its {@code String}
     * overloads here.
     *
     * <p>
     * The default behaves exactly as {@link #submit(UserInput, SubmitOptions)}'s does: a {@link TextInput} is
     * unwrapped and handed to {@link #submitAsync(TurnId, String, SubmitOptions, Consumer)}, so text works on every
     * session; anything else throws {@link UnsupportedOperationException}.
     *
     * @param turnId
     *            the identity to run this turn under (must not be null)
     * @param input
     *            the user input (must not be null)
     * @param submitOptions
     *            the per-turn options (must not be null; use {@link SubmitOptions#empty()} for executor defaults)
     * @param listener
     *            the event listener invoked once per emitted event (must not be null)
     * @return a stage that completes with the turn's result or exceptionally on failure (never null)
     * @throws NullPointerException
     *             if any argument is null
     * @throws UnsupportedOperationException
     *             if the input is not text and this session accepts text only
     * @throws IllegalStateException
     *             if the session has already been closed
     */
    default CompletionStage<AgentExecutionResult> submitAsync(TurnId turnId, UserInput input,
            SubmitOptions submitOptions, Consumer<AgentExecutionEvent> listener) {
        Objects.requireNonNull(turnId, "turnId must not be null");
        Objects.requireNonNull(input, "input must not be null");
        Objects.requireNonNull(submitOptions, "submitOptions must not be null");
        Objects.requireNonNull(listener, "listener must not be null");
        if (input instanceof TextInput text) {
            return submitAsync(turnId, text.getText(), submitOptions, listener);
        }
        throw new UnsupportedOperationException(
                "This LiveSession implementation accepts text input only: " + input.getType());
    }

    /**
     * Returns the {@link TurnId} of the turn currently executing on this session, or {@link Optional#empty()} when the
     * session is idle.
     *
     * <p>
     * <b>Best-effort, not a control gate</b> — same caveat as {@link #status()}. The value is read without
     * synchronization against a concurrently settling turn, so it may be momentarily stale in either direction. It is
     * intended for diagnostics and for correlating an already-known id, not for deciding whether a turn may start.
     *
     * <p>
     * The default implementation returns {@link Optional#empty()} so legacy / test-double implementations remain
     * behaviourally unchanged.
     *
     * @return the active turn's id, or empty when idle (never null)
     */
    default Optional<TurnId> currentTurnId() {
        return Optional.empty();
    }

    /**
     * Offers one turn of user input for execution, letting the session decide whether to run it now or defer it to the
     * message queue.
     *
     * <p>
     * Convenience overload equivalent to
     * {@link #offerAsync(String, SubmitOptions, java.util.function.Consumer) offerAsync(input, SubmitOptions.empty(),
     * listener)}.
     *
     * @param input
     *            the raw user input (must not be null)
     * @param listener
     *            the event listener invoked once per emitted event (must not be null)
     * @return the outcome describing whether the turn started or was queued (never null)
     * @throws NullPointerException
     *             if {@code input} or {@code listener} is null
     * @throws IllegalStateException
     *             if the session has already been closed
     */
    default SubmitOutcome offerAsync(String input, Consumer<AgentExecutionEvent> listener) {
        return offerAsync(input, SubmitOptions.empty(), listener);
    }

    /**
     * Offers one turn of user input plus per-turn {@link SubmitOptions} for execution, letting the session decide
     * whether to run it now or defer it to the message queue.
     *
     * <p>
     * This is the SESSION-04 entry point: it consolidates the busy-vs-idle decision that CLI callers previously made
     * themselves via an external {@code QueryGuard}. Hosts that want the auto-queue behavior (REPL today, SDK
     * embedders tomorrow) should invoke this method instead of
     * {@link #submitAsync(String, SubmitOptions, Consumer) submitAsync} so they get consistent semantics across
     * implementations. When the session enqueues the input instead of executing it immediately, the per-turn options
     * are stored on the resulting {@link at.aimon.core.agent.queue.QueuedInput} so the eventual mid-turn drain
     * preserves the metadata.
     *
     * @param input
     *            the raw user input (must not be null)
     * @param submitOptions
     *            the per-turn options (must not be null; use {@link SubmitOptions#empty()} for executor defaults)
     * @param listener
     *            the event listener invoked once per emitted event (must not be null)
     * @return the outcome describing whether the turn started or was queued (never null)
     * @throws NullPointerException
     *             if {@code input}, {@code submitOptions}, or {@code listener} is null
     * @throws IllegalStateException
     *             if the session has already been closed
     */
    SubmitOutcome offerAsync(String input, SubmitOptions submitOptions, Consumer<AgentExecutionEvent> listener);

    /**
     * Offers one turn of {@link UserInput} for execution, letting the session decide whether to run it now or defer
     * it to the message queue.
     *
     * <p>
     * The general form of {@link #offerAsync(String, SubmitOptions, Consumer)}, defaulting the same way
     * {@link #submit(UserInput, SubmitOptions)} does: a {@link TextInput} is unwrapped and handed to the
     * {@code String} overload, anything else throws {@link UnsupportedOperationException}.
     *
     * <p>
     * <b>Deferral is a text channel, so it can refuse.</b> A queued input is replayed as a
     * {@code <system-reminder>} text block ({@link QueuedInput#getInputText()}), which nothing but text fits into.
     * When a turn is already running, an implementation therefore has neither answer available for a non-text input
     * — it cannot defer it, and running it anyway would put two turns on one transcript — and must say so rather
     * than pick the quiet wrong one. A caller that means to run turns concurrently has
     * {@link #submitAsync(UserInput, SubmitOptions, Consumer)} for exactly that.
     *
     * @param input
     *            the user input (must not be null)
     * @param submitOptions
     *            the per-turn options (must not be null; use {@link SubmitOptions#empty()} for executor defaults)
     * @param listener
     *            the event listener invoked once per emitted event (must not be null)
     * @return the outcome describing whether the turn started or was queued (never null)
     * @throws NullPointerException
     *             if any argument is null
     * @throws UnsupportedOperationException
     *             if the input is not text and this session accepts text only
     * @throws IllegalStateException
     *             if the session has already been closed, or a turn is running and the input cannot be deferred
     */
    default SubmitOutcome offerAsync(UserInput input, SubmitOptions submitOptions,
            Consumer<AgentExecutionEvent> listener) {
        Objects.requireNonNull(input, "input must not be null");
        Objects.requireNonNull(submitOptions, "submitOptions must not be null");
        Objects.requireNonNull(listener, "listener must not be null");
        if (input instanceof TextInput text) {
            return offerAsync(text.getText(), submitOptions, listener);
        }
        throw new UnsupportedOperationException(
                "This LiveSession implementation accepts text input only: " + input.getType());
    }

    /**
     * Requests cancellation of the turn currently executing on this session, if any.
     *
     * <p>
     * Entry point for external actors (REPL SIGINT handler, priority-queue preemption, parent-agent cascade) call
     * this to trip the turn-scoped {@link at.aimon.core.agent.interrupt.InterruptCoordinator} established by
     * the executor. The first trip wins; subsequent calls on the same turn are idempotent no-ops (see
     * {@link at.aimon.core.agent.interrupt.InterruptCoordinator#requestInterrupt(InterruptReason)}). When no turn is
     * active the call is a silent no-op — implementations must not throw in that case.
     *
     * <p>
     * The default implementation is a no-op so legacy / test-double {@link LiveSession} implementations remain
     * behaviourally unchanged. {@link DefaultLiveSession} overrides this to route to the live coordinator captured via
     * {@link at.aimon.core.agent.interrupt.InterruptCoordinator}.
     *
     * @param reason
     *            the reason for the interrupt (must not be null)
     * @throws NullPointerException
     *             if {@code reason} is null
     */
    default void interrupt(InterruptReason reason) {
        Objects.requireNonNull(reason, "reason must not be null");
        // Default: no live coordinator to trip.
    }

    /**
     * Requests cancellation of the turn currently executing on this session <em>only if</em> it is the turn identified
     * by {@code turnId}.
     *
     * <p>
     * This is the addressed form of {@link #interrupt(InterruptReason)}. The unaddressed form trips whatever turn is
     * running when the call lands, which is correct for administrative cancellation (eviction, shutdown, lease loss)
     * but
     * wrong for a user who meant "stop the turn I submitted": between the user's click and the interrupt reaching the
     * holder node, that turn may have finished and the next one may already be running. Passing the id makes the
     * mismatch observable to the implementation, which then leaves the innocent turn alone.
     *
     * <p>
     * A mismatch — or an idle session — is a silent no-op; implementations must not throw. Callers that need to know
     * whether the interrupt landed cannot learn it from this method (the answer is inherently racy); they should
     * observe
     * the turn's terminal event instead.
     *
     * <p>
     * The default implementation is a no-op, matching {@link #interrupt(InterruptReason)}.
     * {@link DefaultLiveSession} overrides it to compare against its active turn before tripping the coordinator.
     *
     * @param turnId
     *            the turn this interrupt is meant for (must not be null)
     * @param reason
     *            the reason for the interrupt (must not be null)
     * @throws NullPointerException
     *             if {@code turnId} or {@code reason} is null
     */
    default void interrupt(TurnId turnId, InterruptReason reason) {
        Objects.requireNonNull(turnId, "turnId must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        // Default: no live coordinator to trip, and no active turn id to compare against.
    }

    /**
     * Enqueues a {@link QueuedInput} into this session's mid-turn injection queue.
     *
     * <p>
     * Narrow API surface introduced for {@code SessionRouter} (routing design §3.4) so external dispatchers can
     * inject inbox-delivered inputs while a turn is in progress without exposing the session's internal
     * {@link at.aimon.core.agent.queue.MessageQueueManager}. Implementations MUST set the queued input's
     * {@code agentRuntimeId} to match this session's active context — callers that load a
     * {@code QueuedInput} from a remote source (Redis Streams, etc.) are responsible for rebuilding it with the local
     * context id before calling this method.
     *
     * <p>
     * The default implementation throws {@link UnsupportedOperationException} so legacy / test-double sessions remain
     * unchanged. {@link DefaultLiveSession} overrides this to forward to its
     * {@link at.aimon.core.agent.queue.MessageQueueManager#enqueue}, or to throw when no queue is wired.
     *
     * @param input
     *            the queued input to inject (must not be null; {@code agentRuntimeId} must match this
     *            session's active context)
     * @throws NullPointerException
     *             if {@code input} is null
     * @throws UnsupportedOperationException
     *             if this session does not support mid-turn injection
     * @throws IllegalStateException
     *             if the session has already been closed
     */
    default void enqueueMidTurnInput(QueuedInput input) {
        Objects.requireNonNull(input, "input must not be null");
        throw new UnsupportedOperationException(
                "This LiveSession implementation does not support mid-turn input injection");
    }

    /**
     * Takes the last turn back out of the history and runs it again, when that turn was interrupted.
     *
     * <p>
     * The second attempt is submitted under the <b>same {@link SubmitOptions} as the first</b> — the same principal,
     * system-prompt variables and user-context setting — because those are as much a part of the turn as the request
     * is. Use {@link #retryLastTurn(SubmitOptions)} to submit it under different ones.
     *
     * @return the result of the second attempt, or empty when the last turn ended some other way and there is
     *         therefore nothing to retry (never null)
     * @throws UnsupportedOperationException
     *             if this session does not support retrying
     */
    default Optional<AgentExecutionResult> retryLastTurn() {
        return rewindLastTurn().map(turn -> submit(turn.getUserInput(), turn.getSubmitOptions()));
    }

    /**
     * Takes the last turn back out of the history and runs it again, when that turn was interrupted.
     *
     * <p>
     * Only a turn that ended {@code INTERRUPTED} can be retried, and only the most recent one. Retrying is not the
     * same as asking again: the stopped turn left a partial trail — the user message, the synthetic context blocks
     * injected ahead of it, the assistant output produced before the stop and the tool results, including the ones
     * filled in as skipped. Submitting the same request on top of that trail would ask the model to redo work in a
     * history that says it already half-did it, so the trail is removed first and the turn starts from where it
     * originally did.
     *
     * <p>
     * <b>One outcome, not two calls.</b> There is deliberately no "can I retry?" predicate to check first: the answer
     * could change between the check and the act, and a caller that branched on it would be writing a race. An empty
     * return <em>is</em> the answer.
     *
     * <p>
     * <b>The rewind is durable and the retry is a normal turn.</b> The shortened history is written back before the
     * turn is submitted, so a retry that is itself interrupted leaves the session retryable again rather than
     * compounding two partial trails. Everything else about the turn — budget, hooks, events, interrupts — behaves as
     * it would for any other submission.
     *
     * <p>
     * <b>These options replace the ones the turn originally carried.</b> That is what this overload is for; the
     * no-argument {@link #retryLastTurn()} reuses the originals instead, which is what makes a plain retry the same
     * turn rather than one like it.
     *
     * @param submitOptions
     *            per-turn options for the second attempt, replacing the ones the original turn was submitted under
     *            (must not be null)
     * @return the result of the second attempt, or empty when there is nothing to retry (never null)
     * @throws NullPointerException
     *             if {@code submitOptions} is null
     * @throws UnsupportedOperationException
     *             if this session does not support retrying
     * @throws IllegalStateException
     *             if the session has already been closed
     */
    default Optional<AgentExecutionResult> retryLastTurn(SubmitOptions submitOptions) {
        Objects.requireNonNull(submitOptions, "submitOptions must not be null");
        return rewindLastTurn().map(turn -> submit(turn.getUserInput(), submitOptions));
    }

    /**
     * Takes an interrupted turn back out of the history and hands back what started it, leaving the caller to submit
     * it however it submits anything else.
     *
     * <p>
     * The half of {@link #retryLastTurn(SubmitOptions)} that is not a submission, separated because the submission is
     * the part callers differ on. A REPL wants the streaming path with its event listener and its Ctrl+C handler
     * bound around the turn; {@code retryLastTurn} runs a plain synchronous one. Composing the two here rather than
     * reimplementing the rewind on each side is what keeps the two from drifting.
     *
     * <p>
     * <b>Rewinding is the act, not a question about one.</b> There is no predicate to consult first: the answer could
     * change between the check and the call. An empty return means the last turn ended some way other than
     * interrupted and there was nothing to take out — and in that case nothing was changed either.
     *
     * <p>
     * The shortened history is written through before this returns, so a caller that never gets round to submitting
     * leaves a session that is merely back where it was, not one in a half-rewound state.
     *
     * <p>
     * <b>Call it between turns.</b> A rewind concurrent with a running turn cannot work: that turn holds its own copy
     * of the history and writes it back when it ends, putting the trail straight back. {@code DefaultLiveSession}
     * refuses when it can see a turn in flight, but a turn starting immediately afterwards races the same way — so
     * interrupt first, then rewind.
     *
     * <p>
     * <b>What comes back is the turn, not its text.</b> The {@link RewoundTurn} carries the {@link UserInput} the
     * turn was submitted with — an image, a document or a multimodal combination comes back as itself — together
     * with the {@link SubmitOptions} it was submitted under. Passing both to
     * {@link #submit(UserInput, SubmitOptions)} runs the same turn; passing only the first runs the same words as a
     * different caller.
     *
     * @return what the interrupted turn was, or empty when there is nothing to rewind (never null)
     * @throws UnsupportedOperationException
     *             if this session does not support rewinding
     * @throws IllegalStateException
     *             if the session has already been closed
     */
    default Optional<RewoundTurn> rewindLastTurn() {
        throw new UnsupportedOperationException("This LiveSession implementation does not support rewinding a turn");
    }

    /**
     * Closes session-scoped resources.
     *
     * <p>
     * Implementations must release only what dies with this session — its message-queue subscription, its references
     * to the live interrupt coordinator and budget tracker — and then fire {@code OnSessionEnd}.
     *
     * <p>
     * Implementations must <b>not</b> close anything implementing {@link at.aimon.core.base.AgentScoped} or
     * {@link at.aimon.core.base.ApplicationScoped}. In particular they must not call {@code close()} on the
     * {@code AgentRuntime} (agent-scoped and shared by every session targeting the same agent — closing it tears down
     * MCP subprocesses out from under other live sessions), on its {@code McpClientManager}, or on the
     * {@code KnowledgeStore} / {@code SchedulingEngine} / {@code ScheduledTaskManager}. Agent-runtime teardown is the
     * job of {@code OrcaAgentRuntimeManager.destroyRuntime}, at application shutdown or explicit agent removal.
     *
     * <p>
     * Calling {@code close()} more than once is permitted and must be a no-op after the first invocation.
     */
    @Override
    void close();

    /**
     * Returns a {@link Flow.Publisher} of {@link AgentExecutionEvent}s produced by this session.
     *
     * <p>
     * The default implementation returns a publisher that immediately completes with no events. A future
     * implementation (STREAM-03) will return a live publisher backed by the executor's event bus. Consumers can safely
     * subscribe to the default publisher today; they will simply receive an {@code onComplete} signal at once.
     *
     * @return a publisher of execution events (never null)
     */
    default Flow.Publisher<AgentExecutionEvent> events() {
        return subscriber -> {
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long n) {
                    // No events will ever be delivered; complete immediately on first demand.
                    subscriber.onComplete();
                }

                @Override
                public void cancel() {
                    // No-op — nothing to cancel.
                }
            });
        };
    }
}
