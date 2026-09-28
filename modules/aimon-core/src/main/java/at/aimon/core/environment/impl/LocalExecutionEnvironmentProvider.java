package at.aimon.core.environment.impl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.environment.EnvironmentRequest;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.environment.ExecutionEnvironmentProvider;
import at.aimon.core.filesystem.PathRule;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.filesystem.impl.PathRuleVirtualFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystemConfig;
import at.aimon.core.shell.VirtualShell;
import at.aimon.core.shell.impl.local.LocalShell;

/**
 * The default {@link ExecutionEnvironmentProvider}: one local workspace filesystem and one local shell, the same
 * {@link ExecutionEnvironment} for every request (execution-environment design §4.2). A fork's request carries its
 * parent environment, which is returned as-is (§5.2) — a fork of an isolated branch stays in that branch.
 *
 * <p>
 * Two ways to build it:
 * <ul>
 * <li>{@link Builder#workspaceRoot(Path)} — the provider <b>owns</b> a {@link LocalFileSystem} and a {@link LocalShell}
 * rooted at that directory, and {@link #close()} closes them;</li>
 * <li>{@link Builder#fileSystem(VirtualFileSystem)} — a <b>borrowed</b> filesystem (possibly remote, e.g. GridFS/S3)
 * with an owned {@code LocalShell} rooted at the filesystem's working directory when that is a local path, and at no
 * particular directory otherwise. There the host shell cannot see remote files — the long-standing limit of that
 * deployment.</li>
 * </ul>
 *
 * <p>
 * <b>Path rules</b> (§9.2). The file tools see the workspace through a {@link PathRuleVirtualFileSystem}; by default
 * the control store {@code .aimon/} is hidden and the staging area {@code .aimon-staged/} is read-only. An assembly
 * that wants the model to edit its own skills passes other rules explicitly. The shell is not restricted (§2).
 *
 * <p>
 * <b>Staging</b> (§4.4) copies into {@code {workspace}/.aimon-staged/{name}/{contentKey}/}. In owned
 * {@code workspaceRoot} mode — a directory private to this provider — building the provider sweeps copies that are
 * neither the newest for their name nor younger than {@link Builder#stagingSweepGrace(Duration)} (24 hours by
 * default, the longest a background command can outlive the execution that started it), and marker-less directories
 * older than that (interrupted copies). A borrowed, possibly shared filesystem is never swept: another runtime may be
 * using any copy on it. Copies there accumulate, bounded by the number of distinct skill versions.
 *
 * <p>
 * <b>Content search</b>: when {@code rg} is on the {@code PATH} (checked once at build time without starting a
 * process) and the workspace is a local directory, {@code Grep} is answered by {@code rg}; otherwise {@code Grep}
 * walks the filesystem.
 *
 * <p>
 * This is the only in-core assembly point that constructs {@code LocalFileSystem}, {@code LocalShell} and the
 * path-rule filesystem for tools, which is why ArchUnit lets {@code at.aimon.core.environment.impl} reach
 * {@code filesystem.impl} and {@code shell.impl}.
 */
public final class LocalExecutionEnvironmentProvider implements ExecutionEnvironmentProvider, AutoCloseable {

    /** The control store's directory under the workspace, hidden from the file tools by default. */
    public static final String CONTROL_DIRECTORY = ".aimon";

    /** The default staging area under the workspace, read-only to the file tools. */
    public static final String DEFAULT_STAGING_ROOT = ".aimon-staged";

    /** Default limit on one resource's staged size: 50 MB. */
    public static final long DEFAULT_MAX_STAGED_BYTES = 50L * 1024 * 1024;

    /** Default age before a superseded staged copy may be swept: 24 hours. */
    public static final Duration DEFAULT_STAGING_SWEEP_GRACE = Duration.ofHours(24);

    private static final Logger log = LoggerFactory.getLogger(LocalExecutionEnvironmentProvider.class);

    private final VirtualFileSystem rawFileSystem;
    private final List<AutoCloseable> owned;
    private final LocalExecutionEnvironment environment;

    private LocalExecutionEnvironmentProvider(Builder builder) {
        final List<AutoCloseable> ownedResources = new ArrayList<>();
        final Path ownedRoot;
        if (builder.workspaceRoot != null) {
            if (builder.fileSystem != null) {
                throw new IllegalStateException("Set either workspaceRoot or fileSystem, not both");
            }
            ownedRoot = builder.workspaceRoot.toAbsolutePath().normalize();
            final LocalFileSystem localFileSystem = new LocalFileSystem(
                    new LocalFileSystemConfig(ownedRoot.toString()));
            localFileSystem.initialize();
            ownedResources.add(localFileSystem::close);
            this.rawFileSystem = localFileSystem;
        } else {
            ownedRoot = null;
            this.rawFileSystem = Objects.requireNonNull(builder.fileSystem, "workspaceRoot or fileSystem is required");
        }
        final String workingDirectory = rawFileSystem.getWorkingDirectory();
        final Path hostRoot = LocalExecutionEnvironment.localPathOf(workingDirectory);

        final VirtualShell shell;
        if (builder.shell != null) {
            shell = builder.shell;
        } else {
            final LocalShell localShell = new LocalShell(hostRoot);
            ownedResources.add(localShell::close);
            shell = localShell;
        }
        this.owned = List.copyOf(ownedResources);

        final List<PathRule> rules = builder.pathRules != null
                ? builder.pathRules
                : defaultPathRules(builder.stagingRoot);
        final VirtualFileSystem toolFileSystem = rules.isEmpty()
                ? rawFileSystem
                : new PathRuleVirtualFileSystem(rawFileSystem, rules);

        if (ownedRoot != null) {
            sweepStaging(ownedRoot, ownedRoot.resolve(builder.stagingRoot), builder.stagingSweepGrace, builder.clock);
        }

        final LocalStaging staging = new LocalStaging(rawFileSystem, toolFileSystem, builder.stagingRoot,
                builder.maxStagedBytes);
        final RipgrepContentSearch contentSearch = builder.contentSearch && hostRoot != null
                ? Optional.ofNullable(builder.ripgrepExecutable).or(RipgrepContentSearch::probe)
                        .map(rg -> new RipgrepContentSearch(rg, hostRoot, hiddenPrefixes(rules))).orElse(null)
                : null;
        this.environment = new LocalExecutionEnvironment(toolFileSystem, shell, staging, contentSearch,
                workingDirectory);
    }

    /**
     * The rules the local provider applies unless told otherwise: the control store hidden, the staging area
     * read-only.
     *
     * @param stagingRoot
     *            the staging area's directory
     * @return the rules
     */
    public static List<PathRule> defaultPathRules(String stagingRoot) {
        return List.of(PathRule.deny(CONTROL_DIRECTORY), PathRule.readOnly(stagingRoot));
    }

    private static List<String> hiddenPrefixes(List<PathRule> rules) {
        return rules.stream().filter(rule -> rule.getAccess() == PathRule.Access.DENY).map(PathRule::getPrefix)
                .toList();
    }

    @Override
    public ExecutionEnvironment resolve(EnvironmentRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        return request.parent().orElse(environment);
    }

    /**
     * Returns the workspace filesystem as the model's file tools see it (behind the path rules).
     *
     * @return the filesystem
     */
    public VirtualFileSystem fileSystem() {
        return environment.fileSystem();
    }

    /**
     * Returns the workspace directory the environment describes.
     *
     * @return the working directory
     */
    public String workingDirectory() {
        return environment.descriptor().workingDirectory();
    }

    /**
     * Closes the filesystem and shell this provider owns. A borrowed filesystem or shell is left open.
     */
    @Override
    public void close() {
        for (AutoCloseable resource : owned) {
            try {
                resource.close();
            } catch (Exception e) {
                log.warn("Failed to close execution environment resource: {}", e.getMessage(), e);
            }
        }
    }

    /**
     * Deletes superseded staged copies that nothing can still reference: for each resource name, every
     * {@code {contentKey}} directory except the newest whose marker is older than the grace period, and marker-less
     * (interrupted) directories older than the grace period.
     *
     * <p>
     * The staging area is a directory inside the workspace, which anyone who can write the workspace (a cloned
     * repository, the model through the shell) can replace with a symbolic link. The sweep therefore never follows
     * one: it does nothing when the staging directory is a link or resolves outside the workspace, and it only
     * descends into, and deletes, name and copy directories that are real directories rather than links to them.
     */
    static void sweepStaging(Path ownedRoot, Path stagingDir, Duration grace, Clock clock) {
        if (!Files.isDirectory(stagingDir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            if (!stagingDir.toRealPath().startsWith(ownedRoot.toRealPath())) {
                log.warn("Staging sweep skipped: {} resolves outside the workspace {}", stagingDir, ownedRoot);
                return;
            }
        } catch (IOException e) {
            log.warn("Staging sweep of {} skipped: {}", stagingDir, e.getMessage());
            return;
        }
        final Instant cutoff = clock.instant().minus(grace);
        try (Stream<Path> names = Files.list(stagingDir)) {
            for (Path nameDir : names.filter(LocalExecutionEnvironmentProvider::isRealDirectory).toList()) {
                sweepName(nameDir, cutoff);
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Staging sweep of {} failed: {}", stagingDir, e.getMessage());
        }
    }

    private static boolean isRealDirectory(Path path) {
        return Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
    }

    private static void sweepName(Path nameDir, Instant cutoff) throws IOException {
        final List<Path> copies;
        try (Stream<Path> keys = Files.list(nameDir)) {
            copies = keys.filter(LocalExecutionEnvironmentProvider::isRealDirectory).toList();
        }
        final Optional<Path> newest = copies.stream().filter(k -> Files.exists(k.resolve(LocalStaging.MARKER)))
                .max(Comparator.comparing(k -> modified(k.resolve(LocalStaging.MARKER))));
        for (Path copy : copies) {
            if (newest.isPresent() && newest.get().equals(copy)) {
                continue;
            }
            final Path marker = copy.resolve(LocalStaging.MARKER);
            final Instant age = Files.exists(marker) ? modified(marker) : modified(copy);
            if (age.isBefore(cutoff)) {
                deleteTree(copy);
                log.debug("Swept superseded staged copy {}", copy);
            }
        }
    }

    private static Instant modified(Path path) {
        try {
            return Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toInstant();
        } catch (IOException e) {
            return Instant.MAX;
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        // Files.walk does not follow links: a link inside the copy is deleted, not what it points at.
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    /** @return a new builder */
    public static Builder builder() {
        return new Builder();
    }

    /** Builder for {@link LocalExecutionEnvironmentProvider}. */
    public static final class Builder {
        private Path workspaceRoot;
        private VirtualFileSystem fileSystem;
        private VirtualShell shell;
        private List<PathRule> pathRules;
        private String stagingRoot = DEFAULT_STAGING_ROOT;
        private long maxStagedBytes = DEFAULT_MAX_STAGED_BYTES;
        private Duration stagingSweepGrace = DEFAULT_STAGING_SWEEP_GRACE;
        private Clock clock = Clock.systemUTC();
        private boolean contentSearch = true;
        private Path ripgrepExecutable;

        private Builder() {
        }

        /**
         * Roots an owned local filesystem and shell at a directory.
         *
         * @param workspaceRoot
         *            the workspace directory
         * @return this builder
         */
        public Builder workspaceRoot(Path workspaceRoot) {
            this.workspaceRoot = workspaceRoot;
            return this;
        }

        /**
         * Uses a borrowed filesystem as the workspace. The provider does not close it and never sweeps its staging
         * area.
         *
         * @param fileSystem
         *            the workspace filesystem
         * @return this builder
         */
        public Builder fileSystem(VirtualFileSystem fileSystem) {
            this.fileSystem = fileSystem;
            return this;
        }

        /**
         * Uses a borrowed shell instead of an owned local one. The provider does not close it.
         *
         * @param shell
         *            the shell
         * @return this builder
         */
        public Builder shell(VirtualShell shell) {
            this.shell = shell;
            return this;
        }

        /**
         * Replaces the default path rules ({@code .aimon/} hidden, the staging area read-only). An empty list exposes
         * the whole workspace to the file tools, the control store included.
         *
         * @param pathRules
         *            the rules
         * @return this builder
         */
        public Builder pathRules(List<PathRule> pathRules) {
            this.pathRules = List.copyOf(Objects.requireNonNull(pathRules, "pathRules must not be null"));
            return this;
        }

        /**
         * @param stagingRoot
         *            the staging area's directory under the workspace (default {@value #DEFAULT_STAGING_ROOT})
         * @return this builder
         */
        public Builder stagingRoot(String stagingRoot) {
            this.stagingRoot = Objects.requireNonNull(stagingRoot, "stagingRoot must not be null");
            return this;
        }

        /**
         * @param maxStagedBytes
         *            the limit on one resource's staged size (default 50 MB)
         * @return this builder
         */
        public Builder maxStagedBytes(long maxStagedBytes) {
            this.maxStagedBytes = maxStagedBytes;
            return this;
        }

        /**
         * @param stagingSweepGrace
         *            how old a superseded staged copy must be before the startup sweep deletes it (default 24h)
         * @return this builder
         */
        public Builder stagingSweepGrace(Duration stagingSweepGrace) {
            this.stagingSweepGrace = Objects.requireNonNull(stagingSweepGrace, "stagingSweepGrace must not be null");
            return this;
        }

        /**
         * @param clock
         *            the clock the sweep measures age with
         * @return this builder
         */
        public Builder clock(Clock clock) {
            this.clock = Objects.requireNonNull(clock, "clock must not be null");
            return this;
        }

        /**
         * @param contentSearch
         *            whether to answer {@code Grep} with {@code rg} when it is on the {@code PATH} (default true)
         * @return this builder
         */
        public Builder contentSearch(boolean contentSearch) {
            this.contentSearch = contentSearch;
            return this;
        }

        /**
         * Uses this {@code rg} instead of probing the {@code PATH}. Package-private: it exists so tests can pin the
         * program the provider runs.
         *
         * @param executable
         *            the {@code rg} executable
         * @return this builder
         */
        Builder ripgrepExecutable(Path executable) {
            this.ripgrepExecutable = Objects.requireNonNull(executable, "executable must not be null");
            return this;
        }

        /** @return the provider */
        public LocalExecutionEnvironmentProvider build() {
            return new LocalExecutionEnvironmentProvider(this);
        }
    }
}
