package at.aimon.core.command.execution.skill;

import java.util.Objects;
import java.util.Optional;

import at.aimon.core.agent.ExecutionId;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.command.Command;
import at.aimon.core.command.execution.CommandExecutionContext;
import at.aimon.core.command.execution.CommandExecutionRequest;
import at.aimon.core.command.execution.CommandExecutionResult;
import at.aimon.core.command.execution.CommandExecutor;
import at.aimon.core.command.execution.ExecutionMetadata;
import at.aimon.core.command.skill.SkillBackedCommand;
import at.aimon.core.skill.Skill;
import at.aimon.core.skill.execution.SkillExecutionContext;
import at.aimon.core.skill.execution.SkillExecutionMetadata;
import at.aimon.core.skill.execution.SkillExecutionRequest;
import at.aimon.core.skill.execution.SkillExecutionResult;
import at.aimon.core.skill.execution.SkillExecutor;
import at.aimon.core.skill.render.RenderContext;
import at.aimon.core.tools.SkillRenderContextAccess;
import at.aimon.core.tools.ToolContextKeys;

/**
 * {@link CommandExecutor} that routes a {@link SkillBackedCommand} through the user-invocation
 * {@link SkillExecutor skill execution pipeline}.
 *
 * <p>
 * Completes the SK-08-D acceptance criterion "{@code /<name>} 호출이 SkillExecutor로 라우팅". Lookup-only consumers — REPL
 * routing, {@code /commands} listing, help text — continue to see a {@link Command}; execution is delegated here so the
 * skill body is rendered through {@link at.aimon.core.skill.render.SkillContentRenderer} (argument interpolation +
 * context tokens in a single pass).
 *
 * <p>
 * Only {@link SkillBackedCommand} is accepted. Other command types must be routed through their own executor — the
 * composite dispatcher checks command type before delegating here.
 *
 * <p>
 * Thread-safe if the supplied {@link SkillExecutor} is thread-safe.
 */
public final class SkillBackedCommandExecutor implements CommandExecutor {

    private final SkillExecutor skillExecutor;

    /**
     * Creates a new executor.
     *
     * @param skillExecutor
     *            The skill executor to delegate to (must not be null)
     * @throws NullPointerException
     *             if {@code skillExecutor} is null
     */
    public SkillBackedCommandExecutor(SkillExecutor skillExecutor) {
        this.skillExecutor = Objects.requireNonNull(skillExecutor, "Skill executor cannot be null");
    }

    @Override
    public CommandExecutionResult execute(CommandExecutionContext context, CommandExecutionRequest request) {
        Objects.requireNonNull(context, "Context cannot be null");
        Objects.requireNonNull(request, "Request cannot be null");

        final Command command = context.getCommand();
        if (!(command instanceof SkillBackedCommand skillBacked)) {
            throw new IllegalArgumentException(
                    "SkillBackedCommandExecutor can only execute SkillBackedCommand, but received: "
                            + (command == null ? "null" : command.getClass().getName()));
        }

        // One id per invocation, not per skill: a user can run the same slash command twice in a row and the two runs
        // must not share per-run state. Hence generate(prefix) rather than of(name) — the prefix is for whoever reads
        // the log, the random tail is what keeps the runs apart. This run has no session of its own; the session it
        // acts for is already carried by the forwarded ToolContext.
        final Skill skill = skillBacked.getSkill();
        final ExecutionId executionId = ExecutionId.generate("skill:" + skill.getName());
        final SkillExecutionContext skillContext = SkillExecutionContext.builder().skill(skill)
                .defaultModel(context.getDefaultModel()).toolRegistry(context.getToolRegistry())
                .executionId(executionId).transcriptBuffer(context.getTranscriptBuffer())
                .toolContext(context.getToolContext()).build();

        // Staging the skill into the run's environment (${AIMON_SKILL_DIR}) can fail — over the size limit, the
        // source changed since it was loaded, no usable environment. That is this command's error, not a crash.
        // Every failure, not only the two types stage()'s contract names (EE-15): a directory name the workspace's
        // filesystem refuses (InvalidPathException), a backend write that fails mid-copy, or whatever a provider's
        // own environment throws must still come back as this command's result rather than escape it.
        final RenderContext renderContext;
        try {
            renderContext = buildRenderContext(skill, context.getToolContext(), request, executionId);
        } catch (RuntimeException e) {
            return CommandExecutionResult.failure("Failed to stage skill '" + skill.getName() + "': " + e.getMessage(),
                    e);
        }
        final SkillExecutionRequest skillRequest = SkillExecutionRequest.builder()
                .rawArguments(request.getRawArguments()).arguments(request.getArguments())
                .principal(request.getPrincipal().orElse(null))
                .previousSnapshot(request.getPreviousSnapshot().orElse(null)).renderContext(renderContext).build();

        final SkillExecutionResult skillResult = skillExecutor.execute(skillContext, skillRequest);
        return toCommandResult(skillResult);
    }

    /**
     * Builds the {@link RenderContext} the skill body is rendered with — the one the {@code Skill} tool builds for
     * this skill and tool context, so {@code ${AIMON_SKILL_DIR}} and the other {@code AIMON_*} variables expand
     * identically whichever way the skill was invoked. Without it the request falls back to
     * {@link RenderContext#empty()} and a body such as {@code bash ${AIMON_SKILL_DIR}/scripts/x.sh} renders as
     * {@code bash /scripts/x.sh}.
     *
     * <p>
     * Two values are more specific here than in the tool context. The request's principal is the caller of this
     * command, so it wins when present. And the run's identity: when the forwarded tool context names neither a session
     * nor an execution — a command run outside any session — this run's own execution id fills the
     * {@code ${AIMON_EXECUTION_ID}} half of the exclusive pair. It is never set alongside a session id, which would
     * make the pair ambiguous.
     */
    private static RenderContext buildRenderContext(Skill skill, ToolContext toolContext,
            CommandExecutionRequest request, ExecutionId executionId) {
        final RenderContext.Builder builder = SkillRenderContextAccess.builderFor(skill, toolContext);
        request.getPrincipal().ifPresent(builder::principal);
        if (toolContext.get(ToolContextKeys.SESSION_ID).isEmpty()
                && toolContext.get(ToolContextKeys.EXECUTION_ID).isEmpty()) {
            builder.executionId(executionId.value());
        }
        return builder.build();
    }

    private static CommandExecutionResult toCommandResult(SkillExecutionResult skillResult) {
        final Optional<ExecutionMetadata> metadata = skillResult.getMetadata()
                .map(SkillBackedCommandExecutor::toExecutionMetadata);
        if (skillResult.isSuccess()) {
            return metadata.map(m -> CommandExecutionResult.success(skillResult.getResponse(), m))
                    .orElseGet(() -> CommandExecutionResult.success(skillResult.getResponse()));
        }
        final Throwable cause = skillResult.getError()
                .orElseGet(() -> new IllegalStateException(skillResult.getResponse()));
        return metadata.map(m -> CommandExecutionResult.failure(skillResult.getResponse(), cause, m))
                .orElseGet(() -> CommandExecutionResult.failure(skillResult.getResponse(), cause));
    }

    private static ExecutionMetadata toExecutionMetadata(SkillExecutionMetadata skillMetadata) {
        return ExecutionMetadata.builder().iterationCount(skillMetadata.getIterationCount())
                .tokenUsage(skillMetadata.getTokenUsage())
                .timestamps(skillMetadata.getStartTime(), skillMetadata.getEndTime()).build();
    }
}
