package at.aimon.core.tools.monitor;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.queue.DefaultMessageQueueManager;
import at.aimon.core.agent.queue.InMemoryMessageQueueRepository;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.tools.BackgroundTaskOwner;
import at.aimon.core.tools.ToolContextKeys;

@DisplayName("MonitorStop — owner-scoped answers")
class MonitorStopToolTest {

    private static final AgentRuntimeId RUNTIME = AgentRuntimeId.of("agent:stop-tool");
    private static final SessionId SESSION = SessionId.of("session-1");
    private static final BackgroundTaskOwner OWNER = BackgroundTaskOwner.of(RUNTIME, SESSION, null);

    private MonitorManager manager;

    @AfterEach
    void tearDown() {
        manager.close();
    }

    private static ToolContext context() {
        return ToolContext.builder().put(ToolContextKeys.AGENT_RUNTIME_ID, RUNTIME)
                .put(ToolContextKeys.SESSION_ID, SESSION).build();
    }

    @Test
    @DisplayName("an unknown id lists the caller's running monitors")
    void notFoundListsRunningMonitors() {
        manager = MonitorManager.builder(new DefaultMessageQueueManager(new InMemoryMessageQueueRepository()))
                .sources(List.of(new IdleSource())).build();
        final MonitorRecord running = manager
                .start(MonitorRequest.builder().owner(OWNER).kind("idle").description("disk usage").build());

        final ToolResult result = new MonitorStopTool(manager).execute(ToolInput.of(Map.of("monitorId", "mon_x")),
                context());

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("No running monitor mon_x")
                .contains(running.getMonitorId() + " (disk usage)");
    }

    @Test
    @DisplayName("a monitor of the caller's on another node is reported as such")
    void runningElsewhere() {
        final InMemoryMonitorStore store = new InMemoryMonitorStore();
        store.putIfAbsent(MonitorRecord.builder().monitorId("mon_0000abcd").owner(OWNER).nodeId("node-b").kind("x")
                .description("d").startedAt(Instant.now()).deadline(Instant.now().plusSeconds(600)).build());
        manager = MonitorManager.builder(new DefaultMessageQueueManager(new InMemoryMessageQueueRepository()))
                .store(store).nodeId("node-a").build();

        final ToolResult result = new MonitorStopTool(manager)
                .execute(ToolInput.of(Map.of("monitorId", "mon_0000abcd")), context());

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("runs on another node");
    }

    /** A source that never emits. */
    private static final class IdleSource implements MonitorSourceFactory {

        @Override
        public String kind() {
            return "idle";
        }

        @Override
        public String describe() {
            return "idle";
        }

        @Override
        public MonitorSource create(MonitorRequest request) {
            return sink -> () -> {
            };
        }
    }
}
