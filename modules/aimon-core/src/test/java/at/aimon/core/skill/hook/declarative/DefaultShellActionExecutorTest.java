package at.aimon.core.skill.hook.declarative;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import at.aimon.core.agent.InvokerType;
import at.aimon.core.base.UserLocale;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.environment.TestExecutionEnvironments;
import at.aimon.core.environment.UnavailableExecutionEnvironment;
import at.aimon.core.environment.exception.ExecutionEnvironmentUnavailableException;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.HookEventType;
import at.aimon.core.hook.event.OnSessionStartContext;
import at.aimon.core.hook.event.OnStartContext;
import at.aimon.core.hook.execution.HookContext;
import at.aimon.core.shell.ExecutionOptions;
import at.aimon.core.shell.ShellCommand;
import at.aimon.core.shell.ShellCommandResult;
import at.aimon.core.shell.VirtualShell;
import at.aimon.core.shell.exception.ShellExecutionException;
import at.aimon.core.shell.exception.ShellTimeoutException;
import at.aimon.core.skill.hook.action.ShellAction;

class DefaultShellActionExecutorTest {

    private final DefaultShellActionExecutor executor = new DefaultShellActionExecutor();

    @Test
    void isShellSupported_returnsTrue() {
        assertThat(executor.isShellSupported()).isTrue();
    }

    @Test
    void requiresExecutionEnvironment_returnsTrue() {
        assertThat(executor.requiresExecutionEnvironment()).isTrue();
    }

    @Test
    void canRunOn_onlyEventsThatFireInsideAnExecution() {
        for (HookEventType<?> type : HookEventType.values()) {
            assertThat(executor.canRunOn(type)).as(type.name()).isEqualTo(type.firesInsideExecution());
        }
    }

    @Test
    void run_passesCommandEnvStdinAndTimeoutToTheEnvironmentShell() throws Exception {
        VirtualShell shell = mock(VirtualShell.class);
        when(shell.execute(any(ShellCommand.class), any(ExecutionOptions.class)))
                .thenReturn(new ShellCommandResult(0, "ok", "", Duration.ofMillis(10)));

        Map<String, String> env = Map.of("AIMON_HOOK_EVENT", "preTool", "AIMON_TOOL_NAME", "Bash");
        ShellAction action = new ShellAction("echo hi", Duration.ofSeconds(7));

        ShellHookOutcome outcome = executor.run(action, contextIn(shell), env, "{\"a\":1}");

        ArgumentCaptor<ShellCommand> cmdCaptor = ArgumentCaptor.forClass(ShellCommand.class);
        ArgumentCaptor<ExecutionOptions> optsCaptor = ArgumentCaptor.forClass(ExecutionOptions.class);
        verify(shell).execute(cmdCaptor.capture(), optsCaptor.capture());

        assertThat(cmdCaptor.getValue().asString()).isEqualTo("echo hi");
        assertThat(optsCaptor.getValue().getTimeout()).isEqualTo(Duration.ofSeconds(7));
        assertThat(optsCaptor.getValue().getEnvironment()).containsAllEntriesOf(env);
        assertThat(optsCaptor.getValue().getStdin()).isEqualTo("{\"a\":1}");
        // Marked as a hook's command, so a shell with a persistent session runs it outside the model's session.
        assertThat(optsCaptor.getValue().isHook()).isTrue();
        assertThat(optsCaptor.getValue().isBackground()).isFalse();
        assertThat(outcome.getExitCode()).isZero();
        assertThat(outcome.isDenied()).isFalse();
    }

    @Test
    void run_usesTheShellOfTheContextItIsGiven_notOneBoundEarlier() throws Exception {
        // One executor instance, shared by every execution: each firing must land in its own context's shell.
        VirtualShell first = mock(VirtualShell.class);
        VirtualShell second = mock(VirtualShell.class);
        when(first.execute(any(ShellCommand.class), any(ExecutionOptions.class)))
                .thenReturn(new ShellCommandResult(0, "", "", Duration.ofMillis(1)));
        when(second.execute(any(ShellCommand.class), any(ExecutionOptions.class)))
                .thenReturn(new ShellCommandResult(0, "", "", Duration.ofMillis(1)));
        ShellAction action = new ShellAction("true", Duration.ofSeconds(1));

        executor.run(action, contextIn(first), Map.of(), null);
        verify(first).execute(any(ShellCommand.class), any(ExecutionOptions.class));
        verifyNoInteractions(second);

        executor.run(action, contextIn(second), Map.of(), null);
        verify(second).execute(any(ShellCommand.class), any(ExecutionOptions.class));
    }

    @Test
    void run_copiesEnvDefensively_callerMutationsDoNotLeak() throws Exception {
        VirtualShell shell = mock(VirtualShell.class);
        when(shell.execute(any(ShellCommand.class), any(ExecutionOptions.class)))
                .thenReturn(new ShellCommandResult(0, "", "", Duration.ofMillis(1)));

        Map<String, String> env = new HashMap<>();
        env.put("AIMON_HOOK_EVENT", "preTool");
        ShellAction action = new ShellAction("true", Duration.ofSeconds(1));

        executor.run(action, contextIn(shell), env, null);

        // Mutating the original after the call must not affect what the shell already saw.
        env.put("AIMON_HOOK_EVENT", "postTool");

        ArgumentCaptor<ExecutionOptions> optsCaptor = ArgumentCaptor.forClass(ExecutionOptions.class);
        verify(shell).execute(any(ShellCommand.class), optsCaptor.capture());
        assertThat(optsCaptor.getValue().getEnvironment()).containsEntry("AIMON_HOOK_EVENT", "preTool");
    }

    @Test
    void run_exitTwo_reportsDeny() throws Exception {
        VirtualShell shell = mock(VirtualShell.class);
        when(shell.execute(any(ShellCommand.class), any(ExecutionOptions.class))).thenReturn(
                new ShellCommandResult(ShellHookOutcome.DENY_EXIT_CODE, "", "not allowed", Duration.ofMillis(5)));

        ShellHookOutcome outcome = executor.run(new ShellAction("guard", Duration.ofSeconds(1)), contextIn(shell),
                Map.of(), null);

        assertThat(outcome.isDenied()).isTrue();
        assertThat(outcome.denyReason()).isEqualTo("not allowed");
    }

    @Test
    void run_noExecutionEnvironmentInContext_doesNotRunAndReportsNotObserved() {
        // An out-of-execution context: there is no environment, and there must be no host fallback.
        HookContext context = OnSessionStartContext.builder().invokerType(InvokerType.MAIN_AGENT).invokerName("agent")
                .hookRegistry(new DefaultHookRegistry()).userLocale(UserLocale.createDefault()).build();
        assertThat(context.getExecutionEnvironment()).isEmpty();

        ShellHookOutcome outcome = executor.run(new ShellAction("touch /tmp/should-not-exist", Duration.ofSeconds(1)),
                context, Map.of(SkillHookEnv.AIMON_SKILL_NAME, "s", SkillHookEnv.AIMON_HOOK_EVENT, "onSessionStart"),
                null);

        assertThat(outcome.isObserved()).isFalse();
        assertThat(outcome.isDenied()).isFalse();
        assertThat(outcome.getUnrunCause()).contains(ShellHookOutcome.Unrun.NO_ENVIRONMENT);
    }

    @Test
    void run_inExecutionContextBuiltWithoutEnvironment_doesNotRun() {
        HookContext context = OnStartContext.builder().executorType(InvokerType.MAIN_AGENT).invokerName("agent")
                .hookRegistry(new DefaultHookRegistry()).userLocale(UserLocale.createDefault()).userMessage("hi")
                .build();

        ShellHookOutcome outcome = executor.run(new ShellAction("true", Duration.ofSeconds(1)), context, Map.of(),
                null);

        assertThat(outcome.getUnrunCause()).contains(ShellHookOutcome.Unrun.NO_ENVIRONMENT);
    }

    @Test
    void run_unavailableEnvironment_reportsNotObservedWithoutThrowing() {
        ExecutionEnvironment unavailable = UnavailableExecutionEnvironment.of("sandbox is down");

        ShellHookOutcome outcome = executor.run(new ShellAction("guard", Duration.ofSeconds(1)), contextIn(unavailable),
                Map.of(), null);

        assertThat(outcome.isObserved()).isFalse();
        assertThat(outcome.isDenied()).isFalse();
        assertThat(outcome.getUnrunCause()).contains(ShellHookOutcome.Unrun.ENVIRONMENT_UNAVAILABLE);
        assertThat(outcome.unrunReason()).contains("sandbox is down");
    }

    @Test
    void run_environmentThatGivesNoShell_reportsEnvironmentUnavailable() {
        ExecutionEnvironment broken = mock(ExecutionEnvironment.class);
        when(broken.shell()).thenThrow(new IllegalStateException("no shell here: provider internals"));

        ShellHookOutcome outcome = executor.run(new ShellAction("guard", Duration.ofSeconds(1)), contextIn(broken),
                Map.of(), null);

        assertThat(outcome.getUnrunCause()).contains(ShellHookOutcome.Unrun.ENVIRONMENT_UNAVAILABLE);
        // The provider's message stays in the log; the model reads only the type.
        assertThat(outcome.unrunReason()).contains("IllegalStateException").doesNotContain("provider internals");
    }

    @Test
    void run_environmentLostWhileExecuting_reportsEnvironmentUnavailable() throws Exception {
        VirtualShell shell = mock(VirtualShell.class);
        when(shell.execute(any(ShellCommand.class), any(ExecutionOptions.class)))
                .thenThrow(new ExecutionEnvironmentUnavailableException("sandbox went away", null));

        ShellHookOutcome outcome = executor.run(new ShellAction("guard", Duration.ofSeconds(1)), contextIn(shell),
                Map.of(), null);

        assertThat(outcome.getUnrunCause()).contains(ShellHookOutcome.Unrun.ENVIRONMENT_UNAVAILABLE);
        assertThat(outcome.unrunReason()).contains("sandbox went away");
    }

    @Test
    void run_closedShell_reportsNotObservedWithoutThrowing() throws Exception {
        // What a skill hook meets when it fires after its environment's provider was torn down.
        VirtualShell shell = mock(VirtualShell.class);
        when(shell.execute(any(ShellCommand.class), any(ExecutionOptions.class)))
                .thenThrow(new IllegalStateException("Shell is closed"));

        ShellHookOutcome outcome = executor.run(new ShellAction("x", Duration.ofSeconds(1)), contextIn(shell), Map.of(),
                null);

        assertThat(outcome.getUnrunCause()).contains(ShellHookOutcome.Unrun.EXECUTION_FAILED);
        assertThat(outcome.unrunReason()).isEqualTo("shell execution failed: IllegalStateException");
    }

    @Test
    void run_nonZeroExit_doesNotThrow() throws Exception {
        VirtualShell shell = mock(VirtualShell.class);
        when(shell.execute(any(ShellCommand.class), any(ExecutionOptions.class)))
                .thenReturn(new ShellCommandResult(7, "", "boom", Duration.ofMillis(5)));

        ShellHookOutcome outcome = executor.run(new ShellAction("false", Duration.ofSeconds(1)), contextIn(shell),
                Map.of(), null);

        // Non-zero exit is logged but never propagated, and only exit 2 is a veto.
        assertThat(outcome.getExitCode()).isEqualTo(7);
        assertThat(outcome.isDenied()).isFalse();
    }

    @Test
    void run_timeoutException_doesNotThrow() throws Exception {
        VirtualShell shell = mock(VirtualShell.class);
        when(shell.execute(any(ShellCommand.class), any(ExecutionOptions.class)))
                .thenThrow(new ShellTimeoutException("timeout", Duration.ofSeconds(1), "", ""));

        ShellHookOutcome outcome = executor.run(new ShellAction("sleep 999", Duration.ofSeconds(1)), contextIn(shell),
                Map.of(), null);

        assertThat(outcome.getUnrunCause()).contains(ShellHookOutcome.Unrun.TIMEOUT);
        assertThat(outcome.unrunReason()).isEqualTo("timed out: no exit status within 1000ms");
    }

    @Test
    void run_executionException_doesNotThrow() throws Exception {
        VirtualShell shell = mock(VirtualShell.class);
        when(shell.execute(any(ShellCommand.class), any(ExecutionOptions.class)))
                .thenThrow(new ShellExecutionException("io fail: nope"));

        ShellHookOutcome outcome = executor.run(new ShellAction("nope", Duration.ofSeconds(1)), contextIn(shell),
                Map.of(), null);

        assertThat(outcome.getUnrunCause()).contains(ShellHookOutcome.Unrun.EXECUTION_FAILED);
        assertThat(outcome.unrunReason()).isEqualTo("shell execution failed: ShellExecutionException");
    }

    @Test
    void run_nullArguments_throwNpe() {
        VirtualShell shell = mock(VirtualShell.class);
        ShellAction action = new ShellAction("echo", Duration.ofSeconds(1));

        assertThatThrownBy(() -> executor.run(null, contextIn(shell), Map.of(), null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> executor.run(action, null, Map.of(), null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> executor.run(action, contextIn(shell), null, null))
                .isInstanceOf(NullPointerException.class);
    }

    private static HookContext contextIn(VirtualShell shell) {
        return contextIn(TestExecutionEnvironments.ofShell(shell));
    }

    private static HookContext contextIn(ExecutionEnvironment executionEnvironment) {
        return OnStartContext.builder().executorType(InvokerType.MAIN_AGENT).invokerName("agent")
                .hookRegistry(new DefaultHookRegistry()).userLocale(UserLocale.createDefault())
                .executionEnvironment(executionEnvironment).userMessage("hi").build();
    }
}
