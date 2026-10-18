package at.aimon.core.agent.quota;

import java.util.List;

/**
 * Picks the scopes one execution's usage is charged to. Supplied by the application.
 *
 * <p>
 * Called once per turn, when the executor builds the turn's {@link QuotaGuard}, or once per scheduled routine run
 * (no session; the task's owner as principal). Never called for a fork — a fork shares its parent's guard, so its usage
 * lands on the scopes its parent's turn or routine run resolved.
 */
public interface QuotaScopeResolver {

    /**
     * Picks the scopes of one execution.
     *
     * @param context
     *            what is known about the execution (must not be null)
     * @return the scopes, in no particular order (never null; empty means the execution is not metered)
     */
    List<QuotaScope> resolve(QuotaResolutionContext context);
}
