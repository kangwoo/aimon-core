package at.aimon.core.tools.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.subagent.SubagentTaskController;
import at.aimon.core.subagent.task.BackgroundTask;
import at.aimon.core.subagent.task.BackgroundTaskState;
import at.aimon.core.tools.ToolContextKeys;

@DisplayName("TaskStopTool — cooperative stop of a running background task")
class TaskStopToolTest {

    private SubagentTaskController controller;
    private TaskStopTool tool;

    @BeforeEach
    void setUp() {
        controller = mock(SubagentTaskController.class);
        tool = new TaskStopTool(controller);
    }

    @Test
    @DisplayName("a live handle: stop request is acknowledged")
    void stopRequested() {
        when(controller.stop("t1")).thenReturn(true);

        ToolResult result = tool.execute(ToolInput.of("taskId", "t1"), ToolContext.empty());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("Stop requested").contains("t1");
    }

    @Test
    @DisplayName("no live handle but a known snapshot: reports the non-running state, nothing to stop")
    void alreadyTerminalSnapshot() {
        when(controller.stop("t1")).thenReturn(false);
        when(controller.status("t1")).thenReturn(Optional.of(BackgroundTask.builder().taskId("t1")
                .subagentName("explore").state(BackgroundTaskState.COMPLETED).startTime(Instant.now()).build()));

        ToolResult result = tool.execute(ToolInput.of("taskId", "t1"), ToolContext.empty());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("not running").contains("COMPLETED");
    }

    @Test
    @DisplayName("no handle and no snapshot: task not found is an error")
    void notFoundIsError() {
        when(controller.stop("ghost")).thenReturn(false);
        when(controller.status("ghost")).thenReturn(Optional.empty());

        ToolResult result = tool.execute(ToolInput.of("taskId", "ghost"), ToolContext.empty());

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("Task not found").contains("ghost");
    }

    @Test
    @DisplayName("a task owned by another agent context is reported not found and is never stopped")
    void foreignContextTaskIsDenied() {
        final AgentRuntimeId mine = AgentRuntimeId.of("agent:alpha");
        final AgentRuntimeId other = AgentRuntimeId.of("agent:bravo");
        // The store knows the task, but it belongs to another agent's context.
        when(controller.status("b1"))
                .thenReturn(Optional.of(BackgroundTask.builder().taskId("b1").subagentName("explore")
                        .state(BackgroundTaskState.RUNNING).startTime(Instant.now()).agentRuntimeId(other).build()));
        final ToolContext context = ToolContext.builder().put(ToolContextKeys.AGENT_RUNTIME_ID, mine).build();

        ToolResult result = tool.execute(ToolInput.of("taskId", "b1"), context);

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("Task not found").contains("b1");
        // Authorization: the foreign task must never reach the underlying stop.
        verify(controller, never()).stop(any());
    }

    @Test
    @DisplayName("a task in the caller's own context is stopped")
    void ownContextTaskIsStopped() {
        final AgentRuntimeId mine = AgentRuntimeId.of("agent:alpha");
        when(controller.status("a1"))
                .thenReturn(Optional.of(BackgroundTask.builder().taskId("a1").subagentName("explore")
                        .state(BackgroundTaskState.RUNNING).startTime(Instant.now()).agentRuntimeId(mine).build()));
        when(controller.stop("a1")).thenReturn(true);
        final ToolContext context = ToolContext.builder().put(ToolContextKeys.AGENT_RUNTIME_ID, mine).build();

        ToolResult result = tool.execute(ToolInput.of("taskId", "a1"), context);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("Stop requested").contains("a1");
        verify(controller).stop("a1");
    }

    @Test
    @DisplayName("a missing taskId is a caller error")
    void missingTaskIdIsError() {
        ToolResult result = tool.execute(ToolInput.of(Map.of()), ToolContext.empty());

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("Invalid parameter");
    }

    @Test
    @DisplayName("null constructor / execute arguments are rejected")
    void rejectsNulls() {
        assertThatNullPointerException().isThrownBy(() -> new TaskStopTool(null));
        assertThatNullPointerException().isThrownBy(() -> tool.execute(null, ToolContext.empty()));
        assertThatNullPointerException().isThrownBy(() -> tool.execute(ToolInput.of("taskId", "t1"), null));
    }
}
