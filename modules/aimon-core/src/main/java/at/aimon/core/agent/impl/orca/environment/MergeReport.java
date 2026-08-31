package at.aimon.core.agent.impl.orca.environment;

import java.util.List;

/**
 * The outcome of a {@link WorktreeMerge#promote} call (design §6.3): which canonical paths were promoted from
 * a branch subtree to the canonical filesystem, and which canonical paths had a cross-branch collision. Immutable data,
 * not an exception — a recoverable merge conflict must not be a run-fatal {@code WorkflowException}.
 */
public final class MergeReport {

    private final List<String> promoted;
    private final List<String> conflicts;

    MergeReport(List<String> promoted, List<String> conflicts) {
        this.promoted = List.copyOf(promoted);
        this.conflicts = List.copyOf(conflicts);
    }

    /** @return the canonical paths that were promoted (copied to canonical and their branch source deleted) */
    public List<String> promoted() {
        return promoted;
    }

    /** @return the canonical paths written by more than one branch (collision); non-empty only under a conflict */
    public List<String> conflicts() {
        return conflicts;
    }

    /** @return {@code true} if any canonical path was written by more than one branch */
    public boolean hasConflicts() {
        return !conflicts.isEmpty();
    }

    @Override
    public String toString() {
        return "MergeReport{promoted=" + promoted.size() + ", conflicts=" + conflicts.size() + '}';
    }
}
