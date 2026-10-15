/**
 * Accumulated usage quotas: limits on what a session, a principal or a tenant spends across executions, in total or
 * per day.
 *
 * <h2>Overview</h2>
 *
 * <p>
 * An {@link at.aimon.core.agent.budget.ExecutionBudget} bounds <em>one</em> execution and starts again on the next. A
 * quota bounds the sum: every LLM call any execution makes — a turn, or any fork under it — is charged to the same
 * {@link at.aimon.core.agent.quota.UsageLedger} keys, and once a key reaches its limit the next iteration ends with
 * {@link at.aimon.core.agent.budget.CompletionReason#QUOTA_EXCEEDED} before it calls the LLM.
 *
 * <ul>
 * <li>{@link at.aimon.core.agent.quota.QuotaScope} · {@link at.aimon.core.agent.quota.QuotaWindow} ·
 * {@link at.aimon.core.agent.quota.QuotaKey} — who is charged, over which period, under which ledger key
 * <li>{@link at.aimon.core.agent.quota.QuotaUsage} · {@link at.aimon.core.agent.quota.QuotaLimit} — tokens and USD
 * cost, used and allowed
 * <li>{@link at.aimon.core.agent.quota.UsageLedger} — the application-scoped counter store; the in-memory default is
 * in {@code .impl}
 * <li>{@link at.aimon.core.agent.quota.QuotaScopeResolver} · {@link at.aimon.core.agent.quota.QuotaPolicy} — the
 * application's answer to "whose execution is this" and "how much may they use"
 * <li>{@link at.aimon.core.agent.quota.QuotaGuard} — the per-turn check and charge, shared by the turn's forks;
 * {@link at.aimon.core.agent.quota.QuotaGuardFactory} builds one per turn
 * <li>{@link at.aimon.core.agent.quota.QuotaViolation} — which key stopped an execution, on its result
 * </ul>
 *
 * <p>
 * Opt-in: an executor without a factory uses {@link at.aimon.core.agent.quota.QuotaGuardFactory#disabled()} and
 * behaves exactly as before. Design: {@code docs/design/agent-execution/usage-quota.md}.
 */
package at.aimon.core.agent.quota;
