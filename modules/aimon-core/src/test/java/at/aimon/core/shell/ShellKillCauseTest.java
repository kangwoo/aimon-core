package at.aimon.core.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ShellCommandResult — kill cause")
class ShellKillCauseTest {

    @Test
    @DisplayName("absent by default; withKillCause copies the result and says why the shell ended the command")
    void killCause() {
        final ShellCommandResult plain = new ShellCommandResult(137, "o", "e", Duration.ofSeconds(1));
        assertThat(plain.killCause()).isEmpty();
        final ShellCommandResult killed = plain.withKillCause(ShellKillCause.LINE_LIMIT_EXCEEDED);
        assertThat(killed.killCause()).hasValue(ShellKillCause.LINE_LIMIT_EXCEEDED);
        assertThat(killed.exitCode()).isEqualTo(137);
        assertThat(killed.stdout()).isEqualTo("o");
        assertThat(killed).isNotEqualTo(plain);
        assertThat(killed.toString()).contains("LINE_LIMIT_EXCEEDED");
        assertThat(killed).isEqualTo(plain.withKillCause(ShellKillCause.LINE_LIMIT_EXCEEDED));
    }
}
