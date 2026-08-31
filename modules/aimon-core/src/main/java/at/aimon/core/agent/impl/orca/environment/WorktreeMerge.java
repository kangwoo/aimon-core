package at.aimon.core.agent.impl.orca.environment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.filesystem.exception.VirtualFileSystemException;
import at.aimon.core.filesystem.impl.ScopedVirtualFileSystem;

/**
 * Assembler-side helper that promotes isolated worktree subtrees to the canonical filesystem (design §6.3). Not
 * an workflow SPI primitive — it is an optional, script-driven convenience.
 *
 * <p>
 * Each branch wrote into a disjoint {@code .worktrees/<branchKey>/} subtree, so the write phase never clobbers.
 * The <b>promotion</b> phase, however, is not clobber-free: two branches may have written the same canonical relative
 * path. This helper therefore <b>pre-scans for cross-branch collisions</b> and applies an explicit {@link Policy}
 * instead of a silent last-writer-wins. Promotion of each chosen file is {@code copy(overwrite=true)} &rarr; verify
 * {@code exists} &rarr; delete the branch source — never a bare {@code move} (S3/GridFS move is a non-atomic
 * copy-then-delete).
 *
 * <p>
 * <b>Where branch keys come from.</b> Callers do not invent branch keys: the workflow runner derives each
 * isolated leaf's key from its deterministic structural step path
 * ({@code at.aimon.core.workflow.impl.DefaultWorkflowContext}'s {@code sanitizeBranchKey}, design
 * §6.3), which always produces the shape {@code [A-Za-z0-9_]+}. An assembler script that did not record the keys
 * can discover the existing subtrees by listing {@code .worktrees/} on the shared backend. {@link #promote}
 * re-validates the shape defensively before touching the filesystem.
 */
public final class WorktreeMerge {

    private static final Logger log = LoggerFactory.getLogger(WorktreeMerge.class);

    /** The shape every branch key must have — exactly what the framework's branch-key derivation produces. */
    private static final Pattern BRANCH_KEY_SHAPE = Pattern.compile("[A-Za-z0-9_]+");

    private WorktreeMerge() {
    }

    /** How to resolve a canonical path written by more than one branch. */
    public enum Policy {
        /** Do not promote any conflicting path; report the collisions for the caller to resolve. */
        FAIL,
        /** The earliest branch in {@code branchKeys} wins a conflict. */
        FIRST_WINS,
        /** The latest branch in {@code branchKeys} wins a conflict. */
        LAST_WINS
    }

    /**
     * Promotes the given branches' worktree subtrees to the canonical filesystem.
     *
     * <p>
     * <b>Branch key shape.</b> Every branch key must match {@code [A-Za-z0-9_]+} — the exact shape the framework's
     * branch-key derivation ({@code DefaultWorkflowContext#sanitizeBranchKey}) produces, so framework-derived
     * keys always pass. Anything else ({@code null}, empty, path separators, {@code ..} segments) is rejected with
     * {@link IllegalArgumentException} <em>before</em> any filesystem access: a non-conforming key (e.g.
     * {@code "../docs"}) would scope the merge outside its {@code .worktrees/<branchKey>/} subtree and relocate or
     * delete canonical files.
     *
     * <p>
     * <b>Failure semantics.</b> Promotion is fail-fast with <em>no rollback</em>: if a copy/verify/delete fails
     * mid-merge, the partial progress (files promoted and conflicts so far) is logged at WARN and included in the
     * message of the {@link VirtualFileSystemException} that propagates. Already-promoted files stay in place on the
     * canonical filesystem (their branch sources already deleted); per-file promotion is idempotent, so the merge can
     * simply be re-run after the underlying failure is resolved.
     *
     * @param baseVfs
     *            the shared backend filesystem (must not be null)
     * @param branchKeys
     *            the branch keys to promote, in precedence order (must not be null; every key must match
     *            {@code [A-Za-z0-9_]+})
     * @param policy
     *            how to resolve a canonical path written by more than one branch (must not be null)
     * @return the merge report (promoted paths + collisions); a conflict under {@link Policy#FAIL} promotes nothing
     * @throws IllegalArgumentException
     *             if any branch key is null, empty, or does not match {@code [A-Za-z0-9_]+}
     * @throws VirtualFileSystemException
     *             if a promotion step fails mid-merge; the message carries the partial progress (no rollback)
     */
    public static MergeReport promote(VirtualFileSystem baseVfs, List<String> branchKeys, Policy policy) {
        Objects.requireNonNull(baseVfs, "baseVfs cannot be null");
        Objects.requireNonNull(branchKeys, "branchKeys cannot be null");
        Objects.requireNonNull(policy, "policy cannot be null");
        for (final String branchKey : branchKeys) {
            if (branchKey == null || !BRANCH_KEY_SHAPE.matcher(branchKey).matches()) {
                throw new IllegalArgumentException("branchKey must match [A-Za-z0-9_]+ (the framework-derived branch"
                        + " key shape), got: " + branchKey);
            }
        }

        // Pre-scan: canonical path -> branch keys that wrote it, in precedence order.
        final Map<String, List<String>> canonicalToBranches = new LinkedHashMap<>();
        for (final String branchKey : branchKeys) {
            final ScopedVirtualFileSystem branch = new ScopedVirtualFileSystem(baseVfs,
                    WorktreeToolEnvironmentFactory.WORKTREE_ROOT + branchKey);
            if (!branch.isDirectory(".")) {
                continue; // branch produced no files
            }
            for (final String canonical : branch.listRecursive(".")) {
                canonicalToBranches.computeIfAbsent(canonical, k -> new ArrayList<>()).add(branchKey);
            }
        }

        final List<String> conflicts = new ArrayList<>();
        for (final Map.Entry<String, List<String>> entry : canonicalToBranches.entrySet()) {
            if (entry.getValue().size() > 1) {
                conflicts.add(entry.getKey());
            }
        }
        if (policy == Policy.FAIL && !conflicts.isEmpty()) {
            return new MergeReport(List.of(), conflicts);
        }

        final List<String> promoted = new ArrayList<>();
        for (final Map.Entry<String, List<String>> entry : canonicalToBranches.entrySet()) {
            final String canonical = entry.getKey();
            final List<String> branches = entry.getValue();
            final String winner = policy == Policy.LAST_WINS ? branches.get(branches.size() - 1) : branches.get(0);
            final String source = WorktreeToolEnvironmentFactory.WORKTREE_ROOT + winner + "/" + canonical;
            try {
                // copy -> verify -> delete (idempotent, retry-safe; never a bare move).
                baseVfs.copy(source, canonical, true);
                if (baseVfs.exists(canonical)) {
                    baseVfs.delete(source);
                    promoted.add(canonical);
                }
            } catch (final RuntimeException e) {
                // Fail-fast abort, but make the partial progress observable before propagating (no rollback:
                // already-promoted files stay in place; per-file promotion is idempotent, so re-running is safe).
                log.warn(
                        "Worktree promotion aborted at '{}' (branch '{}'): {} file(s) already promoted, "
                                + "{} conflict(s); already-promoted files remain in place (no rollback): {}",
                        canonical, winner, promoted.size(), conflicts.size(), e.getMessage());
                throw new VirtualFileSystemException("Worktree promotion aborted at '" + canonical + "' (branch '"
                        + winner + "') after " + promoted.size() + " promoted file(s) and " + conflicts.size()
                        + " conflict(s); already-promoted files remain in place (no rollback)", e);
            }
        }
        return new MergeReport(promoted, conflicts);
    }
}
