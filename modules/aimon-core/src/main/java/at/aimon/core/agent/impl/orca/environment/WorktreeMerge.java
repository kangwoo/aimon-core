package at.aimon.core.agent.impl.orca.environment;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.filesystem.FileMetadata;
import at.aimon.core.filesystem.PathRule;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.filesystem.VirtualFileSystems;
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
 * <b>Where branches come from.</b> Each branch is an {@link ExecutionEnvironment#isolate(String) isolate(key)} of the
 * parent, under a key the caller derives deterministically; the local environment accepts keys of the shape
 * {@code [A-Za-z0-9_]+} ({@code LocalExecutionEnvironment.isolate}). An assembler that knows only the keys — for
 * example from listing {@code .worktrees/} — rebuilds each branch with
 * {@code parent.isolate(key).orElseThrow()}: isolation is deterministic on the key, so this is the same view the run
 * used. Staged skill copies are shared with the parent and never appear in a branch listing, so they are never
 * promoted — nor is a branch-local staging directory a shell made, which the branch listing leaves out too; a branch
 * cannot write under its own {@code .aimon/} (the parent's path rules apply at the branch root), so a merge never
 * meets one.
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
     * simply be re-run after the underlying failure is resolved. Three failures are caught before anything is written:
     * a branch that does not belong to {@code parent} (below); a destination the parent's path rules refuse — for
     * example a file a shell wrote under a {@code READ_ONLY} directory of the branch — when the parent's filesystem is
     * a {@link VirtualFileSystems#withPathRules path-rule filesystem} (the local provider's is; for any other, such a
     * destination still fails when it is written); and a branch file whose metadata cannot be read — for example a
     * symbolic link a shell made in the branch, which the local filesystem refuses.
     *
     * <p>
     * <b>Ownership.</b> Each branch must be a distinct environment derived from {@code parent}: not {@code parent}
     * itself, not sharing its filesystem (promoting it would copy each file onto itself and then delete it), not listed
     * twice, and — when it declares its {@link ExecutionEnvironment#isolatedFrom() lineage} — isolated from this very
     * instance. Pass the same environment instance the branches were isolated from, and each branch once: two objects
     * for one key are not detected and would report every file of that key as a conflict. A branch that declares no
     * lineage is accepted on the other checks alone.
     *
     * @param parent
     *            the environment the branches were isolated from — the same instance (must not be null)
     * @param branches
     *            the branch environments to promote, in precedence order, each once (must not be null)
     * @param policy
     *            how to resolve a canonical path written by more than one branch (must not be null)
     * @return the merge report (promoted paths + collisions); a conflict under {@link Policy#FAIL} promotes nothing
     * @throws IllegalArgumentException
     *             if a branch is {@code parent} itself, shares its filesystem, is listed twice, or was isolated from
     *             another environment; no file has been read or written
     * @throws VirtualFileSystemException
     *             if a destination is refused by the parent's path rules or a branch file's metadata cannot be read
     *             (nothing has been promoted), or a promotion step fails mid-merge; the message carries the partial
     *             progress (no rollback)
     */
    public static MergeReport promote(ExecutionEnvironment parent, List<ExecutionEnvironment> branches, Policy policy) {
        Objects.requireNonNull(parent, "parent cannot be null");
        Objects.requireNonNull(branches, "branches cannot be null");
        Objects.requireNonNull(policy, "policy cannot be null");
        for (final ExecutionEnvironment branch : branches) {
            Objects.requireNonNull(branch, "branches must not contain null");
        }
        validateBranches(parent, branches);

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

        // Pre-flight 1: no destination may be refused by the parent's path rules, or nothing is promoted. Only a
        // path-rule filesystem answers ahead of time; any other refuses when it is written (mid-merge, no rollback).
        final List<PathRule> parentRules = VirtualFileSystems.pathRules(parent.fileSystem());
        for (final Map.Entry<String, List<Integer>> entry : canonicalToBranches.entrySet()) {
            final String canonical = entry.getKey();
            final Optional<PathRule> refusing = parentRules.stream().filter(rule -> rule.covers(canonical)).findFirst();
            if (refusing.isPresent()) {
                throw new VirtualFileSystemException(
                        "Worktree promotion aborted before promoting anything: '" + canonical + "' (branch #"
                                + winner(entry.getValue(), policy) + ") would land under '" + refusing.get().getPrefix()
                                + "', which the parent's path rules make " + refusing.get().getAccess());
            }
        }

        // Pre-flight 2: every file about to be promoted must be readable as a file, or nothing is promoted. Metadata
        // is read again right before each copy, so a file changed in between is copied with its current length.
        for (final Map.Entry<String, List<Integer>> entry : canonicalToBranches.entrySet()) {
            final int winner = winner(entry.getValue(), policy);
            try {
                branches.get(winner).fileSystem().getMetadata(entry.getKey());
            } catch (final RuntimeException e) {
                throw new VirtualFileSystemException("Worktree promotion aborted before promoting anything: '"
                        + entry.getKey() + "' (branch #" + winner + ") cannot be read: " + e.getMessage(), e);
            }
        }

        final VirtualFileSystem canonicalFs = parent.fileSystem();
        final List<String> promoted = new ArrayList<>();
        for (final Map.Entry<String, List<Integer>> entry : canonicalToBranches.entrySet()) {
            final String canonical = entry.getKey();
            final List<Integer> writers = entry.getValue();
            final int winner = winner(writers, policy);
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

    /** The index of the branch whose copy of a path is promoted, among the branches that wrote it. */
    private static int winner(List<Integer> writers, Policy policy) {
        return policy == Policy.LAST_WINS ? writers.get(writers.size() - 1) : writers.get(0);
    }

    /**
     * Refuses branches that do not belong to {@code parent}, before any file is read or written. Identity, not
     * equality: the local provider hands out one environment instance, and its branches hold that instance. The check
     * does call each environment's {@code fileSystem()}: the local provider's answers without I/O, while another
     * provider's may provision on first use (execution-environment design §13).
     */
    private static void validateBranches(ExecutionEnvironment parent, List<ExecutionEnvironment> branches) {
        for (int i = 0; i < branches.size(); i++) {
            final ExecutionEnvironment branch = branches.get(i);
            if (branch == parent) {
                throw new IllegalArgumentException("branches[" + i + "] is the parent environment itself");
            }
            if (branch.fileSystem() == parent.fileSystem()) {
                throw new IllegalArgumentException("branches[" + i + "] shares the parent's filesystem; promoting it"
                        + " would copy each file onto itself and then delete it");
            }
            final ExecutionEnvironment origin = branch.isolatedFrom().orElse(null);
            if (origin != null && origin != parent) {
                throw new IllegalArgumentException(
                        "branches[" + i + "] was isolated from " + origin + ", not from " + parent);
            }
            for (int j = 0; j < i; j++) {
                if (branches.get(j) == branch) {
                    throw new IllegalArgumentException(
                            "branches[" + j + "] and branches[" + i + "] are the same environment");
                }
            }
        }
    }
}
