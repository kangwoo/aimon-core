package at.aimon.core.skill.hook.declarative;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import at.aimon.core.agent.Environment;
import at.aimon.core.agent.InvokerType;
import at.aimon.core.agent.compact.CompactionTrigger;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.command.execution.ExecutionMetadata;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.hook.event.OnStartContext;
import at.aimon.core.hook.event.OnStopContext;
import at.aimon.core.hook.event.PermissionRequestContext;
import at.aimon.core.hook.event.PreCompactContext;
import at.aimon.core.hook.event.SubagentStopContext;
import at.aimon.core.hook.execution.Decision;
import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.hook.execution.HookStatus;
import at.aimon.core.skill.hook.action.ShellAction;

/**
 * Exit-code contract of the shared declarative shell-hook base class.
 *
 * <p>
 * This is the security-relevant mapping: which events turn an exit-{@value ShellHookOutcome#DENY_EXIT_CODE} veto into
 * an actual block/deny, and which ones are advisory and must swallow it. The stub executor deliberately overrides the
 * <em>three-argument</em> {@code run} — the two-argument overload never reports an outcome, so a stub that only
 * implements it can never reach the veto branch at all.
 */
class AbstractDeclarativeShellHookTest {

    private static final HookRegistry REGISTRY = new DefaultHookRegistry();
    private static final Environment ENV = Environment.createDefault();
    private static final ShellAction ACTION = new ShellAction("gate.sh", Duration.ofSeconds(1));

    // --- onStart: blocks (OrcaAgentExecutor aborts the turn) -----------------------------------------------------

    @Test
    void onStart_exitTwo_blocksWithStderrAsFeedback() {
        RecordingExecutor exec = RecordingExecutor.exiting(2, "start gate refused: repo is dirty");
        DeclarativeOnStartHook hook = new DeclarativeOnStartHook("my-skill", ACTION, exec);

        HookResult result = hook.execute(onStartContext());

        assertThat(result.getStatus()).isEqualTo(HookStatus.BLOCKED);
        assertThat(result.getDecision()).isEqualTo(Decision.DENY);
        assertThat(result.getFeedback()).contains("start gate refused: repo is dirty");
    }

    @Test
    void onStart_exitZero_succeeds() {
        RecordingExecutor exec = RecordingExecutor.exiting(0, "");
        DeclarativeOnStartHook hook = new DeclarativeOnStartHook("my-skill", ACTION, exec);

        assertThat(hook.execute(onStartContext()).getStatus()).isEqualTo(HookStatus.SUCCESS);
    }

    @Test
    void onStart_crashedScript_failsSoftToSuccess() {
        RecordingExecutor exec = RecordingExecutor.exiting(1, "gate.sh: command not found");
        DeclarativeOnStartHook hook = new DeclarativeOnStartHook("my-skill", ACTION, exec);

        HookResult result = hook.execute(onStartContext());

        assertThat(result.getStatus()).isEqualTo(HookStatus.SUCCESS);
        assertThat(result.getFeedback()).isEmpty();
    }

    @Test
    void onStart_blankStderr_fallsBackToGenericReason() {
        RecordingExecutor exec = RecordingExecutor.exiting(2, "   \n");
        DeclarativeOnStartHook hook = new DeclarativeOnStartHook("my-skill", ACTION, exec);

        HookResult result = hook.execute(onStartContext());

        assertThat(result.getStatus()).isEqualTo(HookStatus.BLOCKED);
        assertThat(result.getFeedback()).hasValueSatisfying(
                feedback -> assertThat(feedback).contains("Blocked by a shell hook").contains("exit code 2"));
    }

    // --- preCompact: blocks --------------------------------------------------------------------------------------

    @Test
    void preCompact_exitTwo_blocksWithStderrAsFeedback() {
        RecordingExecutor exec = RecordingExecutor.exiting(2, "compaction not allowed mid-incident");
        DeclarativePreCompactHook hook = new DeclarativePreCompactHook("my-skill", ACTION, exec);

        HookResult result = hook.execute(preCompactContext());

        assertThat(result.getStatus()).isEqualTo(HookStatus.BLOCKED);
        assertThat(result.getFeedback()).contains("compaction not allowed mid-incident");
    }

    @Test
    void preCompact_exitZero_succeeds() {
        RecordingExecutor exec = RecordingExecutor.exiting(0, "");
        DeclarativePreCompactHook hook = new DeclarativePreCompactHook("my-skill", ACTION, exec);

        assertThat(hook.execute(preCompactContext()).getStatus()).isEqualTo(HookStatus.SUCCESS);
    }

    @Test
    void preCompact_crashedScript_failsSoftToSuccess() {
        RecordingExecutor exec = RecordingExecutor.exiting(127, "boom");
        DeclarativePreCompactHook hook = new DeclarativePreCompactHook("my-skill", ACTION, exec);

        assertThat(hook.execute(preCompactContext()).getStatus()).isEqualTo(HookStatus.SUCCESS);
    }

    // --- permissionRequest: denies -------------------------------------------------------------------------------

    @Test
    void permissionRequest_exitTwo_deniesWithStderrAsReason() {
        RecordingExecutor exec = RecordingExecutor.exiting(2, "Bash is not permitted for this principal");
        DeclarativePermissionRequestHook hook = new DeclarativePermissionRequestHook("my-skill", ACTION, exec);

        HookResult result = hook.execute(permissionRequestContext());

        assertThat(result.getDecision()).isEqualTo(Decision.DENY);
        assertThat(result.isBlocked()).isTrue();
        assertThat(result.getFeedback()).contains("Bash is not permitted for this principal");
    }

    @Test
    void permissionRequest_exitZero_allows() {
        RecordingExecutor exec = RecordingExecutor.exiting(0, "");
        DeclarativePermissionRequestHook hook = new DeclarativePermissionRequestHook("my-skill", ACTION, exec);

        assertThat(hook.execute(permissionRequestContext()).getDecision()).isEqualTo(Decision.ALLOW);
    }

    @Test
    void permissionRequest_crashedScript_failsSoftToAllow() {
        RecordingExecutor exec = RecordingExecutor.exiting(3, "auth backend unreachable");
        DeclarativePermissionRequestHook hook = new DeclarativePermissionRequestHook("my-skill", ACTION, exec);

        assertThat(hook.execute(permissionRequestContext()).getDecision()).isEqualTo(Decision.ALLOW);
    }

    // --- onStop: advisory, the veto is ignored -------------------------------------------------------------------

    @Test
    void onStop_exitTwo_isAdvisorySoVetoIsIgnored() {
        RecordingExecutor exec = RecordingExecutor.exiting(2, "too late to stop me");
        DeclarativeOnStopHook hook = new DeclarativeOnStopHook("my-skill", ACTION, exec);

        HookResult result = hook.execute(onStopContext());

        assertThat(result.getStatus()).isEqualTo(HookStatus.SUCCESS);
        assertThat(result.isBlocked()).isFalse();
        assertThat(result.getFeedback()).isEmpty();
    }

    @Test
    void onStop_exitZero_succeeds() {
        RecordingExecutor exec = RecordingExecutor.exiting(0, "");
        DeclarativeOnStopHook hook = new DeclarativeOnStopHook("my-skill", ACTION, exec);

        assertThat(hook.execute(onStopContext()).getStatus()).isEqualTo(HookStatus.SUCCESS);
    }

    @Test
    void subagentStop_exitTwo_isAdvisorySoVetoIsIgnored() {
        RecordingExecutor exec = RecordingExecutor.exiting(2, "nope");
        DeclarativeSubagentStopHook hook = new DeclarativeSubagentStopHook("my-skill", ACTION, exec);

        HookResult result = hook.execute(subagentStopContext());

        assertThat(result.getStatus()).isEqualTo(HookStatus.SUCCESS);
        assertThat(result.isBlocked()).isFalse();
    }

    // --- shared env + stdin payload ------------------------------------------------------------------------------

    @Test
    void execute_passesSharedEnvAndJsonPayloadOnThreeArgRun() {
        RecordingExecutor exec = RecordingExecutor.exiting(0, "");
        DeclarativePermissionRequestHook hook = new DeclarativePermissionRequestHook("my-skill", ACTION, exec);

        hook.execute(permissionRequestContext());

        assertThat(exec.calls).hasSize(1);
        RecordingExecutor.Call call = exec.calls.get(0);
        assertThat(call.action).isSameAs(ACTION);
        assertThat(call.env).containsEntry(SkillHookEnv.AIMON_HOOK_EVENT, DeclarativePermissionRequestHook.EVENT_NAME)
                .containsEntry(SkillHookEnv.AIMON_SKILL_NAME, "my-skill")
                .containsEntry(SkillHookEnv.AIMON_INVOKER_NAME, "default-agent")
                .containsEntry(SkillHookEnv.AIMON_INVOKER_TYPE, InvokerType.MAIN_AGENT.name())
                .containsEntry(SkillHookEnv.AIMON_TOOL_NAME, "Bash");
        assertThat(call.stdinPayload).contains("\"hook_event\":\"permissionRequest\"")
                .contains("\"skill_name\":\"my-skill\"").contains("\"tool_input\":{\"command\":\"ls\"}");
    }

    // --- fixtures ------------------------------------------------------------------------------------------------

    private static OnStartContext onStartContext() {
        return OnStartContext.builder().executorType(InvokerType.MAIN_AGENT).invokerName("default-agent")
                .hookRegistry(REGISTRY).environment(ENV).userMessage("deploy please").build();
    }

    private static PreCompactContext preCompactContext() {
        return PreCompactContext.builder().invokerType(InvokerType.MAIN_AGENT).invokerName("default-agent")
                .hookRegistry(REGISTRY).environment(ENV).trigger(CompactionTrigger.AUTO).sessionIdValue("conv-1")
                .messageCount(42).estimatedTokens(120_000).build();
    }

    private static PermissionRequestContext permissionRequestContext() {
        return PermissionRequestContext.builder().invokerType(InvokerType.MAIN_AGENT).invokerName("default-agent")
                .hookRegistry(REGISTRY).environment(ENV).toolName("Bash")
                .toolInput(ToolInput.of(Map.of("command", "ls"))).build();
    }

    private static OnStopContext onStopContext() {
        final Instant now = Instant.now();
        final ExecutionMetadata metadata = ExecutionMetadata.builder().iterationCount(3).duration(Duration.ofMillis(50))
                .startTime(now.minusMillis(50)).endTime(now).build();
        return OnStopContext.builder().executorType(InvokerType.MAIN_AGENT).invokerName("default-agent")
                .hookRegistry(REGISTRY).environment(ENV).success(true).finalAnswer("done").metadata(metadata).build();
    }

    private static SubagentStopContext subagentStopContext() {
        return SubagentStopContext.builder().invokerType(InvokerType.MAIN_AGENT).invokerName("default-agent")
                .hookRegistry(REGISTRY).environment(ENV).subagentName("Explore").taskId("t-1").success(true).build();
    }

    /**
     * Executor stub that reports a real exit status.
     *
     * <p>
     * It overrides the three-argument {@code run} on purpose: the default implementation of that overload delegates to
     * the two-argument one and returns {@link ShellHookOutcome#notObserved()}, which can never deny — a stub that only
     * overrides the two-argument overload would silently pass every veto test.
     */
    private static final class RecordingExecutor implements ShellActionExecutor {

        private final List<Call> calls = new ArrayList<>();
        private final int exitCode;
        private final String stderr;

        private RecordingExecutor(int exitCode, String stderr) {
            this.exitCode = exitCode;
            this.stderr = stderr;
        }

        static RecordingExecutor exiting(int exitCode, String stderr) {
            return new RecordingExecutor(exitCode, stderr);
        }

        @Override
        public boolean isShellSupported() {
            return true;
        }

        @Override
        public void run(ShellAction action, Map<String, String> environmentOverrides) {
            run(action, environmentOverrides, null);
        }

        @Override
        public ShellHookOutcome run(ShellAction action, Map<String, String> environmentOverrides, String stdinPayload) {
            calls.add(new Call(action, Map.copyOf(environmentOverrides), stdinPayload));
            return ShellHookOutcome.of(exitCode, "", stderr);
        }

        record Call(ShellAction action, Map<String, String> env, String stdinPayload) {
        }
    }
}
