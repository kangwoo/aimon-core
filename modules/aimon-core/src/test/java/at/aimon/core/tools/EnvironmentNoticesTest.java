package at.aimon.core.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.environment.EnvironmentDescriptor;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.environment.TestExecutionEnvironments;
import at.aimon.core.llm.ToolUseResult;

@DisplayName("EnvironmentNotices")
class EnvironmentNoticesTest {

    private static ExecutionEnvironment queueing(String... notices) {
        final Queue<String> queue = new ConcurrentLinkedQueue<>(List.of(notices));
        return TestExecutionEnvironments.builder().workingDirectory("/workspace").notices(queue).build();
    }

    @Test
    @DisplayName("prepends one [environment] line per notice, oldest first")
    void prependsNotices() {
        final ToolUseResult result = EnvironmentNotices.deliver(ToolUseResult.success("t1", "body"),
                queueing("first", "second"), "Read");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getToolUseId()).isEqualTo("t1");
        assertThat(result.getContent()).isEqualTo("[environment] first\n[environment] second\nbody");
    }

    @Test
    @DisplayName("keeps an error an error and carries the render payload across")
    void preservesErrorAndRenderPayload() {
        final ToolUseResult original = ToolUseResult.error("t1", "boom").withRenderPayload(Map.of("kind", "diff"));

        final ToolUseResult result = EnvironmentNotices.deliver(original, queueing("reset"), "Edit");

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).isEqualTo("[environment] reset\nboom");
        assertThat(result.getRenderPayload()).containsEntry("kind", "diff");
    }

    @Test
    @DisplayName("drains once: the same environment has nothing for the next result")
    void drainsOnce() {
        final ExecutionEnvironment environment = queueing("reset");

        EnvironmentNotices.deliver(ToolUseResult.success("t1", "a"), environment, "Read");
        final ToolUseResult original = ToolUseResult.success("t2", "b");

        assertThat(EnvironmentNotices.deliver(original, environment, "Read")).isSameAs(original);
    }

    @Test
    @DisplayName("returns the same instance when there is no environment, nothing queued, or the drain throws")
    void returnsSameInstanceWhenNothingIsDelivered() {
        final ToolUseResult original = ToolUseResult.success("t1", "body");
        final ExecutionEnvironment throwing = mock(ExecutionEnvironment.class);
        when(throwing.drainNotices()).thenThrow(new IllegalStateException("queue unreachable"));
        final ExecutionEnvironment returnsNull = mock(ExecutionEnvironment.class);
        when(returnsNull.drainNotices()).thenReturn(null);

        assertThat(EnvironmentNotices.deliver(original, null, "Read")).isSameAs(original);
        assertThat(EnvironmentNotices.deliver(original, queueing(), "Read")).isSameAs(original);
        assertThat(EnvironmentNotices.deliver(original, throwing, "Read")).isSameAs(original);
        assertThat(EnvironmentNotices.deliver(original, returnsNull, "Read")).isSameAs(original);
    }

    @Test
    @DisplayName("an environment that keeps the SPI default never changes a result")
    void defaultDrainIsEmpty() {
        final ToolUseResult original = ToolUseResult.success("t1", "body");

        assertThat(EnvironmentNotices.deliver(original,
                TestExecutionEnvironments.withDescriptor(
                        EnvironmentDescriptor.builder().workingDirectory("/w").platform("linux").build()),
                "Read")).isSameAs(original);
    }

    @Test
    @DisplayName("skips null and blank entries")
    void skipsNullAndBlankEntries() {
        assertThat(EnvironmentNotices.render(Arrays.asList("kept", null, "", "  ", "also kept")))
                .isEqualTo("[environment] kept\n[environment] also kept\n");
        assertThat(EnvironmentNotices.render(Arrays.asList(null, " "))).isEmpty();
        assertThat(EnvironmentNotices.render(null)).isEmpty();
    }
}
