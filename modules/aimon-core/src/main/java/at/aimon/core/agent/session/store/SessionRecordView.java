package at.aimon.core.agent.session.store;

import java.util.List;
import java.util.Optional;

import at.aimon.core.agent.budget.ExecutionBudget;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.transcript.SessionRewindPoint;
import at.aimon.core.llm.Message;

/**
 * Read-only view of a {@link SessionRecord}.
 *
 * <p>
 * The type every read of a persisted record hands back — {@link SessionRecordStore#load(SessionId)},
 * {@link SessionRecordStore#provision(SessionId, String)}, {@code SessionStore.load} — so that the
 * mutable {@link SessionRecord} class never leaves its own package. An ArchUnit rule states exactly that and now has no
 * exceptions (see {@code SessionRecordSoleWriterArchitectureTest}).
 *
 * <p>
 * <b>Why this survives.</b> The narrowing looks redundant once you notice that every read returns an independent copy:
 * a caller that mutated the returned object would change nothing persisted. That is the point — the failure it prevents
 * is not corruption but the <em>silent no-op</em>, a caller believing it wrote when it did not. Reading the record and
 * writing it are deliberately different shapes: reads yield this view, writes go through the named atomic primitives on
 * {@link SessionRecordStore}, fenced by the lease when reached through {@code SessionStore.records()}.
 *
 * <p>
 * The companion {@code MutableConversationView} is gone. It existed to expose {@code setAgentRef} across the package
 * boundary, and nothing has needed that since binding writes moved inside {@code SessionStore.claim} — the
 * downcast it enabled would today be a write to a detached copy.
 *
 * <p>
 * Sole implementation: {@link SessionRecord}. In-package writers obtain a mutable instance through
 * {@link SessionRecord#copyOf(SessionRecordView)}.
 */
public interface SessionRecordView {

    /**
     * Gets the session id.
     *
     * @return the session id (never null)
     */
    SessionId getId();

    /**
     * Gets the system prompt.
     *
     * <p>
     * Returns the raw nullable string for backwards compatibility with existing callers. New code should prefer null
     * checks at the call site or wrap with {@link Optional#ofNullable(Object)}.
     *
     * @return the system prompt (may be null)
     */
    String getSystemPrompt();

    /**
     * Gets all messages in the transcript as an immutable copy.
     *
     * @return an immutable list of messages (never null, may be empty)
     */
    List<Message> getMessages();

    /**
     * Gets the agent binding for this session.
     *
     * <p>
     * The {@code agentRef} identifies which agent (e.g. agent factory key) is bound to this session. Used by
     * {@code SessionRouter} to enforce that the same session cannot be served by different agents
     * concurrently.
     *
     * @return the bound agent reference, or empty if not yet bound
     */
    Optional<String> getAgentRef();

    /**
     * Gets the persisted consecutive AUTO compaction failure count.
     *
     * @return the failure count (always {@code >= 0})
     */
    int getCompactionFailureCount();

    /**
     * Gets the persisted cumulative session totals for this session.
     *
     * <p>
     * Restored on session open to seed the in-memory accumulator that backs session-status reporting. Defaults to
     * {@link SessionTotals#empty()} for views that do not persist this side field.
     *
     * @return the cumulative session totals (never null)
     */
    default SessionTotals getSessionTotals() {
        return SessionTotals.empty();
    }

    /**
     * Gets the persisted runtime budget override for this session.
     *
     * <p>
     * When present, the recorded {@code ExecutionBudget} takes priority over the opener-supplied default on session
     * re-open. Defaults to empty for views that do not persist this side field.
     *
     * @return the runtime budget override, or empty if none is recorded
     */
    default Optional<ExecutionBudget> getBudgetOverride() {
        return Optional.empty();
    }

    /**
     * Gets where the last turn began, when that turn ended interrupted and can therefore be retried.
     *
     * <p>
     * Unlike the totals and the budget override above, this is not a side field: it counts the messages it sits
     * beside, so it is held inside the transcript and replaced whenever they are. Defaults to empty for views that do
     * not carry it.
     *
     * @return the rewind point, or empty when the last turn ended some other way (never null)
     */
    default Optional<SessionRewindPoint> getRewindPoint() {
        return Optional.empty();
    }
}
