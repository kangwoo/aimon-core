package at.aimon.core.agent.impl.orca.tool;

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.Agent;
import at.aimon.core.agent.orca.tool.OrcaToolProvider;
import at.aimon.core.agent.orca.tool.OrcaToolProviderContext;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.scheduling.ScheduledTaskManager;
import at.aimon.core.tools.scheduling.CancelScheduledTaskTool;
import at.aimon.core.tools.scheduling.ListScheduledTasksTool;
import at.aimon.core.tools.scheduling.ScheduleTaskTool;

public class OrcaSchedulingToolProvider implements OrcaToolProvider {

    private static final Logger log = LoggerFactory.getLogger(OrcaSchedulingToolProvider.class);

    @Override
    public void registerTools(ToolRegistry registry, OrcaToolProviderContext context) {
        Objects.requireNonNull(registry, "registry must not be null");
        Objects.requireNonNull(context, "context must not be null");

        final ScheduledTaskManager taskManager = context.getScheduledTaskManager();
        if (taskManager == null) {
            log.warn("ScheduledTaskManager is not available in context. Scheduling tools will not be registered.");
            return;
        }

        // The agent is what lets a scheduled task record the definition version it was created against, so that a
        // fire weeks later can report whether the definition still matches. Without it the task is still scheduled and
        // still runs — only the drift check goes quiet.
        final Agent agent = context.getAgent();
        if (agent != null) {
            registry.register(ScheduleTaskTool.forAgent(taskManager, agent));
        } else {
            log.warn("Agent is not available in context. Scheduled tasks will not record an agent definition version.");
            registry.register(new ScheduleTaskTool(taskManager));
        }
        registry.register(new ListScheduledTasksTool(taskManager));
        registry.register(new CancelScheduledTaskTool(taskManager));
    }
}
