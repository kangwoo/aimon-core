package at.aimon.core.memory;

import java.util.List;

/**
 * {@link MemoryCapability#SEARCH} — finds the observations most relevant to a phrase.
 *
 * <p>
 * Two things are promised here, and they are promised separately on purpose.
 *
 * <ol>
 * <li><b>Order.</b> Every backend returns hits from most to least relevant. That is the ranking, and it holds even for
 * backends that have no numbers to attach to it.
 * <li><b>Score.</b> Only backends whose {@link #ranksByScore()} is {@code true} fill {@link MemoryHit#getScore()}.
 * </ol>
 *
 * <p>
 * The consequence is {@link MemorySearchQuery#getMinScore()}: a backend that cannot score must <em>reject</em> a
 * positive floor rather than ignore it. Ignoring it is the failure this whole design exists to remove — a caller who
 * asked for a filter, was not told it could not run, and reads the unfiltered result as filtered.
 *
 * <p>
 * {@link MemorySearchQuery#getSessionId()} is the second axis with that shape, and it gets the same treatment for the
 * same reason: {@link #narrowsBySession()} says whether the backend can apply it, and one that cannot rejects it.
 * The query's third optional axis, {@link MemorySearchQuery#getObserver()}, is deliberately <em>not</em> in this
 * family — it says who is asking rather than narrowing what comes back, so there is no filter for a caller to believe
 * in.
 *
 * <p>
 * Implementations must be thread-safe.
 */
public interface MemorySearcher {

    /**
     * Searches for the observations {@code query} asks about.
     *
     * @param query
     *            what to look for and how much of it (must not be null)
     * @return the hits, <b>always ordered from most to least relevant</b>; empty when nothing matched. Never null and
     *         never longer than {@link MemorySearchQuery#getTopK()}
     * @throws NullPointerException
     *             if {@code query} is null
     * @throws IllegalArgumentException
     *             if {@code query} carries a positive {@link MemorySearchQuery#getMinScore()} while
     *             {@link #ranksByScore()} is {@code false}, or a {@link MemorySearchQuery#getSessionId()} while
     *             {@link #narrowsBySession()} is {@code false}
     */
    List<MemoryHit> search(MemorySearchQuery query);

    /**
     * Returns whether this backend can express relevance as a number as well as an order.
     *
     * <p>
     * A question, not a query — which is why it takes no argument. When it is {@code false}, every
     * {@link MemoryHit#getScore()} is {@code 0} and a positive {@link MemorySearchQuery#getMinScore()} is rejected.
     *
     * @return {@code true} when {@link MemoryHit#getScore()} carries a measured value
     */
    boolean ranksByScore();

    /**
     * Returns whether this backend can confine a search to one session.
     *
     * <p>
     * A question, not a query — which is why it takes no argument, and the third of them after
     * {@link #ranksByScore()} and {@link ObservationRecorder#storesConfidence()}. When it is {@code false}, a
     * {@link MemorySearchQuery} carrying a session id is rejected rather than answered across every session: the
     * caller asked for a narrower result and would otherwise read a wider one as narrow.
     *
     * <p>
     * The store-backed default answers {@code false}, and not by omission —
     * {@link ObservationStore#semanticSearch(PeerView, String, int)} has no session axis to narrow on. A backend
     * whose server does have one answers {@code true}.
     *
     * @return {@code true} when {@link MemorySearchQuery#getSessionId()} is honoured
     */
    boolean narrowsBySession();
}
