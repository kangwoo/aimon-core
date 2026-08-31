package at.aimon.core.agent.tool.permission;

import java.util.List;

import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;

/**
 * Rule for tool-specific permission validation logic.
 *
 * <p>
 * This interface defines pluggable validation rules that {@link DefaultToolPermissionValidator} consults when a tool
 * cannot express its permission subject as a single value. Each rule is responsible for one tool type, and judges a
 * call against the patterns configured for it.
 *
 * <h2>Prefer {@link ToolPermissionSubjectAware}</h2>
 *
 * <p>
 * Most tools do not need a rule. A tool that is restricted by one input field — {@code Bash} by its {@code command},
 * {@code Read} by its {@code file_path} — implements {@link ToolPermissionSubjectAware} instead, names that value, and
 * lets the framework match it. A rule is for judgements a single value cannot carry: a combination of several inputs,
 * a private pattern grammar, or a lookup against an external policy. {@code BrowserToolPermissionRule}
 * (in {@code aimon-browser-playwright}) is the in-tree example — its {@code action:url} specs are one tool's own
 * syntax, deliberately not part of the framework vocabulary.
 *
 * <h2>Design Principles</h2>
 *
 * <ul>
 * <li><b>Strategy Pattern (OCP):</b> Each rule encapsulates tool-specific validation strategy
 * <li><b>SRP:</b> Each rule focuses on validating a single tool type
 * <li><b>Stateless:</b> Rules should be stateless and thread-safe
 * <li><b>Composable:</b> Multiple rules can be combined in {@link DefaultToolPermissionValidator}
 * </ul>
 *
 * <h2>Usage Examples</h2>
 *
 * <p>
 * <b>Example 1 - Basic rule implementation:</b>
 *
 * <pre>
 * {
 *     &#64;code
 *     public class ArchiveRule implements CustomToolPermissionRule {
 *         &#64;Override
 *         public boolean isAllowed(ToolInput input, ToolContext context, List&lt;AllowedTool&gt; allowedTools) {
 *             // Raw reads, not getStringOrNull — a wrong-typed argument must deny, not throw
 *             if (!(input.get("source") instanceof String source) || !(input.get("target") instanceof String target)) {
 *                 return false;
 *             }
 *             // Both endpoints have to clear the same pattern
 *             return allowedTools.stream().filter(AllowedTool::hasPattern).anyMatch(at -> {
 *                 ToolPattern p = at.getPattern().orElseThrow();
 *                 return p.matches(source) &amp;&amp; p.matches(target);
 *             });
 *         }
 *
 *         &#64;Override
 *         public String buildErrorDetail(ToolInput input, ToolContext context) {
 *             return " (source: " + input.get("source") + ")";
 *         }
 *     }
 * }
 * </pre>
 *
 * <p>
 * <b>Example 2 - Using a custom rule:</b>
 *
 * <pre>
 * {
 *     &#64;code
 *     ToolPermissionValidator validator = new DefaultToolPermissionValidator();
 *
 *     List<AllowedTool> allowedTools = List.of(AllowedTool.parse("Archive(/tmp/**)"));
 *
 *     // The validator finds the rule on the tool itself, via CustomToolPermissionAware
 *     validator.validateOrThrow(archiveTool, ToolInput.of("source", "/tmp/a", "target", "/tmp/b"), context,
 *             allowedTools);
 * }
 * </pre>
 *
 * <h2>Read the input defensively</h2>
 *
 * <p>
 * A rule must not throw. It runs ahead of the tool, outside the guard that turns a failing execution into an error
 * result, so an exception here escapes the whole batch instead of denying one call. That rules out the typed
 * accessors: {@link ToolInput#getStringOrNull(String)} and its siblings throw {@link IllegalArgumentException} when a
 * key is present but holds the wrong type, and nothing stops a model from emitting a number where a string was
 * declared. Read through {@link ToolInput#get(String)} and pattern-match on the result — an argument the rule cannot
 * make sense of is a call it cannot judge, which is a denial.
 *
 * <h2>Thread Safety</h2>
 *
 * <p>
 * All implementations must be stateless and thread-safe. Rule methods should be pure functions with no side effects,
 * making them safe to call from multiple threads concurrently.
 *
 * @see DefaultToolPermissionValidator
 * @see ToolPermissionSubjectAware
 * @see AllowedTool
 * @see ToolPattern
 */
public interface CustomToolPermissionRule {

    /**
     * Validates whether a tool invocation is allowed based on matching allowed tools.
     *
     * <p>
     * This method implements the core validation logic for the tool, checking whether the tool input matches the
     * patterns defined in the allowed tools list.
     *
     * <p>
     * <b>Important:</b> The {@code allowedTools} parameter contains only tools that match the tool name. The rule does
     * not need to filter by tool name again.
     *
     * <p>
     * The rule receives the same {@link ToolInput} instance the tool will be executed with, so it judges the values
     * that will actually be used — not a separately built map that could drift from them. The {@link ToolContext} is
     * passed for the same reason a subject producer gets one: a rule may need the working directory or another ambient
     * value to make sense of a relative input.
     *
     * <p>
     * <b>Must not throw.</b> Return false for anything this rule cannot make sense of, including an argument of an
     * unexpected type — see the class javadoc.
     *
     * @param input
     *            Input parameters for the tool (must not be null, may be empty)
     * @param context
     *            Runtime context for the invocation (must not be null)
     * @param allowedTools
     *            List of allowed tools that match the tool name (must not be null, may be empty)
     * @return true if the tool invocation is allowed, false otherwise
     */
    boolean isAllowed(ToolInput input, ToolContext context, List<AllowedTool> allowedTools);

    /**
     * Builds a detailed error message fragment for validation failures.
     *
     * <p>
     * This method provides additional context when a tool invocation is denied. The returned string is appended to the
     * standard error message to help diagnose why validation failed.
     *
     * <p>
     * <b>Example output:</b>
     *
     * <pre>
     * " (command: rm -rf /)"
     * " (path: /etc/passwd)"
     * </pre>
     *
     * <p>
     * <b>Example implementation:</b>
     *
     * <pre>
     * {
     *     &#64;code
     *     &#64;Override
     *     public String buildErrorDetail(ToolInput input, ToolContext context) {
     *         return input.get("command") instanceof String command ? " (command: " + command + ")" : "";
     *     }
     * }
     * </pre>
     *
     * @param input
     *            Input parameters for the tool (must not be null)
     * @param context
     *            Runtime context for the invocation (must not be null)
     * @return Additional error detail string, or empty string if no additional detail
     */
    default String buildErrorDetail(ToolInput input, ToolContext context) {
        return "";
    }

}
