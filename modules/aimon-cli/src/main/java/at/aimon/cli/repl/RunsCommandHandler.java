package at.aimon.cli.repl;

import java.util.List;
import java.util.Locale;

import at.aimon.core.workflow.RunId;
import at.aimon.core.workflow.RunQuery;
import at.aimon.core.workflow.WorkflowRun;
import at.aimon.core.workflow.WorkflowRunner;

/**
 * Processes {@code /runs} slash-command invocations on behalf of the REPL: lists background workflow runs, shows a
 * single run's status, and requests cooperative cancellation of a run.
 *
 * <p>
 * The handler talks only to an {@link WorkflowRunner} (the per-context runner the {@code Workflow} tool submits
 * background runs to) and returns the message to display as a plain {@link String} — presentation stays in the caller
 * so unit tests can assert behaviour without capturing stdout, mirroring {@code BudgetCommandHandler}. A {@code null}
 * runner means background runs are disabled ({@code cli.enableWorkflow} is off).
 */
public final class RunsCommandHandler {

    private static final String USAGE = "Usage: /runs [list] | /runs status <runId> | /runs stop <runId>";

    private final WorkflowRunner runner;

    /**
     * @param runner
     *            the per-context workflow runner, or {@code null} when background runs are disabled
     */
    public RunsCommandHandler(WorkflowRunner runner) {
        this.runner = runner;
    }

    /**
     * Handles the {@code /runs} command.
     *
     * @param args
     *            the command text after {@code /runs} (may be null/blank → list)
     * @return the message to display
     */
    public String handle(String args) {
        if (runner == null) {
            return "Background workflow runs are disabled. Enable them with `cli.enableWorkflow: true` "
                    + "in your config.";
        }
        final String trimmed = args == null ? "" : args.trim();
        final String[] parts = trimmed.isEmpty() ? new String[0] : trimmed.split("\\s+", 2);
        final String sub = parts.length > 0 ? parts[0].toLowerCase(Locale.ROOT) : "list";
        final String arg = parts.length > 1 ? parts[1].trim() : "";
        return switch (sub) {
            case "list" -> renderList();
            case "status" -> renderStatus(arg);
            case "stop" -> renderStop(arg);
            default -> USAGE;
        };
    }

    private String renderList() {
        final List<WorkflowRun> runs = runner.list(RunQuery.all());
        if (runs.isEmpty()) {
            return "No workflow runs.";
        }
        final StringBuilder sb = new StringBuilder("Workflow runs (" + runs.size() + "):");
        for (final WorkflowRun run : runs) {
            sb.append('\n').append(formatRow(run));
        }
        return sb.toString();
    }

    private String renderStatus(String arg) {
        if (arg.isEmpty()) {
            return "Usage: /runs status <runId>";
        }
        final RunId id = parseRunId(arg);
        if (id == null) {
            return "Invalid run id: '" + arg + "'.";
        }
        return runner.status(id).map(RunsCommandHandler::formatRow).orElse("No run found for id '" + arg + "'.");
    }

    private String renderStop(String arg) {
        if (arg.isEmpty()) {
            return "Usage: /runs stop <runId>";
        }
        final RunId id = parseRunId(arg);
        if (id == null) {
            return "Invalid run id: '" + arg + "'.";
        }
        return runner.stop(id)
                ? "Requested stop for run '" + arg + "'."
                : "No live run '" + arg + "' to stop on this node.";
    }

    private static String formatRow(WorkflowRun run) {
        final String ended = run.getEndTime().map(t -> "  ended " + t).orElse("");
        return String.format("  %-30s %-9s started %s%s", run.getRunId().value(), run.getState(), run.getStartTime(),
                ended);
    }

    private static RunId parseRunId(String raw) {
        try {
            return RunId.of(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
