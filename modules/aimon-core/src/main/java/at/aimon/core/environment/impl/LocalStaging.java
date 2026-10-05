package at.aimon.core.environment.impl;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
 * {@link StagedResource#scan}, and writes the marker last; a size over the limit or an unreadable recorded file fails
 * with {@link StagingException}, the partial copy removed and no marker written, so an incomplete or mislabelled copy
 * is never served and the next call retries. A resource holding two files whose names differ only by case is refused
 * the same way when the workspace kept one file for both (EE-38).
 *
 * <p>
 * <b>A source that changed since it was loaded (EE-3).</b> The content key was computed when the registry read the
 * resource, and nothing reloads a registry in the CLI or the bootstrap. When the bytes being copied no longer hash to
 * that key — or a recorded file is gone, or the files outgrew the limit — the copy is dropped and the source is
 * {@link StagedResource#scan scanned} again: the directory as it is now is staged under the key <em>it</em> has, the
 * one a reload or a restart would compute, and one WARN names the resource. So bytes are still never stored under a
 * key they do not hash to, and reverting the source cannot bring edited bytes back under the loaded key. The second
 * scan takes the current file set — files added, files removed, a changed {@code .stageignore} — rather than the
 * loaded list with new bytes, and its size is what the limit is checked against. The answer is remembered for the
 * loaded resource while that copy's marker exists, so later calls cost what any staged copy costs; a source that
 * changes again between the second scan and its copy is refused, and the next call starts over. This is only about
 * the <em>first</em> copy: once a copy under the loaded key exists it is what is served, in this process and in any
 * that loaded the same version, and an edit made afterwards is not seen until the registry reads the resource again
 * (design §4.4 — the hash is taken when the registry reads, not on every call). What is staged is the resource's
 * files; whatever the registry parsed from them at load (a skill's rendered body, its tool restrictions and hooks)
 * stays the loaded version until then.
 *
 * <p>
 * <b>A resource that was over the limit when it was loaded.</b> Its recorded size refuses it before anything is
 * copied, and the error says to exclude large files with {@code .stageignore} — which has to work without a restart.
 * So the recorded size is not the last word either: the source's current size is taken from file metadata
 * ({@link StagedResource#sizeOf}, no file content read), and a source now under the limit goes the way of any changed
 * source — scanned again, staged under its own key, remembered. One still over the limit is refused with the size it
 * has now, at the cost of a listing and one size query per file on each such call; nothing is remembered about a
 * refusal, so the next call sees a fix. A source that cannot report sizes is scanned in full instead.
 *
 * <p>
 * <b>Only a scanned resource is followed.</b> Scanning again is right when the resource's file list <em>is</em> its
 * directory, which is what {@link StagedResource#isScanned()} says. A resource assembled by hand (SPI code, a remote
 * repository's keys) lists what its author chose, and its directory may hold files it deliberately left out; a second
 * scan would copy those into the staging area, where the model's file tools and shell read them. Such a resource is
 * staged as recorded or refused with a {@link StagingException} — the changed-since-load refusal, or the read or size
 * error that was met — and nothing but its listed files is ever written, not even to a temporary directory.
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
    /** One per target a caller asked for: held around a call that may have to scan the source again (EE-3). */
    private final ConcurrentMap<String, Object> sourceLocks = new ConcurrentHashMap<>();
    /**
     * The target a caller's resource names, to the resource its source was found to be when that one could not be
     * copied (EE-3). Used only while the copy it names has its marker, like {@link #verified}.
     */
    private final ConcurrentMap<String, StagedResource> rescanned = new ConcurrentHashMap<>();
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

    /** Stages for the workspace itself: a resource the file tools already see there is answered in place. */
    String stage(StagedResource resource) {
        return stageInto(resource, true);
    }

    /**
     * Stages for an isolated branch of the workspace: always a copy in the staging area, never the source directory
     * (EE-26). The in-place answer is a path in the <em>parent's</em> working tree. A branch's file tools map that
     * path into {@code .worktrees/{key}/}, where the resource is not — or where the branch has written its own files
     * under the same names — while its shell opens the parent's; the staging area is the one directory both resolve
     * to the same files. The copy is checked against its content key like any other, so a workspace resource edited
     * since it was loaded, with no copy of the loaded version left, is staged as it is now under its own key (EE-3):
     * the same files the workspace itself, which is handed the live directory, is reading.
     */
    String stageCopy(StagedResource resource) {
        return stageInto(resource, false);
    }

    private String stageInto(StagedResource resource, boolean inPlaceWhenVisible) {
        Objects.requireNonNull(resource, "resource must not be null");
        final VirtualFileSystem source = resource.getSourceFileSystem();
        final boolean inWorkspace = source == rawFileSystem || source == passthroughFileSystem;
        if (inPlaceWhenVisible && inWorkspace && passthroughFileSystem.exists(resource.getSourceDir())) {
            // The resource already lives in this workspace where the file tools can see it: nothing to copy, and the
            // path is valid as it is. One under a hidden prefix (the control store) is copied like any other.
            return absolute(resource.getSourceDir());
        }
        validate(resource);
        // Read a workspace resource through the unguarded filesystem: the tools' view may hide it.
        final VirtualFileSystem reader = inWorkspace ? rawFileSystem : source;
        final String asked = targetOf(resource);
        final String staged = stagedAlready(rescanned.getOrDefault(asked, resource));
        if (staged != null) {
            return staged;
        }
        // One caller at a time finds out that a source changed; the others then find its answer in the map.
        synchronized (sourceLocks.computeIfAbsent(asked, k -> new Object())) {
            final StagedResource known = rescanned.getOrDefault(asked, resource);
            try {
                return copyVerified(known, reader);
            } catch (SourceChangedException changed) {
                if (!resource.isScanned()) {
                    throw changed.ifUnchanged != null ? changed.ifUnchanged : changedSinceLoad(resource);
                }
                if (changed.recordedOverLimit) {
                    refuseWhileOverLimit(resource, reader);
                }
                final StagedResource current = scanAgain(resource, known, reader, changed);
                final String path;
                try {
                    path = copyVerified(current, reader);
                } catch (SourceChangedException again) {
                    throw again.ifUnchanged != null ? again.ifUnchanged : stillChanging(resource);
                }
                rescanned.put(asked, current);
                log.warn(
                        "Skill '{}' changed on disk since it was loaded ({}); staged it as it is now, under {}. What"
                                + " was parsed from it at load stays as it is until the skill registry reads it again",
                        resource.getName(), changed.what, path);
                return path;
            }
        }
    }

    /**
     * Refuses a resource whose name, content key or file paths could reach outside its own copy, before anything is
     * written or deleted.
     */
    private static void validate(StagedResource resource) {
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
    }

    private String targetOf(StagedResource resource) {
        return VfsPaths.join(stagingRoot, resource.getName(), resource.getContentKey());
    }

    /** The path of a copy this instance has already checked and whose marker is still there, or null. */
    private String stagedAlready(StagedResource resource) {
        final String target = targetOf(resource);
        return verified.contains(target) && rawFileSystem.exists(target + "/" + MARKER) ? absolute(target) : null;
    }

    private StagingException overLimit(StagedResource resource, long bytes) {
        return new StagingException(
                "Cannot stage '" + resource.getName() + "': " + bytes + " bytes exceeds the staging limit of "
                        + maxStagedBytes + " bytes; exclude large files with " + StagedResource.STAGE_IGNORE_FILE);
    }

    /** The refusal a source that no longer matches its key got before EE-3; still what an assembled resource gets. */
    private static StagingException changedSinceLoad(StagedResource resource) {
        return new StagingException("Skill '" + resource.getName() + "' changed on disk after it was loaded, so its"
                + " files no longer match the version this session uses. Restart the application, or reload the"
                + " skill registry, to pick up the change.");
    }

    /**
     * Refuses a scanned resource recorded over the limit while its source still is, by the sizes its files have now —
     * or returns, and the caller scans the source again.
     *
     * <p>
     * This is what keeps a skill that is simply too large from being read in full on every call: the check lists the
     * directory, reads {@code .stageignore} and asks for each file's size, and reads no file content. Nothing about
     * the refusal is remembered, so the call after the user excluded or deleted the large files finds out. A source
     * that cannot report sizes is scanned instead, which reads every file once per refused call.
     */
    private void refuseWhileOverLimit(StagedResource resource, VirtualFileSystem reader) {
        final long bytes;
        try {
            bytes = StagedResource.sizeOf(reader, resource.getSourceDir());
        } catch (RuntimeException e) {
            log.debug("Could not size {} without reading it ({}); scanning it instead", resource.getSourceDir(),
                    e.getMessage());
            return;
        }
        if (bytes > maxStagedBytes) {
            throw overLimit(resource, bytes);
        }
    }

    private static StagingException stillChanging(StagedResource resource) {
        return new StagingException("Skill '" + resource.getName() + "' is changing on disk while it is being staged,"
                + " so no consistent copy of it could be made. Try again once its files are no longer being written.");
    }

    /**
     * Scans the source of a resource that could not be copied as its content key names it, and returns what is there
     * now (EE-3).
     *
     * @throws StagingException
     *             if the source turns out to hold what the key names after all (the copy failed for another reason, or
     *             the source changed back), cannot be scanned, or has no file left
     */
    private StagedResource scanAgain(StagedResource resource, StagedResource known, VirtualFileSystem reader,
            SourceChangedException changed) {
        final StagedResource current;
        try {
            current = StagedResource.scan(reader, resource.getSourceDir(), resource.getName());
        } catch (RuntimeException e) {
            if (changed.ifUnchanged != null) {
                throw changed.ifUnchanged;
            }
            throw new StagingException("Cannot stage '" + resource.getName() + "': it changed on disk since it was"
                    + " loaded and could not be read again: " + e.getMessage(), e);
        }
        if (current.getContentKey().equals(known.getContentKey())) {
            throw changed.ifUnchanged != null ? changed.ifUnchanged : stillChanging(resource);
        }
        if (current.getFiles().isEmpty()) {
            throw new StagingException("Cannot stage '" + resource.getName() + "': it changed on disk since it was"
                    + " loaded and no file of it is left at " + resource.getSourceDir());
        }
        validate(current);
        return current;
    }

    /**
     * Stages a resource under its own content key, or reuses the copy that is there.
     *
     * @throws SourceChangedException
     *             if the source no longer holds what the content key names, or the resource's recorded size is over
     *             the limit (which the source may no longer be); nothing is left behind
     */
    private String copyVerified(StagedResource resource, VirtualFileSystem reader) {
        final String target = targetOf(resource);
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
                // Nothing was read: the recorded size says so. Whether that is still true is the caller's question.
                throw new SourceChangedException("its recorded size was over the staging limit",
                        overLimit(resource, resource.getTotalBytes()), true);
            }
            // Never into the target: another process may be copying to, or already running from, that directory.
            final String staging = temporarySibling(target);
            try {
                copy(resource, reader, staging);
                refuseMergedFiles(resource, staging);
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
            if (!contentKeyOnDisk(resource, target).equals(resource.getContentKey())) {
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

    /** The content key of what a directory holds under the resource's file names, read back from the workspace. */
    private String contentKeyOnDisk(StagedResource resource, String directory) throws IOException {
        final StagedResource.ContentKeyBuilder hasher = new StagedResource.ContentKeyBuilder();
        for (String relPath : resource.getFiles()) {
            try (InputStream in = rawFileSystem.read(directory + "/" + relPath)) {
                hasher.add(relPath, in.readAllBytes());
            }
        }
        return hasher.build();
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
     *
     * @throws SourceChangedException
     *             if the source cannot be what the key names: a recorded file could not be read, the files are larger
     *             than the limit the recorded size passed, or the bytes hash to another key (EE-3)
     */
    private void copy(StagedResource resource, VirtualFileSystem reader, String staging) {
        final StagedResource.ContentKeyBuilder hasher = new StagedResource.ContentKeyBuilder();
        long copied = 0;
        for (String relPath : resource.getFiles()) {
            final byte[] bytes;
            try (InputStream in = reader.read(resource.sourcePath(relPath))) {
                bytes = in.readAllBytes();
            } catch (IOException | RuntimeException e) {
                // Gone since the load, or a read that failed: the second scan tells the two apart.
                throw new SourceChangedException(relPath + " could not be read", new StagingException("Cannot stage '"
                        + resource.getName() + "': " + relPath + " could not be read: " + e.getMessage(), e));
            }
            copied += bytes.length;
            if (copied > maxStagedBytes) {
                // The recorded size passed the limit, so these are not the recorded bytes: stop writing them.
                throw new SourceChangedException("its files grew past the staging limit", overLimit(resource, copied));
            }
            hasher.add(relPath, bytes);
            rawFileSystem.write(staging + "/" + relPath, bytes);
        }
        if (!hasher.build().equals(resource.getContentKey())) {
            throw new SourceChangedException("its files no longer hash to " + resource.getContentKey(), null);
        }
    }

    /**
     * The source does not hold what the resource's content key names, or may not (EE-3). Never leaves this class: the
     * caller scans
     * the source again and either stages what is there or throws a {@link StagingException}.
     */
    private static final class SourceChangedException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /** What was observed, for the log. */
        private final String what;
        /** What to throw when a second scan finds the source unchanged; null when that means it changed back. */
        private final transient StagingException ifUnchanged;

        /** Nothing was copied: the size the resource recorded is over the limit, and the source may have shrunk. */
        private final boolean recordedOverLimit;

        SourceChangedException(String what, StagingException ifUnchanged) {
            this(what, ifUnchanged, false);
        }

        SourceChangedException(String what, StagingException ifUnchanged, boolean recordedOverLimit) {
            super(what, null, false, false);
            this.what = what;
            this.ifUnchanged = ifUnchanged;
            this.recordedOverLimit = recordedOverLimit;
        }
    }

    /**
     * Refuses a copy in which the workspace kept one file for two of the resource's names (EE-38). A classpath, S3 or
     * GridFS source can hold {@code RUN.sh} beside {@code run.sh}; APFS and NTFS cannot, and the second write lands
     * in the first one's file. The hash {@link #copy} checks is of bytes read from the source, so it cannot see that.
     *
     * <p>
     * Names that fold to one are only where it can happen, not proof that it did: {@link VfsPaths#foldCase} folds at
     * least as much as any store, and a case-sensitive disk keeps such a pair apart. So the copy itself is read back
     * and hashed, and only a copy that differs is refused. A resource without such a pair — nearly every one — pays
     * nothing for this.
     */
    private void refuseMergedFiles(StagedResource resource, String staging) {
        final Map<String, String> byFoldedName = new HashMap<>();
        for (String relPath : resource.getFiles()) {
            final String twin = byFoldedName.putIfAbsent(VfsPaths.foldCase(relPath), relPath);
            if (twin == null || twin.equals(relPath)) {
                continue;
            }
            String onDisk;
            try {
                onDisk = contentKeyOnDisk(resource, staging);
            } catch (IOException | RuntimeException e) {
                log.debug("Could not read back staged copy {}: {}", staging, e.getMessage());
                onDisk = null;
            }
            if (!resource.getContentKey().equals(onDisk)) {
                throw new StagingException("Cannot stage '" + resource.getName() + "': " + twin + " and " + relPath
                        + " differ only by letter case or Unicode form, and this workspace's filesystem keeps one"
                        + " file for both. Rename one of them.");
            }
            // The workspace kept them apart, so it keeps every other such pair apart too.
            return;
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
                    } catch (IOException e) {
                        last = e;
                        continue;
                    }
                    // The check above and this move are two steps, and another stager can finish between them: what
                    // was moved aside may be its complete copy, whose path it has already handed out. That one goes
                    // back rather than being replaced by these same bytes under a new directory.
                    if (isStaged(resource, aside) && restore(aside, hostTarget)) {
                        log.debug("Staged copy {} appeared while replacing an invalid one; using it", target);
                        discard(staging);
                        return;
                    }
                    displaced.add(aside);
                }
            }
            throw new StagingException(
                    "Cannot stage '" + resource.getName() + "': the copy could not be moved to " + target + ": " + last,
                    last);
        } finally {
            displaced.forEach(this::discard);
        }
    }

    /** Puts a directory that was moved aside back under the target's name; {@code false} when the name is taken. */
    private boolean restore(String aside, Path hostTarget) {
        try {
            Files.move(hostRoot.resolve(aside), hostTarget, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (IOException e) {
            log.debug("Could not put {} back at {}: {}", aside, hostTarget, e.getMessage());
            return false;
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
