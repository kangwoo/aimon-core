/**
 * Shared per-tool invocation pipeline for the ReAct executors.
 *
 * <h2>Overview</h2>
 *
 * <p>
 * This package holds the single component both ReAct executors delegate a tool dispatch to:
 * {@link at.aimon.core.toolinvocation.SingleToolInvoker}. It runs the delicate per-tool sequence — interrupt-registrar
 * lifecycle plus PermissionRequest / PreTool / execute / PostTool hooks — that the main-agent executor
 * ({@code OrcaAgentExecutor}) and the subagent executor ({@code DefaultSubagentExecutor}) previously hand-cloned.
 *
 * <p>
 * Consolidating this logic removes the highest parity/drift risk in the codebase (a divergence in permission-hook
 * ordering or interrupt-registrar cleanup is security-relevant) and makes the pipeline independently testable. Callers
 * vary only through {@link at.aimon.core.toolinvocation.ToolInvocationSpec} — the invoker type / name woven into every
 * hook context and the permission allow-list applied at dispatch.
 *
 * <h2>Package placement</h2>
 *
 * <p>
 * The pipeline depends on {@code at.aimon.core.hook..} (hook contexts + execution manager) and
 * {@code at.aimon.core.tools} (context keys), so by the ArchUnit boundary rules it cannot live under
 * {@code at.aimon.core.agent..}. It also must be reachable from both {@code at.aimon.core.agent.impl.orca} and
 * {@code at.aimon.core.subagent.execution}, ruling out {@code at.aimon.core.agent.impl}. A neutral top-level package
 * satisfies both constraints without introducing a package cycle.
 */
package at.aimon.core.toolinvocation;
