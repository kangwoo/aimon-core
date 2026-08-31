package at.aimon.core.subagent.task;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("BackgroundTaskState — terminal predicate gates idempotent transitions")
class BackgroundTaskStateTest {

    @Test
    @DisplayName("PENDING and RUNNING are non-terminal")
    void nonTerminalStates() {
        assertThat(BackgroundTaskState.PENDING.isTerminal()).isFalse();
        assertThat(BackgroundTaskState.RUNNING.isTerminal()).isFalse();
    }

    @Test
    @DisplayName("COMPLETED, FAILED and KILLED are terminal")
    void terminalStates() {
        assertThat(BackgroundTaskState.COMPLETED.isTerminal()).isTrue();
        assertThat(BackgroundTaskState.FAILED.isTerminal()).isTrue();
        assertThat(BackgroundTaskState.KILLED.isTerminal()).isTrue();
    }
}
