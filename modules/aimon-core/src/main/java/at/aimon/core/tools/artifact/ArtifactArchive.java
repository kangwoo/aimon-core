package at.aimon.core.tools.artifact;

import java.io.InputStream;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.artifact.ArtifactCollector;
import at.aimon.core.agent.artifact.ArtifactFileNames;
import at.aimon.core.agent.artifact.ArtifactStorage;
import at.aimon.core.agent.artifact.FileArtifact;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.filesystem.FileMetadata;
import at.aimon.core.filesystem.VfsPaths;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.tools.ExecutionEnvironmentAccess;
import at.aimon.core.tools.ToolContextKeys;

/**
 * Registers the files the artifact-aware tools wrote, archiving them into the control store first when the execution
 * environment is not durable (execution-environment design §9.3).
 *
 * <p>
 * A durable environment's path is registered as it is ({@link ArtifactStorage#WORKSPACE}). A non-durable one — a
 * sandbox workspace, an isolated workflow branch — may lose the file after the execution, so the file is copied to
 * {@code /artifacts/{archiveKey}/{relativePath}} on the control store (the key made path-safe by
 * {@link #directoryName}) and <b>that</b> path is registered ({@link ArtifactStorage#CONTROL}). The relative path is
 * the file's path under the environment's working directory, so {@code a/report.md} and {@code b/report.md} keep
 * separate copies, and registering one file again — every {@code Edit} does — overwrites its own copy and counts
 * against the execution's total once. A file over the per-file limit, or one that would take the execution over its
 * total limit, is not registered, and neither is one whose size cannot be read (the limits could not be checked),
 * whose copy fails, or whose registration fails in any other way; the write itself succeeded, so the caller appends a
 * note rather than failing.
 *
 * <p>
 * This is the one class in {@code at.aimon.core.tools} that holds a filesystem: the <em>control</em> store, which the
 * model never works in. The tools that use it still take the working filesystem from the execution's environment on
 * every call.
 */
public final class ArtifactArchive {

    /** The control-store directory archived artifacts are copied under. */
    public static final String ARTIFACTS_DIRECTORY = "artifacts";

    /** Between an archive path and the random suffix of a copy still being written beside it. */
    private static final String PARTIAL_INFIX = ".part-";

    private static final Logger log = LoggerFactory.getLogger(ArtifactArchive.class);

    private final VirtualFileSystem controlFileSystem;
    private final ArtifactPolicy policy;

    /**
     * @param controlFileSystem
     *            the control store, or null when there is none (files from non-durable environments are then not
     *            registered)
     * @param policy
     *            the limits (must not be null)
     */
    public ArtifactArchive(VirtualFileSystem controlFileSystem, ArtifactPolicy policy) {
        this.controlFileSystem = controlFileSystem;
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
    }

    /** @return the policy */
    public ArtifactPolicy getPolicy() {
        return policy;
    }

    /**
     * Registers a file written by an artifact-aware tool with the execution's collector.
     *
     * @param context
     *            the tool context (collector, environment, tool use id)
     * @param filePath
     *            the path the tool wrote
     * @param fallbackSize
     *            the size to use when the file's metadata cannot be read — what the caller knows it wrote — or a
     *            negative value when the caller does not know it either. An unknown size is registered as 0 in a
     *            durable environment and is not archived from a non-durable one
     * @return a note to append to the (successful) tool result when the file was not registered, or empty
     */
    public Optional<String> register(ToolContext context, String filePath, long fallbackSize) {
        final Optional<ArtifactCollector> collector = context.get(ToolContextKeys.ARTIFACT_COLLECTOR);
        final Optional<ExecutionEnvironment> env = ExecutionEnvironmentAccess.of(context);
        if (collector.isEmpty() || env.isEmpty()) {
            return Optional.empty();
        }
        try {
            final VirtualFileSystem fileSystem = env.get().fileSystem();
            String mimeType = null;
            long size = fallbackSize;
            try {
                final FileMetadata metadata = fileSystem.getMetadata(filePath);
                mimeType = metadata.getMimeType().orElse(null);
                size = metadata.getSize();
            } catch (Exception e) {
                log.warn("Failed to get metadata for artifact: {}", filePath, e);
            }
            final String fileName = ArtifactFileNames.extractFileName(filePath);
            final String toolUseId = context.get(ToolContextKeys.CURRENT_TOOL_USE_ID_KEY).orElse(null);

            if (env.get().durable()) {
                collector.get().add(FileArtifact.builder().path(filePath).size(Math.max(size, 0)).mimeType(mimeType)
                        .fileName(fileName).toolUseId(toolUseId).build());
                return Optional.empty();
            }
            return archive(collector.get(), env.get(), filePath, fileName, size, mimeType, toolUseId);
        } catch (Exception e) {
            // The file is written and the tool result is a success either way; without a note nothing would tell the
            // model, or the user waiting for a download, that this file is not among the artifacts.
            log.warn("Failed to register artifact: {}", filePath, e);
            return Optional.of("[artifact not registered: registering it failed: "
                    + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()) + "]");
        }
    }

    private Optional<String> archive(ArtifactCollector collector, ExecutionEnvironment env, String filePath,
            String fileName, long size, String mimeType, String toolUseId) {
        if (controlFileSystem == null) {
            return Optional.of("[artifact not registered: this environment is not durable and no control store is"
                    + " configured to archive it]");
        }
        if (size < 0) {
            // Taking the unknown size for 0 would pass both limits below whatever the file holds.
            return Optional.of("[artifact not registered: its size could not be read, so the archive limits cannot be"
                    + " checked]");
        }
        if (size > policy.getMaxFileBytes()) {
            return Optional.of("[artifact not registered: " + size + " bytes exceeds the per-file archive limit of "
                    + policy.getMaxFileBytes() + " bytes]");
        }
        final VirtualFileSystem source = env.fileSystem();
        final String archivedPath = VfsPaths.join(ARTIFACTS_DIRECTORY, directoryName(collector.getArchiveKey()),
                relativePath(env, filePath, fileName));
        // The copy below replaces whatever this file's earlier registration archived, so those bytes are not held
        // twice and are left out of the total.
        if (collector.totalBytesExcluding(ArtifactStorage.CONTROL, archivedPath) + size > policy
                .getMaxExecutionBytes()) {
            return Optional.of("[artifact not registered: archiving it would exceed this execution's limit of "
                    + policy.getMaxExecutionBytes() + " bytes]");
        }
        // Copied beside the archive path and moved over it: a copy that fails part-way must not cost the copy an
        // earlier registration of this file made, which the collector still lists.
        final String partial = archivedPath + PARTIAL_INFIX + UUID.randomUUID().toString().replace("-", "");
        try (InputStream in = source.openInputStream(filePath)) {
            controlFileSystem.write(partial, in, size);
            controlFileSystem.move(partial, archivedPath, true);
        } catch (Exception e) {
            log.warn("Failed to archive artifact {} to {}", filePath, archivedPath, e);
            discard(partial);
            return Optional
                    .of("[artifact not registered: copying it to the control store failed: " + e.getMessage() + "]");
        }
        collector.add(FileArtifact.builder().path(archivedPath).size(size).mimeType(mimeType).fileName(fileName)
                .toolUseId(toolUseId).storage(ArtifactStorage.CONTROL).build());
        log.debug("Archived artifact {} from a non-durable environment to {}", filePath, archivedPath);
        return Optional.empty();
    }

    /**
     * Where a file sits under the environment's working directory — the base the file tools resolve a model's path
     * against (the descriptor's directory when it is absolute, else the filesystem's). {@code report.md},
     * {@code ./report.md} and the absolute path under the base are one path here, so a file edited under another
     * spelling still lands on its own copy. A file outside the base has no relative form and is archived under its
     * normalised full path.
     */
    private static String relativePath(ExecutionEnvironment env, String filePath, String fileName) {
        final String descriptorDirectory = env.descriptor().workingDirectory();
        final String base = descriptorDirectory.startsWith("/")
                ? descriptorDirectory
                : env.fileSystem().getWorkingDirectory();
        final String underBase = VfsPaths.resolveUnder(base, filePath);
        if (underBase != null && !underBase.isEmpty()) {
            return underBase;
        }
        final String normalized = VfsPaths.normalizeRelative(filePath.replace('\\', '/'));
        return normalized == null || normalized.isEmpty() ? fileName : normalized;
    }

    /** Removes this attempt's own partial copy; never the archive path, which may hold an earlier registration. */
    private void discard(String partial) {
        try {
            if (controlFileSystem.exists(partial)) {
                controlFileSystem.delete(partial);
            }
        } catch (Exception e) {
            log.debug("Could not remove the partial archive copy {}: {}", partial, e.getMessage());
        }
    }

    /**
     * Turns an archive key into one directory name every control store accepts. Keys are execution ids
     * ({@code archive:<uuid>}, {@code subagent:<name>:<uuid>}), and {@code ':'} is not a legal path character on a
     * local store (nor on Windows), so every character outside {@code [A-Za-z0-9._-]} becomes {@code '_'}. The ids
     * end in a UUID, so two keys do not collapse into one name.
     *
     * @param archiveKey
     *            the collector's archive key
     * @return a single path segment: never empty, never {@code .} or {@code ..}, no separator
     */
    public static String directoryName(String archiveKey) {
        Objects.requireNonNull(archiveKey, "archiveKey must not be null");
        final String safe = archiveKey.replaceAll("[^A-Za-z0-9._-]", "_");
        return safe.isEmpty() || safe.chars().allMatch(c -> c == '.') ? "_" + safe : safe;
    }
}
