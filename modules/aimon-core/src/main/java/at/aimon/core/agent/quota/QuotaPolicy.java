package at.aimon.core.agent.quota;

import java.util.List;

/**
 * Decides which limits a scope is held to. Static configuration is the in-tree default; a policy may also look limits
 * up per subject.
 *
 * <p>
 * Asked once per scope when a {@link QuotaGuard} is built. The answer is fixed for that guard's lifetime, so a policy
 * change applies to the next turn, not to a turn — or a fork — already running.
 */
public interface QuotaPolicy {

    /**
     * Gets the limits of one scope.
     *
     * @param scope
     *            the scope (must not be null)
     * @return the limits, at most one per window being meaningful (never null; empty means the scope is unlimited)
     */
    List<QuotaLimit> limitsFor(QuotaScope scope);
}
