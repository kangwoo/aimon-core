package at.aimon.core.skill.hook.declarative;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import at.aimon.core.agent.Environment;
import at.aimon.core.agent.InvokerType;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.hook.event.OnStartContext;
import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.hook.execution.HookStatus;
import at.aimon.core.skill.hook.action.ShellAction;

class DeclarativeOnStartHookTest {

    private static final HookRegistry REGISTRY = new DefaultHookRegistry();
    private static final Environment ENV = Environment.createDefault();

    @Test
    void execute_runsExecutorWithExpectedEnv() {
        RecordingExecutor exec = new RecordingExecutor();
        ShellAction action = new ShellAction("echo started", Duration.ofSeconds(1));
        DeclarativeOnStartHook hook = new DeclarativeOnStartHook("my-skill", action, exec);

        HookResult result = hook.execute(contextFor("hello world"));

        assertThat(result.getStatus()).isEqualTo(HookStatus.SUCCESS);
        assertThat(exec.calls).hasSize(1);
        assertThat(exec.calls.get(0).action).isSameAs(action);
        Map<String, String> env = exec.calls.get(0).env;
        assertThat(env).containsEntry(SkillHookEnv.AIMON_HOOK_EVENT, "onStart")
                .containsEntry(SkillHookEnv.AIMON_SKILL_NAME, "my-skill")
                .containsEntry(SkillHookEnv.AIMON_INVOKER_NAME, "default-agent")
                .containsEntry(SkillHookEnv.AIMON_INVOKER_TYPE, InvokerType.MAIN_AGENT.name())
                .containsEntry(SkillHookEnv.AIMON_USER_MESSAGE_LENGTH, Integer.toString("hello world".length()));
        assertThat(env).doesNotContainKey(SkillHookEnv.AIMON_TOOL_NAME);
    }

    @Test
    void execute_emptyUserMessage_lengthZero() {
        RecordingExecutor exec = new RecordingExecutor();
        DeclarativeOnStartHook hook = new DeclarativeOnStartHook("s", new ShellAction("x", Duration.ofSeconds(1)),
                exec);

        hook.execute(contextFor(""));

        assertThat(exec.calls.get(0).env).containsEntry(SkillHookEnv.AIMON_USER_MESSAGE_LENGTH, "0");
    }

    @Test
    void execute_nullContext_throws() {
        DeclarativeOnStartHook hook = new DeclarativeOnStartHook("s", new ShellAction("x", Duration.ofSeconds(1)),
                NoOpShellActionExecutor.INSTANCE);

        assertThatThrownBy(() -> hook.execute(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void constructor_nullArgs_throw() {
        ShellAction action = new ShellAction("x", Duration.ofSeconds(1));

        assertThatThrownBy(() -> new DeclarativeOnStartHook(null, action, NoOpShellActionExecutor.INSTANCE))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DeclarativeOnStartHook("s", null, NoOpShellActionExecutor.INSTANCE))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DeclarativeOnStartHook("s", action, null))
                .isInstanceOf(NullPointerException.class);
    }

    private static OnStartContext contextFor(String userMessage) {
        return OnStartContext.builder().executorType(InvokerType.MAIN_AGENT).invokerName("default-agent")
                .hookRegistry(REGISTRY).environment(ENV).userMessage(userMessage).build();
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
