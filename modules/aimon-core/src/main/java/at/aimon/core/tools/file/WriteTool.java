package at.aimon.core.tools.file;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.ToolCategories;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.agent.tool.permission.PermissionSubject;
import at.aimon.core.agent.tool.permission.ToolPermissionSubjectAware;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.filesystem.exception.FileAlreadyExistsException;
import at.aimon.core.filesystem.exception.InvalidPathException;

/**
 * Tool for writing file contents.
 *
 * <p>
 * This tools allows the LLM to write files to a VirtualFileSystem with support for:
 *
 * <ul>
 * <li>Creating new files
 * <li>Overwriting existing files
 * <li>Multiple storage backends (local, GridFS, S3)
 * <li>Automatic parent directory creation (if supported by backend)
 * </ul>
 *
 * <p>
 * Thread-safe as long as the underlying VirtualFileSystem is thread-safe.
 *
 * <p>
 * <strong>CRITICAL</strong>: This tools will OVERWRITE existing files without warning. For existing files, prefer using
 * Edit tools for targeted modifications.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     VirtualFileSystem vfs = new LocalFileSystem("/base/path");
 *     Tool writeTool = new WriteTool(vfs);
 *     ToolContext context = ToolContext.empty();
 *
 *     // Create new file
 *     ToolInput input1 = ToolInput.of(Map.of("file_path", "/path/to/newfile.txt", "content", "Hello World"));
 *     ToolResult result1 = writeTool.execute(input1, context);
 *
 *     // Overwrite existing file
 *     ToolInput input2 = ToolInput.of(Map.of("file_path", "/path/to/existing.txt", "content", "New content"));
 *     ToolResult result2 = writeTool.execute(input2, context);
 * }
 * </pre>
 */
public class WriteTool extends AbstractTool implements ToolPermissionSubjectAware {
    public static final String TOOL_NAME = "Write";
    private static final Logger log = LoggerFactory.getLogger(WriteTool.class);
    private final VirtualFileSystem fileSystem;
    private final boolean useRelativePaths;

    /**
     * Creates a new WriteTool with relative path display enabled by default.
     *
     * @param fileSystem
     *            The virtual file system to use for file operations (must not be null)
     * @throws NullPointerException
     *             if fileSystem is null
     */
    public WriteTool(VirtualFileSystem fileSystem) {
        this(fileSystem, true);
    }

    /**
     * Creates a new WriteTool with configurable path display.
     *
     * <p>
     * The tools is configured with the following schema:
     *
     * <ul>
     * <li>Name: "write"
     * <li>Required parameter: "file_path" (string) - The path to the file
     * <li>Required parameter: "content" (string) - The content to write
     * </ul>
     *
     * @param fileSystem
     *            The virtual file system to use for file operations (must not be null)
     * @param useRelativePaths
     *            If true, display file paths relative to the working directory in result messages. If false, display
     *            absolute paths.
     * @throws NullPointerException
     *             if fileSystem is null
     */
    public WriteTool(VirtualFileSystem fileSystem, boolean useRelativePaths) {
        super(TOOL_NAME,
                "Write content to a file in the filesystem. Creates new files or overwrites existing files completely. "
                        + "CRITICAL: This will overwrite existing files without warning. "
                        + "For existing files, prefer Edit tools. "
                        + "Parent directories may be created automatically if supported by backend. "
                        + "The file_path must be an absolute path, not a relative path.",
                ToolCategories.FILESYSTEM, createInputSchema());
        this.fileSystem = Objects.requireNonNull(fileSystem, "File system cannot be null");
        this.useRelativePaths = useRelativePaths;
    }

    /**
     * Creates the JSON Schema for write tools input.
     *
     * @return The input schema map
     */
    private static Map<String, Object> createInputSchema() {
        return Map.of("type", "object", "additionalProperties", false, "properties",
                Map.of("file_path", Map.of("type", "string", "description", "The path to the file to write"), "content",
                        Map.of("type", "string", "description", "The content to write to the file")),
                "required", List.of("file_path", "content"));
    }

    /**
     * Writes content to a file in the virtual filesystem.
     *
     * <p>
     * The method performs the following operations:
     *
     * <ol>
     * <li>Validates and extracts the file_path and content parameters
     * <li>Checks if path is a directory (directories cannot be written)
     * <li>Converts content string to InputStream
     * <li>Writes to file using VirtualFileSystem
     * <li>Returns success or error result
     * </ol>
     *
     * <p>
     * <strong>CRITICAL</strong>: This operation will OVERWRITE existing files without warning.
     *
     * @param input
     *            The input parameters containing file_path and content
     * @param context
     *            The execution context (currently unused)
     * @return A success result if file is written successfully, or an error result if the file cannot be written or
     *         parameters are invalid
     * @throws NullPointerException
     *             if input or context is null
     */
    @Override
    public ToolResult execute(ToolInput input, ToolContext context) {
        Objects.requireNonNull(input, "Input cannot be null");
        Objects.requireNonNull(context, "Context cannot be null");

        try {
            // Extract file_path and content parameters
            final String filePath = input.getRequiredString("file_path");
            final String content = input.getRequiredString("content");

            log.debug("Writing file: {} ({} bytes)", filePath, content.length());

            // Check if path is a directory
            if (fileSystem.isDirectory(filePath)) {
                return ToolResult.error("Cannot write to directory: " + filePath + ". Provide a file path instead.");
            }

            // Convert content to InputStream
            final byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
            try (InputStream contentStream = new ByteArrayInputStream(contentBytes)) {
                // Write file
                fileSystem.write(filePath, contentStream, contentBytes.length);

                // Return success message
                log.debug("Successfully wrote file: {}", filePath);
                final String displayPath = toDisplayPath(filePath);
                final String message = String.format("Successfully wrote %d bytes to %s", contentBytes.length,
                        displayPath);
                return ToolResult.success(message);
            }
        } catch (IllegalArgumentException e) {
            log.warn("Invalid parameter: {}", e.getMessage());
            return ToolResult.error("Invalid parameter: " + e.getMessage());
        } catch (InvalidPathException e) {
            log.warn("Invalid path: {}", e.getMessage());
            return ToolResult.error("Invalid path: " + e.getMessage());
        } catch (FileAlreadyExistsException e) {
            // This shouldn't happen normally as write() should overwrite,
            // but handle it just in case
            log.warn("File already exists: {}", e.getMessage());
            return ToolResult.error("File already exists and cannot be overwritten: " + e.getMessage());
        } catch (Exception e) {
            log.error("Failed to write file: {}", e.getMessage(), e);
            return ToolResult.error("Failed to write file: " + e.getMessage());
        }
    }

    private String toDisplayPath(String filePath) {
        if (!useRelativePaths) {
            return filePath;
        }
        final String workingDirectory = fileSystem.getWorkingDirectory();
        if (filePath.startsWith(workingDirectory)) {
            String relative = filePath.substring(workingDirectory.length());
            if (relative.startsWith("/")) {
                relative = relative.substring(1);
            }
            return relative;
        }
        return filePath;
    }

    /**
     * Names {@code file_path} — absolute and lexically normalized — as the value a {@code Write(...)} pattern is
     * matched against.
     *
     * <p>
     * Empty when the call cannot be judged: no {@code file_path}, or a relative one with no {@code Environment} in the
     * context to resolve it against. A configured pattern then denies the call rather than guessing.
     */
    @Override
    public Optional<PermissionSubject> permissionSubject(ToolInput input, ToolContext context) {
        return FilePathSubjects.filePathSubject(input, context);
    }
}
