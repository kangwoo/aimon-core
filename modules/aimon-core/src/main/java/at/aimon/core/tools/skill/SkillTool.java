package at.aimon.core.tools.skill;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.Constants;
import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.ToolCategories;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.environment.StagedResource;
import at.aimon.core.llm.DynamicToolDefinitionProvider;
import at.aimon.core.skill.ExecutionMode;
import at.aimon.core.skill.Skill;
import at.aimon.core.skill.SkillRegistry;
import at.aimon.core.skill.exception.SkillNotFoundException;
import at.aimon.core.skill.fork.NoOpSkillForkExecutor;
import at.aimon.core.skill.fork.SkillForkExecutor;
import at.aimon.core.skill.fork.SkillForkOutcome;
import at.aimon.core.skill.hook.NoOpSkillHookActivator;
import at.aimon.core.skill.hook.SkillHookActivator;
import at.aimon.core.skill.hook.SkillHookScope;
import at.aimon.core.skill.policy.AlwaysAllowSkillInvocationPolicy;
import at.aimon.core.skill.policy.SkillInvocationDecision;
import at.aimon.core.skill.policy.SkillInvocationPolicy;
import at.aimon.core.skill.policy.SkillInvocationRequest;
import at.aimon.core.skill.render.NoOpSkillContentRenderer;
import at.aimon.core.skill.render.RenderContext;
import at.aimon.core.skill.render.SkillContentRenderer;
import at.aimon.core.tools.HookRegistryAccess;
import at.aimon.core.tools.InvokingSessionAccess;
import at.aimon.core.tools.SkillRenderContextAccess;
import at.aimon.core.tools.ToolContextKeys;

/**
 * Tool for executing specialized skills within the main conversation.
 *
 * <p>
 * The Skill tools activates Agent Skills (https://agentskills.io/) by injecting their instructions into the
 * conversation context. Skills provide domain-specific capabilities and expert knowledge that extend the agent's core
 * functionality.
 *
 * <p>
 * Features:
 *
 * <ul>
 * <li>Dynamic skill discovery from SkillRegistry
 * <li>Skill instructions injection for skill activation
 * <li>Tool restriction validation
 * <li>Support for namespaced skill names (e.g., "ms-office-suite:pdf")
 * </ul>
 *
 * <p>
 * Thread-safe as long as SkillRegistry is thread-safe.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     SkillRegistry registry = new FileBasedSkillRegistry(repository, parser);
 *     Tool skillTool = new SkillTool(registry);
 *
 *     ToolContext context = ToolContext.empty();
 *
 *     // Activate a skill
 *     Map&lt;String, Object&gt; input = Map.of("skill", "alert-analysis");
 *     ToolResult result = skillTool.execute(input, context);
 *     // Result contains the skill's instructions
 * }
 * </pre>
 */
public class SkillTool extends AbstractTool {

    public static final String TOOL_NAME = "Skill";
    /** Maximum allowed length (in characters) of the {@code args} parameter. */
    public static final int MAX_ARGS_LENGTH = 4096;
    private static final Pattern SKILL_NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9._:-]+$");
    private static final Logger log = LoggerFactory.getLogger(SkillTool.class);

    private final SkillRegistry skillRegistry;
    private final SkillContentRenderer renderer;
    private final SkillForkExecutor forkExecutor;
    private final SkillHookActivator hookActivator;
    private final SkillInvocationPolicy invocationPolicy;

    /**
     * Creates a new SkillTool with a {@link NoOpSkillContentRenderer}, a {@link NoOpSkillForkExecutor}, and a
     * {@link NoOpSkillHookActivator}.
     *
     * <p>
     * Provided for backward compatibility with callers that were constructed before the renderer, fork-executor, and
     * hook-activator abstractions were introduced.
     *
     * @param skillRegistry
     *            The skill registry (must not be null)
     * @throws NullPointerException
     *             if skillRegistry is null
     */
    public SkillTool(SkillRegistry skillRegistry) {
        this(skillRegistry, new NoOpSkillContentRenderer(), new NoOpSkillForkExecutor(), new NoOpSkillHookActivator());
    }

    /**
     * Creates a new SkillTool with the given renderer, a {@link NoOpSkillForkExecutor}, and a
     * {@link NoOpSkillHookActivator}.
     *
     * <p>
     * Provided for backward compatibility with callers that wired SkillTool before the fork-executor and
     * hook-activator abstractions were introduced. Skills declared as {@code execution.mode: fork} will fail at
     * execute time with a clear error.
     *
     * @param skillRegistry
     *            The skill registry (must not be null)
     * @param renderer
     *            The renderer that produces the final instructions (must not be null)
     * @throws NullPointerException
     *             if any argument is null
     */
    public SkillTool(SkillRegistry skillRegistry, SkillContentRenderer renderer) {
        this(skillRegistry, renderer, new NoOpSkillForkExecutor(), new NoOpSkillHookActivator());
    }

    /**
     * Creates a new SkillTool with the given renderer and fork executor, plus a {@link NoOpSkillHookActivator}.
     *
     * <p>
     * Provided for backward compatibility with callers that wired SkillTool before the hook-activator abstraction was
     * introduced.
     *
     * @param skillRegistry
     *            The skill registry (must not be null)
     * @param renderer
     *            The renderer that produces the final instructions injected into the conversation (must not be null)
     * @param forkExecutor
     *            The executor that handles fork-mode skills by delegating to a named subagent (must not be null)
     * @throws NullPointerException
     *             if any argument is null
     */
    public SkillTool(SkillRegistry skillRegistry, SkillContentRenderer renderer, SkillForkExecutor forkExecutor) {
        this(skillRegistry, renderer, forkExecutor, new NoOpSkillHookActivator());
    }

    /**
     * Creates a new SkillTool.
     *
     * <p>
     * This tools uses a dynamic definition provider to reflect the current state of available skills in its
     * description. Each time the LLM requests the tools definition, it will see the latest list of available skills.
     *
     * @param skillRegistry
     *            The skill registry (must not be null)
     * @param renderer
     *            The renderer that produces the final instructions injected into the conversation (must not be null)
     * @param forkExecutor
     *            The executor that handles fork-mode skills by delegating to a named subagent (must not be null)
     * @param hookActivator
     *            The activator that registers per-skill hooks for the duration of each invocation (must not be null;
     *            use {@link NoOpSkillHookActivator} for deployments without per-skill hook scoping)
     * @throws NullPointerException
     *             if any argument is null
     */
    public SkillTool(SkillRegistry skillRegistry, SkillContentRenderer renderer, SkillForkExecutor forkExecutor,
            SkillHookActivator hookActivator) {
        this(skillRegistry, renderer, forkExecutor, hookActivator, AlwaysAllowSkillInvocationPolicy.INSTANCE);
    }

    /**
     * Creates a new SkillTool with an explicit {@link SkillInvocationPolicy} (SK-11).
     *
     * <p>
     * The policy is consulted after the registry lookup succeeds and before any side-effects (per-skill hook
     * activation, rendering, fork). When the policy returns {@link SkillInvocationDecision#DENY} the tool returns an
     * error result without invoking the renderer or fork executor.
     *
     * <p>
     * {@link SkillInvocationDecision#ASK} is treated as {@code DENY} with a distinct message until SK-11.4 wires the
     * pre-flight scan + suspend mechanism into the agent loop. Headless contexts (scheduled tasks, batch agents) will
     * keep that fail-closed behaviour even after SK-11.4 lands, since they cannot host an interactive prompt.
     *
     * @param skillRegistry
     *            The skill registry (must not be null)
     * @param renderer
     *            The renderer (must not be null)
     * @param forkExecutor
     *            The fork executor (must not be null)
     * @param hookActivator
     *            The hook activator (must not be null)
     * @param invocationPolicy
     *            The invocation policy (must not be null; pass
     *            {@link AlwaysAllowSkillInvocationPolicy#INSTANCE} to keep pre-SK-11 behaviour)
     * @throws NullPointerException
     *             if any argument is null
     */
    public SkillTool(SkillRegistry skillRegistry, SkillContentRenderer renderer, SkillForkExecutor forkExecutor,
            SkillHookActivator hookActivator, SkillInvocationPolicy invocationPolicy) {
        super(new DynamicToolDefinitionProvider(TOOL_NAME, ToolCategories.EXECUTION,
                () -> buildDescription(Objects.requireNonNull(skillRegistry, "Skill registry cannot be null")),
                createInputSchema()));
        this.skillRegistry = Objects.requireNonNull(skillRegistry, "Skill registry cannot be null");
        this.renderer = Objects.requireNonNull(renderer, "Renderer cannot be null");
        this.forkExecutor = Objects.requireNonNull(forkExecutor, "Fork executor cannot be null");
        this.hookActivator = Objects.requireNonNull(hookActivator, "Hook activator cannot be null");
        this.invocationPolicy = Objects.requireNonNull(invocationPolicy, "Invocation policy cannot be null");
    }

    /**
     * Builds the tools description including available skills.
     *
     * <p>
     * Only skills with {@code invoke.model = true} (the default) are listed; skills declared as user-only via
     * {@code invoke.model: false} are intentionally hidden from the LLM-facing description.
     *
     * @param registry
     *            The skill registry
     * @return The complete tools description with available skills
     */
    private static String buildDescription(SkillRegistry registry) {
        Objects.requireNonNull(registry, "Skill registry cannot be null");

        final StringBuilder desc = new StringBuilder();
        desc.append("Execute specialized skills within the main conversation. Skills provide domain-specific ");
        desc.append("capabilities and expert knowledge that extend the agent's functionality.")
                .append(Constants.DOUBLE_NEWLINE);

        final List<Skill> skills = registry.getAllSkills().stream().filter(SkillTool::isModelInvocable).toList();
        if (!skills.isEmpty()) {
            desc.append("<available_skills>").append(Constants.NEWLINE);
            for (Skill skill : skills) {
                desc.append("- ").append(skill.getName()).append(": ").append(skill.getMetadata().getDescription())
                        .append(Constants.NEWLINE);
            }
            desc.append("</available_skills>").append(Constants.NEWLINE);
        } else {
            desc.append("(No skills currently available)");
        }

        return desc.toString();
    }

    private static boolean isModelInvocable(Skill skill) {
        return skill.getMetadata().getInvokePolicy().isModelInvocable();
    }

    /**
     * Creates the JSON Schema for skill tools input.
     *
     * @return The input schema map
     */
    private static Map<String, Object> createInputSchema() {
        return Map.of("type", "object", "additionalProperties", false, "properties",
                Map.of("skill", Map.of("type", "string", "description",
                        "The skill name (e.g., 'pdf' or 'ms-office-suite:pdf')", "pattern", "^[a-zA-Z0-9._:-]+$"),
                        "args",
                        Map.of("type", "string", "description",
                                "Optional arguments forwarded to the skill body. "
                                        + "Supports POSIX shell quoting; substituted into $ARGUMENTS, $0..$9 "
                                        + "placeholders or appended at the end if no placeholder is present.")),
                "required", List.of("skill"));
    }

    /**
     * Executes the skill tools to activate a skill.
     *
     * <p>
     * The method performs the following operations:
     *
     * <ol>
     * <li>Validates required parameter (skill) and optional {@code args} length
     * <li>Validates skill name format
     * <li>Looks up the skill in the registry
     * <li>Renders the skill body, substituting placeholders with the supplied {@code args}
     * <li>Formats and returns the skill's instructions
     * </ol>
     *
     * @param input
     *            The input parameters containing the skill name and an optional {@code args} string
     * @param context
     *            The execution context (used to populate the render context with session/principal information)
     * @return A success result with skill instructions if successful, or an error result if the skill cannot be found
     *         or validation fails
     * @throws NullPointerException
     *             if input or context is null
     */
    @Override
    public ToolResult execute(ToolInput input, ToolContext context) {
        Objects.requireNonNull(input, "Input cannot be null");
        Objects.requireNonNull(context, "Context cannot be null");

        try {
            // Validate and extract skill parameter
            final String skillName = input.getRequiredString("skill");

            // Validate skill name format
            if (!SKILL_NAME_PATTERN.matcher(skillName).matches()) {
                return ToolResult.error(String.format("Invalid skill name format: '%s'. "
                        + "Skill name must contain only letters, numbers, dots, underscores, colons, or hyphens.",
                        skillName));
            }

            // Extract optional args parameter and enforce length cap
            final String args = input.getString("args", "");
            if (args.length() > MAX_ARGS_LENGTH) {
                log.warn("Skill '{}' invocation rejected: args length {} exceeds limit {}", skillName, args.length(),
                        MAX_ARGS_LENGTH);
                return ToolResult
                        .error(String.format("args too long (max %d chars, got %d)", MAX_ARGS_LENGTH, args.length()));
            }

            // Look up skill in registry. Skills declared as model-invisible (invoke.model=false) are intentionally
            // surfaced as "not found" rather than "permission denied" — the LLM should not learn that a hidden
            // skill exists by probing names.
            final Optional<Skill> skillOpt = skillRegistry.getSkill(skillName).filter(SkillTool::isModelInvocable);
            if (skillOpt.isEmpty()) {
                return ToolResult.error(String.format("Skill not found: '%s'. Available skills: %s", skillName,
                        String.join(", ", getAvailableSkillNames())));
            }

            final Skill skill = skillOpt.get();

            // SK-11: consult the invocation policy. ASK is treated as DENY with a distinct message until SK-11.4
            // wires up the pre-flight scan + suspend channel; headless contexts keep this fail-closed behaviour.
            final SkillInvocationDecision decision = invocationPolicy.check(SkillInvocationRequest.builder()
                    .skill(skill).args(args).agentRuntimeId(context.get(ToolContextKeys.AGENT_RUNTIME_ID).orElse(null))
                    .sessionId(context.get(ToolContextKeys.SESSION_ID).orElse(null))
                    // The raw read, not idToPropagate: a fork must be told which session it acts for, whereas
                    // the main agent has no invoker and must leave this empty rather than repeat its own id.
                    .invokingSessionId(InvokingSessionAccess.invokerOf(context).orElse(null))
                    .principal(context.get(ToolContextKeys.PRINCIPAL).orElse(null)).build());
            if (decision != SkillInvocationDecision.ALLOW) {
                log.info("Skill invocation rejected by policy: skill={}, decision={}", skill.getName(), decision);
                return ToolResult.error(formatPolicyRejection(skill, decision));
            }

            // Activate any per-skill hooks for the duration of the skill body. The scope spans rendering and (for
            // fork-mode) the spawned subagent's lifetime, and its hooks reach only that fork: they are layered over
            // the registry the fork dispatches against, never registered where this execution or another session
            // would see them. Inline mode has no fork, so there the hooks do not fire.
            try (SkillHookScope hookScope = hookActivator.activate(skill, context)) {
                // Stage the skill into this execution's environment (${AIMON_SKILL_DIR}), then render the
                // instructions through the configured renderer (no-op by default). Any staging failure is reported
                // as one (EE-15) — not only the two types stage()'s contract names. An InvalidPathException would
                // otherwise reach the outer catch below as an IllegalArgumentException and read "Invalid parameter",
                // blaming the model's input for a directory name the workspace refused.
                final RenderContext renderContext;
                try {
                    renderContext = SkillRenderContextAccess.builderFor(skill, context).build();
                } catch (RuntimeException e) {
                    log.warn("Failed to stage skill '{}': {}", skill.getName(), e.getMessage());
                    return ToolResult.error("Failed to stage skill '" + skill.getName() + "': " + e.getMessage());
                }
                final String renderedInstructions;
                try {
                    renderedInstructions = renderer.render(skill, args, renderContext);
                } catch (RuntimeException e) {
                    log.error("Failed to render skill '{}': {}", skill.getName(), e.getMessage(), e);
                    return ToolResult.error("Failed to render skill: " + e.getMessage());
                }

                // Branch on execution mode: FORK delegates to a subagent; INLINE keeps the historical
                // inject-into-context behaviour. The rendered body is reused verbatim in both paths so
                // $ARGUMENTS / $1..$9 substitution stays consistent regardless of how the skill is consumed.
                if (skill.getMetadata().getExecutionMode() == ExecutionMode.FORK) {
                    final ToolContext forkContext = hookScope.hookRegistry()
                            .map(registry -> HookRegistryAccess.withHookRegistry(context, registry)).orElse(context);
                    final SkillForkOutcome outcome = forkExecutor.fork(skill, renderedInstructions, forkContext);
                    if (outcome.isSuccess()) {
                        return ToolResult.success(formatForkResult(skill, outcome.getFinalAnswer().orElse("")));
                    }
                    return ToolResult.error(String.format("Skill fork failed for '%s': %s", skill.getName(),
                            outcome.getErrorMessage().orElse("(no message)")));
                }

                // Format result with skill information (inline mode)
                final String formattedResult = formatSkillResult(skill, renderedInstructions,
                        renderContext.getSkillBaseDir().orElse(null));

                return ToolResult.success(formattedResult);
            }

        } catch (IllegalArgumentException e) {
            return ToolResult.error("Invalid parameter: " + e.getMessage());
        } catch (SkillNotFoundException e) {
            // Use getSkillName(), not getMessage(): the exception's message is already "Skill not found: <name>",
            // so interpolating getMessage() here would produce a doubled "Skill not found: 'Skill not found: ...'".
            return ToolResult.error(String.format("Skill not found: '%s'. Available skills: %s", e.getSkillName(),
                    String.join(", ", getAvailableSkillNames())));
        } catch (Exception e) {
            return ToolResult.error(String.format("Skill activation failed: %s", e.getMessage()));
        }
    }

    private static String formatPolicyRejection(Skill skill, SkillInvocationDecision decision) {
        return switch (decision) {
            case DENY -> String.format("Skill invocation denied by policy: '%s'.", skill.getName());
            case ASK -> String.format(
                    "Skill '%s' requires user approval, but no approval channel is available in this context.",
                    skill.getName());
            default ->
                String.format("Skill invocation rejected by policy: '%s' (decision=%s).", skill.getName(), decision);
        };
    }

    /**
     * Formats the skill result for display.
     *
     * <p>
     * The formatted result includes:
     *
     * <ul>
     * <li>Skill name and description
     * <li>Available files (rootFiles, scripts, references, assets)
     * <li>Instructions (rendered by the configured {@link SkillContentRenderer})
     * <li>Allowed tools (if specified)
     * <li>Additional metadata (if present)
     * </ul>
     *
     * @param skill
     *            The skill to format
     * @param renderedInstructions
     *            The instructions text produced by the renderer
     * @param stagedDir
     *            The directory the skill was staged to (its {@code ${AIMON_SKILL_DIR}}), or null when it was not staged
     * @return A formatted string representation
     */
    private String formatSkillResult(Skill skill, String renderedInstructions, String stagedDir) {
        final StringBuilder output = new StringBuilder();

        output.append("=== Skill Activated ===").append(Constants.NEWLINE);
        output.append("Skill: ").append(skill.getName()).append(Constants.NEWLINE);
        output.append("Description: ").append(skill.getMetadata().getDescription()).append(Constants.DOUBLE_NEWLINE);

        // Include allowed tools if specified
        if (skill.hasToolRestrictions()) {
            output.append("Allowed Tools: ");
            output.append(skill.getMetadata().getAllowedTools().stream().map(Object::toString)
                    .reduce((a, b) -> a + ", " + b).orElse("None"));
            output.append(Constants.DOUBLE_NEWLINE);
        } else {
            output.append("Allowed Tools: No restrictions").append(Constants.DOUBLE_NEWLINE);
        }

        // Include available files if present. Every path is under the staged copy — the directory the execution's
        // shell and file tools can both read (execution-environment design §4.4) — never the repository's own path,
        // which lives in the control store. A file .stageignore excluded was never copied and is not listed.
        final Map<String, String> rootFiles = stagedPaths(skill, stagedDir, skill.getRootFiles(), "");
        final Map<String, String> scripts = stagedPaths(skill, stagedDir, skill.getScripts(), "scripts/");
        final Map<String, String> references = stagedPaths(skill, stagedDir, skill.getReferences(), "references/");
        final Map<String, String> assets = stagedPaths(skill, stagedDir, skill.getAssets(), "assets/");
        final Map<String, String> others = otherFiles(skill, stagedDir);
        if (!rootFiles.isEmpty() || !scripts.isEmpty() || !references.isEmpty() || !assets.isEmpty()
                || !others.isEmpty()) {

            output.append("Available Files:").append(Constants.NEWLINE);
            appendCategory(output, "Root:", rootFiles);
            appendCategory(output, "Scripts:", scripts);
            appendCategory(output, "References:", references);
            appendCategory(output, "Assets:", assets);
            // Other files (arbitrary sub-directories such as templates/) not covered by the conventional categories.
            appendCategory(output, "Other Files:", others);

            output.append(Constants.NEWLINE);
        }

        output.append("Instructions:").append(Constants.NEWLINE);
        output.append(renderedInstructions).append(Constants.NEWLINE);

        return output.toString();
    }

    private static void appendCategory(StringBuilder output, String heading, Map<String, String> entries) {
        if (entries.isEmpty()) {
            return;
        }
        output.append(heading).append(Constants.NEWLINE);
        entries.forEach((name, path) -> output.append("  - ").append(name).append(" → ").append(path)
                .append(Constants.NEWLINE));
    }

    /**
     * Maps one file category onto the staged copy: {@code name → {stagedDir}/{category}{name}}. Without a staged
     * directory the value is the path relative to the skill directory. Files the staged copy does not hold
     * ({@code .stageignore}d, unreadable) are left out.
     */
    private static Map<String, String> stagedPaths(Skill skill, String stagedDir, Map<String, String> category,
            String categoryPrefix) {
        final Map<String, String> result = new TreeMap<>();
        category.keySet().forEach(
                name -> stagedPath(skill, stagedDir, categoryPrefix + name).ifPresent(path -> result.put(name, path)));
        return result;
    }

    /**
     * The bundled files not already covered by the conventional categories (root files, scripts, references, assets)
     * — files in arbitrary sub-directories such as {@code templates/} — keyed by their path relative to the skill
     * directory.
     */
    private static Map<String, String> otherFiles(Skill skill, String stagedDir) {
        final Set<String> categorized = new HashSet<>();
        categorized.addAll(skill.getRootFiles().values());
        categorized.addAll(skill.getScripts().values());
        categorized.addAll(skill.getReferences().values());
        categorized.addAll(skill.getAssets().values());

        final Map<String, String> others = new TreeMap<>();
        skill.getFiles().forEach((relativePath, path) -> {
            if (!categorized.contains(path)) {
                stagedPath(skill, stagedDir, relativePath).ifPresent(staged -> others.put(relativePath, staged));
            }
        });
        return others;
    }

    private static Optional<String> stagedPath(Skill skill, String stagedDir, String relativePath) {
        final Optional<StagedResource> resource = skill.getStagedResource();
        if (resource.isPresent() && !resource.get().getFiles().contains(relativePath)) {
            return Optional.empty();
        }
        return Optional.of(stagedDir == null || stagedDir.isEmpty() ? relativePath : stagedDir + "/" + relativePath);
    }

    /**
     * Formats a fork-mode skill result by labelling the subagent's final answer with the skill name and forking
     * agent. Keeps the parent LLM aware that it received a forked subagent's output rather than direct skill
     * instructions.
     *
     * @param skill
     *            The forked skill
     * @param finalAnswer
     *            The subagent's final answer
     * @return A formatted string representation
     */
    private String formatForkResult(Skill skill, String finalAnswer) {
        final StringBuilder output = new StringBuilder();
        output.append("=== Skill Forked ===").append(Constants.NEWLINE);
        output.append("Skill: ").append(skill.getName()).append(Constants.NEWLINE);
        output.append("Agent: ").append(skill.getMetadata().getForkAgentName()).append(Constants.DOUBLE_NEWLINE);
        output.append("Final Answer:").append(Constants.NEWLINE);
        output.append(finalAnswer).append(Constants.NEWLINE);
        return output.toString();
    }

    /**
     * Gets the list of model-invocable skill names.
     *
     * <p>
     * Used in error messages presented to the LLM, so we exclude skills hidden via {@code invoke.model = false}.
     *
     * @return A list of model-invocable skill names
     */
    private List<String> getAvailableSkillNames() {
        return skillRegistry.getAllSkills().stream().filter(SkillTool::isModelInvocable).map(Skill::getName).toList();
    }
}
