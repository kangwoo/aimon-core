package at.aimon.core.skill.hook.declarative;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;

import at.aimon.core.agent.Environment;
import at.aimon.core.agent.InvokerType;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.event.OnStartContext;
import at.aimon.core.hook.execution.HookContext;
import at.aimon.core.skill.hook.action.ShellAction;

class NoOpShellActionExecutorTest {

    private static final HookContext CONTEXT = OnStartContext.builder().executorType(InvokerType.MAIN_AGENT)
            .invokerName("agent").hookRegistry(new DefaultHookRegistry()).environment(Environment.createDefault())
            .userMessage("hi").build();

    @Test
    void isShellSupported_returnsFalse() {
        assertThat(NoOpShellActionExecutor.INSTANCE.isShellSupported()).isFalse();
    }

    @Test
    void requiresExecutionEnvironment_returnsFalse() {
        assertThat(NoOpShellActionExecutor.INSTANCE.requiresExecutionEnvironment()).isFalse();
    }

    @Test
    void run_nullContext_throwsNpe() {
        ShellAction action = new ShellAction("echo hi", Duration.ofSeconds(1));

        assertThatThrownBy(() -> NoOpShellActionExecutor.INSTANCE.run(action, null, Map.of(), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void run_doesNotThrow() {
        ShellAction action = new ShellAction("echo hi", Duration.ofSeconds(1));

        // Must not throw — this is the fail-soft contract.
        assertThat(NoOpShellActionExecutor.INSTANCE.run(action, CONTEXT, Map.of("AIMON_HOOK_EVENT", "preTool"), null)
                .isObserved()).isFalse();
    }

    @Test
    void run_nullAction_throwsNpe() {
        assertThatThrownBy(() -> NoOpShellActionExecutor.INSTANCE.run(null, CONTEXT, Map.of(), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void run_nullEnv_throwsNpe() {
        ShellAction action = new ShellAction("echo hi", Duration.ofSeconds(1));

        assertThatThrownBy(() -> NoOpShellActionExecutor.INSTANCE.run(action, CONTEXT, null, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void instance_isSingleton() {
        assertThat(NoOpShellActionExecutor.INSTANCE).isSameAs(NoOpShellActionExecutor.INSTANCE);
    }
}
