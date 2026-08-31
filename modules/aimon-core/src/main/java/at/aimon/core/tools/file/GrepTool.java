package at.aimon.core.tools.file;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

import at.aimon.core.agent.interrupt.CancellationSignal;
import at.aimon.core.agent.interrupt.InterruptBehavior;
import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.agent.tool.ConcurrencyBehavior;
import at.aimon.core.agent.tool.InterruptAccess;
import at.aimon.core.agent.tool.SideEffectLevel;
import at.aimon.core.agent.tool.ToolCategories;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.agent.tool.exception.ToolExecutionException;
import at.aimon.core.agent.tool.generic.GenericTool;
import at.aimon.core.filesystem.VirtualFileSystem;

/**
 * Tool for searching file content using regular expressions.
 *
 * <p>
 * This tools provides fast, flexible content searching with multiple output modes, file filtering, and context control.
 * Implemented using VirtualFileSystem for cross-backend compatibility (LocalFileSystem, GridFS, S3).
 *
 * <p>
 * Key features:
 *
 * <ul>
 * <li>Regular expression pattern matching
 * <li>Multiple output modes (files, content, count)
 * <li>File type filtering (java, py, js, etc.)
 * <li>Glob pattern filtering
 * <li>Case-sensitive/insensitive search
 * <li>Context lines (before/after/both)
 * <li>Multiline mode support
 * <li>Result limiting and pagination
 * </ul>
 *
 * <p>
 * <strong>CRITICAL</strong>: Always use this tools for search tasks. NEVER invoke `grep` or `rg` as Bash commands.
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
 *     Tool grepTool = new GrepTool(vfs);
 *     ToolContext context = ToolContext.empty();
 *
 *     // Basic search - find files containing pattern
 *     ToolInput input1 = ToolInput.of(Map.of("pattern", "authenticate"));
 *     ToolResult result1 = grepTool.execute(input1, context);
 *
 *     // Search with content display
 *     ToolInput input2 = ToolInput.of(Map.of("pattern", "TODO", "output_mode", "content", "-n", true));
 *     ToolResult result2 = grepTool.execute(input2, context);
 *
 *     // Search specific file type
 *     ToolInput input3 = ToolInput.of(Map.of("pattern", "class \\w+", "type", "java", "output_mode", "content"));
 *     ToolResult result3 = grepTool.execute(input3, context);
 * }
 * </pre>
 *
 * <p>
 * Parameters are declared by {@link GrepInput} and bound by {@link GenericTool}; there is no hand-written schema and
 * no hand-written extraction. With thirteen parameters, twelve of them optional and five spelled as grep flags that
 * are not Java identifiers, this is the shape the two-copy approach went wrong on most easily.
 */
public class GrepTool extends GenericTool<GrepInput, String> {

    public static final String TOOL_NAME = "Grep";
    // File type to extension mappings (common types)
    private static final Map<String, List<String>> FILE_TYPE_EXTENSIONS = Map.ofEntries(
            Map.entry("java", List.of(".java")), Map.entry("py", List.of(".py")),
            Map.entry("js", List.of(".js", ".jsx")), Map.entry("ts", List.of(".ts", ".tsx")),
            Map.entry("go", List.of(".go")), Map.entry("rust", List.of(".rs")),
            Map.entry("cpp", List.of(".cpp", ".cc", ".cxx", ".hpp", ".h")), Map.entry("c", List.of(".c", ".h")),
            Map.entry("md", List.of(".md", ".markdown")), Map.entry("yaml", List.of(".yaml", ".yml")),
            Map.entry("json", List.of(".json")), Map.entry("xml", List.of(".xml")),
            Map.entry("html", List.of(".html", ".htm")), Map.entry("css", List.of(".css")),
            Map.entry("sh", List.of(".sh", ".bash")), Map.entry("sql", List.of(".sql")));

    private static final int MAX_PATTERN_LENGTH = 500;

    private final VirtualFileSystem fileSystem;
    private final String workingDirectory;

    /**
     * Creates a new GrepTool.
     *
     * <p>
     * The schema is derived from {@link GrepInput}: {@code pattern} is required and the other twelve parameters
     * ({@code path}, {@code output_mode}, {@code type}, {@code glob}, {@code -i}, {@code -n}, {@code -A}, {@code -B},
     * {@code -C}, {@code multiline}, {@code head_limit}, {@code offset}) are optional.
     *
     * @param fileSystem
     *            The virtual file system to use for file operations (must not be null)
     * @throws NullPointerException
     *             if fileSystem is null
     */
    public GrepTool(VirtualFileSystem fileSystem) {
        super(TOOL_NAME,
                "A powerful search tools that searches file content using regular expressions. "
                        + "Provides fast, flexible content searching with multiple output modes, "
                        + "file filtering, and context control. "
                        + "Works with any VirtualFileSystem backend (LocalFileSystem, GridFS, S3). "
                        + "CRITICAL: ALWAYS use the Grep tools for search tasks. "
                        + "NEVER invoke `grep` or `rg` as Bash commands. "
                        + "Output modes: 'files_with_matches' (default, file paths only), "
                        + "'content' (matching lines), 'count' (match counts). "
                        + "Supports file type filtering (type parameter), glob patterns, case-insensitive search (-i), "
                        + "line numbers (-n), context lines (-A, -B, -C), and multiline mode.",
                ToolCategories.FILESYSTEM, GrepInput.class);
        this.fileSystem = Objects.requireNonNull(fileSystem, "File system cannot be null");
        workingDirectory = fileSystem.getWorkingDirectory();
    }

    /**
     * Runs the search.
     *
     * <p>
     * The steps are: validate the pattern, discover files, apply the type and glob filters, search each file, format
     * according to {@code output_mode}, paginate. Parameter extraction is not among them — {@link GrepInput} and its
     * binder did that before this method was entered, which is why nothing here re-reads a parameter by name.
     *
     * <p>
     * Everything a caller is meant to see is a {@link ToolExecutionException} whose message was written for the model.
     * The three of them are a pattern too long to compile safely, a pattern that will not compile at all, and a search
     * that was interrupted.
     *
     * @param input
     *            the bound search parameters (never null)
     * @param context
     *            the execution context, read for the cancellation signal (never null)
     * @return the formatted search output, ready to return verbatim
     * @throws ToolExecutionException
     *             if the pattern is unusable or the search was interrupted
     */
    @Override
    protected String doExecute(GrepInput input, ToolContext context) {
        final CancellationSignal signal = InterruptAccess.signalOf(context);

        try {
            final String patternString = input.pattern();

            // Validate pattern length to prevent ReDoS
            if (patternString.length() > MAX_PATTERN_LENGTH) {
                throw new ToolExecutionException("Pattern too long: " + patternString.length()
                        + " characters (maximum: " + MAX_PATTERN_LENGTH + ")");
            }

            // Apply the defaults the schema documents. Absent stays distinguishable from zero for -A / -B / -C.
            final String path = orDefault(input.path(), workingDirectory);
            final String outputMode = orDefault(input.outputMode(), "files_with_matches");
            final boolean caseInsensitive = orDefault(input.caseInsensitive(), false);
            final boolean showLineNumbers = orDefault(input.showLineNumbers(), true);
            final boolean multiline = orDefault(input.multiline(), false);
            final int offset = orDefault(input.offset(), 0);

            // Compile pattern
            int flags = caseInsensitive ? Pattern.CASE_INSENSITIVE : 0;
            if (multiline) {
                flags |= Pattern.DOTALL | Pattern.MULTILINE;
            }
            final Pattern pattern;
            try {
                pattern = Pattern.compile(patternString, flags);
            } catch (PatternSyntaxException e) {
                throw new ToolExecutionException("Invalid regex pattern: " + e.getMessage());
            }

            // Cooperative checkpoint before the (potentially very wide) file scan.
            if (signal.isCancelled()) {
                throw interrupted(signal);
            }

            // Discover files
            List<String> files = discoverFiles(path);

            // Apply filters
            if (input.type() != null) {
                files = applyFileTypeFilter(files, input.type());
            }
            if (input.glob() != null) {
                files = applyGlobFilter(files, input.glob());
            }

            // Search files
            final SearchOptions options = new SearchOptions(outputMode, showLineNumbers, input.beforeContext(),
                    input.afterContext(), input.bothContext(), multiline);

            final List<SearchResult> results = searchFiles(files, pattern, options, signal);
            if (signal.isCancelled()) {
                throw interrupted(signal);
            }

            // Format output
            final String output = formatOutput(results, outputMode, showLineNumbers);

            // Apply pagination
            final List<String> outputLines = List.of(output.split("\n"));
            final List<String> paginatedLines = applyPagination(outputLines, offset, input.headLimit());
            final String finalOutput = String.join("\n", paginatedLines);

            if (finalOutput.isEmpty() || results.isEmpty()) {
                return "No matches found for pattern: " + patternString;
            }

            return finalOutput;

        } catch (ToolExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolExecutionException("Grep search failed: " + e.getMessage(), e);
        }
    }

    /**
     * Wraps the search output.
     *
     * <p>
     * Nothing is decided here: every outcome that is not a success already left {@link #doExecute} as an exception, and
     * an empty search is a successful search that found nothing.
     *
     * @param output
     *            the formatted search output
     * @return a success result carrying it
     */
    @Override
    protected ToolResult render(String output) {
        return ToolResult.success(output);
    }

    private static String orDefault(String value, String fallback) {
        return value != null ? value : fallback;
    }

    private static boolean orDefault(Boolean value, boolean fallback) {
        return value != null ? value : fallback;
    }

    private static int orDefault(Integer value, int fallback) {
        return value != null ? value : fallback;
    }

    /** Discovers all files in the given path using VirtualFileSystem. */
    private List<String> discoverFiles(String path) {
        try {
            if (fileSystem.isDirectory(path)) {
                return fileSystem.listRecursive(path);
            } else if (fileSystem.exists(path)) {
                return List.of(path);
            } else {
                return List.of();
            }
        } catch (Exception e) {
            // If error, assume it's current directory
            try {
                return fileSystem.listRecursive(".");
            } catch (Exception e2) {
                return List.of();
            }
        }
    }

    /** Applies file type filter to file list. */
    private List<String> applyFileTypeFilter(List<String> files, String type) {
        final List<String> extensions = FILE_TYPE_EXTENSIONS.get(type.toLowerCase());
        if (extensions == null || extensions.isEmpty()) {
            return files;
        }

        return files.stream().filter(file -> extensions.stream().anyMatch(ext -> file.toLowerCase().endsWith(ext)))
                .toList();
    }

    /** Applies glob pattern filter to file list. */
    private List<String> applyGlobFilter(List<String> files, String globPattern) {
        try {
            final PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + globPattern);
            return files.stream().filter(file -> matcher.matches(Paths.get(file).getFileName())).toList();
        } catch (Exception e) {
            // If glob pattern is invalid, return all files
            return files;
        }
    }

    /**
     * Searches all files for pattern matches. Polls the cancellation signal between files so a trip lands on
     * the next file boundary rather than waiting until the entire scan completes.
     */
    private List<SearchResult> searchFiles(List<String> files, Pattern pattern, SearchOptions options,
            CancellationSignal signal) {
        final List<SearchResult> results = new ArrayList<>();

        for (String file : files) {
            if (signal.isCancelled()) {
                break;
            }
            try {
                searchFile(file, pattern, options).ifPresent(results::add);
            } catch (Exception e) {
                // Skip files that can't be read
                continue;
            }
        }

        return results;
    }

    /** Searches a single file for pattern matches. */
    private Optional<SearchResult> searchFile(String filePath, Pattern pattern, SearchOptions options)
            throws IOException {
        List<MatchedLine> matches = new ArrayList<>();

        if (options.multiline) {
            // Multiline search - read entire file
            final String content = readFileContent(filePath);
            final Matcher matcher = pattern.matcher(content);

            int lineNumber = 1;
            int lastEnd = 0;
            while (matcher.find()) {
                // Count line numbers up to match
                for (int i = lastEnd; i < matcher.start(); i++) {
                    if (content.charAt(i) == '\n') {
                        lineNumber++;
                    }
                }

                final String matchedText = matcher.group();
                matches.add(new MatchedLine(lineNumber, matchedText, null, null));
                lastEnd = matcher.end();
            }
        } else {
            // Line-by-line search
            matches = searchFileByLines(filePath, pattern, options);
        }

        if (matches.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(new SearchResult(filePath, matches));
    }

    /** Searches a file line by line for pattern matches. */
    private List<MatchedLine> searchFileByLines(String filePath, Pattern pattern, SearchOptions options)
            throws IOException {
        final List<MatchedLine> matches = new ArrayList<>();
        final List<String> allLines = new ArrayList<>();
        final List<Integer> matchedLineNumbers = new ArrayList<>();

        try (InputStream is = fileSystem.read(filePath);
                BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {

            String line;
            int lineNumber = 1;

            while ((line = reader.readLine()) != null) {
                allLines.add(line);
                final Matcher matcher = pattern.matcher(line);

                if (matcher.find()) {
                    matchedLineNumbers.add(lineNumber - 1); // 0-based index
                }
                lineNumber++;
            }
        }

        // Collect matches with context
        final int before = options.bothContext != null
                ? options.bothContext
                : (options.beforeContext != null ? options.beforeContext : 0);
        final int after = options.bothContext != null
                ? options.bothContext
                : (options.afterContext != null ? options.afterContext : 0);

        for (int matchIndex : matchedLineNumbers) {
            final List<String> beforeLines = new ArrayList<>();
            final List<String> afterLines = new ArrayList<>();

            // Collect before context
            for (int i = Math.max(0, matchIndex - before); i < matchIndex; i++) {
                beforeLines.add(allLines.get(i));
            }

            // Collect after context
            for (int i = matchIndex + 1; i <= Math.min(allLines.size() - 1, matchIndex + after); i++) {
                afterLines.add(allLines.get(i));
            }

            matches.add(new MatchedLine(matchIndex + 1, // 1-based line number
                    allLines.get(matchIndex), beforeLines.isEmpty() ? null : beforeLines,
                    afterLines.isEmpty() ? null : afterLines));
        }

        return matches;
    }

    /** Reads entire file content as string. */
    private String readFileContent(String filePath) throws IOException {
        try (InputStream is = fileSystem.read(filePath);
                BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
            return reader.lines().collect(Collectors.joining("\n"));
        }
    }

    /** Formats search results according to output mode. */
    private String formatOutput(List<SearchResult> results, String outputMode, boolean showLineNumbers) {
        switch (outputMode) {
            case "files_with_matches" :
                return formatFilesWithMatches(results);
            case "content" :
                return formatContent(results, showLineNumbers);
            case "count" :
                return formatCount(results);
            default :
                return formatFilesWithMatches(results);
        }
    }

    /** Formats output as file paths only. */
    private String formatFilesWithMatches(List<SearchResult> results) {
        return results.stream().map(r -> r.filePath).collect(Collectors.joining("\n"));
    }

    /** Formats output with matching lines and context. */
    private String formatContent(List<SearchResult> results, boolean showLineNumbers) {
        final StringBuilder sb = new StringBuilder();

        for (SearchResult result : results) {
            sb.append(result.filePath).append('\n');

            for (MatchedLine match : result.matches) {
                // Before context
                if (match.beforeContext != null) {
                    for (String line : match.beforeContext) {
                        sb.append("  ").append(line).append('\n');
                    }
                }

                // Matched line
                if (showLineNumbers) {
                    sb.append(match.lineNumber).append(':');
                }
                sb.append(match.line).append('\n');

                // After context
                if (match.afterContext != null) {
                    for (String line : match.afterContext) {
                        sb.append("  ").append(line).append('\n');
                    }
                }
            }

            sb.append('\n');
        }

        return sb.toString().trim();
    }

    /** Formats output with match counts per file. */
    private String formatCount(List<SearchResult> results) {
        return results.stream().map(r -> r.filePath + ':' + r.matches.size()).collect(Collectors.joining("\n"));
    }

    /** Applies pagination to output lines. */
    private List<String> applyPagination(List<String> lines, int offset, Integer limit) {
        if (lines.isEmpty()) {
            return lines;
        }

        final int startIndex = Math.min(offset, lines.size());
        final int endIndex;

        if (limit != null) {
            endIndex = Math.min(startIndex + limit, lines.size());
        } else {
            endIndex = lines.size();
        }

        return lines.subList(startIndex, endIndex);
    }

    /**
     * Declares {@link InterruptBehavior#COOPERATIVE}: the tool polls the {@link CancellationSignal} before the file
     * scan starts and between files inside the search loop, so a trip aborts the scan on the next file boundary
     * rather than waiting until the entire directory has been walked.
     */
    @Override
    public InterruptBehavior getInterruptBehavior() {
        return InterruptBehavior.COOPERATIVE;
    }

    /**
     * Declares {@link ConcurrencyBehavior#CONCURRENT_SAFE}. {@code Grep} is read-only: it allocates all of its mutable
     * state locally per invocation and only reads through the (thread-safe) {@link VirtualFileSystem}, so it is safe to
     * run concurrently with other concurrent-safe tools.
     */
    @Override
    public ConcurrencyBehavior getConcurrencyBehavior() {
        return ConcurrencyBehavior.CONCURRENT_SAFE;
    }

    /**
     * Declares {@link SideEffectLevel#READ_ONLY}. {@code Grep} only walks and reads through the
     * {@link VirtualFileSystem}; every match it collects is local to the invocation and discarded once the result is
     * returned.
     */
    @Override
    public SideEffectLevel getSideEffectLevel() {
        return SideEffectLevel.READ_ONLY;
    }

    private static ToolExecutionException interrupted(CancellationSignal signal) {
        final String reason = signal.getReason().map(InterruptReason::name).orElse("UNKNOWN");
        return new ToolExecutionException("Grep interrupted: " + reason);
    }

    // Inner classes for organizing search results

    private static class SearchOptions {
        final String outputMode;
        final boolean showLineNumbers;
        final Integer beforeContext;
        final Integer afterContext;
        final Integer bothContext;
        final boolean multiline;

        SearchOptions(String outputMode, boolean showLineNumbers, Integer beforeContext, Integer afterContext,
                Integer bothContext, boolean multiline) {
            this.outputMode = outputMode;
            this.showLineNumbers = showLineNumbers;
            this.beforeContext = beforeContext;
            this.afterContext = afterContext;
            this.bothContext = bothContext;
            this.multiline = multiline;
        }
    }

    private static class SearchResult {
        final String filePath;
        final List<MatchedLine> matches;

        SearchResult(String filePath, List<MatchedLine> matches) {
            this.filePath = filePath;
            this.matches = matches;
        }
    }

    private static class MatchedLine {
        final int lineNumber;
        final String line;
        final List<String> beforeContext;
        final List<String> afterContext;

        MatchedLine(int lineNumber, String line, List<String> beforeContext, List<String> afterContext) {
            this.lineNumber = lineNumber;
            this.line = line;
            this.beforeContext = beforeContext;
            this.afterContext = afterContext;
        }
    }
}
