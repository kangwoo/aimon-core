package at.aimon.core.tools.workflow;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.interrupt.CancellationSignal;
import at.aimon.core.agent.interrupt.NoopCancellationSignal;
import at.aimon.core.agent.tool.InterruptToolKeys;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolContextEnricher;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.agent.tool.exception.ToolExecutionException;
import at.aimon.core.agent.tool.generic.GenericTool;
import at.aimon.core.base.Principal;
import at.aimon.core.base.UserLocale;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.subagent.Subagent;
import at.aimon.core.subagent.SubagentExecutionEnvironment;
import at.aimon.core.subagent.SubagentExecutionManager;
import at.aimon.core.subagent.SubagentRegistry;
import at.aimon.core.tools.CallerAllowedTools;
import at.aimon.core.tools.ExecutionEnvironmentAccess;
import at.aimon.core.tools.HookRegistryAccess;
import at.aimon.core.tools.InvokingSessionAccess;
import at.aimon.core.tools.ToolContextKeys;
import at.aimon.core.workflow.AgentStepResult;
import at.aimon.core.workflow.AgentTask;
import at.aimon.core.workflow.JudgedResult;
import at.aimon.core.workflow.RunId;
import at.aimon.core.workflow.Verdict;
import at.aimon.core.workflow.WorkflowPatterns;
import at.aimon.core.workflow.WorkflowRunner;
import at.aimon.core.workflow.WorkflowRunners;
import at.aimon.core.workflow.WorkflowScript;

/**
 * Runs a built-in multi-perspective workflow: fans the given prompt out to several perspective sub-agents in
 * parallel, then (optionally) synthesizes their analyses into one answer.
 *
 * <p>
 * This is the built-in LLM-facing consumer of the workflow subsystem: the workflow is a fixed Java
 * {@link WorkflowScript} rather than a caller-supplied script. Each perspective is an inline
 * {@link Subagent} whose system prompt scopes it to one angle; the sub-agents run through the same
 * {@link SubagentExecutionManager} (and therefore the same LLM) as the main agent's tools.
 *
 * <p>
 * <b>Placing the built-in steps.</b> Each step's subagent is built here, not taken from the registry, so it has no
 * definition of its own for an execution environment provider to read. To give one role's steps
 * {@linkplain at.aimon.core.base.DefinitionAttributes attributes} (a {@code sandbox.slot}, say), register a subagent
 * under that role's lookup name — {@value #ROLE_PERSPECTIVE}, {@value #ROLE_SYNTHESIZER}, {@value #ROLE_CANDIDATE},
 * {@value #ROLE_JUDGE} or {@value #ROLE_SKEPTIC}, one per role and not per angle. Only that definition's attributes
 * are copied, once per role at the start of each run (so every step of one role in a run — the perspectives of one
 * fan-out, say — gets the same attributes even if the registry is reloaded mid-run); its prompt, tools and model are
 * ignored by this tool, and the step names ({@code workflow:judge} …) do not change. Note that such a definition is
 * also an ordinary registered subagent: the model sees it and can call it through {@code Task}, where — and only
 * where — its own prompt is used.
 *
 * <p>
 * <b>A failing registry does not fail the run.</b> If the role lookup throws, a WARN is logged and that role's steps
 * run with no attributes — that is, wherever the execution environment provider places a step it has no hint for
 * (default placement). The built-in steps are fixed code, not model-authored, and the attributes are an optional
 * placement hint, so the user's workflow is not failed for them. This deliberately differs from the GraalJS workflow
 * frontend, where a registry failure while resolving an {@code agentType}'s attributes fails the script with a
 * {@code JsScriptException}: there the attributes come from a definition the script named explicitly, and silently
 * dropping them could run the step outside the placement the operator pinned for that definition.
 */
public class WorkflowTool extends GenericTool<WorkflowInput, String> {

    /** The tool name exposed to the LLM. */
    public static final String TOOL_NAME = "Workflow";

    private static final Logger log = LoggerFactory.getLogger(WorkflowTool.class);

    /** Deterministic sub-agent name prefix, so behaviors/tests can key on a stable name. */
    private static final String PERSPECTIVE_PREFIX = "workflow:perspective:";
    private static final String CANDIDATE_PREFIX = "workflow:candidate:";
    private static final String SYNTHESIZER_NAME = "workflow:synthesizer";
    private static final String JUDGE_NAME = "workflow:judge";
    private static final String SKEPTIC_NAME = "workflow:skeptic";

    /**
     * Registry names looked up for each built-in role's attributes (see the class description). One per role: angles
     * are free text from the model, so they never become part of a lookup name.
     */
    static final String ROLE_PERSPECTIVE = "workflow-perspective";
    static final String ROLE_SYNTHESIZER = "workflow-synthesizer";
    static final String ROLE_CANDIDATE = "workflow-candidate";
    static final String ROLE_JUDGE = "workflow-judge";
    static final String ROLE_SKEPTIC = "workflow-skeptic";

    private static final List<String> DEFAULT_PERSPECTIVES = List.of("technical", "risk", "user_impact");

    /**
     * The built-in workflow strategies exposed to the LLM (quality-pattern consumers), and the two run modes.
     *
     * <p>
     * Package-private rather than private so {@link WorkflowInput} can declare the same values as its allowed sets —
     * these constants are the one place the vocabulary is written down, and both the schema shown to the model and the
     * dispatch below read from it.
     */
    static final String STRATEGY_PERSPECTIVES = "perspectives";
    static final String STRATEGY_JUDGE_PANEL = "judge_panel";
    static final String STRATEGY_ADVERSARIAL = "adversarial_verify";
    static final String MODE_FOREGROUND = "foreground";
    static final String MODE_BACKGROUND = "background";

    /**
     * Judges per candidate for {@link #STRATEGY_JUDGE_PANEL}, and skeptics/quorum for {@link #STRATEGY_ADVERSARIAL}.
     */
    private static final int JUDGE_PANEL_SIZE = 2;
    private static final int ADVERSARIAL_SKEPTICS = 3;
    private static final int ADVERSARIAL_QUORUM = 2;

    /** Structured verdict schemas the quality helpers request from the judge/skeptic sub-agents. */
    private static final Map<String, Object> SCORE_SCHEMA = Map.of("type", "object", "properties",
            Map.of("score", Map.of("type", "number")), "required", List.of("score"));
    private static final Map<String, Object> REFUTE_SCHEMA = Map.of("type", "object", "properties",
            Map.of("refuted", Map.of("type", "boolean"), "reason", Map.of("type", "string")), "required",
            List.of("refuted"));

    private final LlmModel defaultModel;
    private final SubagentRegistry subagentRegistry;
    private final ToolRegistry toolRegistry;
    private final HookRegistry hookRegistry;
    private final UserLocale userLocale;
    private final SubagentExecutionManager subagentExecutionManager;
    private final List<ToolContextEnricher> toolContextEnrichers;
    private final WorkflowRunner backgroundRunner;

    /**
     * Creates the tool without background-run support (background mode returns an error). Delegates to the eight-arg
     * constructor with a {@code null} runner.
     *
     * @param defaultModel
     *            the default model for sub-agents (must not be null)
     * @param subagentRegistry
     *            the subagent registry forwarded to the execution environment, and looked up for the built-in roles'
     *            attributes (must not be null)
     * @param toolRegistry
     *            the tool registry forwarded to the execution environment (must not be null)
     * @param hookRegistry
     *            the hook registry forwarded to the execution environment (must not be null)
     * @param userLocale
     *            the user locale forwarded to sub-agents (must not be null)
     * @param subagentExecutionManager
     *            the borrowed subagent execution manager (must not be null)
     * @param toolContextEnrichers
     *            the tool-context enrichers forwarded to sub-agent tools (nullable; treated as empty)
     */
    public WorkflowTool(LlmModel defaultModel, SubagentRegistry subagentRegistry, ToolRegistry toolRegistry,
            HookRegistry hookRegistry, UserLocale userLocale, SubagentExecutionManager subagentExecutionManager,
            List<ToolContextEnricher> toolContextEnrichers) {
        this(defaultModel, subagentRegistry, toolRegistry, hookRegistry, userLocale, subagentExecutionManager,
                toolContextEnrichers, null);
    }

    /**
     * Creates the tool, optionally wired with a long-lived {@link WorkflowRunner} for background runs.
     *
     * @param defaultModel
     *            the default model for sub-agents (must not be null)
     * @param subagentRegistry
     *            the subagent registry forwarded to the execution environment, and looked up for the built-in roles'
     *            attributes (must not be null)
     * @param toolRegistry
     *            the tool registry forwarded to the execution environment (must not be null)
     * @param hookRegistry
     *            the hook registry forwarded to the execution environment (must not be null)
     * @param userLocale
     *            the user locale forwarded to sub-agents (must not be null)
     * @param subagentExecutionManager
     *            the borrowed subagent execution manager (must not be null)
     * @param toolContextEnrichers
     *            the tool-context enrichers forwarded to sub-agent tools (nullable; treated as empty)
     * @param backgroundRunner
     *            the agent-scoped runner used for background mode, owned and closed by the {@code AgentRuntime} that
     *            supplied it — this tool must not close it (nullable; when null, background mode returns an error)
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public WorkflowTool(LlmModel defaultModel, SubagentRegistry subagentRegistry, ToolRegistry toolRegistry,
            HookRegistry hookRegistry, UserLocale userLocale, SubagentExecutionManager subagentExecutionManager,
            List<ToolContextEnricher> toolContextEnrichers, WorkflowRunner backgroundRunner) {
        super(TOOL_NAME,
                "Run a workflow that fans several sub-agents out on a prompt in parallel, choosing a strategy: "
                        + "'perspectives' (default) fans out to several angle sub-agents and synthesizes them; "
                        + "'judge_panel' generates several candidate answers, scores them with a judge panel, and "
                        + "returns the best synthesized answer; 'adversarial_verify' has several skeptics try to "
                        + "refute a claim and returns a survive/refute verdict. Use for open questions, decisions that "
                        + "benefit from independent angles, choosing among candidates, or fact-checking a claim.",
                WorkflowInput.class);
        this.defaultModel = Objects.requireNonNull(defaultModel, "defaultModel must not be null");
        this.subagentRegistry = Objects.requireNonNull(subagentRegistry, "subagentRegistry must not be null");
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry must not be null");
        this.hookRegistry = Objects.requireNonNull(hookRegistry, "hookRegistry must not be null");
        this.userLocale = Objects.requireNonNull(userLocale, "userLocale must not be null");
        this.subagentExecutionManager = Objects.requireNonNull(subagentExecutionManager,
                "subagentExecutionManager must not be null");
        this.toolContextEnrichers = toolContextEnrichers != null ? List.copyOf(toolContextEnrichers) : List.of();
        this.backgroundRunner = backgroundRunner; // nullable
    }

    /**
     * Runs the chosen workflow, in the foreground or as a background run.
     *
     * <p>
     * The two closed sets that used to be re-checked here — {@code strategy} and {@code mode} — are enforced by
     * binding now, from the same declaration the model was shown. What is left are the checks a schema cannot express:
     * a prompt that is present but blank, a perspective list that parses to nothing, and background mode asked for on
     * an agent that has no runner.
     *
     * @param input
     *            the bound parameters (never null)
     * @param context
     *            the execution context, read for the agent runtime and the parent execution's attribution (never null)
     * @return the workflow's answer, or the acknowledgement of a started background run
     * @throws ToolExecutionException
     *             if the request is unusable or the workflow fails
     */
    @Override
    protected String doExecute(WorkflowInput input, ToolContext context) {
        try {
            final String prompt = input.prompt();
            if (prompt.isBlank()) {
                throw new ToolExecutionException("prompt cannot be blank");
            }
            final List<String> perspectives = parsePerspectives(input.perspectives());
            if (perspectives.isEmpty()) {
                throw new ToolExecutionException("perspectives cannot be empty");
            }
            final boolean synthesize = input.synthesize() == null || input.synthesize();
            final String strategy = input.strategy() == null ? STRATEGY_PERSPECTIVES : input.strategy();

            if (MODE_BACKGROUND.equals(input.mode())) {
                return runInBackground(prompt, perspectives, synthesize, strategy, context);
            }

            final AgentRuntimeId agentRuntimeId = context.get(ToolContextKeys.AGENT_RUNTIME_ID).orElse(null);
            if (agentRuntimeId == null) {
                throw new ToolExecutionException("Agent runtime ID not found in tool context; " + TOOL_NAME
                        + " must run within an " + "agent runtime.");
            }

            final SubagentExecutionEnvironment env = buildEnvironment(agentRuntimeId, context);
            log.debug("Workflow: strategy={} prompt='{}' perspectives={} synthesize={}", strategy, prompt, perspectives,
                    synthesize);
            // The per-call foreground runner owns a lazily-created fan-out pool; close it once the synchronous run()
            // returns so a long-lived process does not leak a worker pool per invocation.
            try (WorkflowRunner runner = WorkflowRunners.create(subagentExecutionManager, env)) {
                return runner.run(script(prompt, perspectives, synthesize, strategy));
            }

        } catch (ToolExecutionException e) {
            throw e;
        } catch (Exception e) {
            log.error("Workflow failed: {}", e.getMessage(), e);
            throw new ToolExecutionException("Workflow failed: " + e.getMessage(), e);
        }
    }

    /**
     * Wraps the workflow's answer.
     *
     * @param output
     *            the answer or the background-run acknowledgement
     * @return a success result carrying it
     */
    @Override
    protected ToolResult render(String output) {
        return ToolResult.success(output);
    }

    /** Dispatches a fire-and-forget run and returns the acknowledgement the model reads. */
    private String runInBackground(String prompt, List<String> perspectives, boolean synthesize, String strategy,
            ToolContext context) {
        if (backgroundRunner == null) {
            throw new ToolExecutionException(
                    "Background mode is not available: no workflow runner is configured for this agent.");
        }
        // The run below dispatches against the runtime's registry, so the hooks of a skill this call runs inside do
        // not follow it. A guard that would be silently off is refused; a hook that only observes is not.
        final List<String> guardSkills = HookRegistryAccess.activeSkillGuards(context);
        if (!guardSkills.isEmpty()) {
            throw new ToolExecutionException(HookRegistryAccess.backgroundRefusal(guardSkills));
        }
        final List<String> hookSkills = HookRegistryAccess.activeSkillHooks(context);
        if (!hookSkills.isEmpty()) {
            log.warn("Workflow (background): the hooks of skill(s) {} do not fire for this run's subagents",
                    hookSkills);
        }
        // Fire-and-forget on the shared runner's own base environment: a background run does NOT inherit the
        // invoking execution's agent runtime id, principal, or trace attribution. The run id is derived from
        // the full request (prompt + perspectives + synthesize) so distinct requests get distinct runs, while
        // an identical request that is still in flight is joined idempotently rather than duplicated.
        final RunId runId = RunId.from("workflow", runDiscriminator(prompt, perspectives, synthesize, strategy));
        backgroundRunner.runInBackground(script(prompt, perspectives, synthesize, strategy), runId);
        log.debug("Workflow (background): runId='{}' strategy={} perspectives={} synthesize={}", runId.value(),
                strategy, perspectives, synthesize);
        return "Started background workflow run '" + runId.value() + "' over " + perspectives.size()
                + " perspective(s). Track it with the /runs command (or /runs status " + runId.value() + ").";
    }

    /** Dispatches to the workflow for the chosen strategy. Kept small so control flow stays obvious. */
    private WorkflowScript<String> script(String prompt, List<String> perspectives, boolean synthesize,
            String strategy) {
        switch (strategy) {
            case STRATEGY_JUDGE_PANEL :
                return judgePanelScript(prompt, perspectives);
            case STRATEGY_ADVERSARIAL :
                return adversarialScript(prompt);
            default :
                return perspectivesScript(prompt, perspectives, synthesize);
        }
    }

    /** The default multi-perspective workflow: fan out to angle sub-agents, then optionally synthesize. */
    private WorkflowScript<String> perspectivesScript(String prompt, List<String> perspectives, boolean synthesize) {
        return ctx -> {
            // Resolved once per run, before the fan-out: every perspective gets the same attributes even if the
            // registry changes while the thunks run on worker threads.
            final Map<String, String> perspectiveAttributes = roleAttributes(ROLE_PERSPECTIVE);
            final Map<String, String> synthesizerAttributes = roleAttributes(ROLE_SYNTHESIZER);
            ctx.phase("Perspectives");
            final List<Supplier<AgentStepResult>> thunks = new ArrayList<>(perspectives.size());
            for (final String perspective : perspectives) {
                final Subagent subagent = perspectiveSubagent(perspective, perspectiveAttributes);
                thunks.add(() -> ctx.agent(subagent, prompt));
            }
            final List<AgentStepResult> analyses = ctx.parallel(thunks);
            final String combined = combine(perspectives, analyses);
            if (!synthesize) {
                return combined;
            }
            ctx.phase("Synthesize");
            return ctx.agent(synthesizerSubagent(synthesizerAttributes), combined).text();
        };
    }

    /**
     * Judge-panel workflow (design §6.4): generate candidate answers, score each with a panel, synthesize the best.
     */
    private WorkflowScript<String> judgePanelScript(String prompt, List<String> perspectives) {
        return ctx -> {
            final Map<String, String> candidateAttributes = roleAttributes(ROLE_CANDIDATE);
            final Subagent judge = judgeSubagent(roleAttributes(ROLE_JUDGE));
            final Subagent synthesizer = synthesizerSubagent(roleAttributes(ROLE_SYNTHESIZER));
            ctx.phase("Candidates");
            final List<AgentTask> attempts = new ArrayList<>(perspectives.size());
            for (final String perspective : perspectives) {
                attempts.add(AgentTask.of(candidateSubagent(perspective, candidateAttributes), prompt));
            }
            ctx.phase("Judge");
            final JudgedResult judged = WorkflowPatterns.judgePanel(ctx, attempts, judge, JUDGE_PANEL_SIZE, synthesizer,
                    SCORE_SCHEMA);
            return renderJudged(judged, perspectives);
        };
    }

    /** Adversarial-verify workflow (design §6.4): several skeptics try to refute the prompt as a claim. */
    private WorkflowScript<String> adversarialScript(String prompt) {
        return ctx -> {
            ctx.phase("Adversarial verify");
            final Verdict verdict = WorkflowPatterns.adversarialVerify(ctx, prompt,
                    skepticSubagent(roleAttributes(ROLE_SKEPTIC)), ADVERSARIAL_SKEPTICS, ADVERSARIAL_QUORUM,
                    REFUTE_SCHEMA);
            return renderVerdict(verdict);
        };
    }

    /** Renders a judge-panel result: the synthesized answer (or best candidate) plus a per-candidate score line. */
    private static String renderJudged(JudgedResult judged, List<String> perspectives) {
        final String body = judged.synthesis().map(AgentStepResult::text)
                .or(() -> judged.best().map(AgentStepResult::text)).orElse("(no candidate produced a usable answer)");
        final StringBuilder sb = new StringBuilder(body);
        final List<Double> scores = judged.scores();
        if (!scores.isEmpty()) {
            sb.append("\n\n---\nCandidate scores: ");
            for (int i = 0; i < scores.size(); i++) {
                final String label = i < perspectives.size() ? perspectives.get(i) : "candidate " + i;
                final Double score = scores.get(i);
                sb.append(label).append('=').append(score.isNaN() ? "n/a" : score);
                if (i < scores.size() - 1) {
                    sb.append(", ");
                }
            }
            if (judged.bestIndex() >= 0 && judged.bestIndex() < perspectives.size()) {
                sb.append(" (best: ").append(perspectives.get(judged.bestIndex())).append(')');
            }
        }
        return sb.toString();
    }

    /** Renders an adversarial verdict into a short, model-readable summary. */
    private static String renderVerdict(Verdict verdict) {
        final String outcome;
        if (verdict.isInconclusive()) {
            outcome = "INCONCLUSIVE (too few valid votes)";
        } else if (verdict.isSurvived()) {
            outcome = "SURVIVED (not refuted by a majority)";
        } else {
            outcome = "REFUTED (refuted by a majority)";
        }
        return "Adversarial verdict: " + outcome + ".\n" + verdict.getTotal() + " skeptics — "
                + verdict.getRefutations() + " refuted, " + verdict.getValidations() + " validated, "
                + (verdict.getTotal() - verdict.getValidVotes()) + " abstained (quorum " + verdict.getQuorum() + ").";
    }

    private static Subagent perspectiveSubagent(String perspective, Map<String, String> attributes) {
        return Subagent.builder().name(PERSPECTIVE_PREFIX + perspective)
                .systemPrompt("You analyze the user's request strictly from the \"" + perspective + "\" perspective. "
                        + "Give a focused, specific analysis from that angle only. Be concise.")
                .attributes(attributes).build();
    }

    private static Subagent synthesizerSubagent(Map<String, String> attributes) {
        return Subagent.builder().name(SYNTHESIZER_NAME)
                .systemPrompt("You are given several labeled perspective analyses of a request. Produce one coherent, "
                        + "non-redundant synthesis that integrates them. Be concise.")
                .attributes(attributes).build();
    }

    private static Subagent candidateSubagent(String angle, Map<String, String> attributes) {
        return Subagent.builder().name(CANDIDATE_PREFIX + angle)
                .systemPrompt("You produce a complete, standalone candidate answer to the user's request, "
                        + "emphasizing the \"" + angle + "\" angle. Be concrete and self-contained.")
                .attributes(attributes).build();
    }

    private static Subagent judgeSubagent(Map<String, String> attributes) {
        return Subagent.builder().name(JUDGE_NAME)
                .systemPrompt("You are a strict evaluator. Score the given candidate answer for quality, correctness, "
                        + "and completeness. Respond with the requested JSON only.")
                .attributes(attributes).build();
    }

    private static Subagent skepticSubagent(Map<String, String> attributes) {
        return Subagent.builder().name(SKEPTIC_NAME)
                .systemPrompt("You are a rigorous skeptic. Independently try to REFUTE the given claim, and report "
                        + "whether it is refuted. Respond with the requested JSON only.")
                .attributes(attributes).build();
    }

    /**
     * The attributes of the subagent registered under a built-in role's lookup name, or none. Called once per role at
     * the start of a run, on the thread that runs the script — never from inside a fan-out thunk.
     *
     * <p>
     * A registry that throws is logged at WARN and treated as holding nothing, so that role's steps run with no
     * attributes and are placed by default: an optional placement hint must not fail the user's workflow. This is the
     * opposite of the GraalJS frontend, which fails the script when the registry lookup for an {@code agentType}
     * throws (see the class description for why the two differ).
     */
    private Map<String, String> roleAttributes(String role) {
        try {
            return subagentRegistry.getSubagent(role).map(s -> s.getMetadata().getAttributes()).orElse(Map.of());
        } catch (RuntimeException e) {
            log.warn("Workflow: attribute lookup for role '{}' failed; running it without attributes", role, e);
            return Map.of();
        }
    }

    /** Joins the per-perspective results into a single labeled document, null-safe on isolated failures. */
    private static String combine(List<String> perspectives, List<AgentStepResult> analyses) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < perspectives.size(); i++) {
            final AgentStepResult result = i < analyses.size() ? analyses.get(i) : null;
            sb.append("## Perspective: ").append(perspectives.get(i)).append('\n')
                    .append(result == null ? "(no result)" : result.text()).append("\n\n");
        }
        return sb.toString().strip();
    }

    private static List<String> parsePerspectives(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_PERSPECTIVES;
        }
        return Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /**
     * Derives a stable background-run discriminator from the full request (strategy + prompt + perspectives +
     * synthesize) using a 64-bit SHA-256 prefix. Distinct requests get distinct run ids; an identical request maps to
     * the same id so a still-in-flight duplicate is joined idempotently rather than re-dispatched.
     */
    private static String runDiscriminator(String prompt, List<String> perspectives, boolean synthesize,
            String strategy) {
        final String canonical = strategy + '\n' + prompt + '\n' + String.join(",", perspectives) + '\n' + synthesize;
        try {
            final byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            final StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) { // 8 bytes -> 16 hex chars
                sb.append(Character.forDigit((hash[i] >> 4) & 0xF, 16)).append(Character.forDigit(hash[i] & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed on every conformant JVM; fall back to an identity hash of the same canonical form.
            return Integer.toHexString(canonical.hashCode());
        }
    }

    private SubagentExecutionEnvironment buildEnvironment(AgentRuntimeId agentRuntimeId, ToolContext context) {
        final Map<String, Object> executionAttributes = context.get(ToolContextKeys.EXECUTION_ATTRIBUTES_KEY)
                .orElse(Map.of());
        final LlmCallMetadata parentMetadata = context.get(ToolContextKeys.LLM_CALL_METADATA_KEY)
                .orElse(LlmCallMetadata.empty());
        final CancellationSignal parentSignal = context.get(InterruptToolKeys.CANCELLATION_SIGNAL)
                .orElse(NoopCancellationSignal.INSTANCE);
        final Principal principal = context.get(ToolContextKeys.PRINCIPAL).orElse(null);

        return SubagentExecutionEnvironment.builder().agentRuntimeId(agentRuntimeId).subagentRegistry(subagentRegistry)
                .toolRegistry(toolRegistry)
                // The caller's registry first: inside a forked skill it carries the skill's hooks, and the workflow's
                // subagents must stay under them.
                .hookRegistry(HookRegistryAccess.of(context).orElse(hookRegistry)).userLocale(userLocale)
                .defaultModel(defaultModel).executionAttributes(executionAttributes)
                .parentLlmCallMetadata(parentMetadata).cancellationSignal(parentSignal).principal(principal)
                .toolContextEnrichers(toolContextEnrichers).callerAllowedTools(CallerAllowedTools.of(context))
                .invokingSessionId(InvokingSessionAccess.idToPropagate(context).orElse(null))
                // The fork resolves its own environment from the spawning runtime's provider, with this execution's
                // environment as its parent (execution-environment design §5.2).
                .executionEnvironment(ExecutionEnvironmentAccess.of(context).orElse(null))
                .executionEnvironmentProvider(ExecutionEnvironmentAccess.providerOf(context).orElse(null)).build();
    }
}
