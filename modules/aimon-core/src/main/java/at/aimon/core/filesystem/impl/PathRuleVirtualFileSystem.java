package at.aimon.core.filesystem.impl;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.filesystem.BackendStatus;
import at.aimon.core.filesystem.FileMetadata;
import at.aimon.core.filesystem.FileSystemUsage;
import at.aimon.core.filesystem.PathRule;
import at.aimon.core.filesystem.VfsPaths;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.filesystem.exception.FileAccessDeniedException;

/**
 * A {@link VirtualFileSystem} decorator that applies {@link PathRule}s by root-anchored prefix
 * (execution-environment design §9.2). The local execution environment wraps its workspace in one so the model's file
 * tools cannot see the control store ({@code .aimon/} → {@link PathRule.Access#DENY}) and cannot modify staged skill
 * copies ({@code .aimon-staged/} → {@link PathRule.Access#READ_ONLY}).
 *
 * <p>
 * Paths are resolved with {@link VfsPaths#resolveUnder} against the delegate's working directory before matching —
 * the way a base-anchored backend resolves them — so {@code ./.aimon/x}, {@code a/../.aimon/x},
 * {@code {base}/.aimon/x}, {@code ../<base-name>/.aimon/x} and {@code {base}/../<base-name>/.aimon/x} are all caught.
 * Prefixes match {@linkplain VfsPaths#isUnderIgnoreCase ignoring case}, so {@code .AIMON/x} is caught on a
 * case-insensitive store. The guard fails closed: a path that does not land under the working directory is treated
 * like a {@code DENY}ed one rather than passed through. A {@code DENY}ed entry does not exist as far as callers can
 * tell: {@code exists}/{@code isDirectory} are false and listings and searches omit it; any other access throws
 * {@link FileAccessDeniedException}. Deleting or moving a directory that contains a protected prefix is refused as
 * well.
 *
 * <p>
 * This is a guard against the model's file tools editing framework state by accident, not a security boundary: a
 * shell in the same environment still reaches the files (§2 non-goals). It <b>borrows</b> the delegate:
 * {@link #initialize()} and {@link #close()} do nothing.
 */
public final class PathRuleVirtualFileSystem implements VirtualFileSystem {

    private static final String HIDDEN = "hidden from this filesystem";
    private static final String OUTSIDE = "outside this filesystem";

    private final VirtualFileSystem delegate;
    private final List<PathRule> rules;
    private final String baseWorkingDir;

    /**
     * @param delegate
     *            the filesystem to guard (must not be null; borrowed)
     * @param rules
     *            the rules (must not be null; the first rule covering a path decides)
     */
    public PathRuleVirtualFileSystem(VirtualFileSystem delegate, List<PathRule> rules) {
        this.delegate = Objects.requireNonNull(delegate, "delegate cannot be null");
        this.rules = List.copyOf(Objects.requireNonNull(rules, "rules cannot be null"));
        this.baseWorkingDir = delegate.getWorkingDirectory();
    }

    /** @return the rules this decorator applies */
    public List<PathRule> getRules() {
        return rules;
    }

    /** Where a path lands relative to the base, or {@code null} when it does not land under it. */
    private String resolve(String path) {
        return VfsPaths.resolveUnder(baseWorkingDir, path);
    }

    private Optional<PathRule> ruleFor(String rel) {
        return rules.stream().filter(rule -> rule.covers(rel)).findFirst();
    }

    private boolean denied(String path) {
        final String rel = resolve(path);
        return rel == null || ruleFor(rel).map(rule -> rule.getAccess() == PathRule.Access.DENY).orElse(false);
    }

    private void checkRead(String path) {
        final String rel = resolve(path);
        if (rel == null) {
            throw new FileAccessDeniedException(path, OUTSIDE);
        }
        if (ruleFor(rel).filter(rule -> rule.getAccess() == PathRule.Access.DENY).isPresent()) {
            throw new FileAccessDeniedException(path, HIDDEN);
        }
    }

    private void checkWrite(String path) {
        final String rel = resolve(path);
        if (rel == null) {
            throw new FileAccessDeniedException(path, OUTSIDE);
        }
        final Optional<PathRule> rule = ruleFor(rel);
        if (rule.isPresent()) {
            throw new FileAccessDeniedException(path,
                    rule.get().getAccess() == PathRule.Access.DENY ? HIDDEN : "read-only");
        }
    }

    /** Refuses a recursive delete or move of a directory that contains a protected prefix. */
    private void checkSubtree(String path) {
        checkWrite(path);
        final String rel = resolve(path);
        for (PathRule rule : rules) {
            if (rel.isEmpty() || VfsPaths.isUnderIgnoreCase(rule.getPrefix(), rel)) {
                throw new FileAccessDeniedException(path, "contains the protected directory " + rule.getPrefix());
            }
        }
    }

    private List<String> visible(List<String> paths) {
        return paths.stream().filter(p -> !denied(p)).toList();
    }

    @Override
    public void write(String path, InputStream content, long contentLength) {
        checkWrite(path);
        delegate.write(path, content, contentLength);
    }

    @Override
    public InputStream read(String path) {
        checkRead(path);
        return delegate.read(path);
    }

    @Override
    public void delete(String path) {
        checkWrite(path);
        delegate.delete(path);
    }

    @Override
    public boolean exists(String path) {
        return !denied(path) && delegate.exists(path);
    }

    @Override
    public boolean isDirectory(String path) {
        return !denied(path) && delegate.isDirectory(path);
    }

    @Override
    public FileMetadata getMetadata(String path) {
        checkRead(path);
        return delegate.getMetadata(path);
    }

    @Override
    public List<String> list(String directory) {
        checkRead(directory);
        return visible(delegate.list(directory));
    }

    @Override
    public List<String> listRecursive(String directory) {
        checkRead(directory);
        return visible(delegate.listRecursive(directory));
    }

    @Override
    public void copy(String sourcePath, String destinationPath, boolean overwrite) {
        checkRead(sourcePath);
        checkWrite(destinationPath);
        delegate.copy(sourcePath, destinationPath, overwrite);
    }

    @Override
    public void move(String sourcePath, String destinationPath, boolean overwrite) {
        checkSubtree(sourcePath);
        checkWrite(destinationPath);
        delegate.move(sourcePath, destinationPath, overwrite);
    }

    @Override
    public OutputStream openOutputStream(String path) {
        checkWrite(path);
        return delegate.openOutputStream(path);
    }

    @Override
    public InputStream openInputStream(String path) {
        checkRead(path);
        return delegate.openInputStream(path);
    }

    @Override
    public void createDirectory(String path) {
        checkWrite(path);
        delegate.createDirectory(path);
    }

    @Override
    public void deleteRecursive(String path) {
        checkSubtree(path);
        delegate.deleteRecursive(path);
    }

    @Override
    public List<String> search(String directory, String pattern, int maxResults) {
        checkRead(directory);
        return visible(delegate.search(directory, pattern, maxResults));
    }

    @Override
    public FileSystemUsage getUsageSummary() {
        return delegate.getUsageSummary();
    }

    @Override
    public FileSystemUsage getUsageSummary(String path) {
        checkRead(path);
        return delegate.getUsageSummary(path);
    }

    @Override
    public String getWorkingDirectory() {
        return baseWorkingDir;
    }

    @Override
    public void initialize() {
        // No-op: the borrowed delegate is initialised by whoever created it.
    }

    @Override
    public BackendStatus getStatus() {
        return delegate.getStatus();
    }

    @Override
    public void close() {
        // No-op: the borrowed delegate is closed by whoever created it.
    }
}
