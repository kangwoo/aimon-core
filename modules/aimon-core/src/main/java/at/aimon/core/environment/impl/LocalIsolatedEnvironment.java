package at.aimon.core.environment.impl;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import at.aimon.core.environment.ContentSearch;
import at.aimon.core.environment.EnvironmentDescriptor;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.environment.StagedResource;
import at.aimon.core.filesystem.VfsPaths;
import at.aimon.core.filesystem.VirtualFileSystem;
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
 * never appear in a branch listing, so a merge never promotes them. {@link #durable()} is {@code false}: the branch
 * directory disappears after a merge or a discard, so artifacts written here are archived into the control store.
 */
final class LocalIsolatedEnvironment implements ExecutionEnvironment {

    private final LocalExecutionEnvironment parent;
    private final VirtualFileSystem fileSystem;
    private final VirtualShell shell;
    private final EnvironmentDescriptor descriptor;
    private final RipgrepContentSearch contentSearch;

    LocalIsolatedEnvironment(LocalExecutionEnvironment parent, String branchKey) {
        this.parent = Objects.requireNonNull(parent, "parent must not be null");
        final String branchPrefix = LocalExecutionEnvironment.WORKTREE_ROOT + "/" + branchKey;
        this.fileSystem = new ScopedVirtualFileSystem(parent.fileSystem(), branchPrefix,
                Set.of(parent.staging().stagingRoot()));
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

    @Override
    public String toString() {
        return "LocalIsolatedEnvironment{" + descriptor.workingDirectory() + '}';
    }
}
