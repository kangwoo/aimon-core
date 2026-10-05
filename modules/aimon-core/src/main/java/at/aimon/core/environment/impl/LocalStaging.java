package at.aimon.core.environment.impl;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.environment.StagedResource;
import at.aimon.core.environment.exception.StagingException;
import at.aimon.core.filesystem.VfsPaths;
import at.aimon.core.filesystem.VirtualFileSystem;

/**
 * Content-addressed staging into a local environment's workspace (execution-environment design §4.4).
 *
 * <p>
 * A resource is copied to {@code {stagingRoot}/{name}/{contentKey}/} on the <b>raw</b> workspace filesystem (the
 * model's file tools see the staging area read-only through the path rules). The copy is skipped only when the
 * target's {@value #MARKER} marker exists — the target is asked every time, never an in-memory record — and the copy
 * on disk is the one its path names: the first time this instance meets a target it did not copy itself, it checks
 * that the marker holds the content key, that the files are exactly the resource's, and that they hash to the key, and
 * copies again otherwise (EE-37). That check is remembered per target, so it costs one read of the copy per process;
 * a shell can still change a copy afterwards (design §2 non-goals). The first copy also writes
 * {@code {stagingRoot}/}{@value #GITIGNORE} containing {@code *} unless one exists (EE-4). The copy takes exactly
 * {@link StagedResource#getFiles()}, hashes what it copies with the same algorithm as
 * {@link StagedResource#scan}, and writes the marker last; a size over the limit, an unreadable recorded file or bytes
 * that no longer hash to the content key fail with {@link StagingException}, the partial target removed and no marker
 * written, so an incomplete or mislabelled copy is never served and the next call retries.
 */
final class LocalStaging {

    /** Written last in a staged directory; its presence is what "already staged" means. */
    static final String MARKER = ".staged";

    /** Written once at the top of the staging area so version control leaves the copies alone (EE-4). */
    static final String GITIGNORE = ".gitignore";

    private static final Logger log = LoggerFactory.getLogger(LocalStaging.class);

    /** What {@link StagedResource.ContentKeyBuilder} produces: exactly 16 lowercase hex characters, never a path. */
    private static final Pattern CONTENT_KEY = Pattern
            .compile("[0-9a-f]{" + StagedResource.CONTENT_KEY_HEX_LENGTH + "}");

    private final VirtualFileSystem rawFileSystem;
    private final VirtualFileSystem passthroughFileSystem;
    private final String stagingRoot;
    private final long maxStagedBytes;
    private final ConcurrentMap<String, Object> locks = new ConcurrentHashMap<>();
    /** Targets this instance copied, or found already staged and checked against their content key. */
    private final Set<String> verified = ConcurrentHashMap.newKeySet();

    LocalStaging(VirtualFileSystem rawFileSystem, VirtualFileSystem passthroughFileSystem, String stagingRoot,
            long maxStagedBytes) {
        this.rawFileSystem = Objects.requireNonNull(rawFileSystem, "rawFileSystem must not be null");
        this.passthroughFileSystem = passthroughFileSystem;
        this.stagingRoot = Objects.requireNonNull(stagingRoot, "stagingRoot must not be null");
        this.maxStagedBytes = maxStagedBytes;
    }

    /**
     * Whether a directory name has the shape of a content key (exactly 16 lowercase hex): what the sweep may delete.
     */
    static boolean isContentKey(String name) {
        return CONTENT_KEY.matcher(name).matches();
    }

    String stagingRoot() {
        return stagingRoot;
    }

    String stage(StagedResource resource) {
        Objects.requireNonNull(resource, "resource must not be null");
        final VirtualFileSystem source = resource.getSourceFileSystem();
        final boolean inWorkspace = source == rawFileSystem || source == passthroughFileSystem;
        if (inWorkspace && passthroughFileSystem.exists(resource.getSourceDir())) {
            // The resource already lives in this workspace where the file tools can see it: nothing to copy, and the
            // path is valid as it is. One under a hidden prefix (the control store) is copied like any other.
            return absolute(resource.getSourceDir());
        }
        // The name becomes one directory of the staging area, and the sweep and the cleanup of an interrupted copy
        // trust it: anything that could leave that directory is refused.
        final String name = resource.getName();
        if (name.isEmpty() || name.equals(".") || name.equals("..") || name.contains("/") || name.contains("\\")) {
            throw new StagingException("Cannot stage '" + name + "': the name is not a single path segment");
        }
        if (name.equalsIgnoreCase(GITIGNORE)) {
            throw new StagingException("Cannot stage '" + name + "': the name is reserved for the staging area");
        }
        // The content key is the second directory, and every recorded file lands below it: a hand-built resource
        // (SPI code, a remote repository's keys) could otherwise write or delete outside its own copy.
        if (!CONTENT_KEY.matcher(resource.getContentKey()).matches()) {
            throw new StagingException("Cannot stage '" + name + "': the content key '" + resource.getContentKey()
                    + "' is not 16 lowercase hex characters");
        }
        for (String relPath : resource.getFiles()) {
            if (!isConfinedRelativePath(relPath)) {
                throw new StagingException("Cannot stage '" + name + "': the file path '" + relPath
                        + "' is not a relative path inside the resource");
            }
        }
        // Read a workspace resource through the unguarded filesystem: the tools' view may hide it.
        final VirtualFileSystem reader = inWorkspace ? rawFileSystem : source;
        final String target = VfsPaths.join(stagingRoot, resource.getName(), resource.getContentKey());
        final String marker = target + "/" + MARKER;
        if (verified.contains(target) && rawFileSystem.exists(marker)) {
            return absolute(target);
        }
        synchronized (locks.computeIfAbsent(target, k -> new Object())) {
            if (rawFileSystem.exists(marker)) {
                if (verified.contains(target) || matchesContentKey(resource, target)) {
                    verified.add(target);
                    return absolute(target);
                }
                log.warn("Staged copy {} does not match its content key; staging it again", target);
            }
            if (resource.getTotalBytes() > maxStagedBytes) {
                throw new StagingException("Cannot stage '" + resource.getName() + "': " + resource.getTotalBytes()
                        + " bytes exceeds the staging limit of " + maxStagedBytes + " bytes; exclude large files with "
                        + StagedResource.STAGE_IGNORE_FILE);
            }
            ensureGitignore();
            copy(resource, reader, target);
            rawFileSystem.write(marker, resource.getContentKey().getBytes(StandardCharsets.UTF_8));
            verified.add(target);
        }
        log.debug("Staged {} ({} files) to {}", resource.getName(), resource.getFiles().size(), target);
        return absolute(target);
    }

    /**
     * Whether a copy already on disk is the one its path names: the marker holds the content key, the files are
     * exactly the resource's files (and the marker), and they hash to the content key. The key is predictable, so
     * the path and the marker alone are no evidence — a copy committed to a repository or planted through the shell
     * has both (EE-37).
     */
    private boolean matchesContentKey(StagedResource resource, String target) {
        try {
            if (!resource.getContentKey().equals(readString(target + "/" + MARKER))) {
                return false;
            }
            final String prefix = target + "/";
            final Set<String> onDisk = new HashSet<>();
            for (String path : rawFileSystem.listRecursive(target)) {
                onDisk.add(path.startsWith(prefix) ? path.substring(prefix.length()) : path);
            }
            final Set<String> expected = new HashSet<>(resource.getFiles());
            expected.add(MARKER);
            if (!onDisk.equals(expected)) {
                return false;
            }
            final StagedResource.ContentKeyBuilder hasher = new StagedResource.ContentKeyBuilder();
            for (String relPath : resource.getFiles()) {
                try (InputStream in = rawFileSystem.read(prefix + relPath)) {
                    hasher.add(relPath, in.readAllBytes());
                }
            }
            return hasher.build().equals(resource.getContentKey());
        } catch (IOException | RuntimeException e) {
            log.debug("Could not verify staged copy {}: {}", target, e.getMessage());
            return false;
        }
    }

    private String readString(String path) throws IOException {
        try (InputStream in = rawFileSystem.read(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Writes {@value #GITIGNORE} ({@code *}) at the top of the staging area unless one is there, so the copies — and
     * the ignore file itself — never show up in the version control of a workspace that is a repository (EE-4). A
     * file the user put there is left alone.
     */
    private void ensureGitignore() {
        final String path = stagingRoot + "/" + GITIGNORE;
        try {
            if (!rawFileSystem.exists(path)) {
                rawFileSystem.write(path, "*".getBytes(StandardCharsets.UTF_8));
            }
        } catch (RuntimeException e) {
            log.warn("Could not write {}: {}", path, e.getMessage());
        }
    }

    private void copy(StagedResource resource, VirtualFileSystem reader, String target) {
        if (rawFileSystem.exists(target)) {
            // A previous copy was interrupted before its marker: start over.
            rawFileSystem.deleteRecursive(target);
        }
        final StagedResource.ContentKeyBuilder hasher = new StagedResource.ContentKeyBuilder();
        for (String relPath : resource.getFiles()) {
            final byte[] bytes;
            try (InputStream in = reader.read(resource.sourcePath(relPath))) {
                bytes = in.readAllBytes();
            } catch (IOException | RuntimeException e) {
                discard(target);
                throw new StagingException("Cannot stage '" + resource.getName() + "': " + relPath
                        + " could not be read: " + e.getMessage(), e);
            }
            hasher.add(relPath, bytes);
            rawFileSystem.write(target + "/" + relPath, bytes);
        }
        if (!hasher.build().equals(resource.getContentKey())) {
            discard(target);
            throw new StagingException("Skill '" + resource.getName() + "' changed on disk after it was loaded, so its"
                    + " files no longer match the version this session uses. Restart the application, or reload the"
                    + " skill registry, to pick up the change.");
        }
    }

    /** A relative path with no empty, {@code .} or {@code ..} segment and no backslash: it stays below its base. */
    private static boolean isConfinedRelativePath(String relPath) {
        if (relPath.isEmpty() || relPath.startsWith("/") || relPath.contains("\\")) {
            return false;
        }
        for (String segment : relPath.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                return false;
            }
        }
        return true;
    }

    private void discard(String target) {
        try {
            if (rawFileSystem.exists(target)) {
                rawFileSystem.deleteRecursive(target);
            }
        } catch (RuntimeException e) {
            log.warn("Could not remove incomplete staging directory {}: {}", target, e.getMessage());
        }
    }

    /**
     * The path the shell and the file tools both accept: absolute under a local ({@code '/'}-anchored) base,
     * root-anchored for a URI-shaped one.
     */
    String absolute(String relative) {
        final String workingDirectory = rawFileSystem.getWorkingDirectory();
        if (relative.startsWith("/") || relative.contains("://")) {
            return relative;
        }
        if (workingDirectory != null && workingDirectory.startsWith("/")) {
            return VfsPaths.join(workingDirectory, relative);
        }
        return VfsPaths.join("/", relative);
    }
}
