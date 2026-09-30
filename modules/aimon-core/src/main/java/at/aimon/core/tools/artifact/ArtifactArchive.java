package at.aimon.core.tools.artifact;

import java.io.InputStream;
import java.util.Objects;
import java.util.Optional;

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
 * {@code /artifacts/{archiveKey}/{fileName}} on the control store (the key made path-safe by {@link #directoryName})
 * and <b>that</b> path is registered
 * ({@link ArtifactStorage#CONTROL}). A file over the per-file limit, or one that would take the execution over its
 * total limit, is not registered, and neither is one whose copy fails; the write itself succeeded, so the caller
 * appends a note rather than failing.
 *
 * <p>
 * This is the one class in {@code at.aimon.core.tools} that holds a filesystem: the <em>control</em> store, which the
 * model never works in. The tools that use it still take the working filesystem from the execution's environment on
 * every call.
 */
public final class ArtifactArchive {

    /** The control-store directory archived artifacts are copied under. */
    public static final String ARTIFACTS_DIRECTORY = "artifacts";

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
     *            the size to register when the file's metadata cannot be read, or a negative value to use 0
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
            long size = Math.max(fallbackSize, 0);
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
                collector.get().add(FileArtifact.builder().path(filePath).size(size).mimeType(mimeType)
                        .fileName(fileName).toolUseId(toolUseId).build());
                return Optional.empty();
            }
            return archive(collector.get(), fileSystem, filePath, fileName, size, mimeType, toolUseId);
        } catch (Exception e) {
            log.warn("Failed to register artifact: {}", filePath, e);
            return Optional.empty();
        }
    }

    private Optional<String> archive(ArtifactCollector collector, VirtualFileSystem source, String filePath,
            String fileName, long size, String mimeType, String toolUseId) throws java.io.IOException {
        if (controlFileSystem == null) {
            return Optional.of("[artifact not registered: this environment is not durable and no control store is"
                    + " configured to archive it]");
        }
        if (size > policy.getMaxFileBytes()) {
            return Optional.of("[artifact not registered: " + size + " bytes exceeds the per-file archive limit of "
                    + policy.getMaxFileBytes() + " bytes]");
        }
        if (collector.totalBytes(ArtifactStorage.CONTROL) + size > policy.getMaxExecutionBytes()) {
            return Optional.of("[artifact not registered: archiving it would exceed this execution's limit of "
                    + policy.getMaxExecutionBytes() + " bytes]");
        }
        final String archivedPath = VfsPaths.join(ARTIFACTS_DIRECTORY, directoryName(collector.getArchiveKey()),
                fileName);
        try (InputStream in = source.openInputStream(filePath)) {
            controlFileSystem.write(archivedPath, in, size);
        } catch (Exception e) {
            log.warn("Failed to archive artifact {} to {}", filePath, archivedPath, e);
            discard(archivedPath);
            return Optional
                    .of("[artifact not registered: copying it to the control store failed: " + e.getMessage() + "]");
        }
        collector.add(FileArtifact.builder().path(archivedPath).size(size).mimeType(mimeType).fileName(fileName)
                .toolUseId(toolUseId).storage(ArtifactStorage.CONTROL).build());
        log.debug("Archived artifact {} from a non-durable environment to {}", filePath, archivedPath);
        return Optional.empty();
    }

    private void discard(String archivedPath) {
        try {
            if (controlFileSystem.exists(archivedPath)) {
                controlFileSystem.delete(archivedPath);
            }
        } catch (Exception e) {
            log.debug("Could not remove the partial archive copy {}: {}", archivedPath, e.getMessage());
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
