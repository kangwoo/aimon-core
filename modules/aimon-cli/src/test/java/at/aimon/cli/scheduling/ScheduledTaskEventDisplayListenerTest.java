package at.aimon.cli.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.cli.config.CliSettings;
import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.scheduling.RoutineResult;
import at.aimon.core.scheduling.RoutineStep;
import at.aimon.core.scheduling.ScheduledTask;
import at.aimon.core.scheduling.StepResult;
import at.aimon.core.scheduling.event.StepCompletedEvent;
import at.aimon.core.scheduling.event.StepFailedEvent;
import at.aimon.core.scheduling.event.TaskCancelledEvent;
import at.aimon.core.scheduling.event.TaskCompletedEvent;
import at.aimon.core.scheduling.event.TaskFailedEvent;
import at.aimon.core.scheduling.event.TaskInterruptedEvent;
import at.aimon.core.scheduling.event.TaskRegisteredEvent;
import at.aimon.core.scheduling.event.TaskStartedEvent;

@DisplayName("ScheduledTaskEventDisplayListener Tests")
class ScheduledTaskEventDisplayListenerTest {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    private final PrintStream originalOut = System.out;

    private CliSettings settings;
    private ScheduledTaskEventDisplayListener listener;

    @BeforeEach
    void setUp() {
        System.setOut(new PrintStream(outputStream));
        settings = new CliSettings();
        settings.setColorOutput(false);
        listener = new ScheduledTaskEventDisplayListener(settings);
    }

    @AfterEach
    void tearDown() {
        System.setOut(originalOut);
    }

    private String getOutput() {
        return outputStream.toString().trim();
    }

    private ScheduledTask mockTask(String name, String cronExpression) {
        ScheduledTask task = mock(ScheduledTask.class);
        when(task.getName()).thenReturn(name);
        when(task.getCronExpression()).thenReturn(cronExpression);
        return task;
    }

    /**
     * Converts a LocalDateTime to an Instant using the system default zone for deterministic time assertions.
     */
    private static java.time.Instant toInstant(LocalDateTime localDateTime) {
        return localDateTime.atZone(ZoneId.systemDefault()).toInstant();
    }

    /**
     * Formats a LocalDateTime to "HH:mm:ss" string using the system default zone for expected assertion values.
     */
    private static String formatTime(LocalDateTime localDateTime) {
        return TIME_FORMATTER.format(toInstant(localDateTime));
    }

    @Nested
    @DisplayName("Constructor")
    class Constructor {

        @Test
        @DisplayName("Should throw NullPointerException for null settings")
        void shouldThrowNullPointerExceptionForNullSettings() {
            assertThatThrownBy(() -> new ScheduledTaskEventDisplayListener(null))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("settings cannot be null");
        }
    }

    @Nested
    @DisplayName("onTaskRegistered")
    class OnTaskRegistered {

        @Test
        @DisplayName("Should display task name and cron expression")
        void shouldDisplayTaskNameAndCronExpression() {
            ScheduledTask task = mockTask("daily-backup", "0 2 * * *");
            TaskRegisteredEvent event = mock(TaskRegisteredEvent.class);
            when(event.getTask()).thenReturn(task);

            listener.onTaskRegistered(event);

            String output = getOutput();
            assertThat(output).contains("[Schedule]");
            assertThat(output).contains("Task registered:");
            assertThat(output).contains("daily-backup");
            assertThat(output).contains("cron: 0 2 * * *");
        }
    }

    @Nested
    @DisplayName("onTaskStarted")
    class OnTaskStarted {

        @Test
        @DisplayName("Should display task name with time")
        void shouldDisplayTaskNameWithTime() {
            LocalDateTime dateTime = LocalDateTime.of(2025, 6, 15, 10, 30, 45);
            String expectedTime = formatTime(dateTime);

            ScheduledTask task = mockTask("health-check", "*/5 * * * *");
            TaskStartedEvent event = mock(TaskStartedEvent.class);
            when(event.getTask()).thenReturn(task);
            when(event.getTimestamp()).thenReturn(toInstant(dateTime));

            listener.onTaskStarted(event);

            String output = getOutput();
            assertThat(output).contains("[Schedule]");
            assertThat(output).contains("[" + expectedTime + "]");
            assertThat(output).contains("Task started:");
            assertThat(output).contains("health-check");
        }
    }

    @Nested
    @DisplayName("onTaskCompleted")
    class OnTaskCompleted {

        @Test
        @DisplayName("Should display task name, SUCCESS, and duration")
        void shouldDisplayTaskNameSuccessAndDuration() {
            LocalDateTime dateTime = LocalDateTime.of(2025, 6, 15, 8, 0, 5);
            String expectedTime = formatTime(dateTime);

            ScheduledTask task = mockTask("report-gen", "0 8 * * *");
            RoutineResult result = mock(RoutineResult.class);
            when(result.getDuration()).thenReturn(Duration.ofMillis(1500));

            TaskCompletedEvent event = mock(TaskCompletedEvent.class);
            when(event.getTask()).thenReturn(task);
            when(event.getResult()).thenReturn(result);
            when(event.getTimestamp()).thenReturn(toInstant(dateTime));

            listener.onTaskCompleted(event);

            String output = getOutput();
            assertThat(output).contains("[Schedule]");
            assertThat(output).contains("[" + expectedTime + "]");
            assertThat(output).contains("Task completed:");
            assertThat(output).contains("report-gen");
            assertThat(output).contains("[SUCCESS]");
            assertThat(output).contains("1.5s");
        }
    }

    @Nested
    @DisplayName("onTaskFailed")
    class OnTaskFailed {

        @Test
        @DisplayName("Should display task name, FAILED, and error message")
        void shouldDisplayTaskNameFailedAndErrorMessage() {
            LocalDateTime dateTime = LocalDateTime.of(2025, 6, 15, 3, 0, 10);
            String expectedTime = formatTime(dateTime);

            ScheduledTask task = mockTask("db-cleanup", "0 3 * * *");
            RoutineResult result = mock(RoutineResult.class);
            when(result.getErrorMessage()).thenReturn(Optional.of("Connection timeout"));

            TaskFailedEvent event = mock(TaskFailedEvent.class);
            when(event.getTask()).thenReturn(task);
            when(event.getResult()).thenReturn(result);
            when(event.getTimestamp()).thenReturn(toInstant(dateTime));

            listener.onTaskFailed(event);

            String output = getOutput();
            assertThat(output).contains("[Schedule]");
            assertThat(output).contains("[" + expectedTime + "]");
            assertThat(output).contains("Task failed:");
            assertThat(output).contains("db-cleanup");
            assertThat(output).contains("[FAILED]");
            assertThat(output).contains("Error: Connection timeout");
        }

        @Test
        @DisplayName("Should display 'Unknown error' when error message is empty")
        void shouldDisplayUnknownErrorWhenErrorMessageIsEmpty() {
            LocalDateTime dateTime = LocalDateTime.of(2025, 6, 15, 3, 0, 10);

            ScheduledTask task = mockTask("db-cleanup", "0 3 * * *");
            RoutineResult result = mock(RoutineResult.class);
            when(result.getErrorMessage()).thenReturn(Optional.empty());

            TaskFailedEvent event = mock(TaskFailedEvent.class);
            when(event.getTask()).thenReturn(task);
            when(event.getResult()).thenReturn(result);
            when(event.getTimestamp()).thenReturn(toInstant(dateTime));

            listener.onTaskFailed(event);

            String output = getOutput();
            assertThat(output).contains("Error: Unknown error");
        }
    }

    @Nested
    @DisplayName("onTaskInterrupted")
    class OnTaskInterrupted {

        /**
         * A stopped run needs a terminal line of its own. Without one the console shows "Task started" and then
         * nothing further, which is indistinguishable from a task that is still working.
         */
        @Test
        @DisplayName("Should display task name, the interrupt reason and how far the run got")
        void shouldDisplayTaskNameReasonAndProgress() {
            LocalDateTime dateTime = LocalDateTime.of(2025, 6, 15, 3, 0, 10);
            String expectedTime = formatTime(dateTime);

            ScheduledTask task = mockTask("db-cleanup", "0 3 * * *");
            when(task.getRoutine()).thenReturn(
                    List.of(RoutineStep.of("Bash", "{}"), RoutineStep.of("Bash", "{}"), RoutineStep.of("Bash", "{}")));

            RoutineResult result = mock(RoutineResult.class);
            when(result.getCompletedStepCount()).thenReturn(1);
            when(result.getDuration()).thenReturn(Duration.ofSeconds(2));

            TaskInterruptedEvent event = mock(TaskInterruptedEvent.class);
            when(event.getTask()).thenReturn(task);
            when(event.getResult()).thenReturn(result);
            when(event.getReason()).thenReturn(InterruptReason.TASK_CANCELLED);
            when(event.getTimestamp()).thenReturn(toInstant(dateTime));

            listener.onTaskInterrupted(event);

            String output = getOutput();
            assertThat(output).contains("[Schedule]");
            assertThat(output).contains("[" + expectedTime + "]");
            assertThat(output).contains("Task stopped:");
            assertThat(output).contains("db-cleanup");
            assertThat(output).contains("TASK_CANCELLED");
            assertThat(output).contains("1/3 steps");
        }

        /**
         * A stopped run is not a failed one, so the line must not read like the failure line — a subscriber
         * eyeballing the console should not be led to look for a broken step that does not exist.
         */
        @Test
        @DisplayName("Should not render a stopped run as a failure")
        void shouldNotRenderAStoppedRunAsAFailure() {
            ScheduledTask task = mockTask("db-cleanup", "0 3 * * *");
            when(task.getRoutine()).thenReturn(List.of(RoutineStep.of("Bash", "{}")));

            RoutineResult result = mock(RoutineResult.class);
            when(result.getCompletedStepCount()).thenReturn(0);
            when(result.getDuration()).thenReturn(Duration.ofMillis(40));

            TaskInterruptedEvent event = mock(TaskInterruptedEvent.class);
            when(event.getTask()).thenReturn(task);
            when(event.getResult()).thenReturn(result);
            when(event.getReason()).thenReturn(InterruptReason.SYSTEM_SHUTDOWN);
            when(event.getTimestamp()).thenReturn(toInstant(LocalDateTime.of(2025, 6, 15, 3, 0, 10)));

            listener.onTaskInterrupted(event);

            String output = getOutput();
            assertThat(output).contains("SYSTEM_SHUTDOWN");
            assertThat(output).doesNotContain("Task failed:");
            assertThat(output).doesNotContain("[FAILED]");
            assertThat(output).doesNotContain("Error:");
        }
    }

    @Nested
    @DisplayName("onTaskCancelled")
    class OnTaskCancelled {

        @Test
        @DisplayName("Should display task name with cancelled status")
        void shouldDisplayTaskNameWithCancelledStatus() {
            LocalDateTime dateTime = LocalDateTime.of(2025, 6, 15, 0, 0, 1);
            String expectedTime = formatTime(dateTime);

            ScheduledTask task = mockTask("log-rotation", "0 0 * * *");
            TaskCancelledEvent event = mock(TaskCancelledEvent.class);
            when(event.getTask()).thenReturn(task);
            when(event.getTimestamp()).thenReturn(toInstant(dateTime));

            listener.onTaskCancelled(event);

            String output = getOutput();
            assertThat(output).contains("[Schedule]");
            assertThat(output).contains("[" + expectedTime + "]");
            assertThat(output).contains("Task cancelled:");
            assertThat(output).contains("log-rotation");
        }
    }

    @Nested
    @DisplayName("onStepCompleted")
    class OnStepCompleted {

        @Test
        @DisplayName("Should display step index, tool name, and duration")
        void shouldDisplayStepIndexToolNameAndDuration() {
            RoutineStep step = mock(RoutineStep.class);
            when(step.getTool()).thenReturn("Bash");

            StepResult result = mock(StepResult.class);
            when(result.getDuration()).thenReturn(Duration.ofMillis(250));

            StepCompletedEvent event = mock(StepCompletedEvent.class);
            when(event.getStep()).thenReturn(step);
            when(event.getResult()).thenReturn(result);
            when(event.getStepIndex()).thenReturn(1);

            listener.onStepCompleted(event);

            String output = getOutput();
            assertThat(output).contains("[Schedule]");
            assertThat(output).contains("Step 1");
            assertThat(output).contains("(Bash)");
            assertThat(output).contains("[OK]");
            assertThat(output).contains("250ms");
        }
    }

    @Nested
    @DisplayName("onStepFailed")
    class OnStepFailed {

        @Test
        @DisplayName("Should display step index, tool name, and error message")
        void shouldDisplayStepIndexToolNameAndErrorMessage() {
            RoutineStep step = mock(RoutineStep.class);
            when(step.getTool()).thenReturn("Read");

            StepResult result = mock(StepResult.class);
            when(result.getErrorMessage()).thenReturn(Optional.of("File not found: /etc/config.yaml"));

            StepFailedEvent event = mock(StepFailedEvent.class);
            when(event.getStep()).thenReturn(step);
            when(event.getResult()).thenReturn(result);
            when(event.getStepIndex()).thenReturn(2);

            listener.onStepFailed(event);

            String output = getOutput();
            assertThat(output).contains("[Schedule]");
            assertThat(output).contains("Step 2");
            assertThat(output).contains("(Read)");
            assertThat(output).contains("[FAILED]");
            assertThat(output).contains("Error: File not found: /etc/config.yaml");
        }

        @Test
        @DisplayName("Should display 'Unknown error' when error message is empty")
        void shouldDisplayUnknownErrorWhenErrorMessageIsEmpty() {
            RoutineStep step = mock(RoutineStep.class);
            when(step.getTool()).thenReturn("Read");

            StepResult result = mock(StepResult.class);
            when(result.getErrorMessage()).thenReturn(Optional.empty());

            StepFailedEvent event = mock(StepFailedEvent.class);
            when(event.getStep()).thenReturn(step);
            when(event.getResult()).thenReturn(result);
            when(event.getStepIndex()).thenReturn(0);

            listener.onStepFailed(event);

            String output = getOutput();
            assertThat(output).contains("Error: Unknown error");
        }
    }

    @Nested
    @DisplayName("formatDuration")
    class FormatDuration {

        @Test
        @DisplayName("Should format milliseconds correctly for ms range")
        void shouldFormatMilliseconds() {
            RoutineStep step = mock(RoutineStep.class);
            when(step.getTool()).thenReturn("Bash");

            StepResult result = mock(StepResult.class);
            when(result.getDuration()).thenReturn(Duration.ofMillis(450));

            StepCompletedEvent event = mock(StepCompletedEvent.class);
            when(event.getStep()).thenReturn(step);
            when(event.getResult()).thenReturn(result);
            when(event.getStepIndex()).thenReturn(0);

            listener.onStepCompleted(event);

            assertThat(getOutput()).contains("450ms");
        }

        @Test
        @DisplayName("Should format duration in seconds for 1-60s range")
        void shouldFormatSeconds() {
            LocalDateTime dateTime = LocalDateTime.of(2025, 1, 1, 12, 0, 0);

            ScheduledTask task = mockTask("task", "* * * * *");
            RoutineResult result = mock(RoutineResult.class);
            when(result.getDuration()).thenReturn(Duration.ofMillis(2500));

            TaskCompletedEvent event = mock(TaskCompletedEvent.class);
            when(event.getTask()).thenReturn(task);
            when(event.getResult()).thenReturn(result);
            when(event.getTimestamp()).thenReturn(toInstant(dateTime));

            listener.onTaskCompleted(event);

            assertThat(getOutput()).contains("2.5s");
        }

        @Test
        @DisplayName("Should format duration in minutes for 60s+ range")
        void shouldFormatMinutes() {
            LocalDateTime dateTime = LocalDateTime.of(2025, 1, 1, 12, 0, 0);

            ScheduledTask task = mockTask("task", "* * * * *");
            RoutineResult result = mock(RoutineResult.class);
            when(result.getDuration()).thenReturn(Duration.ofMillis(125000));

            TaskCompletedEvent event = mock(TaskCompletedEvent.class);
            when(event.getTask()).thenReturn(task);
            when(event.getResult()).thenReturn(result);
            when(event.getTimestamp()).thenReturn(toInstant(dateTime));

            listener.onTaskCompleted(event);

            assertThat(getOutput()).contains("2m 5s");
        }

        @Test
        @DisplayName("Should format null duration as 0ms")
        void shouldFormatNullDurationAsZeroMs() {
            RoutineStep step = mock(RoutineStep.class);
            when(step.getTool()).thenReturn("Bash");

            StepResult result = mock(StepResult.class);
            when(result.getDuration()).thenReturn(null);

            StepCompletedEvent event = mock(StepCompletedEvent.class);
            when(event.getStep()).thenReturn(step);
            when(event.getResult()).thenReturn(result);
            when(event.getStepIndex()).thenReturn(0);

            listener.onStepCompleted(event);

            assertThat(getOutput()).contains("0ms");
        }
    }
}
