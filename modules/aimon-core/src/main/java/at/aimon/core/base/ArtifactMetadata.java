package at.aimon.core.base;

import java.util.Optional;

/**
 * Common metadata contract for file artifacts produced during agent execution.
 *
 * <p>
 * This interface defines the shared metadata fields for artifact references. Actual file content is stored externally
 * (e.g., in a VirtualFileSystem); implementations hold only metadata.
 *
 * <p>
 * Two implementations exist at different architectural layers:
 *
 * <ul>
 * <li>{@code MessageArtifact} (at.aimon.core.llm) — lightweight metadata stored within conversation messages
 * <li>{@code FileArtifact} (at.aimon.core.agent.artifact) — full artifact reference with VirtualFileSystem integration
 * </ul>
 *
 * @see at.aimon.core.llm.MessageArtifact
 */
public interface ArtifactMetadata {

    /**
     * Gets the file path.
     *
     * @return The file path (never null)
     */
    String getPath();

    /**
     * Gets the file name for user display.
     *
     * @return The file name (never null)
     */
    String getFileName();

    /**
     * Gets the file size in bytes.
     *
     * @return The file size (non-negative)
     */
    long getSize();

    /**
     * Gets the MIME type.
     *
     * @return The MIME type, or empty if unknown
     */
    Optional<String> getMimeType();

    /**
     * Gets the tool use ID that created this artifact.
     *
     * @return The tool use ID, or empty if not tracked
     */
    Optional<String> getToolUseId();

    /**
     * Gets the download token for linking to download storage.
     *
     * @return The download token, or empty if not set
     */
    default Optional<String> getDownloadToken() {
        return Optional.empty();
    }
}
