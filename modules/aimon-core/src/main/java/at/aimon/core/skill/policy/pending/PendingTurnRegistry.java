package at.aimon.core.skill.policy.pending;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import at.aimon.core.agent.AgentRuntimeId;

/**
 * Registry of {@link PendingTurn} snapshots for the SK-11 atomic-suspension flow.
 *
 * <p>
 * Owners (typically the agent executor in SK-11.4) {@link #register(PendingTurn) register} a turn when suspension
 * occurs, and {@link #remove(PendingTurnId) remove} it once it resumes successfully, is cancelled, or times out. Read
 * paths support the {@code /pending} CLI command (per-context listing) and direct id-based lookup for {@code /approve}
 * / {@code /deny}.
 *
 * <p>
 * {@link #removeExpired(Instant)} is provided so a periodic reaper (SK-11.5) can clean up turns whose TTL elapsed
 * without user response.
 *
 * <p>
 * Implementations must be thread-safe. The default implementation is in-memory
 * ({@link InMemoryPendingTurnRegistry}); deployments running multiple aimon instances should provide a shared backing
 * store.
 */
public interface PendingTurnRegistry {

    /**
     * Registers a suspended turn. If a turn with the same id already exists the call must throw.
     *
     * @throws NullPointerException
     *             if {@code turn} is null
     * @throws IllegalStateException
     *             if a turn with the same id is already registered
     */
    void register(PendingTurn turn);

    /**
     * Looks up a pending turn by id.
     */
    Optional<PendingTurn> get(PendingTurnId id);

    /**
     * Returns all pending turns owned by the given context, ordered by creation time (oldest first).
     *
     * <p>
     * The returned list is an immutable snapshot.
     */
    List<PendingTurn> listByAgentRuntime(AgentRuntimeId agentRuntimeId);

    /**
     * Returns every registered pending turn, ordered by creation time (oldest first).
     *
     * <p>
     * Used by {@code /pending} to display the global queue of suspended turns to the user. The returned list is an
     * immutable snapshot.
     */
    List<PendingTurn> listAll();

    /**
     * Removes the pending turn with the given id, returning the removed snapshot if any.
     */
    Optional<PendingTurn> remove(PendingTurnId id);

    /**
     * Removes every turn whose {@link PendingTurn#isExpired(Instant) expired} as of {@code now}.
     *
     * @return the snapshots that were removed (immutable, never null, may be empty)
     */
    List<PendingTurn> removeExpired(Instant now);
}
