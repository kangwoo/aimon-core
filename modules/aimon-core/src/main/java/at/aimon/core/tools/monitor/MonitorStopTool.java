package at.aimon.core.tools.monitor;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.ToolCategories;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.tools.bash.BackgroundBashOwner;

/**
 * Stops a monitor started with {@link MonitorTool}. Only the owner a monitor was started for finds it; for anyone else
 * the id does not exist.
 *
 * <p>
 * A separate tool rather than a wider {@code TaskStop}: that one is the control plane of background subagent tasks,
 * and one tool deciding between two id spaces would leave its errors unclear about which one it meant (design §4.3).
 */
public class MonitorStopTool extends AbstractTool {

    /** The tool name. */
    public static final String TOOL_NAME = "MonitorStop";

    private static final Logger log = LoggerFactory.getLogger(MonitorStopTool.class);

    private final MonitorManager manager;

    /**
     * Creates the tool over a manager.
     *
     * @param manager
     *            the application's monitor manager (must not be null)
     */
    public MonitorStopTool(MonitorManager manager) {
        super(TOOL_NAME, "Stops a background monitor started with " + MonitorTool.TOOL_NAME
                + ". No further events arrive from it.", ToolCategories.EXECUTION, createInputSchema());
        this.manager = Objects.requireNonNull(manager, "manager must not be null");
    }

    private static Map<String, Object> createInputSchema() {
        return Map.of("type", "object", "additionalProperties", false, "properties",
                Map.of("monitorId", Map.of("type", "string", "description", "The id the monitor was started with")),
                "required", List.of("monitorId"));
    }

    @Override
    public ToolResult execute(ToolInput input, ToolContext context) {
        Objects.requireNonNull(input, "Input cannot be null");
        Objects.requireNonNull(context, "Context cannot be null");
        try {
            final String monitorId = input.getRequiredString("monitorId").strip();
            final BackgroundBashOwner owner = BackgroundBashOwner.of(context);
            return switch (manager.stop(owner, monitorId)) {
                case STOPPED -> ToolResult.success("Monitor " + monitorId + " stopped.");
                case RUNNING_ELSEWHERE -> ToolResult.error("Monitor " + monitorId
                        + " runs on another node and cannot be stopped from here; it ends at its timeout.");
                case NOT_FOUND -> ToolResult.error(notFound(monitorId, owner));
            };
        } catch (IllegalArgumentException e) {
            log.warn("Invalid parameter: {}", e.getMessage());
            return ToolResult.error("Invalid parameter: " + e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error stopping a monitor", e);
            return ToolResult.error("Unexpected error: " + e.getMessage());
        }
    }

    private String notFound(String monitorId, BackgroundBashOwner owner) {
        final List<MonitorRecord> running = manager.running(owner);
        final StringBuilder text = new StringBuilder("No running monitor ").append(monitorId)
                .append(" — it may already have ended.");
        if (!running.isEmpty()) {
            text.append(" Your running monitors:");
            for (MonitorRecord record : running) {
                text.append(' ').append(record.getMonitorId()).append(" (").append(record.getDescription()).append(')');
            }
        }
        return text.toString();
    }
}
