package at.aimon.core.agent.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SubagentTaskCompleted — queued-notification metadata")
class SubagentTaskCompletedTest {

    @Test
    @DisplayName("a completion notification yields its task id")
    void notificationYieldsTaskId() {
        assertThat(SubagentTaskCompleted
                .notifiedTaskId(Map.of("kind", "subagent-task-completed", "taskId", "t1", "outcome", "COMPLETED")))
                .contains("t1");
    }

    @Test
    @DisplayName("an input of another kind, or with no kind, is not a notification")
    void otherKindIsNotANotification() {
        assertThat(SubagentTaskCompleted.notifiedTaskId(Map.of("kind", "user-input", "taskId", "t1"))).isEmpty();
        assertThat(SubagentTaskCompleted.notifiedTaskId(Map.of("taskId", "t1"))).isEmpty();
        assertThat(SubagentTaskCompleted.notifiedTaskId(Map.of())).isEmpty();
    }

    @Test
    @DisplayName("a notification without a task id yields nothing")
    void notificationWithoutTaskId() {
        assertThat(SubagentTaskCompleted.notifiedTaskId(Map.of("kind", "subagent-task-completed"))).isEmpty();
    }

    @Test
    @DisplayName("null metadata is rejected")
    void nullMetadataRejected() {
        assertThatNullPointerException().isThrownBy(() -> SubagentTaskCompleted.notifiedTaskId(null));
    }

    @Test
    @DisplayName("the constants are the wire values the manager writes")
    void constantsAreTheWireValues() {
        assertThat(SubagentTaskCompleted.QUEUED_KIND_KEY).isEqualTo("kind");
        assertThat(SubagentTaskCompleted.QUEUED_KIND).isEqualTo("subagent-task-completed");
        assertThat(SubagentTaskCompleted.QUEUED_TASK_ID_KEY).isEqualTo("taskId");
        assertThat(SubagentTaskCompleted.QUEUED_OUTCOME_KEY).isEqualTo("outcome");
        assertThat(SubagentTaskCompleted.RECEIVED_TASK_ID_ATTRIBUTE).isEqualTo("aimon.subagent.receivedTaskId");
    }
}
