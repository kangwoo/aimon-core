package at.aimon.core.command.execution;

import java.util.Objects;

import at.aimon.core.command.Command;
import at.aimon.core.command.execution.direct.DirectCommandExecutor;
import at.aimon.core.command.execution.direct.DirectExecutable;
import at.aimon.core.command.execution.skill.SkillBackedCommandExecutor;
import at.aimon.core.command.skill.SkillBackedCommand;

/**
 * Composite command executor that delegates to the appropriate execution strategy.
 *
 * <p>
 * Routes:
 *
 * <ul>
 * <li>{@link SkillBackedCommand} → {@link SkillBackedCommandExecutor}
 * <li>{@link DirectExecutable} → {@link DirectCommandExecutor}
 * </ul>
 *
 * <p>
 * Legacy LLM-based custom commands were removed in SK-08-F; the only LLM execution path now lives in
 * {@link at.aimon.core.skill.execution.llm.LlmSkillExecutor}, which the skill-backed executor wraps. A command
 * that satisfies neither route is a programming error and triggers an {@link IllegalStateException}.
 *
 * <p>
 * Thread-safe if the supplied executors are thread-safe.
 */
public final class CompositeCommandExecutor implements CommandExecutor {
    private final DirectCommandExecutor directCommandExecutor;
    private final SkillBackedCommandExecutor skillBackedCommandExecutor;

    /**
     * Creates a new CompositeCommandExecutor.
     *
     * @param directCommandExecutor
     *            The executor for {@link DirectExecutable} commands (must not be null)
     * @param skillBackedCommandExecutor
     *            The executor for {@link SkillBackedCommand} commands (must not be null)
     * @throws NullPointerException
     *             if any parameter is null
     */
    public CompositeCommandExecutor(DirectCommandExecutor directCommandExecutor,
            SkillBackedCommandExecutor skillBackedCommandExecutor) {
        this.directCommandExecutor = Objects.requireNonNull(directCommandExecutor,
                "Direct command executor cannot be null");
        this.skillBackedCommandExecutor = Objects.requireNonNull(skillBackedCommandExecutor,
                "Skill-backed command executor cannot be null");
    }

    @Override
    public CommandExecutionResult execute(CommandExecutionContext context, CommandExecutionRequest request) {
        Objects.requireNonNull(context, "Context cannot be null");
        Objects.requireNonNull(request, "Request cannot be null");

        final Command command = context.getCommand();

        if (command instanceof SkillBackedCommand) {
            return skillBackedCommandExecutor.execute(context, request);
        }
        if (command instanceof DirectExecutable) {
            return directCommandExecutor.execute(context, request);
        }
        throw new IllegalStateException("No executor available for command type: " + command.getClass().getName());
    }

    /**
     * @return The direct command executor (never null)
     */
    public DirectCommandExecutor getDirectCommandExecutor() {
        return directCommandExecutor;
    }

    /**
     * @return The skill-backed command executor (never null)
     */
    public SkillBackedCommandExecutor getSkillBackedCommandExecutor() {
        return skillBackedCommandExecutor;
    }

    @Override
    public String toString() {
        return "CompositeCommandExecutor{directExecutor=" + directCommandExecutor + ", skillBackedExecutor="
                + skillBackedCommandExecutor + '}';
    }
}
