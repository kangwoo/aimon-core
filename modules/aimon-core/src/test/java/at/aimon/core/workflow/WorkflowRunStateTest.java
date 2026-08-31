package at.aimon.core.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("WorkflowRunState — terminal predicate")
class WorkflowRunStateTest {

    @Test
    @DisplayName("PENDING and RUNNING are non-terminal")
    void nonTerminal() {
        assertThat(WorkflowRunState.PENDING.isTerminal()).isFalse();
        assertThat(WorkflowRunState.RUNNING.isTerminal()).isFalse();
    }

    @Test
    @DisplayName("COMPLETED, FAILED, KILLED are terminal")
    void terminal() {
        assertThat(WorkflowRunState.COMPLETED.isTerminal()).isTrue();
        assertThat(WorkflowRunState.FAILED.isTerminal()).isTrue();
        assertThat(WorkflowRunState.KILLED.isTerminal()).isTrue();
    }
}
