package at.aimon.core.agent.impl.orca.environment;

import java.util.Objects;

import at.aimon.core.agent.impl.orca.tool.OrcaFileToolProvider;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.agent.tool.Tool;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.filesystem.impl.ScopedVirtualFileSystem;
import at.aimon.core.subagent.SubagentExecutionEnvironment;
import at.aimon.core.tools.artifact.ArtifactAwareEditTool;
import at.aimon.core.tools.artifact.ArtifactAwareWriteTool;
import at.aimon.core.tools.file.EditTool;
import at.aimon.core.tools.file.GrepTool;
import at.aimon.core.tools.file.ReadTool;
import at.aimon.core.tools.file.WriteTool;
import at.aimon.core.workflow.WorktreeEnvironmentFactory;

/**
 * Assembler-side {@link WorktreeEnvironmentFactory} implementation for worktree isolation (design §6.3).
 *
 * <p>
 * File-mutating tools hold their {@code VirtualFileSystem} as a constructor field of the shared, agent-scoped
 * {@code ToolRegistry}, so a per-branch filesystem view requires a per-branch registry whose file tools are rebound to
 * a
 * branch-scoped {@link ScopedVirtualFileSystem}. Workflow itself must not construct tool or
 * {@code filesystem.impl}
 * types, so this lives in the in-core assembler carve-out ({@code agent.impl.orca.environment}) — the only
 * in-core package permitted to touch {@code filesystem.impl} — and is injected into the runner via
 * {@code WorkflowRunnerOptions.worktreeFactory}.
 *
 * <p>
 * Each {@link #derive} rebuilds the file tools named by {@link OrcaFileToolProvider#FILE_TOOL_NAMES}
 * ({@code Read}/{@code Write}/{@code Edit}/{@code Grep}) over the
 * scoped VFS, branching on whether the base registry used the artifact-aware {@code Write}/{@code Edit} variants (so an
 * artifact-enabled deployment keeps registering artifacts inside isolated branches — the review-flagged silent-drop),
 * carries every non-file tool over unchanged, and derives the environment from {@code baseEnv.toBuilder()} so it
 * preserves the per-run {@code cancellationSignal} (required for a {@code stop(runId)} to reach the isolated leaf,
 * §6.3).
 */
public final class WorktreeToolEnvironmentFactory implements WorktreeEnvironmentFactory {

    /** Root under which every branch's isolated subtree lives. */
    static final String WORKTREE_ROOT = ".worktrees/";

    private final VirtualFileSystem baseVfs;

    /**
     * @param baseVfs
     *            the shared backend filesystem to scope per branch (must not be null; borrowed — never closed)
     */
    public WorktreeToolEnvironmentFactory(VirtualFileSystem baseVfs) {
        this.baseVfs = Objects.requireNonNull(baseVfs, "baseVfs cannot be null");
    }

    @Override
    public SubagentExecutionEnvironment derive(SubagentExecutionEnvironment baseEnv, String branchKey) {
        Objects.requireNonNull(baseEnv, "baseEnv cannot be null");
        Objects.requireNonNull(branchKey, "branchKey cannot be null");

        final VirtualFileSystem scoped = new ScopedVirtualFileSystem(baseVfs, WORKTREE_ROOT + branchKey);
        final ToolRegistry baseRegistry = baseEnv.getToolRegistry();

        // Reconstruct whichever Write/Edit variant the base deployment used, so artifact registration is not silently
        // dropped inside isolated branches (both variants share TOOL_NAME with the plain tools, so the rebind replaces
        // cleanly by name).
        final boolean artifactEnabled = baseRegistry.findByName(WriteTool.TOOL_NAME)
                .map(ArtifactAwareWriteTool.class::isInstance).orElse(false);

        final DefaultToolRegistry scopedRegistry = new DefaultToolRegistry();
        // Carry over every non-file tool unchanged (MCP, Bash, Todo, ...). The exclusion set is the canonical
        // OrcaFileToolProvider.FILE_TOOL_NAMES constant, so the two classes cannot drift: a fifth VFS-backed tool
        // added to the provider (and its name set) is excluded here rather than carried over still bound to the base
        // VFS — a missing rebinding branch below then surfaces as an absent tool instead of silently defeating
        // isolation.
        final String[] fileToolNames = OrcaFileToolProvider.FILE_TOOL_NAMES.toArray(String[]::new);
        for (final Tool tool : baseRegistry.findAllExcept(fileToolNames)) {
            scopedRegistry.register(tool);
        }
        // Rebind the four file tools to the scoped VFS.
        scopedRegistry.register(new ReadTool(scoped));
        scopedRegistry.register(artifactEnabled ? new ArtifactAwareWriteTool(scoped) : new WriteTool(scoped));
        scopedRegistry.register(artifactEnabled ? new ArtifactAwareEditTool(scoped) : new EditTool(scoped));
        scopedRegistry.register(new GrepTool(scoped));

        // Derive from toBuilder() so all borrowed collaborators — critically the per-run cancellationSignal — are
        // preserved; override only the tool registry (§6.3).
        return baseEnv.toBuilder().toolRegistry(scopedRegistry).build();
    }
}
