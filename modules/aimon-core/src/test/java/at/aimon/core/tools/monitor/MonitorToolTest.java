package at.aimon.core.tools.monitor;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.queue.DefaultMessageQueueManager;
import at.aimon.core.agent.queue.InMemoryMessageQueueRepository;
import at.aimon.core.agent.queue.MessageQueueManager;
import at.aimon.core.agent.queue.QueuedInput;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.agent.tool.permission.AllowedTool;
import at.aimon.core.agent.tool.permission.DefaultToolPermissionValidator;
import at.aimon.core.agent.tool.permission.PermissionDelegation;
import at.aimon.core.agent.tool.permission.PermissionSubject;
import at.aimon.core.environment.TestExecutionEnvironments;
import at.aimon.core.shell.ExecutionOptions;
import at.aimon.core.shell.ShellCommand;
import at.aimon.core.shell.ShellCommandResult;
import at.aimon.core.shell.ShellFeature;
import at.aimon.core.shell.VirtualShell;
import at.aimon.core.shell.impl.local.LocalShell;
import at.aimon.core.tools.BackgroundTaskOwner;
import at.aimon.core.tools.ToolContextKeys;

@DisplayName("Monitor / MonitorStop tools — forms, the shell source end to end, permissions")
class MonitorToolTest {

    private static final AgentRuntimeId RUNTIME = AgentRuntimeId.of("agent:monitor-tool");
    private static final SessionId SESSION = SessionId.of("session-1");

    private MessageQueueManager queue;
    private MonitorManager manager;
    private MonitorTool tool;
    private MonitorStopTool stopTool;
    private LocalShell shell;

    @BeforeEach
    void setUp() {
        queue = new DefaultMessageQueueManager(new InMemoryMessageQueueRepository());
        manager = MonitorManager.builder(queue).batchWindow(Duration.ofMillis(50)).build();
        tool = new MonitorTool(manager);
        stopTool = new MonitorStopTool(manager);
        shell = new LocalShell();
    }

    @AfterEach
    void tearDown() {
        manager.close();
        shell.close();
    }

    private ToolContext context(VirtualShell withShell) {
        return TestExecutionEnvironments.contextBuilder(TestExecutionEnvironments.ofShell(withShell))
                .put(ToolContextKeys.AGENT_RUNTIME_ID, RUNTIME).put(ToolContextKeys.SESSION_ID, SESSION).build();
    }

    @Test
    @DisplayName("the description lists the registered kinds")
    void describesKinds() {
        assertThat(tool.getDefinition().getDescription()).contains("- shell: shell — runs `command`");
    }

    @Test
    @DisplayName("a shell monitor delivers each stdout line and then tells how it ended")
    @DisabledOnOs(OS.WINDOWS)
    void shellMonitorEndToEnd() {
        final ToolResult started = tool.execute(
                ToolInput.of(
                        Map.of("description", "ticks", "command", "for i in 1 2 3; do echo tick $i; sleep 0.05; done")),
                context(shell));

        assertThat(started.isSuccess()).as(started.getContent()).isTrue();
        assertThat(started.getContent()).startsWith("Monitor mon_").contains("started (shell): ticks");

        await(() -> queue.snapshot().stream().anyMatch(q -> q.getInputText().contains("ended:")));
        final String all = String.join("\n", queue.snapshot().stream().map(QueuedInput::getInputText).toList());
        assertThat(all).contains("tick 1").contains("tick 2").contains("tick 3")
                .contains("ended: the source ended (exit code 0)");
    }

    @Test
    @DisplayName("a failing command fails the monitor with its exit code and stderr")
    @DisabledOnOs(OS.WINDOWS)
    void failingCommand() {
        tool.execute(ToolInput.of(Map.of("description", "d", "command", "echo nope >&2; exit 4")), context(shell));

        await(() -> !queue.snapshot().isEmpty());
        assertThat(queue.snapshot().get(0).getInputText()).contains("the source failed: exit code 4; stderr: nope");
    }

    @Test
    @DisplayName("a failure is told with the LAST lines of stderr, even past the capture limit")
    @DisabledOnOs(OS.WINDOWS)
    void failureShowsTheLastStderrLines() {
        tool.execute(
                ToolInput.of(Map.of("description", "d", "command",
                        "for i in $(seq 1 20000); do echo \"noise $i\" >&2; done; echo 'real cause' >&2; exit 4")),
                context(shell));

        await(() -> !queue.snapshot().isEmpty());
        assertThat(queue.snapshot().get(0).getInputText()).contains("exit code 4").contains("real cause")
                .contains("noise 20000").doesNotContain("noise 1 |");
    }

    @Test
    @DisplayName("MonitorStop ends an endless command")
    @DisabledOnOs(OS.WINDOWS)
    void stopEndsEndlessCommand() {
        final ToolContext context = context(shell);
        final ToolResult started = tool.execute(
                ToolInput.of(Map.of("description", "forever", "command", "while true; do sleep 1; done")), context);
        final String id = started.getContent().substring("Monitor ".length(), "Monitor ".length() + 12);

        final ToolResult stopped = stopTool.execute(ToolInput.of(Map.of("monitorId", id)), context);

        assertThat(stopped.isSuccess()).as(stopped.getContent()).isTrue();
        assertThat(manager.running(BackgroundTaskOwner.of(context))).isEmpty();
        assertThat(stopTool.execute(ToolInput.of(Map.of("monitorId", id)), context).isError()).isTrue();
    }

    @Test
    @DisplayName("a shell without STREAMING is refused with the reason")
    void nonStreamingShellIsRefused() {
        final ToolResult result = tool.execute(ToolInput.of(Map.of("description", "d", "command", "tail -f x")),
                context(new NonStreamingShell()));

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("cannot stream");
    }

    @Test
    @DisplayName("exactly one form: none, two, and params without source are refused")
    void exactlyOneForm() {
        final ToolContext context = context(shell);
        assertThat(tool.execute(ToolInput.of(Map.of("description", "d")), context).getContent())
                .contains("exactly one of command, ws or source");
        assertThat(tool.execute(ToolInput.of(Map.of("description", "d", "command", "x", "source", "y")), context)
                .getContent()).contains("this call gives command, source");
        assertThat(tool.execute(ToolInput.of(Map.of("description", "d", "command", "x", "params", Map.of())), context)
                .getContent()).contains("params belong to source");
    }

    @Test
    @DisplayName("an unregistered source kind is refused; the ws form reaches the websocket source and its policy")
    void unregisteredKinds() {
        final ToolContext context = context(shell);
        assertThat(tool.execute(ToolInput.of(Map.of("description", "d", "source", "kafka")), context).getContent())
                .contains("Unknown monitor source 'kafka'");
        assertThat(tool
                .execute(ToolInput.of(Map.of("description", "d", "ws", Map.of("url", "ws://127.0.0.1:1/"))), context)
                .getContent()).contains("does not allow");
    }

    @Test
    @DisplayName("the command form is judged as a command; the other forms by the tool's own rule")
    void permissions() {
        final ToolContext context = ToolContext.empty();
        assertThat(tool.permissionSubject(ToolInput.of(Map.of("command", "tail -f a.log")), context))
                .contains(PermissionSubject.command("tail -f a.log"));
        assertThat(tool.permissionSubject(ToolInput.of(Map.of("source", "alarms")), context)).isEmpty();

        final List<AllowedTool> allowed = List.of(AllowedTool.parse("Monitor(source:alarms)"),
                AllowedTool.parse("Monitor(ws:wss://events.example.com/*)"));
        assertThat(tool.getCustomPermissionRule().isAllowed(ToolInput.of(Map.of("source", "alarms")), context, allowed))
                .isTrue();
        assertThat(tool.getCustomPermissionRule().isAllowed(ToolInput.of(Map.of("source", "kafka")), context, allowed))
                .isFalse();
        assertThat(tool.getCustomPermissionRule().isAllowed(
                ToolInput.of(Map.of("ws", Map.of("url", "wss://events.example.com/stream?x=1"))), context, allowed))
                .isTrue();
        assertThat(tool.getCustomPermissionRule()
                .isAllowed(ToolInput.of(Map.of("ws", Map.of("url", "wss://evil.example.com/"))), context, allowed))
                .isFalse();
        assertThat(tool.getCustomPermissionRule().isAllowed(ToolInput.of(Map.of("source", "kafka")), context,
                List.of(AllowedTool.parse("Monitor")))).isTrue();
    }

    @Test
    @DisplayName("a command is also judged by the Bash entries; the other forms delegate nothing")
    void commandDelegatesToBash() {
        final ToolContext context = ToolContext.empty();
        assertThat(tool.permissionDelegation(ToolInput.of(Map.of("command", "tail -f a.log")), context))
                .contains(PermissionDelegation.to("Bash", PermissionSubject.command("tail -f a.log")));
        assertThat(tool.permissionDelegation(ToolInput.of(Map.of("source", "alarms")), context)).isEmpty();

        final DefaultToolPermissionValidator validator = new DefaultToolPermissionValidator();
        final List<AllowedTool> allowed = List.of(AllowedTool.parse("Monitor"), AllowedTool.parse("Bash(git:*)"));
        assertThat(validator
                .validate(tool, ToolInput.of(Map.of("description", "d", "command", "rm -rf /")), context, allowed)
                .isAllowed()).isFalse();
        assertThat(validator
                .validate(tool, ToolInput.of(Map.of("description", "d", "command", "git log")), context, allowed)
                .isAllowed()).isTrue();
    }

    private static void await(BooleanSupplier condition) {
        final long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within 10s");
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    /** A shell that runs nothing and streams nothing. */
    private static final class NonStreamingShell implements VirtualShell {

        @Override
        public ShellCommandResult execute(ShellCommand command) {
            return execute(command, ExecutionOptions.defaults());
        }

        @Override
        public ShellCommandResult execute(ShellCommand command, ExecutionOptions options) {
            return new ShellCommandResult(0, "", "", Duration.ZERO);
        }

        @Override
        public String getWorkingDirectory() {
            return "/";
        }

        @Override
        public boolean supports(ShellFeature feature) {
            return feature == ShellFeature.CANCELLATION;
        }

        @Override
        public void close() {
        }
    }
}
