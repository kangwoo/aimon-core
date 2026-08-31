package at.aimon.core.tools.artifact;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.artifact.ArtifactCollector;
import at.aimon.core.agent.artifact.ArtifactFileNames;
import at.aimon.core.agent.artifact.FileArtifact;
import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.ToolCategories;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.agent.tool.permission.PermissionSubject;
import at.aimon.core.agent.tool.permission.ToolPermissionSubjectAware;
import at.aimon.core.filesystem.FileMetadata;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.tools.ToolContextKeys;
import at.aimon.core.tools.file.EditTool;

/**
 * Artifact-aware EditTool decorator.
 *
 * <p>
 * Delegates file editing to the existing {@link EditTool} and adds artifact registration via the {@code artifact}
 * parameter. When {@code artifact=true} and the edit succeeds, a {@link FileArtifact} is registered in the
 * {@link ArtifactCollector} from the tool context.
 *
 * <p>
 * Uses the same tool name ({@code "Edit"}) as the original EditTool. Only one of the two should be registered per
 * agent configuration.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     // For environments that need artifact support (Web API)
 *     VirtualFileSystem vfs = new LocalFileSystem("/base/path");
 *     Tool editTool = new ArtifactAwareEditTool(vfs);
 *
 *     // For environments without artifact needs (CLI)
 *     Tool editTool = new EditTool(vfs);
 * }
 * </pre>
 *
 * @see EditTool
 * @see ArtifactCollector
 * @see FileArtifact
 */
public class ArtifactAwareEditTool extends AbstractTool implements ToolPermissionSubjectAware {

    public static final String TOOL_NAME = "Edit";

    private static final Logger log = LoggerFactory.getLogger(ArtifactAwareEditTool.class);

    private final EditTool delegate;

    private final VirtualFileSystem fileSystem;

    /**
     * Creates a new artifact-aware EditTool.
     *
     * @param fileSystem
     *            The virtual file system for file operations and metadata retrieval (must not be null)
     * @throws NullPointerException
     *             if fileSystem is null
     */
    public ArtifactAwareEditTool(VirtualFileSystem fileSystem) {
        super(TOOL_NAME,
                "Performs exact string replacements in files. Enables precise, surgical modifications "
                        + "to existing files by replacing specific text patterns with new content while preserving "
                        + "file structure and formatting. CRITICAL: You MUST use the Read tools at least once before "
                        + "editing a file. The old_string must match EXACTLY (including whitespace). If replace_all "
                        + "is false (default), old_string must be unique in the file.",
                ToolCategories.FILESYSTEM, createInputSchema());
        this.delegate = new EditTool(Objects.requireNonNull(fileSystem, "File system cannot be null"));
        this.fileSystem = fileSystem;
    }

    private static Map<String, Object> createInputSchema() {
        return Map.of("type", "object", "additionalProperties", false, "properties", Map.of("file_path",
                Map.of("type", "string", "description", "The path to the file to modify"), "old_string",
                Map.of("type", "string", "description", "The text to replace"), "new_string",
                Map.of("type", "string", "description",
                        "The text to replace it with (must be different from old_string)"),
                "replace_all",
                Map.of("type", "boolean", "description", "Replace all occurrences of old_string (default false)"),
                "artifact",
                Map.of("type", "boolean", "description",
                        "Whether this file is intended for user download "
                                + "(e.g., reports, exports, generated documents). "
                                + "Defaults to true. Set to false for internal/temporary files.")),
                "required", List.of("file_path", "old_string", "new_string"));
    }

    /**
     * Edits a file and optionally registers it as an artifact.
     *
     * <p>
     * Delegates file editing to the original EditTool. If the edit succeeds and {@code artifact=true}, registers a
     * {@link FileArtifact} in the {@link ArtifactCollector}. Artifact registration failure is logged as a warning but
     * does not affect the tool result.
     *
     * @param input
     *            The input parameters containing file_path, old_string, new_string, optional replace_all and artifact
     *            flag
     * @param context
     *            The execution context containing ArtifactCollector and toolUseId
     * @return The result from the delegate EditTool (never null)
     */
    @Override
    public ToolResult execute(ToolInput input, ToolContext context) {
        // Read the artifact flag defensively: getBoolean throws IllegalArgumentException when the key is present but
        // not a Boolean, and this runs before the delegate edit. Without this guard a model emitting a non-boolean
        // 'artifact' value would abort an otherwise-valid edit and let the exception escape execute(), violating the
        // never-throw tool contract. Fall back to the default (treat as artifact).
        final boolean isArtifact = readArtifactFlag(input);

        final ToolResult result = delegate.execute(input, context);

        if (result.isSuccess() && isArtifact) {
            final String filePath = input.getRequiredString("file_path");
            registerArtifact(filePath, context);
        }

        return result;
    }

    private static boolean readArtifactFlag(ToolInput input) {
        try {
            return input.getBoolean("artifact", true);
        } catch (IllegalArgumentException e) {
            log.warn("Ignoring non-boolean 'artifact' value; defaulting to true ({})", e.getMessage());
            return true;
        }
    }

    /**
     * Registers a file artifact in the ArtifactCollector.
     *
     * <p>
     * On failure, logs a warning only. The file edit has already succeeded, so artifact metadata retrieval failure must
     * not change the tool result to an error.
     *
     * <p>
     * Unlike {@link ArtifactAwareWriteTool}, there is no content parameter available for a byte-length fallback. If
     * {@code fileSystem.getMetadata()} fails, size defaults to {@code 0}.
     */
    private void registerArtifact(String filePath, ToolContext context) {
        context.get(ToolContextKeys.ARTIFACT_COLLECTOR).ifPresent(collector -> {
            try {
                String mimeType = null;
                long size = 0;
                try {
                    final FileMetadata metadata = fileSystem.getMetadata(filePath);
                    mimeType = metadata.getMimeType().orElse(null);
                    size = metadata.getSize();
                } catch (Exception e) {
                    log.warn("Failed to get metadata for artifact: {}", filePath, e);
                }

                collector.add(FileArtifact.builder().path(filePath).size(size).mimeType(mimeType)
                        .fileName(ArtifactFileNames.extractFileName(filePath))
                        .toolUseId(context.get(ToolContextKeys.CURRENT_TOOL_USE_ID_KEY).orElse(null)).build());
            } catch (Exception e) {
                log.warn("Failed to register artifact: {}", filePath, e);
            }
        });
    }

    /**
     * Forwards to the wrapped {@link EditTool}, so an {@code Edit(...)} pattern judges the same path whether or not the
     * artifact-aware variant is the one registered.
     *
     * <p>
     * The {@code artifact} flag is not part of the subject. It decides whether the edited file is offered to the user
     * for download, not which file is touched, and a permission pattern is about the latter.
     */
    @Override
    public Optional<PermissionSubject> permissionSubject(ToolInput input, ToolContext context) {
        return delegate.permissionSubject(input, context);
    }

}
