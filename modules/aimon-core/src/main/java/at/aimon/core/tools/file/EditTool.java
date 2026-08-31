package at.aimon.core.tools.file;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.ToolCategories;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.agent.tool.permission.PermissionSubject;
import at.aimon.core.agent.tool.permission.ToolPermissionSubjectAware;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.filesystem.exception.FileNotFoundException;
import at.aimon.core.filesystem.exception.InvalidPathException;

/**
 * Tool for performing exact string replacements in files.
 *
 * <p>
 * This tools allows the LLM to make precise, surgical modifications to existing files by replacing specific text
 * patterns with new content while preserving file structure and formatting.
 *
 * <p>
 * Key features:
 *
 * <ul>
 * <li>Exact string matching and replacement
 * <li>Preserves file formatting and indentation
 * <li>Supports multi-line replacements
 * <li>Bulk replacements with replace_all option
 * <li>Maintains code structure and syntax
 * </ul>
 *
 * <p>
 * <strong>CRITICAL REQUIREMENT</strong>: The file MUST be read using Read tools before editing. This ensures the agent
 * understands current file content and can construct accurate old_string values. The set of read files should be
 * provided in the {@link ToolContext} using the key {@link ReadTool#READ_FILES_KEY}.
 *
 * <p>
 * This tools is stateless and thread-safe. All execution state (including read files tracking) is managed through
 * {@link ToolContext}, ensuring proper isolation between concurrent executions.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     VirtualFileSystem vfs = new LocalFileSystem("/base/path");
 *     Tool editTool = new EditTool(vfs);
 *     ToolContext context = ToolContext.empty();
 *
 *     // Basic replacement
 *     ToolInput input1 = ToolInput.of(Map.of("file_path", "/path/to/file.java", "old_string",
 *             "private String username;", "new_string", "private String email;"));
 *     ToolResult result1 = editTool.execute(input1, context);
 *
 *     // Replace all occurrences
 *     ToolInput input2 = ToolInput.of(Map.of("file_path", "/path/to/file.java", "old_string", "oldMethodName",
 *             "new_string", "newMethodName", "replace_all", true));
 *     ToolResult result2 = editTool.execute(input2, context);
 * }
 * </pre>
 */
public class EditTool extends AbstractTool implements ToolPermissionSubjectAware {

    public static final String TOOL_NAME = "Edit";

    private final VirtualFileSystem fileSystem;

    /**
     * Creates a new EditTool.
     *
     * <p>
     * The tools is configured with the following schema:
     *
     * <ul>
     * <li>Name: "edit"
     * <li>Required parameter: "file_path" (string) - The path to the file
     * <li>Required parameter: "old_string" (string) - The text to replace
     * <li>Required parameter: "new_string" (string) - The text to replace it with
     * <li>Optional parameter: "replace_all" (boolean) - Replace all occurrences (default: false)
     * </ul>
     *
     * @param fileSystem
     *            The virtual file system to use for file operations (must not be null)
     * @throws NullPointerException
     *             if fileSystem is null
     */
    public EditTool(VirtualFileSystem fileSystem) {
        super(TOOL_NAME,
                "Performs exact string replacements in files. Enables precise, surgical modifications "
                        + "to existing files by replacing specific text patterns with new content while preserving "
                        + "file structure and formatting. CRITICAL: You MUST use the Read tools at least once before "
                        + "editing a file. The old_string must match EXACTLY (including whitespace). If replace_all "
                        + "is false (default), old_string must be unique in the file.",
                ToolCategories.FILESYSTEM, createInputSchema());
        this.fileSystem = Objects.requireNonNull(fileSystem, "File system cannot be null");
    }

    /**
     * Creates the JSON Schema for edit tools input.
     *
     * @return The input schema map
     */
    private static Map<String, Object> createInputSchema() {
        return Map.of("type", "object", "additionalProperties", false, "properties", Map.of("file_path",
                Map.of("type", "string", "description", "The path to the file to modify"), "old_string",
                Map.of("type", "string", "description", "The text to replace"), "new_string",
                Map.of("type", "string", "description",
                        "The text to replace it with (must be different from old_string)"),
                "replace_all",
                Map.of("type", "boolean", "description", "Replace all occurrences of old_string (default false)")),
                "required", List.of("file_path", "old_string", "new_string"));
    }

    /**
     * Performs exact string replacement in a file.
     *
     * <p>
     * The method performs the following operations:
     *
     * <ol>
     * <li>Retrieves the set of read files from {@link ToolContext}
     * <li>Validates that the file was read before editing
     * <li>Validates and extracts all required parameters
     * <li>Checks that old_string and new_string are different
     * <li>Reads the current file content
     * <li>Validates old_string exists and is unique (if replace_all is false)
     * <li>Performs the replacement
     * <li>Writes the modified content back
     * </ol>
     *
     * <p>
     * <strong>CRITICAL</strong>: The file must have been read using Read tools before editing. The set of read files
     * must be provided in the context using {@link ReadTool#READ_FILES_KEY}. This prevents blind modifications and
     * ensures accurate replacements.
     *
     * @param input
     *            The input parameters containing file_path, old_string, new_string, and optional replace_all
     * @param context
     *            The execution context containing the set of read files
     * @return A success result if replacement is successful, or an error result if validation fails or replacement
     *         cannot be performed
     * @throws NullPointerException
     *             if input or context is null
     */
    @Override
    public ToolResult execute(ToolInput input, ToolContext context) {
        Objects.requireNonNull(input, "Input cannot be null");
        Objects.requireNonNull(context, "Context cannot be null");

        try {
            // Extract file_path parameter
            final String filePath = input.getRequiredString("file_path");

            // Retrieve read files from context
            final Set<String> readFiles = context.get(ReadTool.READ_FILES_KEY).orElse(Collections.emptySet());

            // CRITICAL: Check if file was read before editing
            if (!readFiles.contains(filePath)) {
                return ToolResult
                        .error("File has not been read yet. You MUST use the Read tools before editing a file. "
                                + "This ensures you understand the current file content "
                                + "and can construct accurate old_string values.");
            }

            // Extract old_string and new_string parameters
            final String oldString = input.getRequiredString("old_string");
            final String newString = input.getRequiredString("new_string");

            // Validate old_string and new_string are different
            if (oldString.equals(newString)) {
                return ToolResult.error("old_string and new_string must be different. They are currently identical.");
            }

            // Extract optional replace_all parameter (default: false)
            final boolean replaceAll = input.getBoolean("replace_all", false);

            // Read current file content
            final String fileContent = readFileContent(filePath);

            // Check if old_string exists in file
            if (!fileContent.contains(oldString)) {
                return ToolResult.error(
                        "old_string not found in file. Verify the exact content including whitespace and line breaks. "
                                + "Remember to exclude line number prefixes from Read tools output.");
            }

            // If not replace_all, check that old_string is unique
            if (!replaceAll) {
                final int firstIndex = fileContent.indexOf(oldString);
                final int lastIndex = fileContent.lastIndexOf(oldString);
                if (firstIndex != lastIndex) {
                    // Count occurrences
                    final int count = countOccurrences(fileContent, oldString);
                    return ToolResult.error(String.format("old_string appears %d times in the file. Either:\n"
                            + "1. Make old_string unique by including more context (surrounding code)\n"
                            + "2. Use replace_all: true to replace all occurrences", count));
                }
            }

            // Perform replacement
            final String newContent;
            final int replacementCount;
            if (replaceAll) {
                replacementCount = countOccurrences(fileContent, oldString);
                newContent = fileContent.replace(oldString, newString);
            } else {
                replacementCount = 1;
                newContent = fileContent.replaceFirst(escapeRegex(oldString), escapeReplacement(newString));
            }

            // Write modified content back to file
            writeFileContent(filePath, newContent);

            // Return success message
            final String message = replaceAll
                    ? String.format("Successfully replaced %d occurrence(s) in %s", replacementCount, filePath)
                    : String.format("Successfully replaced 1 occurrence in %s", filePath);

            return ToolResult.success(message);

        } catch (IllegalArgumentException e) {
            return ToolResult.error("Invalid parameter: " + e.getMessage());
        } catch (FileNotFoundException e) {
            return ToolResult.error("File not found: " + e.getMessage());
        } catch (InvalidPathException e) {
            return ToolResult.error("Invalid path: " + e.getMessage());
        } catch (IOException e) {
            return ToolResult.error("Failed to edit file: " + e.getMessage());
        } catch (Exception e) {
            return ToolResult.error("Unexpected error: " + e.getMessage());
        }
    }

    /**
     * Reads the complete file content as a string.
     *
     * @param filePath
     *            The file path to read
     * @return The file content as a string
     * @throws IOException
     *             if an I/O error occurs
     */
    private String readFileContent(String filePath) throws IOException {
        final StringBuilder content = new StringBuilder();

        try (InputStream inputStream = fileSystem.read(filePath);
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append('\n');
            }
        }

        // Remove trailing newline if present (to match exact file content)
        if (content.length() > 0 && content.charAt(content.length() - 1) == '\n') {
            content.setLength(content.length() - 1);
        }

        return content.toString();
    }

    /**
     * Writes the content to a file.
     *
     * @param filePath
     *            The file path to write
     * @param content
     *            The content to write
     * @throws IOException
     *             if an I/O error occurs
     */
    private void writeFileContent(String filePath, String content) throws IOException {
        final byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
        try (InputStream contentStream = new ByteArrayInputStream(contentBytes)) {
            fileSystem.write(filePath, contentStream, contentBytes.length);
        }
    }

    /**
     * Counts the number of occurrences of a substring in a string.
     *
     * @param text
     *            The text to search in
     * @param substring
     *            The substring to count
     * @return The number of occurrences
     */
    private int countOccurrences(String text, String substring) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(substring, index)) != -1) {
            count++;
            index += substring.length();
        }
        return count;
    }

    /**
     * Escapes special regex characters in a string for use in replaceFirst.
     *
     * @param str
     *            The string to escape
     * @return The escaped string
     */
    private String escapeRegex(String str) {
        return str.replace("\\", "\\\\").replace(".", "\\.").replace("[", "\\[").replace("]", "\\]").replace("(", "\\(")
                .replace(")", "\\)").replace("{", "\\{").replace("}", "\\}").replace("*", "\\*").replace("+", "\\+")
                .replace("?", "\\?").replace("^", "\\^").replace("$", "\\$").replace("|", "\\|");
    }

    /**
     * Escapes special replacement characters in a string for use in replaceFirst.
     *
     * @param str
     *            The string to escape
     * @return The escaped string
     */
    private String escapeReplacement(String str) {
        return str.replace("\\", "\\\\").replace("$", "\\$");
    }

    /**
     * Names {@code file_path} — absolute and lexically normalized — as the value an {@code Edit(...)} pattern is
     * matched against.
     *
     * <p>
     * Only the file being edited is judged; {@code old_string} and {@code new_string} are content, not targets. Empty
     * when the call cannot be judged: no {@code file_path}, or a relative one with no {@code Environment} in the
     * context to resolve it against.
     */
    @Override
    public Optional<PermissionSubject> permissionSubject(ToolInput input, ToolContext context) {
        return FilePathSubjects.filePathSubject(input, context);
    }
}
