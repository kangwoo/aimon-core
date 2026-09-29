package at.aimon.core.environment.impl;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import at.aimon.core.environment.ContentSearch;
import at.aimon.core.environment.EnvironmentDescriptor;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.environment.StagedResource;
import at.aimon.core.filesystem.PathRule;
import at.aimon.core.filesystem.VfsPaths;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.filesystem.VirtualFileSystems;
import at.aimon.core.filesystem.impl.ScopedVirtualFileSystem;
import at.aimon.core.shell.VirtualShell;

/**
 * A workflow branch of a {@link LocalExecutionEnvironment}: its writes land under {@code .worktrees/{branchKey}/}
 * (execution-environment design §4.2, §5.2).
 *
 * <p>
 * <b>What is isolated — file tools and the default cwd, nothing more.</b> The filesystem is a
 * {@link ScopedVirtualFileSystem} over the parent's path-rule-guarded filesystem, and the shell is a view whose
 * commands default to the branch root. A command with an absolute path still reaches the whole disk, and so does a
 * <em>canonical</em> absolute path in a shell command ({@code cat /proj/a} reads the canonical file while the file
 * tools map {@code /proj/a} into the branch). This is the local limit; a sandbox provider isolates with git worktrees.
 *
 * <p>
 * The descriptor's working directory is the branch root's host path — the shell's default cwd — and the file tools
 * accept absolute paths under it. The staging area is shared with the parent: {@link #stage} returns the parent's
 * copy, the branch filesystem routes {@code .aimon-staged/} to the parent's (read-only) directory, and staged files
 * never appear in a branch listing, so a merge never promotes them — nor does a staging directory a shell made inside
 * the branch root, which the listing leaves out as unreachable. {@link #durable()} is {@code false}: the branch
 * directory disappears after a merge or a discard, so artifacts written here are archived into the control store.
 * Sharing the staging prefix assumes the parent guards it: with the default rules a branch write there is refused
 * (read-only, in any letter case); an assembly whose rules leave it unguarded lets a branch write it at the root.
 *
 * <p>
 * <b>The parent's path rules, anchored at the branch root.</b> Between the scope and the parent's filesystem sits a
 * second {@link VirtualFileSystems#withPathRules path-rule} layer holding the parent's own rules re-anchored under
 * {@code .worktrees/{branchKey}/}, so the branch's {@code .aimon/} is as hidden as the root's and a merge never meets
 * one. It sits <em>below</em> the scope on purpose: there every spelling of a branch path — relative to the branch,
 * absolute under the workspace or under the branch root (with {@code ./}, {@code //} or the branch key in another
 * letter case) — has already been reduced to one delegate path under {@code .worktrees/{branchKey}/}, up to the letter
 * case of its own segments, which the rules fold. A layer above would see the branch's {@code "."} working directory
 * and miss absolute paths. What the scope does not reduce is a path that names {@code .worktrees/} from inside the
 * branch — {@code .worktrees/other/x}, or {@code .worktrees/k/x} written relative: it lands nested in the branch,
 * outside its rules, and a merge promotes it into that directory (backlog EE-46). The rules follow the parent: an
 * assembly that dropped them leaves its branches unguarded too.
 *
 * <p>
 * <b>No nesting.</b> {@link #isolate} throws: a branch cannot be isolated again (a scope over a scope loses absolute
 * paths, and merging an outer branch would carry an inner one's directory into another branch's).
 */
final class LocalIsolatedEnvironment implements ExecutionEnvironment {

    private final LocalExecutionEnvironment parent;
    private final String branchKey;
    private final VirtualFileSystem fileSystem;
    private final VirtualShell shell;
    private final EnvironmentDescriptor descriptor;
    private final RipgrepContentSearch contentSearch;

    LocalIsolatedEnvironment(LocalExecutionEnvironment parent, String branchKey) {
        this.parent = Objects.requireNonNull(parent, "parent must not be null");
        this.branchKey = branchKey;
        final String branchPrefix = LocalExecutionEnvironment.WORKTREE_ROOT + "/" + branchKey;
        final List<PathRule> branchRules = parent.pathRules().stream().map(rule -> PathRule.builder()
                .prefix(branchPrefix + "/" + rule.getPrefix()).access(rule.getAccess()).build()).toList();
        final VirtualFileSystem guarded = branchRules.isEmpty()
                ? parent.fileSystem()
                : VirtualFileSystems.withPathRules(parent.fileSystem(), branchRules);
        this.fileSystem = new ScopedVirtualFileSystem(guarded, branchPrefix, Set.of(parent.staging().stagingRoot()));
        final String parentDirectory = parent.descriptor().workingDirectory();
        final String branchDirectory = VfsPaths.join(parentDirectory.isEmpty() ? "." : parentDirectory, branchPrefix);
        final Path branchHostPath = LocalExecutionEnvironment.localPathOf(branchDirectory);
        this.shell = branchHostPath != null
                ? new WorkingDirectoryShell(parent.rawShell(), branchHostPath)
                : parent.rawShell();
        this.descriptor = EnvironmentDescriptor.builder().workingDirectory(branchDirectory)
                .platform(parent.descriptor().platform().orElse(null))
                .osVersion(parent.descriptor().osVersion().orElse(null))
                .shellName(parent.descriptor().shellName().orElse(null))
                .notes("isolated workflow branch '" + branchKey + "'; file tools and the default working directory are"
                        + " scoped to it, absolute paths in shell commands are not")
                .build();
        this.contentSearch = parent.ripgrep() != null && branchHostPath != null
                ? parent.ripgrep().rootedAt(branchHostPath)
                : null;
    }

    @Override
    public VirtualFileSystem fileSystem() {
        return fileSystem;
    }

    @Override
    public VirtualShell shell() {
        return shell;
    }

    @Override
    public EnvironmentDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public boolean durable() {
        return false;
    }

    @Override
    public String stage(StagedResource resource) {
        return parent.stage(resource);
    }

    @Override
    public Optional<ContentSearch> contentSearch() {
        return Optional.ofNullable(contentSearch);
    }

    /**
     * Refuses nested isolation, with the reason: an empty answer would read as "this environment has no isolation"
     * and hide that the caller is already inside a branch.
     *
     * @throws UnsupportedOperationException
     *             always
     */
    @Override
    public Optional<ExecutionEnvironment> isolate(String nestedKey) {
        throw new UnsupportedOperationException("nested isolation is not supported: this environment is already the"
                + " isolated workflow branch '" + branchKey + "' (" + LocalExecutionEnvironment.WORKTREE_ROOT + "/"
                + branchKey + "); run the nested workflow's isolated steps from the parent environment, or drop"
                + " isolate from them");
    }

    @Override
    public Optional<ExecutionEnvironment> isolatedFrom() {
        return Optional.of(parent);
    }

    @Override
    public String toString() {
        return "LocalIsolatedEnvironment{" + descriptor.workingDirectory() + '}';
    }
}
