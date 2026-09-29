/**
 * Worktree merge support for the Orca agent system.
 *
 * <p>
 * Workflow branches are isolated by the execution environment itself
 * ({@code at.aimon.core.environment.ExecutionEnvironment#isolate}); this package keeps the assembler-side helper that
 * promotes the files those branches wrote back to the parent environment
 * ({@link at.aimon.core.agent.impl.orca.environment.WorktreeMerge}) and its report
 * ({@link at.aimon.core.agent.impl.orca.environment.MergeReport}).
 *
 * @see at.aimon.core.environment.ExecutionEnvironment
 */
package at.aimon.core.agent.impl.orca.environment;
