package at.aimon.core.tools.monitor;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.environment.TestExecutionEnvironments;
import at.aimon.core.environment.UnavailableExecutionEnvironment;
import at.aimon.core.shell.impl.local.LocalShell;
import at.aimon.core.tools.BackgroundTaskOwner;
import at.aimon.core.tools.ToolContextKeys;
import at.aimon.core.tools.monitor.exception.MonitorRequestException;

@DisplayName("ShellMonitorSourceFactory — what it refuses before anything runs")
class ShellMonitorSourceFactoryTest {

    private static final BackgroundTaskOwner OWNER = BackgroundTaskOwner.of(AgentRuntimeId.of("agent:shell"),
            SessionId.of("s"), null);

    private static MonitorRequest.Builder request(ToolContext context) {
        return MonitorRequest.builder().owner(OWNER).kind(ShellMonitorSourceFactory.KIND).description("d")
                .timeout(Duration.ofMinutes(1)).context(context);
    }

    @Test
    @DisplayName("a missing, blank or overlong command is refused")
    void commandIsChecked() {
        final ToolContext context;
        try (LocalShell shell = new LocalShell()) {
            context = TestExecutionEnvironments.contextBuilder(TestExecutionEnvironments.ofShell(shell)).build();
            final ShellMonitorSourceFactory factory = new ShellMonitorSourceFactory();
            assertThatThrownBy(() -> factory.create(request(context).build()))
                    .isInstanceOf(MonitorRequestException.class).hasMessageContaining("non-empty command");
            assertThatThrownBy(() -> factory.create(request(context).param("command", "  ").build()))
                    .isInstanceOf(MonitorRequestException.class).hasMessageContaining("non-empty command");
            assertThatThrownBy(() -> factory.create(request(context)
                    .param("command", "x".repeat(ShellMonitorSourceFactory.MAX_COMMAND_LENGTH + 1)).build()))
                    .isInstanceOf(MonitorRequestException.class).hasMessageContaining("maximum length");
        }
    }

    @Test
    @DisplayName("no execution environment, or an unavailable one, is refused with its reason")
    void environmentIsRequired() {
        final ShellMonitorSourceFactory factory = new ShellMonitorSourceFactory();
        assertThatThrownBy(() -> factory.create(request(ToolContext.empty()).param("command", "true").build()))
                .isInstanceOf(MonitorRequestException.class);

        final ToolContext unavailable = ToolContext.builder()
                .put(ToolContextKeys.EXECUTION_ENVIRONMENT, UnavailableExecutionEnvironment.of("the sandbox is down"))
                .build();
        assertThatThrownBy(() -> factory.create(request(unavailable).param("command", "true").build()))
                .isInstanceOf(MonitorRequestException.class).hasMessageContaining("the sandbox is down");
    }
}
