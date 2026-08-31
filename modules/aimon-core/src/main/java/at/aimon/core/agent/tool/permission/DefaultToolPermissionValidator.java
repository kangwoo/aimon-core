package at.aimon.core.agent.tool.permission;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.tool.Tool;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;

/**
 * Default implementation of {@link ToolPermissionValidator}.
 *
 * <p>
 * A tool's name decides whether it may run at all; a pattern attached to that name decides which invocations of it may
 * run. This validator resolves the second question by asking the tool itself — for a {@link PermissionSubject} it can
 * match, or for a {@link CustomToolPermissionRule} that judges on its behalf.
 *
 * <h2>Design Characteristics</h2>
 *
 * <ul>
 * <li><b>Strategy Pattern:</b> Pattern judgement is delegated to the tool's subject or its rule
 * <li><b>Stateless:</b> No instance state, safe to reuse
 * <li><b>Thread-safe:</b> Safe to call from multiple threads concurrently
 * </ul>
 *
 * <h2>Decision order</h2>
 *
 * <ol>
 * <li>Allowed-tools list is empty → <b>permit</b> (no restrictions configured)
 * <li>The name is not in the list → <b>deny</b>
 * <li>No matching entry carries a pattern → <b>permit</b> (a bare name grants everything only when it is the only
 * entry for that name — mixing {@code "Bash"} with {@code "Bash(git:*)"} still requires the pattern to match)
 * <li>The tool supplies a {@link PermissionSubject} → match it with the matcher its
 * {@link PermissionSubject.Kind} selects ({@link ToolPattern} for a command, {@link PathPattern} for a path)
 * <li>The tool supplies a {@link CustomToolPermissionRule} → delegate to it
 * <li>Otherwise → <b>deny</b>
 * </ol>
 *
 * <h2>The last line used to permit</h2>
 *
 * <p>
 * A pattern is configured and nothing can interpret it. That state used to be treated as unrestricted access — the
 * strictest-looking configuration produced the weakest enforcement, and silently. It is now a denial: the tools that
 * patterns are actually written for name a subject, so anything landing here has a pattern nobody can evaluate, which
 * is a misconfiguration rather than a permission.
 *
 * <h2>What this validator does not cover</h2>
 *
 * <p>
 * {@code SkillPermissionManager} matches a skill's {@code allowed-tools} argument patterns by calling
 * {@link ToolPattern} directly, without passing through here. That path is command-only by construction; see its own
 * documentation.
 *
 * <h2>Usage Examples</h2>
 *
 * <p>
 * <b>Example 1 - Command patterns:</b>
 *
 * <pre>
 * {
 *     &#64;code
 *     ToolPermissionValidator validator = new DefaultToolPermissionValidator();
 *
 *     List<AllowedTool> allowedTools = List.of(AllowedTool.parse("Bash(git:*)"));
 *
 *     // Allowed — BashTool's subject is its command
 *     validator.validateOrThrow(bashTool, ToolInput.of("command", "git add ."), context, allowedTools);
 *
 *     // Denied
 *     validator.validateOrThrow(bashTool, ToolInput.of("command", "rm -rf /"), context, allowedTools);
 * }
 * </pre>
 *
 * <p>
 * <b>Example 2 - Path patterns:</b>
 *
 * <pre>
 * {
 *     &#64;code
 *     List<AllowedTool> allowedTools = List.of(AllowedTool.parse("Read(/tmp/**)"));
 *
 *     // Allowed — ReadTool's subject is its normalized absolute file_path
 *     validator.validateOrThrow(readTool, ToolInput.of("file_path", "/tmp/notes.txt"), context, allowedTools);
 *
 *     // Denied — normalization folds the "..", so this does not escape /tmp
 *     validator.validateOrThrow(readTool, ToolInput.of("file_path", "/tmp/../etc/passwd"), context, allowedTools);
 * }
 * </pre>
 *
 * <p>
 * <b>Example 3 - Names only (no patterns):</b>
 *
 * <pre>
 * {
 *     &#64;code
 *     List<AllowedTool> allowedTools = List.of(AllowedTool.parse("Read"), AllowedTool.parse("Grep"));
 *
 *     validator.validateOrThrow(readTool, input, context, allowedTools); // OK
 *     validator.validateOrThrow(editTool, input, context, allowedTools); // Denied
 * }
 * </pre>
 *
 * @see ToolPermissionValidator
 * @see ToolPermissionSubjectAware
 * @see CustomToolPermissionRule
 * @see AllowedTool
 */
public class DefaultToolPermissionValidator implements ToolPermissionValidator {

    /**
     * {@inheritDoc}
     *
     * <p>
     * Follows the decision order documented on the class.
     */
    @Override
    public PermissionValidationResult validate(Tool tool, ToolInput input, ToolContext context,
            List<AllowedTool> allowedTools) {
        Objects.requireNonNull(tool, "Tool cannot be null");
        Objects.requireNonNull(input, "Tool input cannot be null");
        Objects.requireNonNull(context, "Tool context cannot be null");
        Objects.requireNonNull(allowedTools, "Allowed tools cannot be null");

        if (isAllowed(tool, input, context, allowedTools)) {
            return PermissionValidationResult.allowed();
        }

        final String toolName = tool.getDefinition().getName();
        return PermissionValidationResult
                .denied(deniedMessage(toolName, buildErrorDetail(tool, input, context), allowedTools));
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * With no instance to consult there is no subject and no rule, so a name carrying a pattern cannot be judged and is
     * denied — the same reasoning as the last line of the decision order.
     */
    @Override
    public PermissionValidationResult validateByName(String toolName, List<AllowedTool> allowedTools) {
        Objects.requireNonNull(toolName, "Tool name cannot be null");
        Objects.requireNonNull(allowedTools, "Allowed tools cannot be null");

        if (isAllowedByName(toolName, allowedTools)) {
            return PermissionValidationResult.allowed();
        }
        return PermissionValidationResult.denied(deniedMessage(toolName, "", allowedTools));
    }

    /**
     * Core validation logic that determines whether a tool invocation is allowed.
     *
     * <p>
     * This method is the heart of the permission validation system, implementing the decision order documented on the
     * class. It is {@code protected} so subclasses can specialize the judgement while the public API stays stable.
     *
     * <h3>Thread Safety</h3>
     *
     * <p>
     * This method is stateless and thread-safe. It performs no side effects and can be called concurrently from
     * multiple threads.
     *
     * @param tool
     *            The tool being invoked (must not be null)
     * @param input
     *            Input parameters for the invocation (must not be null)
     * @param context
     *            Runtime context for the invocation (must not be null)
     * @param allowedTools
     *            List of allowed tools to validate against (must not be null)
     * @return true if the tool invocation is allowed, false otherwise
     * @throws NullPointerException
     *             if any argument is null
     */
    protected boolean isAllowed(Tool tool, ToolInput input, ToolContext context, List<AllowedTool> allowedTools) {
        Objects.requireNonNull(tool, "Tool cannot be null");
        Objects.requireNonNull(input, "Tool input cannot be null");
        Objects.requireNonNull(context, "Tool context cannot be null");
        Objects.requireNonNull(allowedTools, "Allowed tools cannot be null");

        // If no restrictions, allow all
        if (allowedTools.isEmpty()) {
            return true;
        }

        final List<AllowedTool> matchingTools = findMatchingTools(tool.getDefinition().getName(), allowedTools);

        if (matchingTools.isEmpty()) {
            return false; // Tool not in allowed list
        }

        // For tools without patterns, permission granted
        if (matchingTools.stream().noneMatch(AllowedTool::hasPattern)) {
            return true;
        }

        final Optional<PermissionSubject> subject = permissionSubject(tool, input, context);
        if (subject.isPresent()) {
            return matchesAny(subject.get(), matchingTools);
        }

        final CustomToolPermissionRule rule = customRule(tool);
        if (rule != null) {
            return rule.isAllowed(input, context, matchingTools);
        }

        // A pattern is configured but nothing can interpret it: the tool names no subject and carries no rule.
        // Permitting here would make the strictest-looking configuration the weakest one — see the class javadoc.
        return false;
    }

    /**
     * Name-only validation, for an invocation with no resolvable {@link Tool}.
     *
     * @param toolName
     *            Name of the tool being validated (must not be null)
     * @param allowedTools
     *            List of allowed tools to validate against (must not be null)
     * @return true if the invocation is allowed on the strength of its name alone
     * @throws NullPointerException
     *             if toolName or allowedTools is null
     */
    protected boolean isAllowedByName(String toolName, List<AllowedTool> allowedTools) {
        Objects.requireNonNull(toolName, "Tool name cannot be null");
        Objects.requireNonNull(allowedTools, "Allowed tools cannot be null");

        if (allowedTools.isEmpty()) {
            return true;
        }

        final List<AllowedTool> matchingTools = findMatchingTools(toolName, allowedTools);
        if (matchingTools.isEmpty()) {
            return false;
        }
        return matchingTools.stream().noneMatch(AllowedTool::hasPattern);
    }

    /**
     * Matches a subject against the patterns of the entries that carry one, using the matcher its kind selects.
     *
     * <p>
     * A patternless entry never matches here. That is not dead code: the caller only short-circuits when <b>no</b>
     * entry for the name carries a pattern, so a list mixing {@code "Bash"} with {@code "Bash(git:*)"} arrives intact
     * and the unqualified entry contributes nothing. Neither does a {@link PermissionSubject.Kind#PATH} subject against
     * a spec whose body is not valid glob syntax: {@link AllowedTool#getPathPattern()} is empty in that case.
     */
    private boolean matchesAny(PermissionSubject subject, List<AllowedTool> matchingTools) {
        final String value = subject.getValue();
        return switch (subject.getKind()) {
            case COMMAND ->
                matchingTools.stream().anyMatch(at -> at.getPattern().map(p -> p.matches(value)).orElse(false));
            case PATH ->
                matchingTools.stream().anyMatch(at -> at.getPathPattern().map(p -> p.matches(value)).orElse(false));
        };
    }

    private Optional<PermissionSubject> permissionSubject(Tool tool, ToolInput input, ToolContext context) {
        if (tool instanceof ToolPermissionSubjectAware subjectAware) {
            return Objects.requireNonNullElse(subjectAware.permissionSubject(input, context), Optional.empty());
        }
        return Optional.empty();
    }

    private CustomToolPermissionRule customRule(Tool tool) {
        if (tool instanceof CustomToolPermissionAware ruleAware) {
            return ruleAware.getCustomPermissionRule();
        }
        return null;
    }

    /**
     * Builds detailed error information for a permission violation.
     *
     * <p>
     * The detail names the value that was judged, so the message says which invocation was refused rather than only
     * which tool. It comes from the subject when the tool names one — {@code " (command: rm -rf /)"},
     * {@code " (path: /etc/passwd)"} — and otherwise from the tool's own rule.
     *
     * @return Additional error detail string, or empty string if no detail is available (never null)
     */
    private String buildErrorDetail(Tool tool, ToolInput input, ToolContext context) {
        final Optional<PermissionSubject> subject = permissionSubject(tool, input, context);
        if (subject.isPresent()) {
            return " (" + subject.get().describe() + ")";
        }

        final CustomToolPermissionRule rule = customRule(tool);
        if (rule != null) {
            final String detail = rule.buildErrorDetail(input, context);
            if (detail != null && !detail.isEmpty()) {
                return detail;
            }
        }
        return "";
    }

    private String deniedMessage(String toolName, String detail, List<AllowedTool> allowedTools) {
        return "Tool '" + toolName + "' not allowed" + detail + ". Allowed tools: "
                + AllowedTool.formatList(allowedTools);
    }

    /**
     * Finds all allowed tools that match the given tool name.
     *
     * <p>
     * This utility method filters the allowed tools list to find entries that match the specified tool name. This is
     * useful for permission validation implementations that need to check if a tool name exists in the allowed list and
     * retrieve any associated patterns.
     *
     * <h3>Usage Example</h3>
     *
     * <pre>
     * {
     *     &#64;code
     *     List<AllowedTool> allowedTools = List.of(AllowedTool.parse("Bash(git:*)"), AllowedTool.parse("Read"));
     *
     *     List<AllowedTool> bashTools = validator.findMatchingTools("Bash", allowedTools);
     *     // bashTools contains: [AllowedTool("Bash", "git:*")]
     *
     *     List<AllowedTool> readTools = validator.findMatchingTools("Read", allowedTools);
     *     // readTools contains: [AllowedTool("Read")]
     *
     *     List<AllowedTool> editTools = validator.findMatchingTools("Edit", allowedTools);
     *     // editTools is empty
     * }
     * </pre>
     *
     * @param toolName
     *            Name of the tool to find (must not be null)
     * @param allowedTools
     *            List of allowed tools to search (must not be null)
     * @return List of allowed tools that match the tool name (never null, may be empty)
     * @throws NullPointerException
     *             if toolName or allowedTools is null
     */
    public List<AllowedTool> findMatchingTools(String toolName, List<AllowedTool> allowedTools) {
        if (toolName == null) {
            throw new NullPointerException("Tool name cannot be null");
        }
        if (allowedTools == null) {
            throw new NullPointerException("Allowed tools cannot be null");
        }
        return allowedTools.stream().filter(at -> at.getToolName().equals(toolName)).toList();
    }

}
