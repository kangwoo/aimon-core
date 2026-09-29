package at.aimon.core.tools.artifact;

import java.nio.charset.StandardCharsets;
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
import at.aimon.core.tools.file.WriteTool;

/**
 * Artifact-aware WriteTool decorator.
 *
 * <p>
 * Delegates file writing to the existing {@link WriteTool} and adds artifact registration via the {@code artifact}
 * parameter. When {@code artifact=true} and the write succeeds, a {@link FileArtifact} is registered in the
 * {@link ArtifactCollector} from the tool context.
 *
 * <p>
 * Uses the same tool name ({@code "Write"}) as the original WriteTool. Only one of the two should be registered per
 * agent configuration.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     // For environments that need artifact support (Web API)
 *     Tool writeTool = new ArtifactAwareWriteTool(new ArtifactArchive(controlFileSystem, policy));
 *
 *     // For environments without artifact needs (CLI)
 *     Tool writeTool = new WriteTool();
 * }
 * </pre>
 *
 * @see WriteTool
 * @see ArtifactCollector
 * @see FileArtifact
 */
public class ArtifactAwareWriteTool extends AbstractTool implements ToolPermissionSubjectAware {

    public static final String TOOL_NAME = "Write";

    private static final Logger log = LoggerFactory.getLogger(ArtifactAwareWriteTool.class);

    private final WriteTool delegate;

    private final ArtifactArchive archive;

    /**
     * Creates a new artifact-aware WriteTool.
     *
     * <p>
     * The tool holds no working filesystem: the delegate writes through the execution environment's, and the
     * archive registers the result — copying it into the control store first when the environment is not durable.
     *
     * @param archive
     *            registers written files as artifacts (must not be null)
     */
    public ArtifactAwareWriteTool(ArtifactArchive archive) {
        super(TOOL_NAME,
                "Write content to a file in the filesystem. Creates new files or overwrites existing files completely. "
                        + "CRITICAL: This will overwrite existing files without warning. "
                        + "For existing files, prefer Edit tools. "
                        + "Parent directories may be created automatically if supported by backend. "
                        + "The file_path must be an absolute path, not a relative path.",
                ToolCategories.FILESYSTEM, createInputSchema());
        this.delegate = new WriteTool();
        this.archive = Objects.requireNonNull(archive, "archive cannot be null");
    }

    private static Map<String, Object> createInputSchema() {
        return Map.of("type", "object", "additionalProperties", false, "properties",
                Map.of("file_path", Map.of("type", "string", "description", "The path to the file to write"), "content",
                        Map.of("type", "string", "description", "The content to write to the file"), "artifact",
                        Map.of("type", "boolean", "description",
                                "Whether this file is intended for user download "
                                        + "(e.g., reports, exports, generated documents). "
                                        + "Defaults to true. Set to false for internal/temporary files.")),
                "required", List.of("file_path", "content"));
    }

    /**
     * Writes content to a file and optionally registers it as an artifact.
     *
     * <p>
     * Delegates file writing to the original WriteTool. If the write succeeds and {@code artifact=true}, registers a
     * {@link FileArtifact} in the {@link ArtifactCollector}. Artifact registration failure is logged as a warning but
     * does not affect the tool result.
     *
     * @param input
     *            The input parameters containing file_path, content, and optional artifact flag
     * @param context
     *            The execution context containing ArtifactCollector and toolUseId
     * @return The result from the delegate WriteTool (never null)
     */
    @Override
    public ToolResult execute(ToolInput input, ToolContext context) {
        // Read the artifact flag defensively: getBoolean throws IllegalArgumentException when the key is present but
        // not a Boolean, and this runs before the delegate write. Without this guard a model emitting a non-boolean
        // 'artifact' value (e.g. the string "true") would abort an otherwise-valid file write and let the exception
        // escape execute(), violating the never-throw tool contract. Fall back to the default (treat as artifact).
        final boolean isArtifact = readArtifactFlag(input);

        final ToolResult result = delegate.execute(input, context);

        if (result.isSuccess() && isArtifact) {
            final String filePath = input.getRequiredString("file_path");
            final String content = input.getRequiredString("content");
            final Optional<String> note = archive.register(context, filePath,
                    content.getBytes(StandardCharsets.UTF_8).length);
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
     * Forwards to the wrapped {@link WriteTool}, so a {@code Write(...)} pattern judges the same path whether or not
     * the artifact-aware variant is the one registered.
     *
     * <p>
     * The {@code artifact} flag is not part of the subject. It decides whether the written file is offered to
     * the user for download, not which file is touched, and a permission pattern is about the latter.
     */
    @Override
    public Optional<PermissionSubject> permissionSubject(ToolInput input, ToolContext context) {
        return delegate.permissionSubject(input, context);
    }

}
