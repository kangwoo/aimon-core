package at.aimon.core.environment.impl;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
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
 * that no longer hash to the content key fail with {@link StagingException}, the partial copy removed and no marker
 * written, so an incomplete or mislabelled copy is never served and the next call retries.
 *
 * <p>
 * <b>Other stagers of the same workspace (EE-17).</b> The lock here is this instance's; a second process, or a second
 * provider in this one, shares only the disk. So nothing is ever copied into the target itself: the files and the
 * marker go to a sibling {@code {contentKey}}{@value #TEMPORARY_INFIX}{@code {random}} directory, and a failed copy
 * removes that directory and nothing else. How the finished copy gets its name depends on what the workspace can do:
 * <ul>
 * <li><b>A workspace that is a host directory</b> ({@code workspaceRoot} mode, or a borrowed filesystem whose working
 * directory is a local path): one atomic rename. A reader finds no target or a complete one, never half of one. A
 * stager that finds a complete copy already there — it lost a race — removes its own and returns the winner's. A
 * target that is in the way and is not a valid copy (an interrupted copy from before this change, a planted one) is
 * renamed aside first and deleted afterwards, so replacing is two renames with a moment between them in which the
 * target does not exist.</li>
 * <li><b>Any other filesystem</b> (S3, GridFS): there is no rename to use — {@link VirtualFileSystem#move} takes one
 * file, and an object store implements it as copy-then-delete. The verified files are moved into the target one by
 * one and the marker last. That is not atomic, and the marker stays the only commit point; what it does guarantee is
 * that no stager deletes the target or a file of the resource in it, and that bytes which failed verification never
 * reach it. Concurrent stagers of one content key write the same bytes to the same paths.</li>
 * </ul>
 * A temporary directory outlives a process that dies mid-copy. The provider's startup sweep removes those older than
 * its grace period where it runs at all (an owned {@code workspaceRoot}); on a borrowed filesystem they stay, like
 * superseded copies do (EE-2).
 */
final class LocalStaging {

    /** Written last in a staged directory; its presence is what "already staged" means. */
    static final String MARKER = ".staged";

    /** Written once at the top of the staging area so version control leaves the copies alone (EE-4). */
    static final String GITIGNORE = ".gitignore";

    /** Between the content key and a random suffix in the name of a directory that is not a copy: see the class doc. */
    static final String TEMPORARY_INFIX = ".tmp-";

    private static final Logger log = LoggerFactory.getLogger(LocalStaging.class);

    /** What {@link StagedResource.ContentKeyBuilder} produces: exactly 16 lowercase hex characters, never a path. */
    private static final Pattern CONTENT_KEY = Pattern
            .compile("[0-9a-f]{" + StagedResource.CONTENT_KEY_HEX_LENGTH + "}");

    /** A copy being made or a target moved aside: {@code {contentKey}.tmp-{32 hex}}, never a content key itself. */
    private static final Pattern TEMPORARY = Pattern.compile("[0-9a-f]{" + StagedResource.CONTENT_KEY_HEX_LENGTH + "}"
            + Pattern.quote(TEMPORARY_INFIX) + "[0-9a-f]{32}");

    /**
     * How often a finished copy is offered to a target that is in the way. One displacement is the ordinary case; a
     * further one means another stager replaced the target in between, which cannot go on.
     */
    private static final int PUBLISH_ATTEMPTS = 3;

    private final VirtualFileSystem rawFileSystem;
    private final VirtualFileSystem passthroughFileSystem;
    /** The host directory {@link #rawFileSystem} is rooted at, or null when it is not one. */
    private final Path hostRoot;
    private final String stagingRoot;
    private final long maxStagedBytes;
    private final ConcurrentMap<String, Object> locks = new ConcurrentHashMap<>();
    /** Targets this instance copied, or found already staged and checked against their content key. */
    private final Set<String> verified = ConcurrentHashMap.newKeySet();

    LocalStaging(VirtualFileSystem rawFileSystem, VirtualFileSystem passthroughFileSystem, Path hostRoot,
            String stagingRoot, long maxStagedBytes) {
        this.rawFileSystem = Objects.requireNonNull(rawFileSystem, "rawFileSystem must not be null");
        this.passthroughFileSystem = passthroughFileSystem;
        this.hostRoot = hostRoot;
        this.stagingRoot = Objects.requireNonNull(stagingRoot, "stagingRoot must not be null");
        this.maxStagedBytes = maxStagedBytes;
    }

    /**
     * Whether a directory name has the shape of a content key (exactly 16 lowercase hex): what the sweep may delete.
     */
    static boolean isContentKey(String name) {
        return CONTENT_KEY.matcher(name).matches();
    }

    /**
     * Whether a directory name is one this class gives a copy in progress or a target moved aside: what the sweep may
     * delete once it is old enough to belong to no running stager.
     */
    static boolean isTemporary(String name) {
        return TEMPORARY.matcher(name).matches();
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
            // Also on the reuse path: a workspace whose copies predate EE-4 would otherwise never get the file.
            ensureGitignore();
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
            // Never into the target: another process may be copying to, or already running from, that directory.
            final String staging = temporarySibling(target);
            try {
                copy(resource, reader, staging);
                rawFileSystem.write(staging + "/" + MARKER, resource.getContentKey().getBytes(StandardCharsets.UTF_8));
                publish(resource, staging, target);
            } catch (RuntimeException e) {
                discard(staging);
                throw e;
            }
            verified.add(target);
        }
        log.debug("Staged {} ({} files) to {}", resource.getName(), resource.getFiles().size(), target);
        return absolute(target);
    }

    /**
     * Whether a copy already on disk is the one its path names: the marker holds the content key, every resource file
     * is there, and they hash to the content key. The key is predictable, so the path and the marker alone are no
     * evidence — a copy committed to a repository or planted through the shell has both (EE-37).
     *
     * <p>
     * A file that is not the resource's is removed, not taken as a mismatch. It is not harmless — a planted sibling
     * module is what a staged script would import — but it is also what running a staged script leaves behind
     * ({@code __pycache__}, {@code .DS_Store}), and re-copying the whole tree for it would delete the copy under any
     * process still using it, on every start. Removing only the extra files closes the same hole without that.
     */
    private boolean matchesContentKey(StagedResource resource, String target) {
        try {
            if (!resource.getContentKey().equals(readString(target + "/" + MARKER))) {
                return false;
            }
            final String prefix = target + "/";
            final Set<String> onDisk = filesUnder(target);
            final Set<String> expected = new HashSet<>(resource.getFiles());
            expected.add(MARKER);
            if (!onDisk.containsAll(expected)) {
                return false;
            }
            final StagedResource.ContentKeyBuilder hasher = new StagedResource.ContentKeyBuilder();
            for (String relPath : resource.getFiles()) {
                try (InputStream in = rawFileSystem.read(prefix + relPath)) {
                    hasher.add(relPath, in.readAllBytes());
                }
            }
            if (!hasher.build().equals(resource.getContentKey())) {
                return false;
            }
            onDisk.removeAll(expected);
            for (String extra : onDisk) {
                log.info("Removing {} from staged copy {}: it is not one of the resource's files", extra, target);
                rawFileSystem.delete(prefix + extra);
            }
            return true;
        } catch (IOException | RuntimeException e) {
            log.debug("Could not verify staged copy {}: {}", target, e.getMessage());
            return false;
        }
    }

    /** The files below a directory, relative to it. */
    private Set<String> filesUnder(String directory) {
        final String prefix = directory + "/";
        final Set<String> files = new HashSet<>();
        for (String path : rawFileSystem.listRecursive(directory)) {
            files.add(path.startsWith(prefix) ? path.substring(prefix.length()) : path);
        }
        return files;
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

    /**
     * Copies the resource's files into a directory nothing else names and checks them against the content key. The
     * caller removes the directory when this throws.
     */
    private void copy(StagedResource resource, VirtualFileSystem reader, String staging) {
        final StagedResource.ContentKeyBuilder hasher = new StagedResource.ContentKeyBuilder();
        for (String relPath : resource.getFiles()) {
            final byte[] bytes;
            try (InputStream in = reader.read(resource.sourcePath(relPath))) {
                bytes = in.readAllBytes();
            } catch (IOException | RuntimeException e) {
                throw new StagingException("Cannot stage '" + resource.getName() + "': " + relPath
                        + " could not be read: " + e.getMessage(), e);
            }
            hasher.add(relPath, bytes);
            rawFileSystem.write(staging + "/" + relPath, bytes);
        }
        if (!hasher.build().equals(resource.getContentKey())) {
            throw new StagingException("Skill '" + resource.getName() + "' changed on disk after it was loaded, so its"
                    + " files no longer match the version this session uses. Restart the application, or reload the"
                    + " skill registry, to pick up the change.");
        }
    }

    /**
     * Gives a finished copy (files and marker) the target's name, or drops it when a complete copy is already there.
     * The class doc says what each kind of workspace is promised.
     */
    private void publish(StagedResource resource, String staging, String target) {
        final Path hostStaging = hostRoot == null ? null : hostRoot.resolve(staging);
        // The host path is trusted only if the copy just written through the filesystem is really there: a
        // filesystem can report a local-looking working directory without being that directory.
        if (hostStaging == null || !Files.isDirectory(hostStaging, LinkOption.NOFOLLOW_LINKS)) {
            publishFileByFile(resource, staging, target);
            return;
        }
        final Path hostTarget = hostRoot.resolve(target);
        final List<String> displaced = new ArrayList<>();
        IOException last = null;
        try {
            for (int attempt = 0; attempt < PUBLISH_ATTEMPTS; attempt++) {
                try {
                    // A rename takes the place of nothing or of an empty directory, and fails on anything else.
                    Files.move(hostStaging, hostTarget, StandardCopyOption.ATOMIC_MOVE);
                    return;
                } catch (IOException e) {
                    last = e;
                }
                if (isStaged(resource, target)) {
                    // Lost a race to another stager: its copy is the same content, and may already be in use.
                    log.debug("Staged copy {} appeared while copying; using it", target);
                    discard(staging);
                    return;
                }
                if (Files.exists(hostTarget, LinkOption.NOFOLLOW_LINKS)) {
                    // An interrupted or mislabelled copy. Moved aside whole, not deleted in place: the target is
                    // then absent until the next attempt, never half deleted.
                    final String aside = temporarySibling(target);
                    try {
                        Files.move(hostTarget, hostRoot.resolve(aside), StandardCopyOption.ATOMIC_MOVE);
                        displaced.add(aside);
                    } catch (IOException e) {
                        last = e;
                    }
                }
            }
            throw new StagingException(
                    "Cannot stage '" + resource.getName() + "': the copy could not be moved to " + target + ": " + last,
                    last);
        } finally {
            displaced.forEach(this::discard);
        }
    }

    /**
     * Publishing where a directory cannot be renamed: the verified files are moved into the target one at a time, the
     * marker last. Nothing of the resource is deleted from the target, so a stager doing the same at the same time
     * loses nothing; both write the same bytes.
     */
    private void publishFileByFile(StagedResource resource, String staging, String target) {
        final String marker = target + "/" + MARKER;
        if (rawFileSystem.exists(marker)) {
            if (matchesContentKey(resource, target)) {
                log.debug("Staged copy {} appeared while copying; using it", target);
                discard(staging);
                return;
            }
            // The commit point goes first: nothing may take the directory for staged while its files are replaced.
            rawFileSystem.delete(marker);
        }
        for (String relPath : resource.getFiles()) {
            rawFileSystem.move(staging + "/" + relPath, target + "/" + relPath, true);
        }
        // What an interrupted or planted copy left that the resource does not have. The marker is not one of them:
        // only a stager that finished writes it.
        final Set<String> extras = filesUnder(target);
        resource.getFiles().forEach(extras::remove);
        extras.remove(MARKER);
        for (String extra : extras) {
            rawFileSystem.delete(target + "/" + extra);
        }
        rawFileSystem.move(staging + "/" + MARKER, marker, true);
        discard(staging);
    }

    private boolean isStaged(StagedResource resource, String target) {
        return rawFileSystem.exists(target + "/" + MARKER) && matchesContentKey(resource, target);
    }

    private static String temporarySibling(String target) {
        return target + TEMPORARY_INFIX + UUID.randomUUID().toString().replace("-", "");
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

    /** Removes a temporary directory this instance made; never called on a target. */
    private void discard(String temporary) {
        try {
            if (rawFileSystem.exists(temporary)) {
                rawFileSystem.deleteRecursive(temporary);
            }
        } catch (RuntimeException e) {
            log.warn("Could not remove temporary staging directory {}: {}", temporary, e.getMessage());
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
