package at.aimon.core.toolinvocation.approval;

import java.util.Optional;

/**
 * Remembers the answers a user gave to tool-approval prompts so the same question is not asked twice.
 *
 * <p>
 * Without this store an approval gate is unusable in practice: an agent that edits ten files would prompt ten times,
 * and a user who is prompted that often stops reading the prompt. The store is what turns "confirm every mutating
 * call" into "confirm a tool once, then trust it for the rest of its scope".
 *
 * <p>
 * <b>Both answers are remembered.</b> A remembered {@code false} is as binding as a remembered {@code true} — a user
 * who declined {@code Bash} once should not be asked about it again on the next iteration of the same loop. This
 * mirrors the skill-approval stack, where inheritance is explicitly bidirectional.
 *
 * <p>
 * <b>Scope keys.</b> The key is opaque to the store; the caller decides what a scope is. {@link SideEffectApprovalGate}
 * keys by the session whose reach the answer has — the run's own {@code SessionId} for a turn, the spawning session's
 * id for a fork — so a fork inherits what the user already answered in the session that launched it, exactly as
 * {@code ApprovalScope.SESSION} defines reach for skills.
 *
 * <p>
 * Implementations must be thread-safe: a parallel tool batch evaluates several tools against one store concurrently.
 *
 * <p>
 * Implementations must be thread-safe. The default implementation is in-memory
 * ({@link InMemoryToolApprovalStore}) and therefore node-local. Because {@link SideEffectApprovalGate} keys by session,
 * the same thing follows here as for the skill-approval stores: a session that moves to another node asks again. It
 * fails closed — a forgotten answer re-prompts, it does not silently allow — and the assembly reports the whole
 * category once at startup as the {@code distributed-approvals} degradation rather than repeating it per store.
 *
 * @see SideEffectApprovalGate
 * @see InMemoryToolApprovalStore
 */
public interface ToolApprovalStore {

    /**
     * Looks up a remembered answer.
     *
     * @param scopeKey
     *            the scope the answer was given in (must not be null)
     * @param toolName
     *            the tool the answer was about (must not be null)
     * @return {@code true} if approved, {@code false} if declined, or {@link Optional#empty()} when this scope has not
     *         been asked about this tool yet
     * @throws NullPointerException
     *             if any parameter is null
     */
    Optional<Boolean> lookup(String scopeKey, String toolName);

    /**
     * Records the answer a user gave, so the same scope is not asked about the same tool again.
     *
     * @param scopeKey
     *            the scope the answer applies to (must not be null)
     * @param toolName
     *            the tool the answer was about (must not be null)
     * @param allowed
     *            {@code true} when the user approved, {@code false} when they declined
     * @throws NullPointerException
     *             if any parameter is null
     */
    void remember(String scopeKey, String toolName, boolean allowed);

    /**
     * Forgets every answer recorded for a scope, so the next call asks again. Called when a session ends, or when a
     * user explicitly revokes what they granted.
     *
     * @param scopeKey
     *            the scope to clear (must not be null); clearing an unknown scope is a no-op
     * @throws NullPointerException
     *             if {@code scopeKey} is null
     */
    void revoke(String scopeKey);
}
