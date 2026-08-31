package at.aimon.core.tools.file;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.interrupt.InterruptBehavior;
import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.ConcurrencyBehavior;
import at.aimon.core.agent.tool.SideEffectLevel;
import at.aimon.core.agent.tool.ToolCategories;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolContextKey;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.agent.tool.permission.PermissionSubject;
import at.aimon.core.agent.tool.permission.ToolPermissionSubjectAware;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.filesystem.exception.FileNotFoundException;
import at.aimon.core.filesystem.exception.InvalidPathException;

/**
 * Tool for reading file contents with advanced features.
 *
 * <p>
 * This tools allows the LLM to read files from a VirtualFileSystem with support for:
 *
 * <ul>
 * <li>Partial reading (offset and limit)
 * <li>Line numbering (cat -n format)
 * <li>Line truncation (2000 characters)
 * <li>Multiple storage backends (local, GridFS, S3)
 * </ul>
 *
 * <p>
 * Thread-safe as long as the underlying VirtualFileSystem is thread-safe.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     VirtualFileSystem vfs = new LocalFileSystem("/base/path");
 *     Tool readTool = new ReadTool(vfs);
 *     ToolContext context = ToolContext.empty();
 *
 *     // Read entire file (first 2000 lines)
 *     ToolInput input1 = ToolInput.of(Map.of("file_path", "/path/to/file.txt"));
 *     ToolResult result1 = readTool.execute(input1, context);
 *
 *     // Read partial file
 *     ToolInput input2 = ToolInput.of(Map.of("file_path", "/path/to/file.txt", "offset", 100, "limit", 50));
 *     ToolResult result2 = readTool.execute(input2, context);
 * }
 * </pre>
 */
public class ReadTool extends AbstractTool implements ToolPermissionSubjectAware {
    public static final String TOOL_NAME = "Read";

    /**
     * Typed key for the set of files that have been read during execution.
     *
     * <p>
     * Used by {@code ReadTool} to track which files have been read and by {@code EditTool} to verify that a file has
     * been read before allowing edits.
     *
     * <p>
     * The agent executors inject this set into every {@link ToolContext} as a thread-safe set
     * ({@code ConcurrentHashMap.newKeySet()}) so {@code Read} (which is {@link ConcurrencyBehavior#CONCURRENT_SAFE})
     * can
     * record reads concurrently. Callers building a context by hand should do the same:
     *
     * <pre>
     * {@code
     * Set<String> readFiles = ConcurrentHashMap.newKeySet();
     * ToolContext context = ToolContext.builder()
     *         .put(ReadTool.READ_FILES_KEY, readFiles)
     *         .build();
     * }
     * </pre>
     */
    @SuppressWarnings("unchecked")
    public static final ToolContextKey<Set<String>> READ_FILES_KEY = ToolContextKey.of("read_tool.read_files",
            (Class<Set<String>>) (Class<?>) Set.class);
    private static final Logger log = LoggerFactory.getLogger(ReadTool.class);
    private static final int DEFAULT_LIMIT = 2000;
    private static final int MAX_LINE_LENGTH = 2000;
    private static final String LINE_NUMBER_FORMAT = "%6d→";

    private final VirtualFileSystem fileSystem;

    /**
     * Creates a new ReadTool.
     *
     * <p>
     * The tools is configured with the following schema:
     *
     * <ul>
     * <li>Name: "read"
     * <li>Required parameter: "file_path" (string) - The path to the file
     * <li>Optional parameter: "offset" (number) - The line number to start reading from (1-based)
     * <li>Optional parameter: "limit" (number) - The number of lines to read
     * </ul>
     *
     * @param fileSystem
     *            The virtual file system to use for file operations (must not be null)
     * @throws NullPointerException
     *             if fileSystem is null
     */
    public ReadTool(VirtualFileSystem fileSystem) {
        super(TOOL_NAME,
                "Read file contents from the filesystem. Returns file content with line numbers in cat -n format. "
                        + "Supports partial reading for large files using offset and limit parameters. "
                        + "By default, reads first 2000 lines. Lines longer than 2000 characters are truncated.",
                ToolCategories.FILESYSTEM, createInputSchema());
        this.fileSystem = Objects.requireNonNull(fileSystem, "File system cannot be null");
    }

    /**
     * Creates the JSON Schema for read tools input.
     *
     * @return The input schema map
     */
    private static Map<String, Object> createInputSchema() {
        return Map.of("type", "object", "additionalProperties", false, "properties",
                Map.of("file_path", Map.of("type", "string", "description", "The path to the file to read"), "offset",
                        Map.of("type", "number", "description",
                                "The line number to start reading from (1-based). "
                                        + "Only provide if the file is too large to read at once"),
                        "limit",
                        Map.of("type", "number", "description",
                                "The number of lines to read. Only provide if the file is too large to read at once")),
                "required", List.of("file_path"));
    }

    /**
     * Reads a file from the virtual filesystem.
     *
     * <p>
     * The method performs the following operations:
     *
     * <ol>
     * <li>Validates and extracts the file_path parameter
     * <li>Extracts optional offset and limit parameters
     * <li>Opens the file using VirtualFileSystem
     * <li>Reads lines with line numbering in cat -n format
     * <li>Truncates lines exceeding 2000 characters
     * <li>Returns the formatted content
     * </ol>
     *
     * <p>
     * Line numbering format: {@code " 1→First line content"}
     *
     * <p>
     * Default behavior:
     *
     * <ul>
     * <li>offset = 1 (start from first line)
     * <li>limit = 2000 (read up to 2000 lines)
     * </ul>
     *
     * @param input
     *            The input parameters containing file_path, and optional offset/limit
     * @param context
     *            The execution context (currently unused)
     * @return A success result with formatted file content if successful, or an error result if the file cannot be read
     *         or parameters are invalid
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

            log.debug("Reading file: {}", filePath);

            // Check if path is a directory
            if (fileSystem.isDirectory(filePath)) {
                return ToolResult
                        .error("Cannot read directory: " + filePath + ". Use 'ls' command to list directory contents.");
            }

            // Extract optional offset parameter (default: 1)
            final int offset = input.getInteger("offset", 1);
            if (offset < 1) {
                return ToolResult.error("offset must be >= 1, got: " + offset);
            }

            // Extract optional limit parameter (default: 2000)
            final int limit = input.getInteger("limit", DEFAULT_LIMIT);
            if (limit < 1) {
                return ToolResult.error("limit must be >= 1, got: " + limit);
            }

            // Read file content
            final String content = readFileContent(filePath, offset, limit);

            // Mark file as read in context (if readFiles Set is present)
            markFileAsRead(context, filePath);

            // Check if file is empty
            if (content.isEmpty()) {
                return ToolResult.success("[System Warning: This file is empty]");
            }

            log.debug("Successfully read file: {}", filePath);
            return ToolResult.success(content);

        } catch (IllegalArgumentException e) {
            log.warn("Invalid parameter: {}", e.getMessage());
            return ToolResult.error("Invalid parameter: " + e.getMessage());
        } catch (FileNotFoundException e) {
            log.warn("File not found: {}", e.getMessage());
            return ToolResult.error("File not found: " + e.getMessage());
        } catch (InvalidPathException e) {
            log.warn("Invalid path: {}", e.getMessage());
            return ToolResult.error("Invalid path: " + e.getMessage());
        } catch (IOException e) {
            log.error("Failed to read file: {}", e.getMessage(), e);
            return ToolResult.error("Failed to read file: " + e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error reading file: {}", e.getMessage(), e);
            return ToolResult.error("Unexpected error: " + e.getMessage());
        }
    }

    /**
     * Reads file content with line numbering and optional offset/limit.
     *
     * @param filePath
     *            The file path to read
     * @param offset
     *            The starting line number (1-based)
     * @param limit
     *            The maximum number of lines to read
     * @return The formatted file content with line numbers
     * @throws IOException
     *             if an I/O error occurs
     */
    private String readFileContent(String filePath, int offset, int limit) throws IOException {
        final StringBuilder result = new StringBuilder();

        try (InputStream inputStream = fileSystem.read(filePath);
                BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {

            int currentLine = 1;
            int linesRead = 0;
            String line;

            while ((line = reader.readLine()) != null) {
                // Skip lines until we reach offset
                if (currentLine < offset) {
                    currentLine++;
                    continue;
                }

                // Stop if we've read enough lines
                if (linesRead >= limit) {
                    break;
                }

                // Truncate line if it exceeds max length
                String displayLine = line;
                if (line.length() > MAX_LINE_LENGTH) {
                    displayLine = line.substring(0, MAX_LINE_LENGTH) + "...";
                }

                // Format line with line number (cat -n style)
                result.append(String.format(LINE_NUMBER_FORMAT, currentLine)).append(displayLine).append('\n');

                currentLine++;
                linesRead++;
            }
        }

        return result.toString();
    }

    /**
     * Explicitly declares {@link InterruptBehavior#NON_INTERRUPTIBLE}. Read is a bounded single-file operation —
     * at most {@value #DEFAULT_LIMIT} lines through a single {@code InputStream} — so the benefit of inserting a
     * per-line checkpoint is too small to justify the added complexity. If a future use case loads huge files it
     * can be promoted to {@link InterruptBehavior#COOPERATIVE} with a periodic line-count checkpoint inside
     * {@link #readFileContent(String, int, int)}.
     */
    @Override
    public InterruptBehavior getInterruptBehavior() {
        return InterruptBehavior.NON_INTERRUPTIBLE;
    }

    /**
     * Declares {@link ConcurrencyBehavior#CONCURRENT_SAFE}. {@code Read} is a read-only operation; the only shared
     * mutable state it touches is the {@link #READ_FILES_KEY} set, which the executor injects as a thread-safe set
     * ({@code ConcurrentHashMap.newKeySet()}) so concurrent reads can record themselves without racing.
     */
    @Override
    public ConcurrencyBehavior getConcurrencyBehavior() {
        return ConcurrencyBehavior.CONCURRENT_SAFE;
    }

    /**
     * Declares {@link SideEffectLevel#READ_ONLY}. {@code Read} opens the file for reading and never writes through
     * the {@link VirtualFileSystem}. The one write it does perform — recording the path in the {@link #READ_FILES_KEY}
     * set — is execution-scoped bookkeeping that {@code EditTool} consults, and is expressly exempt (see
     * {@link SideEffectLevel#READ_ONLY}).
     */
    @Override
    public SideEffectLevel getSideEffectLevel() {
        return SideEffectLevel.READ_ONLY;
    }

    /**
     * Names {@code file_path} — absolute and lexically normalized — as the value a {@code Read(...)} pattern is
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

    /**
     * Marks a file as read in the context by adding it to the read files set.
     *
     * <p>
     * This method retrieves the mutable Set from the context (if present) and adds the file path to it. This allows
     * EditTool to verify that a file has been read before allowing edits.
     *
     * <p>
     * If the read files Set is not present in the context, this method does nothing. The Set can be provided at the
     * application level if this validation is desired.
     *
     * @param context
     *            The tools context containing the read files Set
     * @param filePath
     *            The absolute path of the file that was successfully read
     */
    private void markFileAsRead(ToolContext context, String filePath) {
        final Set<String> readFiles = context.get(READ_FILES_KEY).orElse(null);
        if (readFiles != null) {
            readFiles.add(filePath);
            log.debug("Marked file as read: {}", filePath);
        }
    }
}
