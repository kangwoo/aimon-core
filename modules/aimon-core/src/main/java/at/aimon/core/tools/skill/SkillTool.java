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

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.Constants;
import at.aimon.core.agent.ExecutionId;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.ToolCategories;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.base.Principal;
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
import at.aimon.core.tools.InvokingSessionAccess;
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
            // fork-mode) the spawned subagent's lifetime — so hooks registered here observe tool calls made by the
            // forked agent. For inline mode the scope only covers rendering, which is documented behaviour.
            try (SkillHookScope ignored = hookActivator.activate(skill)) {
                // Render instructions through the configured renderer (no-op by default)
                final String renderedInstructions;
                try {
                    renderedInstructions = renderer.render(skill, args, buildRenderContext(skill, context));
                } catch (RuntimeException e) {
                    log.error("Failed to render skill '{}': {}", skill.getName(), e.getMessage(), e);
                    return ToolResult.error("Failed to render skill: " + e.getMessage());
                }

                // Branch on execution mode: FORK delegates to a subagent; INLINE keeps the historical
                // inject-into-context behaviour. The rendered body is reused verbatim in both paths so
                // $ARGUMENTS / $1..$9 substitution stays consistent regardless of how the skill is consumed.
                if (skill.getMetadata().getExecutionMode() == ExecutionMode.FORK) {
                    final SkillForkOutcome outcome = forkExecutor.fork(skill, renderedInstructions, context);
                    if (outcome.isSuccess()) {
                        return ToolResult.success(formatForkResult(skill, outcome.getFinalAnswer().orElse("")));
                    }
                    return ToolResult.error(String.format("Skill fork failed for '%s': %s", skill.getName(),
                            outcome.getErrorMessage().orElse("(no message)")));
                }

                // Format result with skill information (inline mode)
                final String formattedResult = formatSkillResult(skill, renderedInstructions);

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
     * Builds a {@link RenderContext} for rendering the given skill.
     *
     * <p>
     * Populates the context with the agent runtime identifier, the identity of the run doing the rendering, principal,
     * and skill base directory derived from the active {@link ToolContext} and the resource paths registered on the
     * skill. Missing values are simply omitted; the renderer is expected to handle absent fields gracefully.
     *
     * <p>
     * The three ids address different lifetimes. The runtime id is <b>agent-scoped</b>: every session served by this
     * agent renders the same value, so a skill body must not treat {@code ${AIMON_AGENT_RUNTIME_ID}} as a per-run
     * uniqueness discriminator. The other two are the exclusive pair that names the run itself —
     * {@code ${AIMON_SESSION_ID}} when the run is a session's turn, {@code ${AIMON_EXECUTION_ID}} when it is not
     * (a skill invoked from inside a subagent fork or a scheduled routine). Both are copied straight across rather
     * than merged: the session key is empty in a fork precisely so a body cannot mistake a run identity for a
     * session, and collapsing them here would undo that.
     *
     * @param skill
     *            The skill being rendered (must not be null)
     * @param context
     *            The tool context (must not be null)
     * @return A render context (never null)
     */
    private RenderContext buildRenderContext(Skill skill, ToolContext context) {
        Objects.requireNonNull(skill, "Skill cannot be null");
        Objects.requireNonNull(context, "Context cannot be null");

        final RenderContext.Builder builder = RenderContext.builder();
        context.get(ToolContextKeys.AGENT_RUNTIME_ID).map(AgentRuntimeId::value).ifPresent(builder::agentRuntimeId);
        context.get(ToolContextKeys.SESSION_ID).map(SessionId::value).ifPresent(builder::sessionId);
        context.get(ToolContextKeys.EXECUTION_ID).map(ExecutionId::value).ifPresent(builder::executionId);
        context.get(ToolContextKeys.PRINCIPAL).ifPresent((Principal p) -> builder.principal(p));
        // Prefer the authoritative base directory carried by the skill; fall back to deriving it from a resource path
        // for skills assembled without an explicit base directory (backwards compatibility).
        skill.getBaseDir().or(() -> deriveSkillBaseDir(skill)).ifPresent(builder::skillBaseDir);
        return builder.build();
    }

    /**
     * Derives the skill's base directory from any of its registered resource paths.
     *
     * <p>
     * Used as a fallback only when a skill carries no explicit {@link Skill#getBaseDir() base directory}. Skills that
     * ship with at least one root file, script, reference, or asset can have their base directory inferred by stripping
     * the filename from the resource's full virtual filesystem path. Skills with no resources return empty, in which
     * case downstream consumers (renderer) may emit a warning when the corresponding placeholder is referenced.
     *
     * @param skill
     *            The skill to inspect (must not be null)
     * @return The base directory if derivable, otherwise empty
     */
    private static Optional<String> deriveSkillBaseDir(Skill skill) {
        return firstResourcePath(skill).map(SkillTool::parentPath);
    }

    private static Optional<String> firstResourcePath(Skill skill) {
        return Optional.<String>empty().or(() -> skill.getRootFiles().values().stream().findFirst())
                .or(() -> skill.getScripts().values().stream().findFirst())
                .or(() -> skill.getReferences().values().stream().findFirst())
                .or(() -> skill.getAssets().values().stream().findFirst());
    }

    private static String parentPath(String fullPath) {
        final int slash = fullPath.lastIndexOf('/');
        if (slash <= 0) {
            return "";
        }
        return fullPath.substring(0, slash);
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
     * @return A formatted string representation
     */
    private String formatSkillResult(Skill skill, String renderedInstructions) {
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

        // Include available files if present
        if (!skill.getRootFiles().isEmpty() || !skill.getScripts().isEmpty() || !skill.getReferences().isEmpty()
                || !skill.getAssets().isEmpty() || !skill.getFiles().isEmpty()) {

            output.append("Available Files:").append(Constants.NEWLINE);

            // Root files (same directory as SKILL.md)
            if (!skill.getRootFiles().isEmpty()) {
                output.append("Root:").append(Constants.NEWLINE);
                skill.getRootFiles().forEach((name, path) -> output.append("  - ").append(name).append(" → ")
                        .append(path).append(Constants.NEWLINE));
            }

            // Scripts
            if (!skill.getScripts().isEmpty()) {
                output.append("Scripts:").append(Constants.NEWLINE);
                skill.getScripts().forEach((name, path) -> output.append("  - ").append(name).append(" → ").append(path)
                        .append(Constants.NEWLINE));
            }

            // References
            if (!skill.getReferences().isEmpty()) {
                output.append("References:").append(Constants.NEWLINE);
                skill.getReferences().forEach((name, path) -> output.append("  - ").append(name).append(" → ")
                        .append(path).append(Constants.NEWLINE));
            }

            // Assets
            if (!skill.getAssets().isEmpty()) {
                output.append("Assets:").append(Constants.NEWLINE);
                skill.getAssets().forEach((name, path) -> output.append("  - ").append(name).append(" → ").append(path)
                        .append(Constants.NEWLINE));
            }

            // Other files (arbitrary sub-directories such as templates/) not covered by the conventional categories.
            appendOtherFiles(output, skill);

            output.append(Constants.NEWLINE);
        }

        output.append("Instructions:").append(Constants.NEWLINE);
        output.append(renderedInstructions).append(Constants.NEWLINE);

        return output.toString();
    }

    /**
     * Appends the bundled files that are not already covered by the conventional categories (root files, scripts,
     * references, assets). This surfaces files in arbitrary sub-directories — such as {@code templates/} — so the model
     * learns their resolvable VFS paths.
     *
     * @param output
     *            The buffer to append to
     * @param skill
     *            The skill being formatted
     */
    private static void appendOtherFiles(StringBuilder output, Skill skill) {
        if (skill.getFiles().isEmpty()) {
            return;
        }

        final Set<String> categorized = new HashSet<>();
        categorized.addAll(skill.getRootFiles().values());
        categorized.addAll(skill.getScripts().values());
        categorized.addAll(skill.getReferences().values());
        categorized.addAll(skill.getAssets().values());

        final Map<String, String> others = new TreeMap<>();
        skill.getFiles().forEach((relativePath, path) -> {
            if (!categorized.contains(path)) {
                others.put(relativePath, path);
            }
        });

        if (others.isEmpty()) {
            return;
        }

        output.append("Other Files:").append(Constants.NEWLINE);
        others.forEach((name, path) -> output.append("  - ").append(name).append(" → ").append(path)
                .append(Constants.NEWLINE));
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
