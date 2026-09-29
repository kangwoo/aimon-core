package at.aimon.core.tools.artifact;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.artifact.ArtifactCollector;
import at.aimon.core.agent.artifact.FileArtifact;
import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.ToolCategories;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.agent.tool.permission.PermissionSubject;
import at.aimon.core.agent.tool.permission.ToolPermissionSubjectAware;
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
 *     Tool editTool = new ArtifactAwareEditTool(new ArtifactArchive(controlFileSystem, policy));
 *
 *     // For environments without artifact needs (CLI)
 *     Tool editTool = new EditTool();
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

    private final ArtifactArchive archive;

    /**
     * Creates a new artifact-aware EditTool.
     *
     * <p>
     * The tool holds no working filesystem: the delegate writes through the execution environment's, and the
     * archive registers the result — copying it into the control store first when the environment is not durable.
     *
     * @param archive
     *            registers written files as artifacts (must not be null)
     */
    public ArtifactAwareEditTool(ArtifactArchive archive) {
        super(TOOL_NAME,
                "Performs exact string replacements in files. Enables precise, surgical modifications "
                        + "to existing files by replacing specific text patterns with new content while preserving "
                        + "file structure and formatting. CRITICAL: You MUST use the Read tools at least once before "
                        + "editing a file. The old_string must match EXACTLY (including whitespace). If replace_all "
                        + "is false (default), old_string must be unique in the file.",
                ToolCategories.FILESYSTEM, createInputSchema());
        this.delegate = new EditTool();
        this.archive = Objects.requireNonNull(archive, "archive cannot be null");
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
            final Optional<String> note = archive.register(context, filePath, 0);
            if (note.isPresent()) {
                return ToolResult.success(result.getContent() + "\n" + note.get());
            }
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
