package at.aimon.cli.hook;

import java.util.Objects;
import java.util.Optional;

import at.aimon.cli.repl.OutputFormatter;
import at.aimon.core.hook.event.SubagentStartContext;
import at.aimon.core.hook.event.SubagentStartHook;
import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.tools.ForkAccess;

/**
 * Displays a {@code [Subagent] <name>: <goal>} line whenever a subagent begins executing.
 *
 * <p>
 * This fires on the {@code SUBAGENT_START} lifecycle event, so it surfaces <b>every</b> directly-executed subagent
 * uniformly — those launched by the {@code Task} tool, by the multi-perspective {@code Workflow} tool, and by
 * skill forks — not just tool-call subagents. Because it renders the launch, the generic tool-call line for the
 * {@code Task} tool is suppressed in {@link OutputFormatter#displayToolCall} to avoid a duplicate. Advisory and
 * non-blocking.
 */
public class SubagentLaunchDisplayHook implements SubagentStartHook {

    private final OutputFormatter outputFormatter;

    /**
     * Creates the hook.
     *
     * @param outputFormatter
     *            the formatter used to render the launch line (must not be null)
     * @throws NullPointerException
     *             if outputFormatter is null
     */
    public SubagentLaunchDisplayHook(OutputFormatter outputFormatter) {
        this.outputFormatter = Objects.requireNonNull(outputFormatter, "OutputFormatter cannot be null");
    }

    @Override
    public HookResult execute(SubagentStartContext context) {
        // Labelled only when the fork carries its task id attribute (parallel foreground tasks on), so the output is
        // unchanged otherwise. The attribute is this fork's own id: the manager adds it before SubagentStart fires.
        Optional<String> taskId = ForkAccess.taskIdOf(context.getExecutionAttributes());
        if (taskId.isPresent()) {
            outputFormatter.displaySubagentLaunch(context.getSubagentName(), context.getGoal(),
                    ForkLabels.shortTaskId(taskId.get()));
        } else {
            outputFormatter.displaySubagentLaunch(context.getSubagentName(), context.getGoal());
        }
        return HookResult.success();
    }
}
