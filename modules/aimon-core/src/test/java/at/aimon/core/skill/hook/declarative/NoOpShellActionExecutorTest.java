package at.aimon.core.skill.hook.declarative;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;

import at.aimon.core.skill.hook.action.ShellAction;

class NoOpShellActionExecutorTest {

    @Test
    void isShellSupported_returnsFalse() {
        assertThat(NoOpShellActionExecutor.INSTANCE.isShellSupported()).isFalse();
    }

    @Test
    void run_doesNotThrow() {
        ShellAction action = new ShellAction("echo hi", Duration.ofSeconds(1));

        // Must not throw — this is the fail-soft contract.
        NoOpShellActionExecutor.INSTANCE.run(action, Map.of("AIMON_HOOK_EVENT", "preTool"));
    }

    @Test
    void run_nullAction_throwsNpe() {
        assertThatThrownBy(() -> NoOpShellActionExecutor.INSTANCE.run(null, Map.of()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void run_nullEnv_throwsNpe() {
        ShellAction action = new ShellAction("echo hi", Duration.ofSeconds(1));

        assertThatThrownBy(() -> NoOpShellActionExecutor.INSTANCE.run(action, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void instance_isSingleton() {
        assertThat(NoOpShellActionExecutor.INSTANCE).isSameAs(NoOpShellActionExecutor.INSTANCE);
    }
}
