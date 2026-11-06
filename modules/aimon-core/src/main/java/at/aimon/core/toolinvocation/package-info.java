/**
 * Shared per-tool invocation pipeline for the ReAct executors.
 *
 * <h2>Overview</h2>
 *
 * <p>
 * This package holds the implementations of the single entrance through which a tool call reaches the gates,
 * {@link at.aimon.core.agent.tool.ToolCallDispatcher}: the gated
 * {@link at.aimon.core.toolinvocation.DefaultToolCallDispatcher}, which every gated loop binds into its tool context —
 * the main agent ({@code OrcaAgentExecutor}), a fork ({@code DefaultSubagentExecutor}) and a slash-command skill
 * ({@code LlmSkillExecutor}, through {@code narrow}) — and the explicitly named
 * {@link at.aimon.core.toolinvocation.ToolCallDispatchers#ungated ungated} one. Behind the gated dispatcher sits
 * {@link at.aimon.core.toolinvocation.SingleToolInvoker}, the delicate per-tool sequence — interrupt-registrar
 * lifecycle plus PermissionRequest / approval / PreTool / execute / PostTool — assembled from one
 * {@link at.aimon.core.toolinvocation.ToolInvocationSpec}.
 *
 * <p>
 * Keeping this logic in one place removes a parity/drift risk (a divergence in permission-hook ordering or
 * interrupt-registrar cleanup is security-relevant). It is also the only package allowed to call
 * {@code ToolExecutionManager#execute} / {@code #executeAll} ({@code ToolExecutionGateArchitectureTest}): a direct call
 * elsewhere would pass schema validation while skipping every hook and approval gate.
 *
 * <h2>Package placement</h2>
 *
 * <p>
 * The pipeline depends on {@code at.aimon.core.hook..} (hook contexts + execution manager) and
 * {@code at.aimon.core.tools} (context keys), so by the ArchUnit boundary rules it cannot live under
 * {@code at.aimon.core.agent..}. It also must be reachable from both {@code at.aimon.core.agent.impl.orca} and
 * {@code at.aimon.core.subagent.execution}, ruling out {@code at.aimon.core.agent.impl}. A neutral top-level package
 * satisfies both constraints without introducing a package cycle.
 *
 * <p>
 * The same two constraints place the tracing helpers both executors share here:
 * {@link at.aimon.core.toolinvocation.ToolCallTracing} (the TOOL span) and
 * {@link at.aimon.core.toolinvocation.TracingCompactionObserver} (the COMPACTION span). Each depends on
 * {@code at.aimon.core.tracing}, which no package under {@code at.aimon.core.agent..} may reach.
 */
package at.aimon.core.toolinvocation;
