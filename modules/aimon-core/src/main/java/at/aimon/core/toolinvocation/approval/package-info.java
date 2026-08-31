/**
 * Interactive approval for tool calls that change something.
 *
 * <h2>Overview</h2>
 *
 * <p>
 * {@link at.aimon.core.toolinvocation.approval.SideEffectApprovalGate} reads a tool's own
 * {@link at.aimon.core.agent.tool.SideEffectLevel} declaration and requires a user to confirm anything that writes,
 * remembering the answer in a {@link at.aimon.core.toolinvocation.approval.ToolApprovalStore} so a scope is asked at
 * most once per tool. It is evaluated inside
 * {@link at.aimon.core.toolinvocation.SingleToolInvoker} — the single pipeline the main agent and subagent forks both
 * dispatch through — so a fork cannot route around it.
 *
 * <h2>Two side-effect controls, not one</h2>
 *
 * <p>
 * This package is the <em>dynamic</em> half of the side-effect story. The <em>static</em> half is the ceiling
 * ({@code DefaultToolExecutionManager(SideEffectLevel)} plus the matching definition filter in
 * {@code OrcaAgentExecutor}), where the host decides up front what class of tool may run at all and no human need be
 * present. They compose: the ceiling withholds over-privileged tools from the model and refuses them if named anyway;
 * the gate asks about what is left.
 *
 * <p>
 * The distinction that keeps them separate is that a {@code SideEffectLevel} says what a tool <em>does</em>, not how
 * dangerous it is <em>here</em> — deleting a scratch file and dropping a production table are both
 * {@link at.aimon.core.agent.tool.SideEffectLevel#MUTATING}. Risk that depends on the argument rather than the tool
 * belongs in the allow-list ({@code AllowedTool}, e.g. {@code Bash(git:*)}) or a
 * {@link at.aimon.core.hook.event.PermissionRequestHook}, which see the input.
 *
 * <h2>Runs with nobody to ask</h2>
 *
 * <p>
 * A subagent fork has no {@code SessionId} of its own, so it is keyed by the
 * {@link at.aimon.core.tools.ToolContextKeys#INVOKING_SESSION_ID} it carries and inherits what the user answered in
 * the session that launched it. A run with no session at all — a scheduled routine, a rewake replay — has neither a
 * scope to cache against nor anyone to prompt, and falls to the handler's default, which is deny. That is the
 * fail-safe direction, but it means <b>unattended deployments must either leave the gate unconfigured or supply a
 * handler that can answer without a human</b>; the gate does not quietly approve work no one saw.
 *
 * @see at.aimon.core.agent.tool.SideEffectLevel
 * @see at.aimon.core.hook.execution.AskPromptHandler
 */
package at.aimon.core.toolinvocation.approval;
