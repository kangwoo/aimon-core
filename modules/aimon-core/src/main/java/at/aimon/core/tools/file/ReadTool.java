package at.aimon.core.tools.file;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

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
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.environment.FileStamp;
import at.aimon.core.environment.exception.ExecutionEnvironmentUnavailableException;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.filesystem.exception.FileAccessDeniedException;
import at.aimon.core.filesystem.exception.FileNotFoundException;
import at.aimon.core.filesystem.exception.InvalidPathException;
import at.aimon.core.tools.ExecutionEnvironmentAccess;

/**
 * Tool for reading file contents with advanced features.
 *
 * <p>
 * This tools allows the LLM to read files from the execution environment's filesystem
 * ({@code ToolContextKeys.EXECUTION_ENVIRONMENT}, read on every call) with support for:
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
 *     Tool readTool = new ReadTool();
 *     ToolContext context = ToolContext.builder().put(ToolContextKeys.EXECUTION_ENVIRONMENT, env)
 *             .put(ReadTool.FILE_STAMPS_KEY, new ConcurrentHashMap<>()).build();
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
     * Typed key for the {@link FileStamp}s of the files read during this execution (execution-environment design §7).
     *
     * <p>
     * {@code Read} records each file's stamp under its environment-normalised path; {@code Edit}, and {@code Write}
     * over an existing file, refuse to modify a file that was not read in this execution or whose stamp has changed
     * since — whether another execution, the shell or a person changed it.
     *
     * <p>
     * The agent executors inject a fresh {@link java.util.concurrent.ConcurrentHashMap} into every execution's
     * {@link ToolContext} so {@code Read} (which is {@link ConcurrencyBehavior#CONCURRENT_SAFE}) can record stamps
     * concurrently. A fork does not inherit its parent's stamps. A context without this key performs no check.
     */
    @SuppressWarnings("unchecked")
    public static final ToolContextKey<Map<String, FileStamp>> FILE_STAMPS_KEY = ToolContextKey
            .of("read_tool.file_stamps", (Class<Map<String, FileStamp>>) (Class<?>) Map.class);
    private static final Logger log = LoggerFactory.getLogger(ReadTool.class);
    private static final int DEFAULT_LIMIT = 2000;
    private static final int MAX_LINE_LENGTH = 2000;
    private static final String LINE_NUMBER_FORMAT = "%6d→";

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
     * <p>
     * The tool holds no filesystem: it reads the execution's environment from the {@link ToolContext} on every call.
     */
    public ReadTool() {
        super(TOOL_NAME,
                "Read file contents from the filesystem. Returns file content with line numbers in cat -n format. "
                        + "Supports partial reading for large files using offset and limit parameters. "
                        + "By default, reads first 2000 lines. Lines longer than 2000 characters are truncated.",
                ToolCategories.FILESYSTEM, createInputSchema());
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

            final ExecutionEnvironment env = ExecutionEnvironmentAccess.require(context);
            final VirtualFileSystem fileSystem = env.fileSystem();

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

            // Stamp before reading: a change that lands while the content is read then shows up as a mismatch at the
            // next Edit/Write instead of being silently absorbed into the recorded stamp.
            final FileStamp stamp = FileStamps.current(env, filePath);

            // Read file content
            final String content = readFileContent(fileSystem, filePath, offset, limit);

            // Record the stamp (if the context tracks stamps)
            FileStamps.record(context, env, filePath, stamp);

            // Check if file is empty
            if (content.isEmpty()) {
                return ToolResult.success("[System Warning: This file is empty]");
            }

            log.debug("Successfully read file: {}", filePath);
            return ToolResult.success(content);

        } catch (IllegalArgumentException e) {
            log.warn("Invalid parameter: {}", e.getMessage());
            return ToolResult.error("Invalid parameter: " + e.getMessage());
        } catch (IllegalStateException | ExecutionEnvironmentUnavailableException e) {
            log.warn("No usable execution environment: {}", e.getMessage());
            return ToolResult.error(e.getMessage());
        } catch (FileAccessDeniedException e) {
            // A path rule refused it: an expected answer, not a failure of the tool.
            log.warn("{}", e.getMessage());
            return ToolResult.error(e.getMessage());
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
     * @param fileSystem
     *            The execution environment's filesystem
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
    private String readFileContent(VirtualFileSystem fileSystem, String filePath, int offset, int limit)
            throws IOException {
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
     * {@link #readFileContent(VirtualFileSystem, String, int, int)}.
     */
    @Override
    public InterruptBehavior getInterruptBehavior() {
        return InterruptBehavior.NON_INTERRUPTIBLE;
    }

    /**
     * Declares {@link ConcurrencyBehavior#CONCURRENT_SAFE}. {@code Read} is a read-only operation; the only shared
     * mutable state it touches is the {@link #FILE_STAMPS_KEY} map, which the executor injects as a
     * {@code ConcurrentHashMap} so concurrent reads can record their stamps without racing.
     */
    @Override
    public ConcurrencyBehavior getConcurrencyBehavior() {
        return ConcurrencyBehavior.CONCURRENT_SAFE;
    }

    /**
     * Declares {@link SideEffectLevel#READ_ONLY}. {@code Read} opens the file for reading and never writes through
     * the {@link VirtualFileSystem}. The one write it does perform — recording the stamp in the
     * {@link #FILE_STAMPS_KEY}
     * map — is execution-scoped bookkeeping that {@code EditTool} consults, and is expressly exempt (see
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
     * Empty when the call cannot be judged: no {@code file_path}, or a relative one with no execution environment in
     * the
     * context to resolve it against. A configured pattern then denies the call rather than guessing.
     */
    @Override
    public Optional<PermissionSubject> permissionSubject(ToolInput input, ToolContext context) {
        return FilePathSubjects.filePathSubject(input, context);
    }
}
