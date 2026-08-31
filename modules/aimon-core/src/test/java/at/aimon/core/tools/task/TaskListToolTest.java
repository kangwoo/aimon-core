package at.aimon.core.tools.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.tool.ConcurrencyBehavior;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.subagent.SubagentTaskController;
import at.aimon.core.subagent.task.BackgroundTask;
import at.aimon.core.subagent.task.BackgroundTaskState;
import at.aimon.core.subagent.task.TaskQuery;
import at.aimon.core.tools.ToolContextKeys;

@DisplayName("TaskListTool — lists background tasks with optional state filter")
class TaskListToolTest {

    private SubagentTaskController controller;
    private TaskListTool tool;

    @BeforeEach
    void setUp() {
        controller = mock(SubagentTaskController.class);
        tool = new TaskListTool(controller);
    }

    private static BackgroundTask task(String id, BackgroundTaskState state) {
        return BackgroundTask.builder().taskId(id).subagentName("explore").description("desc").state(state)
                .startTime(Instant.now()).build();
    }

    @Test
    @DisplayName("no filter lists all tasks and renders their ids and count")
    void listsAllTasks() {
        when(controller.list(any())).thenReturn(List.of(task("a", BackgroundTaskState.RUNNING),
                task("b", BackgroundTaskState.COMPLETED)));

        ToolResult result = tool.execute(ToolInput.of(), ToolContext.empty());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("Total: 2").contains("a").contains("b");

        ArgumentCaptor<TaskQuery> captor = ArgumentCaptor.forClass(TaskQuery.class);
        verify(controller).list(captor.capture());
        assertThat(captor.getValue().getState()).isEmpty();
    }

    @Test
    @DisplayName("state filter is parsed case-insensitively and passed as a byState query")
    void filtersByState() {
        when(controller.list(any())).thenReturn(List.of(task("b", BackgroundTaskState.RUNNING)));

        ToolResult result = tool.execute(ToolInput.of("state", "Running"), ToolContext.empty());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("state=running").contains("b");

        ArgumentCaptor<TaskQuery> captor = ArgumentCaptor.forClass(TaskQuery.class);
        verify(controller).list(captor.capture());
        assertThat(captor.getValue().getState()).contains(BackgroundTaskState.RUNNING);
    }

    @Test
    @DisplayName("state filter parses under a Turkish default locale (RUNNING/KILLED contain 'i')")
    void filtersByStateUnderTurkishLocale() {
        // Regression: raw.toUpperCase() without Locale.ROOT maps "running" -> "RUNNİNG" (U+0130) in tr-TR,
        // so valueOf() would reject a valid, schema-enumerated filter. Locale.ROOT keeps parsing locale-neutral.
        final Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            when(controller.list(any())).thenReturn(List.of(task("b", BackgroundTaskState.RUNNING)));

            ToolResult result = tool.execute(ToolInput.of("state", "running"), ToolContext.empty());

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getContent()).contains("state=running").contains("b");

            ArgumentCaptor<TaskQuery> captor = ArgumentCaptor.forClass(TaskQuery.class);
            verify(controller).list(captor.capture());
            assertThat(captor.getValue().getState()).contains(BackgroundTaskState.RUNNING);
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    @DisplayName("empty result renders a friendly 'no matching tasks' message")
    void emptyResult() {
        when(controller.list(any())).thenReturn(List.of());

        ToolResult result = tool.execute(ToolInput.of(), ToolContext.empty());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("No matching background tasks");
    }

    @Test
    @DisplayName("an unknown state is a caller error and never hits the controller")
    void unknownStateIsError() {
        ToolResult result = tool.execute(ToolInput.of("state", "bogus"), ToolContext.empty());

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("unknown state");
        verify(controller, never()).list(any());
    }

    @Test
    @DisplayName("a blank state is treated as no filter (lists all)")
    void blankStateListsAll() {
        when(controller.list(any())).thenReturn(List.of());

        ToolResult result = tool.execute(ToolInput.of("state", "  "), ToolContext.empty());

        assertThat(result.isSuccess()).isTrue();
        ArgumentCaptor<TaskQuery> captor = ArgumentCaptor.forClass(TaskQuery.class);
        verify(controller).list(captor.capture());
        assertThat(captor.getValue().getState()).isEmpty();
    }

    @Test
    @DisplayName("scopes the listing to the calling agent's runtime when the context id is present")
    void scopesToAgentRuntime() {
        when(controller.list(any())).thenReturn(List.of());
        final AgentRuntimeId ctx = AgentRuntimeId.of("agent:alpha");
        final ToolContext context = ToolContext.builder().put(ToolContextKeys.AGENT_RUNTIME_ID, ctx).build();

        tool.execute(ToolInput.of("state", "running"), context);

        // The wrapped query forces the caller's own context in while preserving the state filter.
        ArgumentCaptor<TaskQuery> captor = ArgumentCaptor.forClass(TaskQuery.class);
        verify(controller).list(captor.capture());
        assertThat(captor.getValue().getAgentRuntimeId()).contains(ctx);
        assertThat(captor.getValue().getState()).contains(BackgroundTaskState.RUNNING);
    }

    @Test
    @DisplayName("declared CONCURRENT_SAFE (read-only over a thread-safe store)")
    void concurrencyBehaviorIsSafe() {
        assertThat(tool.getConcurrencyBehavior()).isEqualTo(ConcurrencyBehavior.CONCURRENT_SAFE);
    }

    @Test
    @DisplayName("null constructor / execute arguments are rejected")
    void rejectsNulls() {
        assertThatNullPointerException().isThrownBy(() -> new TaskListTool(null));
        assertThatNullPointerException().isThrownBy(() -> tool.execute(null, ToolContext.empty()));
        assertThatNullPointerException().isThrownBy(() -> tool.execute(ToolInput.of(), null));
    }
}
