package at.aimon.core.agent.session;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

import at.aimon.core.agent.AgentExecutionResult;
import at.aimon.core.agent.queue.MessageQueueManager;
import at.aimon.core.agent.queue.QueuedInput;

/**
 * Result of {@link LiveSession#offerAsync offerAsync}, describing whether the input was executed directly or deferred
 * onto the session's {@link MessageQueueManager}.
 *
 * <p>
 * The session uses {@code offerAsync} to encapsulate the busy / idle decision that CLI callers historically made
 * themselves via an external {@code QueryGuard}: when the session is idle, the turn runs immediately and the outcome
 * carries the in-flight completion stage; when the session is already executing a turn and the underlying queue is
 * wired, the input is appended via {@link MessageQueueManager#enqueue} with {@code NEXT} priority and the outcome
 * reports the resulting queue depth the caller can surface for UX ({@code "[queued: 2]"}). The depth is observed
 * immediately after enqueue; with a single producer per session it equals the 1-based position of the just-enqueued
 * item, but under concurrent producers it must be treated as a best-effort upper bound rather than a stable index.
 *
 * <p>
 * Fields are populated exclusively based on {@link Kind}:
 * <ul>
 * <li>{@link Kind#EXECUTED} — {@link #getResultStage()} is present, {@link #getQueuedInput()} /
 * {@link #getQueuePosition()} are empty.
 * <li>{@link Kind#QUEUED} — {@link #getQueuedInput()} and {@link #getQueuePosition()} are present,
 * {@link #getResultStage()} is empty.
 * </ul>
 *
 * <p>
 * Instances are immutable value objects. Use the {@link #executed(CompletionStage)} and
 * {@link #queued(QueuedInput, int)} factories rather than the private constructor.
 */
public final class SubmitOutcome {

    /** Whether the input was executed inline or deferred onto the queue. */
    public enum Kind {
        /**
         * The session was idle; the turn started immediately and its result is reachable via {@link #getResultStage()}.
         */
        EXECUTED,
        /** The session was busy with another turn; the input was enqueued and will be drained by the host. */
        QUEUED
    }

    private final Kind kind;
    private final CompletionStage<AgentExecutionResult> resultStage;
    private final QueuedInput queuedInput;
    private final int queuePosition;

    private SubmitOutcome(Kind kind, CompletionStage<AgentExecutionResult> resultStage, QueuedInput queuedInput,
            int queuePosition) {
        this.kind = Objects.requireNonNull(kind, "kind must not be null");
        this.resultStage = resultStage;
        this.queuedInput = queuedInput;
        this.queuePosition = queuePosition;
    }

    /**
     * Builds an {@link Kind#EXECUTED} outcome wrapping the stage of an in-flight turn.
     *
     * @param resultStage
     *            the completion stage that eventually carries the turn's result (must not be null)
     */
    public static SubmitOutcome executed(CompletionStage<AgentExecutionResult> resultStage) {
        Objects.requireNonNull(resultStage, "resultStage must not be null");
        return new SubmitOutcome(Kind.EXECUTED, resultStage, null, 0);
    }

    /**
     * Builds a {@link Kind#QUEUED} outcome for an input that was appended to the session's
     * {@link MessageQueueManager}.
     *
     * @param queuedInput
     *            the {@link QueuedInput} instance that was appended (must not be null)
     * @param queuePosition
     *            the queue depth observed immediately after enqueue — equal to the 1-based position of
     *            {@code queuedInput} when there is a single producer per session, and a best-effort upper bound
     *            otherwise (must be &gt;= 1)
     */
    public static SubmitOutcome queued(QueuedInput queuedInput, int queuePosition) {
        Objects.requireNonNull(queuedInput, "queuedInput must not be null");
        if (queuePosition < 1) {
            throw new IllegalArgumentException("queuePosition must be >= 1, got: " + queuePosition);
        }
        return new SubmitOutcome(Kind.QUEUED, null, queuedInput, queuePosition);
    }

    /** Returns the outcome kind (never null). */
    public Kind getKind() {
        return kind;
    }

    /**
     * Returns the in-flight stage when the outcome is {@link Kind#EXECUTED}.
     *
     * @return the stage, or {@link Optional#empty()} when {@link #getKind()} is not {@code EXECUTED}
     */
    public Optional<CompletionStage<AgentExecutionResult>> getResultStage() {
        return Optional.ofNullable(resultStage);
    }

    /**
     * Returns the enqueued input when the outcome is {@link Kind#QUEUED}.
     *
     * @return the queued input, or {@link Optional#empty()} when {@link #getKind()} is not {@code QUEUED}
     */
    public Optional<QueuedInput> getQueuedInput() {
        return Optional.ofNullable(queuedInput);
    }

    /**
     * Returns the queue depth observed immediately after enqueue when {@link #getKind()} is {@link Kind#QUEUED};
     * returns {@code 0} otherwise. Under single-producer sessions this matches the 1-based position of the enqueued
     * input; under concurrent producers it is a best-effort upper bound intended for UX display only.
     *
     * @return the post-enqueue queue depth, or {@code 0} when the outcome is not {@code QUEUED}
     */
    public int getQueuePosition() {
        return queuePosition;
    }
}
