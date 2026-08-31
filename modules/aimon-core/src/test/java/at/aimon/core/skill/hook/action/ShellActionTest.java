package at.aimon.core.skill.hook.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class ShellActionTest {

    @Test
    void getCommand_returnsConfiguredCommand() {
        ShellAction action = new ShellAction("echo hi", Duration.ofSeconds(3));

        assertThat(action.getCommand()).isEqualTo("echo hi");
        assertThat(action.getTimeout()).isEqualTo(Duration.ofSeconds(3));
    }

    @Test
    void constructor_nullTimeout_resolvesToDefault() {
        ShellAction action = new ShellAction("echo hi", null);

        assertThat(action.getTimeout()).isEqualTo(ShellAction.DEFAULT_TIMEOUT);
    }

    @Test
    void constructor_nullCommand_throws() {
        assertThatThrownBy(() -> new ShellAction(null, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void constructor_blankCommand_throws() {
        assertThatThrownBy(() -> new ShellAction("   ", null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
    }

    @Test
    void constructor_zeroTimeout_throws() {
        assertThatThrownBy(() -> new ShellAction("echo", Duration.ZERO)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
    }

    @Test
    void constructor_negativeTimeout_throws() {
        assertThatThrownBy(() -> new ShellAction("echo", Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("positive");
    }

    @Test
    void equalsAndHashCode_basedOnCommandAndTimeout() {
        ShellAction a = new ShellAction("ls", Duration.ofSeconds(1));
        ShellAction b = new ShellAction("ls", Duration.ofSeconds(1));
        ShellAction c = new ShellAction("ls", Duration.ofSeconds(2));
        ShellAction d = new ShellAction("pwd", Duration.ofSeconds(1));

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(c).isNotEqualTo(d);
    }

    @Test
    void toString_includesCommandAndTimeout() {
        assertThat(new ShellAction("echo done", Duration.ofMillis(500)).toString()).contains("echo done")
                .contains("PT0.5S");
    }

    @Test
    void implementsHookActionMarker() {
        assertThat((HookAction) new ShellAction("x", null)).isInstanceOf(ShellAction.class);
    }
}
