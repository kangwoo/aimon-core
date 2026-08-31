package at.aimon.session.routing;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

import at.aimon.core.agent.AgentExecutionResult;
import at.aimon.core.agent.session.TurnId;
import at.aimon.core.agent.session.inbox.InboundMessageId;

/**
 * Outcome of {@link SessionRouter#submit(SubmitRequest)}.
 *
 * <p>
 * Either {@link Kind#EXECUTED_LOCALLY} (the calling node acquired the lock and is running the turn) or
 * {@link Kind#FORWARDED} (the lock is held elsewhere, so the message went to the cross-node inbox for whichever node
 * holds it to drain). <b>Both carry a future</b>: the disposition tells the caller <em>where</em> the work runs, not
 * whether they get an answer. A forwarded turn's future completes when the holder announces the terminal result on the
 * {@code TURN_RESULT} rail — or, if that broadcast is lost, when this node's polling fallback reads the result out of
 * the {@code IdempotencyStore} (design §7.1 F2/F7).
 *
 * <p>
 * A forwarded future can also complete exceptionally without the turn ever running: the holder refuses a message whose
 * {@code agentRef} conflicts with the session's binding, the session is evicted or deleted while the message
 * waits, or nothing resolves it before the forward TTL lapses. That is the point of handing back a real future — the
 * failure reaches the caller instead of stranding them.
 *
 * <p>
 * <b>Forwarded results carry no artifacts.</b> The result crossed a node boundary, and
 * {@link AgentExecutionResult#getArtifacts()} has no wire encoding; see
 * {@link at.aimon.core.agent.session.store.StoredAgentExecutionResult}. Only {@link Kind#EXECUTED_LOCALLY} yields the
 * live
 * result object.
 *
 * <p>
 * Per design §9.1 this is intentionally distinct from {@link at.aimon.core.agent.session.SubmitOutcome} (which has
 * session-level idle/busy semantics rather than node-level inline-vs-deferred semantics).
 */
public final class SubmitDisposition {

    /** Distinguishes the two manager-level outcomes. */
    public enum Kind {
        /** Lock acquired locally; the future completes when the turn finishes on this node. */
        EXECUTED_LOCALLY,
        /** Lock held elsewhere; the message was appended to the cross-node inbox and a holder will drain it. */
        FORWARDED
    }

    private final Kind kind;
    private final TurnId turnId;
    private final CompletionStage<AgentExecutionResult> future;
    private final InboundMessageId inboxId;

    private SubmitDisposition(Kind kind, TurnId turnId, CompletionStage<AgentExecutionResult> future,
            InboundMessageId inboxId) {
        this.kind = Objects.requireNonNull(kind, "kind must not be null");
        this.turnId = Objects.requireNonNull(turnId, "turnId must not be null");
        this.future = Objects.requireNonNull(future, "future must not be null");
        this.inboxId = inboxId;
    }

    public static SubmitDisposition executedLocally(TurnId turnId, CompletionStage<AgentExecutionResult> future) {
        return new SubmitDisposition(Kind.EXECUTED_LOCALLY, turnId, future, null);
    }

    /**
     * The message was handed to the inbox for a remote holder to drain.
     *
     * @param turnId
     *            the id the holder will run the turn under (must not be null)
     * @param inboxId
     *            the inbox handle for the queued message (must not be null)
     * @param future
     *            completed when the holder's outcome reaches this node (must not be null)
     * @return the disposition (never null)
     */
    public static SubmitDisposition forwarded(TurnId turnId, InboundMessageId inboxId,
            CompletionStage<AgentExecutionResult> future) {
        return new SubmitDisposition(Kind.FORWARDED, turnId, future,
                Objects.requireNonNull(inboxId, "inboxId must not be null"));
    }

    public Kind getKind() {
        return kind;
    }

    /**
     * The {@link TurnId} the manager issued for this submission — the handle a caller needs in order to address
     * <em>this</em> turn later, e.g. {@code manager.interrupt(sessionId, turnId, reason)}.
     *
     * <p>
     * Mandatory, not optional: every submission is assigned an id whichever way it is dispositioned, so a caller never
     * has to branch on {@link #getKind()} to find out whether it can name what it submitted. For
     * {@link Kind#FORWARDED} the id travels with the inbox envelope, so the holder node runs the turn under the same id
     * the submitting node reported here.
     *
     * <p>
     * One case carries an id for a turn that never runs: an idempotent replay, where the disposition is
     * {@link Kind#EXECUTED_LOCALLY} over a cached result from an earlier turn. The id is fresh and refers to this
     * submission attempt, so interrupting it is a no-op — which is correct, since the work is already done.
     *
     * @return the turn id (never null)
     */
    public TurnId getTurnId() {
        return turnId;
    }

    /**
     * The turn's result, whichever node produces it.
     *
     * <p>
     * Mandatory for the same reason as {@link #getTurnId()}: a caller that has to branch on {@link #getKind()} to find
     * out whether it will be told the answer has been handed a defect, not an API. Before the {@code TURN_RESULT} rail
     * existed a forwarded submission genuinely had nothing to hand back, and this returned an empty
     * {@link Optional} — that hole is what stranded every forwarded caller.
     *
     * @return the future (never null)
     */
    public CompletionStage<AgentExecutionResult> getFuture() {
        return future;
    }

    public Optional<InboundMessageId> getInboxId() {
        return Optional.ofNullable(inboxId);
    }
}
