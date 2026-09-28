package at.aimon.core.agent.impl.orca.environment;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.filesystem.FileMetadata;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.filesystem.exception.VirtualFileSystemException;

/**
 * Assembler-side helper that promotes isolated worktree branches to the canonical filesystem (design §6.3). Not a
 * workflow SPI primitive — it is an optional, script-driven convenience.
 *
 * <p>
 * Each branch wrote into its own isolated environment ({@link ExecutionEnvironment#isolate(String)}), so the write
 * phase never clobbers. The <b>promotion</b> phase, however, is not clobber-free: two branches may have written the
 * same canonical relative path. This helper therefore <b>pre-scans for cross-branch collisions</b> and applies an
 * explicit {@link Policy} instead of a silent last-writer-wins. Promotion of each chosen file is a cross-environment
 * copy — {@code parent.write(branch.openInputStream)} &rarr; verify {@code exists} &rarr; delete the branch source —
 * never a bare {@code move} (S3/GridFS move is a non-atomic copy-then-delete).
 *
 * <p>
 * <b>Where branches come from.</b> The workflow runner isolates each leaf under a key derived from its deterministic
 * structural step path ({@code at.aimon.core.workflow.impl.DefaultWorkflowContext}'s {@code sanitizeBranchKey}). An
 * assembler that knows only the keys — for example from listing {@code .worktrees/} — rebuilds each branch with
 * {@code parent.isolate(key).orElseThrow()}: isolation is deterministic on the key, so this is the same view the run
 * used. Staged skill copies are shared with the parent and never appear in a branch listing, so they are never
 * promoted; a file a branch wrote under its own {@code .aimon/} is promoted to the root {@code .aimon/}, where the
 * parent's path rules refuse it (the control store is not writable through a merge).
 */
public final class WorktreeMerge {

    private static final Logger log = LoggerFactory.getLogger(WorktreeMerge.class);

    private WorktreeMerge() {
    }

    /** How to resolve a canonical path written by more than one branch. */
    public enum Policy {
        /** Do not promote any conflicting path; report the collisions for the caller to resolve. */
        FAIL,
        /** The earliest branch in the list wins a conflict. */
        FIRST_WINS,
        /** The latest branch in the list wins a conflict. */
        LAST_WINS
    }

    /**
     * Promotes the given branch environments' files to the parent environment.
     *
     * <p>
     * <b>Failure semantics.</b> Promotion is fail-fast with <em>no rollback</em>: if a copy/verify/delete fails
     * mid-merge, the partial progress (files promoted and conflicts so far) is logged at WARN and included in the
     * message of the {@link VirtualFileSystemException} that propagates. Already-promoted files stay in place on the
     * canonical filesystem (their branch sources already deleted); per-file promotion is idempotent, so the merge can
     * simply be re-run after the underlying failure is resolved.
     *
     * @param parent
     *            the environment the branches were isolated from (must not be null)
     * @param branches
     *            the branch environments to promote, in precedence order (must not be null)
     * @param policy
     *            how to resolve a canonical path written by more than one branch (must not be null)
     * @return the merge report (promoted paths + collisions); a conflict under {@link Policy#FAIL} promotes nothing
     * @throws VirtualFileSystemException
     *             if a promotion step fails mid-merge; the message carries the partial progress (no rollback)
     */
    public static MergeReport promote(ExecutionEnvironment parent, List<ExecutionEnvironment> branches, Policy policy) {
        Objects.requireNonNull(parent, "parent cannot be null");
        Objects.requireNonNull(branches, "branches cannot be null");
        Objects.requireNonNull(policy, "policy cannot be null");
        for (final ExecutionEnvironment branch : branches) {
            Objects.requireNonNull(branch, "branches must not contain null");
        }

        // Pre-scan: canonical path -> indexes of the branches that wrote it, in precedence order.
        final Map<String, List<Integer>> canonicalToBranches = new LinkedHashMap<>();
        for (int i = 0; i < branches.size(); i++) {
            final VirtualFileSystem branchFs = branches.get(i).fileSystem();
            if (!branchFs.isDirectory(".")) {
                continue; // branch produced no files
            }
            for (final String canonical : branchFs.listRecursive(".")) {
                canonicalToBranches.computeIfAbsent(canonical, k -> new ArrayList<>()).add(i);
            }
        }

        final List<String> conflicts = new ArrayList<>();
        for (final Map.Entry<String, List<Integer>> entry : canonicalToBranches.entrySet()) {
            if (entry.getValue().size() > 1) {
                conflicts.add(entry.getKey());
            }
        }
        if (policy == Policy.FAIL && !conflicts.isEmpty()) {
            return new MergeReport(List.of(), conflicts);
        }

        final VirtualFileSystem canonicalFs = parent.fileSystem();
        final List<String> promoted = new ArrayList<>();
        for (final Map.Entry<String, List<Integer>> entry : canonicalToBranches.entrySet()) {
            final String canonical = entry.getKey();
            final List<Integer> writers = entry.getValue();
            final int winner = policy == Policy.LAST_WINS ? writers.get(writers.size() - 1) : writers.get(0);
            final VirtualFileSystem branchFs = branches.get(winner).fileSystem();
            try {
                // copy -> verify -> delete (idempotent, retry-safe; never a bare move).
                final FileMetadata metadata = branchFs.getMetadata(canonical);
                try (InputStream in = branchFs.openInputStream(canonical)) {
                    canonicalFs.write(canonical, in, metadata.getSize());
                }
                if (canonicalFs.exists(canonical)) {
                    branchFs.delete(canonical);
                    promoted.add(canonical);
                }
            } catch (final Exception e) {
                // Fail-fast abort, but make the partial progress observable before propagating (no rollback:
                // already-promoted files stay in place; per-file promotion is idempotent, so re-running is safe).
                log.warn(
                        "Worktree promotion aborted at '{}' (branch #{}): {} file(s) already promoted, "
                                + "{} conflict(s); already-promoted files remain in place (no rollback): {}",
                        canonical, winner, promoted.size(), conflicts.size(), e.getMessage());
                throw new VirtualFileSystemException("Worktree promotion aborted at '" + canonical + "' (branch #"
                        + winner + ") after " + promoted.size() + " promoted file(s) and " + conflicts.size()
                        + " conflict(s); already-promoted files remain in place (no rollback)", e);
            }
        }
        return new MergeReport(promoted, conflicts);
    }
}
