package at.aimon.core.tools.file;

import at.aimon.core.agent.tool.generic.GenericTool;
import at.aimon.core.agent.tool.generic.ToolParam;

/**
 * The parameters of a {@link GrepTool} call, and the source of its schema.
 *
 * <p>
 * A record rather than the project's usual builder class — the narrow exception granted to {@link GenericTool} input
 * types, whose reasoning is in {@code at.aimon.core.agent.tool.generic}'s package documentation. In short: this is a
 * deserialization target, not a domain type, and a record's components are what make the schema derivable.
 *
 * <p>
 * <b>Every component is a wrapper type, never a primitive.</b> Twelve of these thirteen parameters are optional and
 * three of them ({@code -A}, {@code -B}, {@code -C}) behave differently when absent than when set to zero, so "not
 * supplied" has to stay distinguishable from "supplied as the default". {@link GrepTool} applies the defaults, which
 * is also where they are documented for the model — in the descriptions below.
 *
 * <p>
 * Five parameters are named things Java cannot spell. {@code -i}, {@code -n}, {@code -A}, {@code -B} and {@code -C}
 * are not identifiers, so they are declared with {@link ToolParam#name()}; the grep-style spelling is kept because it
 * is what a model already knows.
 *
 * <p>
 * The {@code @param} tags below say what each component means to a Java reader; the prose the <em>model</em> is shown
 * lives in the {@link ToolParam} annotations and is not repeated here.
 *
 * @param pattern
 *            the regular expression to match against file contents
 * @param path
 *            the file or directory to search, or null for the working directory
 * @param outputMode
 *            which rendering to produce, or null for {@code files_with_matches}
 * @param type
 *            a named file type to restrict the search to, or null for no restriction
 * @param glob
 *            a glob restricting which files are searched, or null for no restriction
 * @param caseInsensitive
 *            whether to match case-insensitively, or null for false
 * @param showLineNumbers
 *            whether content output carries line numbers, or null for true
 * @param afterContext
 *            trailing context lines per match, or null for none
 * @param beforeContext
 *            leading context lines per match, or null for none
 * @param bothContext
 *            leading and trailing context lines per match, or null for none
 * @param multiline
 *            whether {@code .} matches newlines and patterns may span lines, or null for false
 * @param headLimit
 *            how many entries to keep, or null for all of them
 * @param offset
 *            how many entries to skip before applying {@code headLimit}, or null for none
 */
public record GrepInput(

        @ToolParam(required = true, description = "The regular expression pattern "
                + "to search for in file contents") String pattern,

        @ToolParam(description = "File or directory to search in (defaults to current working directory)") String path,

        @ToolParam(name = "output_mode", allowed = {
                "content", "files_with_matches",
                "count"}, description = "Output mode: 'content' (matching lines), 'files_with_matches' (file paths), "
                        + "'count' (match counts). Default: 'files_with_matches'") String outputMode,

        @ToolParam(description = "File type to search (e.g., 'js', 'py', 'rust', 'go', 'java'). "
                + "More efficient than glob for standard file types.") String type,

        @ToolParam(description = "Glob pattern to filter files (e.g., '*.js', '*.{ts,tsx}')") String glob,

        @ToolParam(name = "-i", description = "Case insensitive search") Boolean caseInsensitive,

        @ToolParam(name = "-n", description = "Show line numbers in output (requires output_mode: 'content'). "
                + "Default: true") Boolean showLineNumbers,

        @ToolParam(name = "-A", description = "Number of lines to show after each match "
                + "(requires output_mode: 'content')") Integer afterContext,

        @ToolParam(name = "-B", description = "Number of lines to show before each match "
                + "(requires output_mode: 'content')") Integer beforeContext,

        @ToolParam(name = "-C", description = "Number of lines to show before and after each match "
                + "(requires output_mode: 'content')") Integer bothContext,

        @ToolParam(description = "Enable multiline mode where . matches newlines and patterns can span lines. "
                + "Default: false") Boolean multiline,

        @ToolParam(name = "head_limit", description = "Limit output to first N lines/entries") Integer headLimit,

        @ToolParam(description = "Skip first N lines/entries before applying head_limit. Default: 0") Integer offset){
}
