package at.aimon.core.skill.hook.declarative;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import at.aimon.core.agent.Environment;
import at.aimon.core.agent.InvokerType;
import at.aimon.core.command.execution.ExecutionMetadata;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.hook.event.OnStopContext;
import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.hook.execution.HookStatus;
import at.aimon.core.skill.hook.action.ShellAction;

class DeclarativeOnStopHookTest {

    private static final HookRegistry REGISTRY = new DefaultHookRegistry();
    private static final Environment ENV = Environment.createDefault();

    @Test
    void execute_runsExecutorWithExpectedEnv() {
        RecordingExecutor exec = new RecordingExecutor();
        ShellAction action = new ShellAction("echo stopped", Duration.ofSeconds(1));
        DeclarativeOnStopHook hook = new DeclarativeOnStopHook("my-skill", action, exec);

        HookResult result = hook.execute(contextFor(true, 7));

        assertThat(result.getStatus()).isEqualTo(HookStatus.SUCCESS);
        assertThat(exec.calls).hasSize(1);
        assertThat(exec.calls.get(0).action).isSameAs(action);
        Map<String, String> env = exec.calls.get(0).env;
        assertThat(env).containsEntry(SkillHookEnv.AIMON_HOOK_EVENT, "onStop")
                .containsEntry(SkillHookEnv.AIMON_SKILL_NAME, "my-skill")
                .containsEntry(SkillHookEnv.AIMON_INVOKER_NAME, "default-agent")
                .containsEntry(SkillHookEnv.AIMON_INVOKER_TYPE, InvokerType.MAIN_AGENT.name())
                .containsEntry(SkillHookEnv.AIMON_SUCCESS, "true")
                .containsEntry(SkillHookEnv.AIMON_ITERATION_COUNT, "7");
    }

    @Test
    void execute_failureRunSetsAimonSuccessFalse() {
        RecordingExecutor exec = new RecordingExecutor();
        DeclarativeOnStopHook hook = new DeclarativeOnStopHook("s", new ShellAction("x", Duration.ofSeconds(1)), exec);

        hook.execute(contextFor(false, 2));

        assertThat(exec.calls.get(0).env).containsEntry(SkillHookEnv.AIMON_SUCCESS, "false")
                .containsEntry(SkillHookEnv.AIMON_ITERATION_COUNT, "2");
    }

    @Test
    void execute_nullContext_throws() {
        DeclarativeOnStopHook hook = new DeclarativeOnStopHook("s", new ShellAction("x", Duration.ofSeconds(1)),
                NoOpShellActionExecutor.INSTANCE);

        assertThatThrownBy(() -> hook.execute(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void constructor_nullArgs_throw() {
        ShellAction action = new ShellAction("x", Duration.ofSeconds(1));

        assertThatThrownBy(() -> new DeclarativeOnStopHook(null, action, NoOpShellActionExecutor.INSTANCE))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DeclarativeOnStopHook("s", null, NoOpShellActionExecutor.INSTANCE))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DeclarativeOnStopHook("s", action, null)).isInstanceOf(NullPointerException.class);
    }

    private static OnStopContext contextFor(boolean success, int iterations) {
        Instant now = Instant.now();
        ExecutionMetadata metadata = ExecutionMetadata.builder().iterationCount(iterations)
                .duration(Duration.ofMillis(50)).startTime(now.minusMillis(50)).endTime(now).build();
        return OnStopContext.builder().executorType(InvokerType.MAIN_AGENT).invokerName("default-agent")
                .hookRegistry(REGISTRY).environment(ENV).success(success).finalAnswer("done").metadata(metadata)
                .build();
    }

    private static final class RecordingExecutor implements ShellActionExecutor {
        private final List<Call> calls = new ArrayList<>();

        @Override
        public boolean isShellSupported() {
            return true;
        }

        @Override
        public void run(ShellAction action, Map<String, String> environmentOverrides) {
            calls.add(new Call(action, Map.copyOf(environmentOverrides)));
        }

        record Call(ShellAction action, Map<String, String> env) {
        }
    }
}
