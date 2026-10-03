package at.aimon.core.tools.bash;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.ToolCategories;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.tools.ToolContextKeys;

/**
 * Tool for stopping a background bash shell — the counterpart of {@link BashOutputTool}.
 *
 * <p>
 * A background command runs until it ends or its ceiling ends it, which by default is a day later. This tool ends it
 * now: it trips the command's cancellation signal, and a shell that declares {@code ShellFeature.CANCELLATION} stops
 * the command and everything it started. The task then reports {@code Killed}, and what the command printed before it
 * was stopped stays readable through {@code BashOutput}.
 *
 * <p>
 * What it answers:
 *
 * <ul>
 * <li>a running command whose shell can stop it — success, once the command is confirmed stopped or after a short wait
 * if it is still shutting down;
 * <li>a running command whose shell cannot stop it — an error; the command runs to its ceiling;
 * <li>a command that already ended — success, saying so;
 * <li>a command on another node — an error; only that node can stop it;
 * <li>an unknown id, or another runtime's task — an error, the same "not found" for both.
 * </ul>
 *
 * <p>
 * A separate tool rather than a {@code kill} argument on {@code BashOutput}, so that a permission rule can allow
 * reading output without allowing a destructive action. Like {@code BashOutput} it acts for the runtime in the tool
 * context: any session of the runtime that started a command can stop it.
 *
 * <p>
 * Stateless; never throws from {@link #execute}.
 */
public class KillShellTool extends AbstractTool {

    public static final String TOOL_NAME = "KillShell";
    private static final Logger log = LoggerFactory.getLogger(KillShellTool.class);

    /** How long to wait for a stopped command to be confirmed stopped before answering "still shutting down". */
    private static final Duration SETTLE_WAIT = Duration.ofSeconds(5);

    private final BackgroundBashManager backgroundManager;

    /**
     * Creates a new KillShellTool.
     *
     * @param backgroundManager
     *            The background bash manager (must not be null)
     * @throws NullPointerException
     *             if backgroundManager is null
     */
    public KillShellTool(BackgroundBashManager backgroundManager) {
        super(TOOL_NAME,
                "Stops a running background bash shell by its ID, including everything the command started. "
                        + "Use the ID returned by the Bash call that started the background task. "
                        + "Output the command produced before it was stopped can still be read with BashOutput. "
                        + "Fails if the environment's shell cannot stop a running command.",
                ToolCategories.EXECUTION, createInputSchema());
        this.backgroundManager = Objects.requireNonNull(backgroundManager, "Background manager cannot be null");
    }

    /**
     * Creates the JSON Schema for KillShell tools input.
     *
     * @return The input schema map
     */
    private static Map<String, Object> createInputSchema() {
        return Map.ofEntries(Map.entry("type", "object"), Map.entry("additionalProperties", false),
                Map.entry("properties",
                        Map.ofEntries(Map.entry("taskId",
                                Map.of("type", "string", "description", "The ID of the background shell to stop")))),
                Map.entry("required", List.of("taskId")));
    }

    /**
     * Stops the background shell with the given id.
     *
     * @param input
     *            The input parameters containing taskId
     * @param context
     *            The execution context; its {@code AGENT_RUNTIME_ID} is the runtime whose tasks can be stopped
     * @return A success result if the command was stopped or was not running, or an error result if it cannot be
     *         stopped from here, is not found, or parameters are invalid
     * @throws NullPointerException
     *             if input or context is null
     */
    @Override
    public ToolResult execute(ToolInput input, ToolContext context) {
        Objects.requireNonNull(input, "Input cannot be null");
        Objects.requireNonNull(context, "Context cannot be null");

        try {
            final String taskId = input.getRequiredString("taskId");
            if (taskId.trim().isEmpty()) {
                return ToolResult.error("Task ID cannot be empty");
            }

            final AgentRuntimeId owner = context.get(ToolContextKeys.AGENT_RUNTIME_ID).orElse(null);
            final BackgroundBashKill kill;
            try {
                kill = backgroundManager.kill(owner, taskId);
            } catch (RuntimeException e) {
                log.warn("Could not look up background task {}: {}", taskId, e.toString());
                return ToolResult.error("Could not look up shell " + taskId + ": " + e.getMessage());
            }

            switch (kill.outcome()) {
                case NOT_FOUND :
                    return BashOutputTool.notFound(taskId);
                case ELSEWHERE :
                    return ToolResult.error(
                            BashOutputTool.describeElsewhere(kill.lookup()) + " It cannot be stopped from here.");
                default :
                    break;
            }

            final BackgroundBashTask task = kill.lookup().task().orElseThrow();
            switch (kill.outcome()) {
                case NOT_RUNNING :
                    return ToolResult.success("Shell " + taskId + " is not running (status: "
                            + statusName(task.getStatus()) + "). Nothing was stopped.");
                case UNSUPPORTED :
                    log.warn("Background task {} runs in a shell that cannot stop a running command", taskId);
                    return ToolResult.error("Shell " + taskId + " cannot be stopped: this environment's shell cannot"
                            + " stop a running command. It runs until it ends"
                            + task.getTimeout()
                                    .map(timeout -> " or is stopped at its limit of " + BashTool.describe(timeout))
                                    .orElse("")
                            + ".");
                default :
                    break;
            }

            log.debug("Stop requested for background task {}", taskId);
            final boolean settled = task.awaitCompletion(SETTLE_WAIT);
            final String readOutput = " Use BashOutput(taskId=\"" + taskId
                    + "\") to read the output it produced before it stopped.";
            if (!settled) {
                return ToolResult
                        .success("Stop requested for shell " + taskId + "; it is still shutting down." + readOutput);
            }
            if (task.getStatus() != BashTaskStatus.KILLED) {
                // The command ended on its own in the instant the signal went out.
                return ToolResult.success("Shell " + taskId + " ended before it could be stopped (status: "
                        + statusName(task.getStatus()) + ")." + readOutput);
            }
            // The shell reported the command cancelled. A local shell has by then terminated the process tree it could
            // enumerate (forcibly, for anything that ignored the polite request); a process the command detached
            // from that tree (nohup, setsid, a double fork) is outside its reach, so say what was stopped, no more.
            return ToolResult.success("Shell " + taskId
                    + " stopped: the command and the processes it was running were terminated." + readOutput);

        } catch (IllegalArgumentException e) {
            log.warn("Invalid parameter: {}", e.getMessage());
            return ToolResult.error("Invalid parameter: " + e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error stopping background shell", e);
            return ToolResult.error("Unexpected error: " + e.getMessage());
        }
    }

    private static String statusName(BashTaskStatus status) {
        return switch (status) {
            case COMPLETED -> "Completed";
            case FAILED -> "Failed";
            case KILLED -> "Killed";
            case RUNNING -> "Running";
            case NOT_FOUND -> "Not found";
        };
    }
}
