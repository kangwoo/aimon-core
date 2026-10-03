package at.aimon.core.environment.impl;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

import at.aimon.core.environment.ContentSearch;
import at.aimon.core.environment.EnvironmentDescriptor;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.environment.StagedResource;
import at.aimon.core.filesystem.PathRule;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.shell.VirtualShell;

/**
 * The environment {@link LocalExecutionEnvironmentProvider} returns for every request: the provider's workspace
 * filesystem (behind its path rules) and shell on the host, described by the host's platform and OS.
 */
final class LocalExecutionEnvironment implements ExecutionEnvironment {

    /** Where isolated branches live, relative to the workspace root. */
    static final String WORKTREE_ROOT = ".worktrees";

    /** The shape every branch key must have — exactly what the workflow runner's branch-key derivation produces. */
    private static final Pattern BRANCH_KEY_SHAPE = Pattern.compile("[A-Za-z0-9_]+");

    private final VirtualFileSystem toolFileSystem;
    private final List<PathRule> pathRules;
    private final VirtualShell shell;
    private final EnvironmentDescriptor descriptor;
    private final LocalStaging staging;
    private final RipgrepContentSearch contentSearch;
    private final Duration backgroundCommandTimeout;

    LocalExecutionEnvironment(VirtualFileSystem toolFileSystem, List<PathRule> pathRules, VirtualShell shell,
            LocalStaging staging, RipgrepContentSearch contentSearch, String workingDirectory,
            Duration backgroundCommandTimeout) {
        this.toolFileSystem = Objects.requireNonNull(toolFileSystem, "toolFileSystem must not be null");
        this.pathRules = List.copyOf(Objects.requireNonNull(pathRules, "pathRules must not be null"));
        this.shell = Objects.requireNonNull(shell, "shell must not be null");
        this.staging = Objects.requireNonNull(staging, "staging must not be null");
        this.contentSearch = contentSearch;
        this.backgroundCommandTimeout = backgroundCommandTimeout;
        this.descriptor = hostDescriptor(workingDirectory);
    }

    @Override
    public VirtualFileSystem fileSystem() {
        return toolFileSystem;
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
    public String stage(StagedResource resource) {
        return staging.stage(resource);
    }

    @Override
    public Optional<ContentSearch> contentSearch() {
        return Optional.ofNullable(contentSearch);
    }

    @Override
    public Optional<Duration> backgroundCommandTimeout() {
        return Optional.ofNullable(backgroundCommandTimeout);
    }

    /**
     * Derives a branch environment whose file tools and default shell cwd are scoped to
     * {@code .worktrees/{branchKey}/}. Deterministic on the key: isolating the same key again yields the same view,
     * which is how a merge rebuilds the branches of a finished run.
     *
     * <p>
     * Two keys that differ only in case name one directory on a case-insensitive store ({@code a} and {@code A} share
     * {@code .worktrees/a}). The workflow runner never derives such a pair — its keys come from lowercase step-path
     * segments and indexes — so only a caller that picks its own keys has to keep them case-unique.
     *
     * @throws IllegalArgumentException
     *             if the key does not match {@code [A-Za-z0-9_]+}
     */
    @Override
    public Optional<ExecutionEnvironment> isolate(String branchKey) {
        if (branchKey == null || !BRANCH_KEY_SHAPE.matcher(branchKey).matches()) {
            throw new IllegalArgumentException(
                    "branchKey must match [A-Za-z0-9_]+ (the framework-derived branch key shape), got: " + branchKey);
        }
        return Optional.of(new LocalIsolatedEnvironment(this, branchKey));
    }

    /** The path rules the tool filesystem applies at the workspace root; a branch re-anchors them at its own root. */
    List<PathRule> pathRules() {
        return pathRules;
    }

    VirtualShell rawShell() {
        return shell;
    }

    LocalStaging staging() {
        return staging;
    }

    RipgrepContentSearch ripgrep() {
        return contentSearch;
    }

    /**
     * Describes the host. The platform mapping matches the one the prompt has always used ({@code darwin},
     * {@code windows}, {@code linux}, else the raw {@code os.name}), so the rendered text is unchanged on local.
     */
    static EnvironmentDescriptor hostDescriptor(String workingDirectory) {
        final boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        return EnvironmentDescriptor.builder().workingDirectory(workingDirectory == null ? "" : workingDirectory)
                .platform(detectPlatform()).osVersion(System.getProperty("os.version"))
                .shellName(windows ? "cmd" : "bash").build();
    }

    private static String detectPlatform() {
        final String osName = System.getProperty("os.name", "").toLowerCase(Locale.ENGLISH);
        if (osName.contains("mac") || osName.contains("darwin")) {
            return "darwin";
        } else if (osName.contains("win")) {
            return "windows";
        } else if (osName.contains("nux") || osName.contains("nix") || osName.contains("aix")) {
            return "linux";
        }
        return osName;
    }

    /** A host directory for a working directory that is a local absolute path; null for a URI-shaped one. */
    static Path localPathOf(String workingDirectory) {
        if (workingDirectory == null) {
            return null;
        }
        final boolean windowsAbsolute = workingDirectory.length() > 2 && Character.isLetter(workingDirectory.charAt(0))
                && workingDirectory.charAt(1) == ':';
        if (!workingDirectory.startsWith("/") && !windowsAbsolute) {
            return null;
        }
        return Path.of(workingDirectory);
    }

    @Override
    public String toString() {
        return "LocalExecutionEnvironment{" + descriptor.workingDirectory() + '}';
    }
}
