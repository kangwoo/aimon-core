package at.aimon.core.skill.execution.llm;

import static at.aimon.core.agent.tool.execution.ToolExecutionResultConverter.toToolUseResults;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.ExecutionId;
import at.aimon.core.agent.budget.StalledIterationGuard;
import at.aimon.core.agent.budget.TruncatedResponses;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.transcript.TranscriptBuffer;
import at.aimon.core.agent.tool.SideEffectLevel;
import at.aimon.core.agent.tool.Tool;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolExecutionManager;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.agent.tool.exception.ToolPermissionViolationException;
import at.aimon.core.agent.tool.execution.ToolExecutionResult;
import at.aimon.core.agent.tool.permission.AllowedTool;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;
import at.aimon.core.skill.ExecutionMode;
import at.aimon.core.skill.Skill;
import at.aimon.core.skill.execution.SkillExecutionContext;
import at.aimon.core.skill.execution.SkillExecutionMetadata;
import at.aimon.core.skill.execution.SkillExecutionRequest;
import at.aimon.core.skill.execution.SkillExecutionResult;
import at.aimon.core.skill.execution.SkillExecutor;
import at.aimon.core.skill.execution.SkillToolDispatcher;
import at.aimon.core.skill.fork.NoOpSkillForkExecutor;
import at.aimon.core.skill.fork.SkillForkExecutor;
import at.aimon.core.skill.fork.SkillForkOutcome;
import at.aimon.core.skill.render.SkillContentRenderer;
import at.aimon.core.tools.ToolContextKeys;

/**
 * LLM-based implementation of {@link SkillExecutor}.
 *
 * <p>
 * Mirrors {@code at.aimon.core.command.execution.llm.LlmCommandExecutor} but operates on a
 * {@link at.aimon.core.skill.Skill} input. The two stages that {@code LlmCommandExecutor} runs separately —
 * dynamic-token formatting (e.g. {@code !`cmd`}, {@code @file}) and argument interpolation ({@code $1}, {@code
 * $ARGUMENTS}) — are collapsed into a single {@link SkillContentRenderer#render(Skill, String,
 * at.aimon.core.skill.render.RenderContext) renderer.render(...)} call. Otherwise the ReAct loop, permission
 * filtering, and metadata accumulation are functionally identical.
 *
 * <p>
 * Execution flow:
 *
 * <ol>
 * <li>Render the skill body via {@link SkillContentRenderer} (single pass).
 * <li>Build the system prompt with tools restrictions.
 * <li>Create a fresh transcript buffer, labelled with the run's {@link ExecutionId}, and add the user message
 * (rendered body).
 * <li>Filter tools by {@link Skill#hasToolRestrictions() declared restrictions}.
 * <li>Call the LLM. Loop while the response contains tool uses, executing tools and feeding results back.
 * <li>Stop when the loop finishes naturally, {@code max-iterations} is reached, or
 * {@link StalledIterationGuard#MAX_CONSECUTIVE_STALLED_ITERATIONS} iterations in a row had every tool call fail.
 * </ol>
 *
 * <p>
 * <b>A response cut off at {@code max_tokens}.</b> This loop gives the answers both agent executors give
 * ({@link TruncatedResponses}). None of a cut response's tool calls is dispatched, on either path below: each is
 * answered with {@link TruncatedResponses#refusal(ToolUse)}, because a cut call's arguments did not arrive and the
 * response does not say which call was cut. A cut final answer comes back as a success whose text ends with
 * {@link TruncatedResponses#TRUNCATION_MARKER} — a skill result has no completion reason, and that is how a fork-mode
 * skill's cut answer already reads. Both log a WARN naming the skill. The executors' stalled-iteration guard
 * ({@link StalledIterationGuard}) stops this loop too, so a streak of refused or failing calls fails the skill with the
 * guard's stop message instead of running to {@code max-iterations}; nothing else could stop it, because a slash
 * command cannot be interrupted.
 *
 * <p>
 * <b>How tool calls leave this loop.</b> Step 5 does not call the {@link ToolExecutionManager} directly when the caller
 * bound a {@link SkillToolDispatcher} under {@link ToolContextKeys#SKILL_TOOL_DISPATCHER_KEY} — the agent executor does
 * on the user-slash path. The dispatcher runs each call through the same invoker the ReAct loop uses, so the hooks and
 * the side-effect approval gate that stand in front of an agent's tools stand in front of a skill's too. Skipping them
 * was not a decision anyone made; it was what calling the manager directly happened to mean. With no dispatcher bound
 * the executor falls back to the manager, which is the historical behaviour and still correct for a host with no hook
 * registry.
 *
 * <p>
 * One consequence worth knowing: on the dispatcher path a permission violation comes back as an error result for that
 * one tool and the skill keeps going, because the invoker catches it per call. On the fallback path
 * {@code executeAll} throws and the whole skill fails with "Permission denied". The dispatcher behaviour is the one the
 * agent has always had.
 *
 * <p>
 * Thread-safe if all dependencies are thread-safe.
 *
 * @see at.aimon.core.skill.render.DefaultSkillContentRenderer
 */
public class LlmSkillExecutor implements SkillExecutor {

    private static final Logger log = LoggerFactory.getLogger(LlmSkillExecutor.class);

    private final LlmClient llmClient;
    private final SkillContentRenderer renderer;
    private final SkillSystemPromptBuilder systemPromptBuilder;
    private final ToolExecutionManager toolExecutionManager;
    private final SkillForkExecutor forkExecutor;

    /**
     * Creates a new executor with a {@link NoOpSkillForkExecutor}.
     *
     * <p>
     * Provided for backward compatibility with callers wired before fork-mode support landed in the user-slash path.
     * Skills declared as {@code execution.mode: fork} will fail with a clear "fork execution is not configured" error
     * rather than silently inlining.
     *
     * @param llmClient
     *            The LLM client (must not be null)
     * @param renderer
     *            The skill content renderer (must not be null)
     * @param toolExecutionManager
     *            The tool execution manager (must not be null)
     */
    public LlmSkillExecutor(LlmClient llmClient, SkillContentRenderer renderer,
            ToolExecutionManager toolExecutionManager) {
        this(llmClient, renderer, toolExecutionManager, new NoOpSkillForkExecutor());
    }

    /**
     * Creates a new executor with an explicit {@link SkillForkExecutor}.
     *
     * <p>
     * Mirrors the fork-branching {@code SkillTool} performs for the LLM tool-call path, so user-slash invocations of
     * fork-mode skills delegate to the same subagent machinery instead of being silently inlined. Pass a
     * {@link NoOpSkillForkExecutor} for deployments without subagent infrastructure.
     *
     * @param llmClient
     *            The LLM client (must not be null)
     * @param renderer
     *            The skill content renderer (must not be null)
     * @param toolExecutionManager
     *            The tool execution manager (must not be null)
     * @param forkExecutor
     *            The fork executor consulted when a skill declares {@code execution.mode: fork} (must not be null;
     *            use {@link NoOpSkillForkExecutor} when subagent infrastructure is unavailable)
     */
    public LlmSkillExecutor(LlmClient llmClient, SkillContentRenderer renderer,
            ToolExecutionManager toolExecutionManager, SkillForkExecutor forkExecutor) {
        this.llmClient = Objects.requireNonNull(llmClient, "LLM client cannot be null");
        this.renderer = Objects.requireNonNull(renderer, "Skill content renderer cannot be null");
        this.toolExecutionManager = Objects.requireNonNull(toolExecutionManager,
                "Tool execution manager cannot be null");
        this.forkExecutor = Objects.requireNonNull(forkExecutor, "Fork executor cannot be null");
        this.systemPromptBuilder = new SkillSystemPromptBuilder();
    }

    @Override
    public SkillExecutionResult execute(SkillExecutionContext context, SkillExecutionRequest request) {
        Objects.requireNonNull(context, "Context cannot be null");
        Objects.requireNonNull(request, "Request cannot be null");

        final Instant startTime = Instant.now();
        TokenUsage accumulatedTokens = TokenUsage.empty();

        final Skill skill = context.getSkill();
        final String rawArguments = request.getRawArguments();

        try {
            final String renderedBody = renderer.render(skill, rawArguments, request.getRenderContext());

            // Branch on execution mode: FORK delegates to a subagent through the configured SkillForkExecutor; INLINE
            // keeps the historical ReAct-loop behaviour. Mirrors SkillTool's fork branching so user-slash and
            // LLM tool-call invocation paths agree on what fork-mode means.
            if (skill.getMetadata().getExecutionMode() == ExecutionMode.FORK) {
                return executeFork(skill, renderedBody, context.getToolContext(), startTime, accumulatedTokens);
            }

            final List<AllowedTool> allowedTools = skill.getMetadata().getAllowedTools();
            final String systemPrompt = systemPromptBuilder.build(allowedTools);

            final TranscriptBuffer transcriptBuffer = new TranscriptBuffer(
                    scratchTranscriptLabel(context.getExecutionId()), systemPrompt);
            transcriptBuffer.addUserMessage(renderedBody);

            // Withhold what the tool execution manager would refuse anyway. The ceiling is read from the manager
            // rather than configured here on purpose: the filter and the refusal then cannot disagree, and a skill
            // offered a tool above the ceiling would spend an iteration picking it and reading the refusal.
            final SideEffectLevel ceiling = toolExecutionManager.getMaxSideEffectLevel();
            final List<ToolDefinition> filteredTools = filterTools(skill, context.getAvailableTools()).stream()
                    .filter(tool -> ceiling.permits(tool.getSideEffectLevel())).map(Tool::getDefinition).toList();

            // Prefer the per-execution dispatcher the agent executor binds into the command tool context: it runs each
            // call through the same SingleToolInvoker pipeline the ReAct loop uses, so a skill cannot reach a tool by
            // a path with no permission hooks and no approval gate in front of it. Absent for embedders with no agent
            // runtime, where the plain execution manager remains the behaviour.
            final SkillToolDispatcher toolDispatcher = context.getToolContext()
                    .get(ToolContextKeys.SKILL_TOOL_DISPATCHER_KEY).orElse(this::dispatchWithoutHooks);

            LlmResponse currentResponse = llmClient.sendMessage(systemPrompt, transcriptBuffer.getMessages(),
                    filteredTools, context.getDefaultModel());
            accumulatedTokens = accumulatedTokens.add(currentResponse.getTokenUsage());

            int iterationCount = 0;
            final int maxIterations = skill.getMetadata().getMaxIterations();
            // The death-spiral guard both agent executors stop on. A slash command cannot be interrupted, so without it
            // a skill whose calls keep failing — or keep being refused at max_tokens — runs to max-iterations.
            final StalledIterationGuard stalledIterationGuard = new StalledIterationGuard();
            while (currentResponse.hasToolUses()) {
                if (iterationCount >= maxIterations) {
                    final SkillExecutionMetadata metadata = buildMetadata(iterationCount, accumulatedTokens, startTime);
                    final String errorMsg = "Max tools execution iterations (" + maxIterations + ") exceeded";
                    return SkillExecutionResult.failure(errorMsg, new IllegalStateException(errorMsg), metadata);
                }

                // A response cut off at max_tokens is read once, here: its tool calls are refused rather than
                // dispatched, the answer both agent executors give (TruncatedResponses).
                final boolean truncated = TruncatedResponses.isTruncated(currentResponse);

                final Message assistantMessage = Message
                        .assistant(currentResponse.getTextContent(), currentResponse.getToolUses())
                        .withReasoningTraces(currentResponse.getReasoningTraces());
                transcriptBuffer.addMessage(assistantMessage);

                final ToolRegistry toolRegistry = context.getToolRegistry();
                // Use the real execution ToolContext (principal, agent runtime id, filesystem, ...) — the same one
                // the fork path forwards — rather than an empty context, so user-invoked skill tools run with identity
                // and environment. SkillExecutionContext guarantees a non-null ToolContext (defaults to empty).
                final List<ToolUseResult> toolResults = truncated
                        ? refuseTruncatedToolUses(skill, currentResponse, iterationCount)
                        : toolDispatcher.dispatch(toolRegistry, context.getToolContext(), currentResponse.getToolUses(),
                                allowedTools, iterationCount);

                final Message toolResultMessage = Message.toolUseResults(toolResults);
                transcriptBuffer.addMessage(toolResultMessage);

                // Before the next call, so a stalled skill spends no further request on the streak.
                if (stalledIterationGuard.recordToolIteration(toolResults, truncated)) {
                    return stalledFailure(skill, stalledIterationGuard, iterationCount + 1, accumulatedTokens,
                            startTime);
                }

                currentResponse = llmClient.sendMessage(transcriptBuffer.getSystemPrompt(),
                        transcriptBuffer.getMessages(), filteredTools, context.getDefaultModel());
                accumulatedTokens = accumulatedTokens.add(currentResponse.getTokenUsage());
                iterationCount++;
            }

            // A final answer cut off at max_tokens is not a clean one. Keep the partial text — a failure's response
            // would be an error message instead — and mark it with the marker the executors put on a TRUNCATED answer.
            if (TruncatedResponses.isTruncated(currentResponse)) {
                final String flaggedAnswer = currentResponse.getTextContent() + TruncatedResponses.TRUNCATION_MARKER;
                log.warn(
                        "Skill '{}' final answer truncated at max_tokens after {} tool iterations; returning flagged "
                                + "partial answer{}",
                        skill.getName(), iterationCount,
                        TruncatedResponses.reasoningClause(currentResponse.getTokenUsage()));
                transcriptBuffer.addMessage(
                        Message.assistant(flaggedAnswer).withReasoningTraces(currentResponse.getReasoningTraces()));
                return SkillExecutionResult.success(flaggedAnswer,
                        buildMetadata(iterationCount, accumulatedTokens, startTime));
            }

            // addAssistantMessage(String) cannot carry traces, and this buffer is discarded at the return below, so
            // attaching here is inert today. It is done anyway: the rule is that every site building an assistant
            // message out of an LlmResponse attaches that response's traces, with no per-site judgement about whether
            // the message will be read back. A rule with an exception is a rule nobody can check with one grep.
            transcriptBuffer.addMessage(Message.assistant(currentResponse.getTextContent())
                    .withReasoningTraces(currentResponse.getReasoningTraces()));

            final SkillExecutionMetadata metadata = buildMetadata(iterationCount, accumulatedTokens, startTime);
            return SkillExecutionResult.success(currentResponse.getTextContent(), metadata);

        } catch (ToolPermissionViolationException e) {
            final SkillExecutionMetadata metadata = buildMetadata(0, accumulatedTokens, startTime);
            return SkillExecutionResult.failure("Permission denied: " + e.getMessage(), e, metadata);
        } catch (Exception e) {
            final SkillExecutionMetadata metadata = buildMetadata(0, accumulatedTokens, startTime);
            return SkillExecutionResult.failure(e, metadata);
        }
    }

    /**
     * Answers every tool call of a response the provider cut off at {@code max_tokens} with
     * {@link TruncatedResponses#refusal(ToolUse)} instead of dispatching it — the answer both agent executors give, for
     * the same reason: a cut call's arguments did not arrive, and the response does not say which call was cut.
     *
     * <p>
     * Nothing on either dispatch path runs for these calls: no PermissionRequest, PreTool or PostTool hook, no
     * approval gate, no allow-list check and no tool. Unlike a turn's refused call, a person watching the REPL sees
     * nothing of it either, because this path emits no lifecycle event for any call; the WARN is where an operator
     * reads it, and the stop message is where the person running the skill does once a streak of refusals ends it.
     *
     * @param skill
     *            the running skill, named in the WARN
     * @param response
     *            the cut response whose tool calls are refused
     * @param iterationCount
     *            the number of tool iterations completed before this one (the WARN names it 1-based)
     * @return one error result per tool call, in the response's order (never null)
     */
    private static List<ToolUseResult> refuseTruncatedToolUses(Skill skill, LlmResponse response, int iterationCount) {
        final List<ToolUse> toolUses = response.getToolUses();
        log.warn(
                "Skill '{}' response truncated at max_tokens in iteration {} with tool calls {}: none of them is run; "
                        + "each is answered with an error result{}",
                skill.getName(), iterationCount + 1, toolUses.stream().map(ToolUse::getName).toList(),
                TruncatedResponses.reasoningClause(response.getTokenUsage()));
        return toolUses.stream().map(TruncatedResponses::refusal).toList();
    }

    /**
     * The failure of a skill the stalled-iteration guard stopped.
     *
     * <p>
     * The response is the guard's stop message ({@link StalledIterationGuard#stopMessage()}), which names
     * {@code max_tokens} when every iteration of the streak was a refused cut response; it is what the turn commits and
     * what a person running the skill reads. The WARN does not name {@code max_tokens}, so each cut response keeps the
     * one WARN that does.
     *
     * @param skill
     *            the stopped skill, named in the WARN
     * @param guard
     *            the guard that tripped
     * @param toolIterations
     *            the tool iterations completed, the one that tripped the guard included
     * @param tokens
     *            the run's accumulated token usage
     * @param startTime
     *            when the run started
     * @return the failure result (never null)
     */
    private static SkillExecutionResult stalledFailure(Skill skill, StalledIterationGuard guard, int toolIterations,
            TokenUsage tokens, Instant startTime) {
        log.warn(
                "Skill '{}' stalled-iteration guard tripped: consecutiveStalledIterations={}, iterations={}, tokens={}",
                skill.getName(), guard.getConsecutiveStalledIterations(), toolIterations, tokens.getTotalTokens());
        final String message = guard.stopMessage();
        return SkillExecutionResult.failure(message, new IllegalStateException(message),
                buildMetadata(toolIterations, tokens, startTime));
    }

    /**
     * Fallback {@link SkillToolDispatcher}: runs the batch straight through the {@link ToolExecutionManager}.
     *
     * <p>
     * This is what every caller got before the agent executor began binding a dispatcher, and it is still right for a
     * host that has no hook registry to fire against. It skips PermissionRequest / PreTool / PostTool hooks and the
     * side-effect approval gate, so the allow-list and the manager's ceiling are the only things standing in front of
     * the tool.
     *
     * <p>
     * {@code iterationCount} is unused here — it exists for hook contexts, and this path fires no hooks.
     */
    private List<ToolUseResult> dispatchWithoutHooks(ToolRegistry toolRegistry, ToolContext toolContext,
            List<ToolUse> toolUses, List<AllowedTool> allowedTools, int iterationCount) {
        final List<ToolExecutionResult> results = toolExecutionManager.executeAll(toolRegistry, toolContext, toolUses,
                allowedTools);
        return toToolUseResults(results);
    }

    /**
     * Labels the scratch transcript of one skill run.
     *
     * <p>
     * A skill invocation has no session of its own: it runs inside whoever invoked it, and this buffer is node-local
     * scratch space &mdash; never checkpointed, never leased, never snapshotted, discarded when {@code execute}
     * returns. {@link TranscriptBuffer} is keyed on {@link SessionId} by design, so the run's {@link ExecutionId}
     * supplies the label; the executor no longer invents an identity of its own.
     *
     * <p>
     * What this replaced was a {@code new SessionId(UUID.randomUUID().toString())} minted here. That id was a lie in
     * two directions at once: a log line quoting it was indistinguishable from one naming a real user session, and
     * nothing in the type system could object, because a fabricated session id and a genuine one are the same type.
     *
     * @param executionId
     *            the correlation id of this run (must not be null)
     * @return the label for this run's scratch transcript (never null)
     */
    private static SessionId scratchTranscriptLabel(ExecutionId executionId) {
        return SessionId.of(executionId.value());
    }

    private static SkillExecutionMetadata buildMetadata(int iterationCount, TokenUsage tokens, Instant startTime) {
        final Instant endTime = Instant.now();
        return SkillExecutionMetadata.builder().iterationCount(iterationCount).tokenUsage(tokens)
                .timestamps(startTime, endTime).build();
    }

    /**
     * Delegates a fork-mode skill invocation to the configured {@link SkillForkExecutor} and translates the resulting
     * {@link SkillForkOutcome} into a {@link SkillExecutionResult}.
     *
     * <p>
     * Prefers a per-execution {@link SkillForkExecutor} carried in {@code toolContext} under
     * {@link ToolContextKeys#SKILL_FORK_EXECUTOR_KEY} (set by the user-slash path so it can resolve a
     * {@link at.aimon.core.skill.fork.SubagentBackedSkillForkExecutor SubagentBackedSkillForkExecutor} from the
     * live {@code OrcaAgentRuntime}); falls back to the constructor-injected executor otherwise.
     *
     * <p>
     * Token usage is reported as zero — the forked subagent owns its own token accounting, which is propagated through
     * its execution attribution rather than aggregated into the parent skill's metadata.
     */
    private SkillExecutionResult executeFork(Skill skill, String renderedBody, ToolContext toolContext,
            Instant startTime, TokenUsage accumulatedTokens) {
        final SkillForkExecutor effectiveForkExecutor = toolContext.get(ToolContextKeys.SKILL_FORK_EXECUTOR_KEY)
                .orElse(forkExecutor);
        final SkillForkOutcome outcome = effectiveForkExecutor.fork(skill, renderedBody, toolContext);
        final SkillExecutionMetadata metadata = buildMetadata(0, accumulatedTokens, startTime);
        if (outcome.isSuccess()) {
            return SkillExecutionResult.success(outcome.getFinalAnswer().orElse(""), metadata);
        }
        final String message = String.format("Skill fork failed for '%s': %s", skill.getName(),
                outcome.getErrorMessage().orElse("(no message)"));
        return SkillExecutionResult.failure(message, new IllegalStateException(message), metadata);
    }

    private static List<Tool> filterTools(Skill skill, List<Tool> availableTools) {
        if (!skill.hasToolRestrictions()) {
            return availableTools;
        }
        final Set<String> allowedToolNames = skill.getMetadata().getAllowedTools().stream()
                .map(AllowedTool::getToolName).collect(Collectors.toSet());
        return availableTools.stream().filter(tool -> allowedToolNames.contains(tool.getDefinition().getName()))
                .toList();
    }
}
