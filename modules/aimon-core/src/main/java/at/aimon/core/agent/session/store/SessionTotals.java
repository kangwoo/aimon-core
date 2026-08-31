package at.aimon.core.agent.session.store;

import java.util.Objects;

import at.aimon.core.agent.session.SessionId;
import at.aimon.core.llm.TokenUsage;

/**
 * Immutable session-cumulative totals folded across all <em>completed</em> turns of a session.
 *
 * <p>
 * These totals span the whole session: every finished turn adds its final iteration count and token usage via
 * {@link #plusTurn(int, TokenUsage)}. The in-flight turn is excluded. There is no budget here because
 * {@code ExecutionBudget} limits are enforced per turn, not per session.
 *
 * <p>
 * They sit on the durable side rather than on the handle, and that is what makes them survive: persisted as a
 * {@link SessionRecord} side field keyed by {@link SessionId}, they are rehydrated by the next {@code LiveSession}
 * opened on the same id instead of restarting at zero across an idle-TTL eviction, a restart or a move to another
 * node. The handle only observes them, surfacing the value through {@code LiveSessionStatus#getSessionTotals()}.
 */
public final class SessionTotals {
    private static final SessionTotals EMPTY = new SessionTotals(0, 0, TokenUsage.empty());

    private final int turnCount;
    private final int iterations;
    private final TokenUsage tokenUsage;

    private SessionTotals(int turnCount, int iterations, TokenUsage tokenUsage) {
        this.turnCount = turnCount;
        this.iterations = iterations;
        this.tokenUsage = Objects.requireNonNull(tokenUsage, "tokenUsage must not be null");
    }

    /**
     * @return the zero totals: no turns, no iterations, empty token usage (shared singleton)
     */
    public static SessionTotals empty() {
        return EMPTY;
    }

    /**
     * Creates session totals with explicit counts.
     *
     * @param turnCount
     *            the number of completed turns (>= 0)
     * @param iterations
     *            the cumulative ReAct iterations across those turns (>= 0)
     * @param tokenUsage
     *            the cumulative token usage across those turns (must not be null)
     * @return a new immutable totals object (never null)
     * @throws NullPointerException
     *             if {@code tokenUsage} is null
     */
    public static SessionTotals of(int turnCount, int iterations, TokenUsage tokenUsage) {
        return new SessionTotals(turnCount, iterations, tokenUsage);
    }

    /**
     * Returns the totals after folding in one more completed turn — increments the turn count by one and adds the
     * turn's iterations and token usage to the running totals.
     *
     * @param turnIterations
     *            the completed turn's iteration count
     * @param turnTokens
     *            the completed turn's token usage (must not be null)
     * @return a new totals object reflecting the additional turn (never null)
     * @throws NullPointerException
     *             if {@code turnTokens} is null
     */
    public SessionTotals plusTurn(int turnIterations, TokenUsage turnTokens) {
        Objects.requireNonNull(turnTokens, "turnTokens must not be null");
        return new SessionTotals(turnCount + 1, iterations + turnIterations, tokenUsage.add(turnTokens));
    }

    /**
     * @return the number of completed turns folded into these totals
     */
    public int getTurnCount() {
        return turnCount;
    }

    /**
     * @return the cumulative ReAct iterations across all completed turns
     */
    public int getIterations() {
        return iterations;
    }

    /**
     * @return the cumulative token usage across all completed turns (never null)
     */
    public TokenUsage getTokenUsage() {
        return tokenUsage;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final SessionTotals that = (SessionTotals) o;
        return turnCount == that.turnCount && iterations == that.iterations && tokenUsage.equals(that.tokenUsage);
    }

    @Override
    public int hashCode() {
        return Objects.hash(turnCount, iterations, tokenUsage);
    }

    @Override
    public String toString() {
        return "SessionTotals{turnCount=" + turnCount + ", iterations=" + iterations + ", tokenUsage=" + tokenUsage
                + '}';
    }
}
