package at.aimon.core.workflow;

import at.aimon.core.subagent.SubagentExecutionEnvironment;

/**
 * Caller-injected factory that derives a per-branch, worktree-scoped {@link SubagentExecutionEnvironment} for an
 * isolated {@code agent()} step (design §6.3).
 *
 * <p>
 * File-mutating tools ({@code Write}/{@code Edit}) hold their {@code VirtualFileSystem} as a constructor-injected field
 * of the shared, agent-scoped {@code ToolRegistry}, so a per-branch filesystem view cannot be achieved by swapping a
 * {@code ToolContext} key. It requires a per-branch {@code ToolRegistry} whose file tools are rebound to a
 * branch-scoped {@code VirtualFileSystem}. Because workflow must not construct tool or {@code filesystem.impl}
 * types (ArchUnit), that scoping is supplied by the assembler (the composition root) through this SPI: the
 * runner only calls {@link #derive} and {@code baseEnv.toBuilder()}.
 *
 * <p>
 * <b>MUST — preserve the per-run cancellation signal.</b> An isolated leaf observes cancellation only through the
 * derived environment's signal (the leaf-permit acquire and {@code manager.execute} both read
 * {@code env.getCancellationSignal()}). {@code SubagentExecutionEnvironment} silently defaults a missing signal to
 * {@code NoopCancellationSignal.INSTANCE}, so an implementation MUST start from {@code baseEnv.toBuilder()} and
 * override
 * only {@code toolRegistry} (and optionally the {@code Environment} working directory) — preserving
 * {@code cancellationSignal} (and {@code agentRuntimeId}/{@code principal}) — or a {@code stop(runId)} will never reach
 * the
 * isolated leaf, defeating cooperative cancellation and orphan cleanup for exactly the file-mutating branches (design
 * §6.3). Implementations MUST NOT throw {@code WorkflowException} (reserved for run-fatal control
 * signals); an ordinary failure is isolated by the fan-out engine.
 */
@FunctionalInterface
public interface WorktreeEnvironmentFactory {

    /**
     * Derives a branch-scoped environment from {@code baseEnv} for the branch identified by {@code branchKey}.
     *
     * @param baseEnv
     *            the run's base environment (must not be null; borrowed — not closed)
     * @param branchKey
     *            the deterministic branch identifier (must not be null); names the branch's isolated file subtree
     * @return a derived environment whose file tools are scoped to the branch (never null)
     */
    SubagentExecutionEnvironment derive(SubagentExecutionEnvironment baseEnv, String branchKey);
}
