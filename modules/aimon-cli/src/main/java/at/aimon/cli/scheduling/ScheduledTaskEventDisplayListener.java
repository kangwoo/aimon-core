/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.cli.scheduling;

import static org.fusesource.jansi.Ansi.ansi;

import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.function.Supplier;

import org.fusesource.jansi.Ansi;

import at.aimon.cli.config.CliSettings;
import at.aimon.core.scheduling.RoutineResult;
import at.aimon.core.scheduling.ScheduledTask;
import at.aimon.core.scheduling.StepResult;
import at.aimon.core.scheduling.event.ScheduledTaskEventListener;
import at.aimon.core.scheduling.event.StepCompletedEvent;
import at.aimon.core.scheduling.event.StepFailedEvent;
import at.aimon.core.scheduling.event.TaskCancelledEvent;
import at.aimon.core.scheduling.event.TaskCompletedEvent;
import at.aimon.core.scheduling.event.TaskFailedEvent;
import at.aimon.core.scheduling.event.TaskInterruptedEvent;
import at.aimon.core.scheduling.event.TaskRegisteredEvent;
import at.aimon.core.scheduling.event.TaskStartedEvent;

/**
 * Event listener that displays scheduled task events to the console.
 *
 * <p>
 * This listener formats and outputs task lifecycle events including task registration, execution start/completion, step
 * progress, and failures.
 * </p>
 */
public class ScheduledTaskEventDisplayListener implements ScheduledTaskEventListener {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private final CliSettings settings;

    /** ScheduledTaskEventDisplayListener를 생성한다. */
    public ScheduledTaskEventDisplayListener(CliSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings cannot be null");
    }

    @Override
    public void onTaskRegistered(TaskRegisteredEvent event) {
        ScheduledTask task = event.getTask();
        String plain = "[Schedule] Task registered: " + task.getName() + " (cron: " + task.getCronExpression() + ")";
        colorPrintln(plain, () -> ansi().fgCyan().a("[Schedule] ").reset().a("Task registered: ").fgYellow()
                .a(task.getName()).reset().fgBrightBlack().a(" (cron: " + task.getCronExpression() + ")"));
    }

    @Override
    public void onTaskStarted(TaskStartedEvent event) {
        ScheduledTask task = event.getTask();
        String time = TIME_FORMATTER.format(event.getTimestamp());
        String plain = "[Schedule] [" + time + "] Task started: " + task.getName();
        colorPrintln(plain, () -> ansi().fgCyan().a("[Schedule] ").reset().fgBrightBlack().a("[" + time + "] ").reset()
                .a("Task started: ").fgYellow().a(task.getName()));
    }

    @Override
    public void onTaskCompleted(TaskCompletedEvent event) {
        ScheduledTask task = event.getTask();
        RoutineResult result = event.getResult();
        String time = TIME_FORMATTER.format(event.getTimestamp());
        String durationStr = formatDuration(result.getDuration());

        String plain = "[Schedule] [" + time + "] Task completed: " + task.getName() + " [SUCCESS] (" + durationStr
                + ")";
        colorPrintln(plain,
                () -> ansi().fgCyan().a("[Schedule] ").reset().fgBrightBlack().a("[" + time + "] ").reset()
                        .a("Task completed: ").fgYellow().a(task.getName()).reset().fgGreen().bold().a(" \u2713")
                        .reset().fgBrightBlack().a(" (" + durationStr + ")"));
    }

    @Override
    public void onTaskFailed(TaskFailedEvent event) {
        ScheduledTask task = event.getTask();
        RoutineResult result = event.getResult();
        String time = TIME_FORMATTER.format(event.getTimestamp());
        String error = result.getErrorMessage().orElse("Unknown error");

        String plain = "[Schedule] [" + time + "] Task failed: " + task.getName() + " [FAILED]";
        colorPrintln(plain, () -> ansi().fgCyan().a("[Schedule] ").reset().fgBrightBlack().a("[" + time + "] ").reset()
                .a("Task failed: ").fgYellow().a(task.getName()).reset().fgRed().bold().a(" \u2717"));

        colorPrintln("  Error: " + error, () -> ansi().a("  ").fgRed().a("Error: " + error));
    }

    @Override
    public void onTaskCancelled(TaskCancelledEvent event) {
        ScheduledTask task = event.getTask();
        String time = TIME_FORMATTER.format(event.getTimestamp());

        String plain = "[Schedule] [" + time + "] Task cancelled: " + task.getName();
        colorPrintln(plain, () -> ansi().fgCyan().a("[Schedule] ").reset().fgBrightBlack().a("[" + time + "] ").reset()
                .a("Task cancelled: ").fgYellow().a(task.getName()));
    }

    /**
     * Renders a run that was stopped before it finished.
     *
     * <p>
     * Distinct from {@link #onTaskCancelled}, which reports that the schedule is gone. This one reports that one run
     * stopped, and it is the terminal line for that run: without it a stopped task prints "Task started" and then
     * nothing at all, which reads as a task still working.
     */
    @Override
    public void onTaskInterrupted(TaskInterruptedEvent event) {
        ScheduledTask task = event.getTask();
        RoutineResult result = event.getResult();
        String time = TIME_FORMATTER.format(event.getTimestamp());
        String durationStr = formatDuration(result.getDuration());
        String reason = event.getReason().name();
        int done = result.getCompletedStepCount();
        int total = task.getRoutine().size();

        String plain = "[Schedule] [" + time + "] Task stopped: " + task.getName() + " [" + reason + "] (" + done + "/"
                + total + " steps, " + durationStr + ")";
        colorPrintln(plain, () -> ansi().fgCyan().a("[Schedule] ").reset().fgBrightBlack().a("[" + time + "] ").reset()
                .a("Task stopped: ").fgYellow().a(task.getName()).reset().fgMagenta().bold().a(" \u2298").reset()
                .fgBrightBlack().a(" (" + reason + ", " + done + "/" + total + " steps, " + durationStr + ")"));
    }

    @Override
    public void onStepCompleted(StepCompletedEvent event) {
        StepResult result = event.getResult();
        int stepIndex = event.getStepIndex();
        String toolName = event.getStep().getTool();
        String durationStr = formatDuration(result.getDuration());

        String plain = "[Schedule]   Step " + stepIndex + " (" + toolName + ") [OK] (" + durationStr + ")";
        colorPrintln(plain, () -> ansi().fgCyan().a("[Schedule] ").reset().a("  Step " + stepIndex + " (").fgCyan()
                .a(toolName).reset().a(")").fgGreen().a(" \u2713").reset().fgBrightBlack().a(" (" + durationStr + ")"));
    }

    @Override
    public void onStepFailed(StepFailedEvent event) {
        StepResult result = event.getResult();
        int stepIndex = event.getStepIndex();
        String toolName = event.getStep().getTool();
        String error = result.getErrorMessage().orElse("Unknown error");

        String plain = "[Schedule]   Step " + stepIndex + " (" + toolName + ") [FAILED]";
        colorPrintln(plain, () -> ansi().fgCyan().a("[Schedule] ").reset().a("  Step " + stepIndex + " (").fgCyan()
                .a(toolName).reset().a(")").fgRed().a(" \u2717"));

        colorPrintln("    Error: " + error, () -> ansi().a("    ").fgRed().a("Error: " + error));
    }

    private void colorPrintln(String plainText, Supplier<Ansi> coloredAnsi) {
        if (settings.isColorOutput()) {
            System.out.println(coloredAnsi.get().reset());
        } else {
            System.out.println(plainText);
        }
    }

    private String formatDuration(Duration duration) {
        if (duration == null) {
            return "0ms";
        }
        long millis = duration.toMillis();
        if (millis < 1000) {
            return millis + "ms";
        } else if (millis < 60000) {
            return String.format("%.1fs", millis / 1000.0);
        } else {
            long minutes = millis / 60000;
            long seconds = (millis % 60000) / 1000;
            return minutes + "m " + seconds + "s";
        }
    }
}
