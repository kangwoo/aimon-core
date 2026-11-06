package at.aimon.core.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.environment.EnvironmentDescriptor;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.environment.TestExecutionEnvironments;

@DisplayName("EnvironmentNotices")
class EnvironmentNoticesTest {

    private static ExecutionEnvironment queueing(String... notices) {
        final Queue<String> queue = new ConcurrentLinkedQueue<>(List.of(notices));
        return TestExecutionEnvironments.builder().workingDirectory("/workspace").notices(queue).build();
    }

    @Test
    @DisplayName("drains every queued notice, oldest first")
    void drainsQueuedNotices() {
        assertThat(EnvironmentNotices.drain(queueing("first", "second"), "Read", "t1")).containsExactly("first",
                "second");
    }

    @Test
    @DisplayName("drains once: the same environment has nothing for the next call")
    void drainsOnce() {
        final ExecutionEnvironment environment = queueing("reset");

        EnvironmentNotices.drain(environment, "Read", "t1");

        assertThat(EnvironmentNotices.drain(environment, "Read", "t2")).isEmpty();
    }

    @Test
    @DisplayName("returns no notices when there is no environment, nothing queued, or the drain throws")
    void emptyWhenNothingIsDrained() {
        final ExecutionEnvironment throwing = mock(ExecutionEnvironment.class);
        when(throwing.drainNotices()).thenThrow(new IllegalStateException("queue unreachable"));
        final ExecutionEnvironment returnsNull = mock(ExecutionEnvironment.class);
        when(returnsNull.drainNotices()).thenReturn(null);

        assertThat(EnvironmentNotices.drain(null, "Read", "t1")).isEmpty();
        assertThat(EnvironmentNotices.drain(queueing(), "Read", "t1")).isEmpty();
        assertThat(EnvironmentNotices.drain(throwing, "Read", "t1")).isEmpty();
        assertThat(EnvironmentNotices.drain(returnsNull, "Read", "t1")).isEmpty();
    }

    @Test
    @DisplayName("an environment that keeps the SPI default has nothing to drain")
    void defaultDrainIsEmpty() {
        assertThat(EnvironmentNotices.drain(
                TestExecutionEnvironments.withDescriptor(
                        EnvironmentDescriptor.builder().workingDirectory("/w").platform("linux").build()),
                "Read", "t1")).isEmpty();
    }

    @Test
    @DisplayName("renders one [environment] line per notice, in order")
    void rendersOneLinePerNotice() {
        assertThat(EnvironmentNotices.render(List.of("first", "second")))
                .isEqualTo("[environment] first\n[environment] second\n");
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
