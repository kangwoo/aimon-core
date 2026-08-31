package at.aimon.cli.repl;

import static org.fusesource.jansi.Ansi.ansi;

import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;

import org.fusesource.jansi.Ansi;

import at.aimon.cli.config.CliSettings;
import at.aimon.core.agent.AgentExecutionResult;
import at.aimon.core.agent.InvokerType;
import at.aimon.core.agent.budget.CompletionReason;
import at.aimon.core.agent.impl.orca.OrcaAgentExecutionResult;
import at.aimon.core.agent.stream.AgentExecutionEvent;
import at.aimon.core.agent.stream.AssistantMessageReceived;
import at.aimon.core.agent.stream.AssistantTextDelta;
import at.aimon.core.agent.stream.AssistantTextStreamCompleted;
import at.aimon.core.agent.stream.AssistantTextStreamReset;
import at.aimon.core.agent.stream.CompactBoundary;
import at.aimon.core.agent.stream.ExecutionCompleted;
import at.aimon.core.agent.stream.ExecutionError;
import at.aimon.core.agent.stream.IterationCompleted;
import at.aimon.core.agent.stream.IterationStarted;
import at.aimon.core.agent.stream.SkillTurnSuspendedEvent;
import at.aimon.core.agent.stream.SubagentTaskCompleted;
import at.aimon.core.agent.stream.ToolResultReady;
import at.aimon.core.agent.stream.ToolUseStarted;
import at.aimon.core.skill.policy.pending.PendingSkillRequest;
import at.aimon.core.tools.task.TaskTool;

public class OutputFormatter {
    private final CliSettings settings;

    /** OutputFormatter를 생성한다. */
    public OutputFormatter(CliSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings cannot be null");
    }

    /** 환영 메시지를 출력한다. */
    public void displayWelcome() {
        String banner = """
                    _    _
                   / \\  (_)_ __ ___   ___  _ __
                  / _ \\ | | '_ ` _ \\ / _ \\| '_ \\
                 / ___ \\| | | | | | | (_) | | | |
                /_/   \\_\\_|_| |_| |_|\\___/|_| |_|
                """;

        colorPrintln(banner, () -> ansi().fgCyan().bold().a(banner));
        colorPrintln("Aimon CLI - Interactive AI Agent",
                () -> ansi().fgBrightBlue().a("Aimon CLI - Interactive AI Agent"));
        colorPrintln("Type '/help' for commands, '/quit' to exit",
                () -> ansi().fgBrightBlack().a("Type '/help' for commands, '/quit' to exit"));
        System.out.println();
    }

    /**
     * 에이전트 실행 결과를 출력한다.
     *
     * <p>
     * Accepts the base {@link AgentExecutionResult} so callers on the session path (see
     * {@link ReplSession#awaitAndRender}) don't need to down-cast. Orca-specific diagnostics such as iteration count
     * are still rendered when the concrete result happens to be an {@link OrcaAgentExecutionResult}; other
     * implementations simply skip the iteration footer.
     */
    public void displayResult(AgentExecutionResult result) {
        if (result.isSuccess()) {
            displaySuccessResult(result);
        } else if (result.getCompletionReason() == CompletionReason.INTERRUPTED) {
            // Cooperative interrupt surfaces as a non-success result carrying
            // CompletionReason.INTERRUPTED. Rendering it through displayErrorResult would paint it red with
            // "Error:" — misleading for a user-initiated Ctrl+C that worked as designed. Show it as an info
            // banner instead so the outcome reads as "you asked to stop, and we stopped cleanly".
            displayInterruptedResult(result);
        } else {
            displayErrorResult(result);
        }
    }

    private void displaySuccessResult(AgentExecutionResult result) {
        // PSTREAM-10: when the executor streamed the final answer, AssistantTextDelta events have already painted the
        // full text inline, and AssistantTextStreamCompleted emitted the trailing newline. Printing getFinalAnswer()
        // again here would duplicate the entire response. Skip the body; only the iteration footer remains relevant.
        if (!result.wasStreamed()) {
            String answer = "\n" + result.getFinalAnswer();
            colorPrintln(answer, () -> ansi().fgGreen().a(answer));
        }

        if (settings.isShowIterations() && result instanceof OrcaAgentExecutionResult orca) {
            String iterations = "\n[Completed in " + orca.getIterationCount() + " iteration(s)]";
            colorPrintln(iterations, () -> ansi().fgBrightBlack().a(iterations));
        }
        System.out.println();
    }

    private void displayErrorResult(AgentExecutionResult result) {
        String errorMessage = "\nError: " + result.getErrorMessage();
        colorPrintln(errorMessage,
                () -> ansi().fgRed().bold().a("\nError: ").reset().fgRed().a(result.getErrorMessage()));

        if (settings.isShowIterations() && result instanceof OrcaAgentExecutionResult orca) {
            String iterations = "\n[Failed after " + orca.getIterationCount() + " iteration(s)]";
            colorPrintln(iterations, () -> ansi().fgBrightBlack().a(iterations));
        }
        System.out.println();
    }

    /**
     * Renders a cooperative-interrupt completion. Shows "[Interrupted]" prefixed by the executor's status
     * message in bright-black info colour so it reads as a user-invoked stop rather than a failure, and preserves the
     * iteration footer when enabled so users can still see how much work ran before the trip.
     */
    private void displayInterruptedResult(AgentExecutionResult result) {
        String banner = "\n[Interrupted] " + result.getErrorMessage();
        colorPrintln(banner, () -> ansi().fgBrightBlack().a(banner));

        if (settings.isShowIterations() && result instanceof OrcaAgentExecutionResult orca) {
            String iterations = "\n[Interrupted after " + orca.getIterationCount() + " iteration(s)]";
            colorPrintln(iterations, () -> ansi().fgBrightBlack().a(iterations));
        }
        System.out.println();
    }

    /** 에러 메시지를 출력한다. */
    public void displayError(String message) {
        colorPrintln("Error: " + message, () -> ansi().fgRed().bold().a("Error: ").reset().fgRed().a(message));
        System.out.println();
    }

    /** 도움말 정보를 출력한다. */
    public void displayHelp() {
        colorPrintln("\nAvailable Commands:", () -> ansi().fgCyan().bold().a("\nAvailable Commands:"));
        if (settings.isColorOutput()) {
            System.out.println(ansi().fgYellow().a("  /quit").reset() + "   - Exit the REPL");
            System.out.println(ansi().fgYellow().a("  /exit").reset() + "   - Exit the REPL");
        } else {
            System.out.println("  /quit   - Exit the REPL");
            System.out.println("  /exit   - Exit the REPL");
        }
        System.out.println();
        System.out.println("For all other commands (including /help, /clear, /version),");
        System.out.println("type the command and the agent will handle it.");
        System.out.println();
    }

    /** 정보 메시지를 출력한다. */
    public void displayInfo(String message) {
        colorPrintln(message, () -> ansi().fgBrightBlack().a(message));
    }

    /** 종료 메시지를 출력한다. */
    public void displayGoodbye() {
        colorPrintln("Goodbye!", () -> ansi().fgCyan().a("Goodbye!"));
    }

    /**
     * Displays a tools call with executor type context.
     *
     * @param toolName
     *            The name of the tools being called
     * @param input
     *            The input parameters as a formatted string
     * @param invokerType
     *            The type of executor invoking the tools
     */
    public void displayToolCall(String toolName, String input, InvokerType invokerType) {
        if (!settings.isShowToolCalls()) {
            return;
        }

        String indent = "  ".repeat(invokerType.getDisplayDepth());

        if (TaskTool.TOOL_NAME.equals(toolName)) {
            // Subagent launches are rendered by the SUBAGENT_START lifecycle hook (SubagentLaunchDisplayHook), which
            // carries the real subagent name + goal and fires uniformly for Task-tool, workflow and skill-fork
            // subagents. Suppress the generic tool-call line here so a Task launch is not displayed twice.
            return;
        }

        String suffix = hasContent(input) ? ": " + input : "";
        colorPrintln(indent + "[Tool] " + toolName + suffix,
                () -> ansi().a(indent).fgYellow().a("[Tool] ").reset().fgCyan().a(toolName).reset()
                        .a(hasContent(input) ? ansi().a(": ").fgBrightBlack().a(input).reset().toString() : ""));
    }

    /**
     * Displays a tools call without executor type context (defaults to MAIN_AGENT).
     *
     * @param toolName
     *            The name of the tools being called
     * @param input
     *            The input parameters as a formatted string
     */
    public void displayToolCall(String toolName, String input) {
        displayToolCall(toolName, input, InvokerType.MAIN_AGENT);
    }

    /**
     * Displays a subagent launch line, driven by the {@code SUBAGENT_START} lifecycle hook. Shows the real subagent
     * name and a truncated, whitespace-collapsed goal preview. Gated by {@code showToolCalls}.
     *
     * @param subagentName
     *            the subagent being launched
     * @param goal
     *            the goal/prompt (truncated and single-lined for display)
     */
    public void displaySubagentLaunch(String subagentName, String goal) {
        if (!settings.isShowToolCalls()) {
            return;
        }
        String indent = "  ";
        String preview = truncateGoal(goal);
        colorPrintln(indent + "[Subagent] " + subagentName + (hasContent(preview) ? ": " + preview : ""),
                () -> ansi().a(indent).fgMagenta().a("[Subagent] ").reset().fgCyan().a(subagentName).reset()
                        .a(hasContent(preview) ? ansi().a(": ").fgBrightBlack().a(preview).reset().toString() : ""));
    }

    private static String truncateGoal(String goal) {
        if (goal == null) {
            return "";
        }
        String collapsed = goal.strip().replaceAll("\\s+", " ");
        return collapsed.length() > 100 ? collapsed.substring(0, 97) + "..." : collapsed;
    }

    /**
     * Displays subagent execution result with visual formatting.
     *
     * @param indent
     *            The indentation string
     * @param subagentName
     *            The name of the subagent
     * @param description
     *            The task description
     * @param status
     *            The execution status (SUCCESS or FAILURE)
     * @param iterations
     *            The number of iterations
     * @param tokens
     *            The total tokens used
     * @param summary
     *            The result summary or error message
     */
    public void displaySubagentResult(String indent, String subagentName, String description, String status,
            int iterations, int tokens, String summary) {
        if (!settings.isShowToolCalls()) {
            return;
        }

        boolean isSuccess = "SUCCESS".equals(status);
        String metrics = " (" + iterations + " iteration(s), " + tokens + " tokens)";

        if (settings.isColorOutput()) {
            Ansi header = ansi().a(indent).fgMagenta().a("[Subagent Result] ").reset().fgCyan().a(subagentName).reset()
                    .a(" - ").a(description);
            if (isSuccess) {
                header.a(" ").fgGreen().bold().a("\u2713 " + status).reset();
            } else {
                header.a(" ").fgRed().bold().a("\u2717 " + status).reset();
            }
            header.fgBrightBlack().a(metrics).reset();
            System.out.println(header);
        } else {
            System.out.println(
                    indent + "[Subagent Result] " + subagentName + " - " + description + " " + status + metrics);
        }

        if (summary != null && !summary.trim().isEmpty()) {
            String summaryIndent = indent + "  ";
            for (String line : summary.split("\n")) {
                if (settings.isColorOutput()) {
                    Ansi color = isSuccess ? ansi().fgGreen() : ansi().fgRed();
                    System.out.println(summaryIndent + color.a(line).reset());
                } else {
                    System.out.println(summaryIndent + line);
                }
            }
        }
    }

    /**
     * Dispatches a streaming {@link AgentExecutionEvent} to the matching per-type display method.
     *
     * <p>
     * STREAM-04 wires this method into {@link ReplSession} via
     * {@link at.aimon.core.agent.stream.StreamingAgentExecutor#executeAsync} so progress is shown as the executor makes
     * it, rather than via a single hard-coded "thinking" spinner. Events whose visible counterpart is already rendered
     * by a hook (e.g. {@link ToolUseStarted} is already shown by {@code ToolCallDisplayHook}) deliberately map to no-op
     * display methods here to avoid double output.
     *
     * @param event
     *            event to render (must not be null)
     */
    public void displayEvent(AgentExecutionEvent event) {
        Objects.requireNonNull(event, "event cannot be null");
        // Java 17 instanceof pattern chain — project toolchain is -source 17, which does not yet support pattern
        // switch. The sealed AgentExecutionEvent hierarchy still forces us to update this chain when a new permitted
        // subtype is added (the final else throws), so exhaustiveness is maintained at runtime rather than compile
        // time. Upgrade to pattern-matching switch once the source level moves to 21.
        if (event instanceof IterationStarted started) {
            displayIterationStarted(started);
        } else if (event instanceof AssistantMessageReceived assistant) {
            displayAssistantMessageReceived(assistant);
        } else if (event instanceof AssistantTextDelta delta) {
            displayAssistantTextDelta(delta);
        } else if (event instanceof AssistantTextStreamReset reset) {
            displayAssistantTextStreamReset(reset);
        } else if (event instanceof AssistantTextStreamCompleted streamCompleted) {
            displayAssistantTextStreamCompleted(streamCompleted);
        } else if (event instanceof ToolUseStarted toolUse) {
            displayToolUseStarted(toolUse);
        } else if (event instanceof ToolResultReady toolResult) {
            displayToolResultReady(toolResult);
        } else if (event instanceof IterationCompleted completed) {
            displayIterationCompleted(completed);
        } else if (event instanceof CompactBoundary compact) {
            displayCompactBoundary(compact);
        } else if (event instanceof ExecutionCompleted finished) {
            displayExecutionCompleted(finished);
        } else if (event instanceof ExecutionError failed) {
            displayExecutionError(failed);
        } else if (event instanceof SkillTurnSuspendedEvent suspended) {
            displaySkillTurnSuspended(suspended);
        } else if (event instanceof SubagentTaskCompleted subagentDone) {
            displaySubagentTaskCompleted(subagentDone);
        } else {
            throw new IllegalStateException("Unhandled AgentExecutionEvent subtype: " + event.getClass().getName());
        }
    }

    /**
     * Prints a dim progress marker on the very first iteration so the user sees the agent has started — replacing the
     * previous hard-coded "[Agent thinking...]" line. Subsequent iterations stay silent to avoid noise.
     */
    public void displayIterationStarted(IterationStarted event) {
        Objects.requireNonNull(event, "event cannot be null");
        if (event.getIteration() != 1) {
            return;
        }
        displayInfo("[Agent running...]");
    }

    /**
     * Prints the (pre-truncated) intermediate assistant text so the user sees some of the agent's reasoning between
     * tool calls. Empty summaries — typically "assistant message is only a tool call" turns — render nothing.
     */
    public void displayAssistantMessageReceived(AssistantMessageReceived event) {
        Objects.requireNonNull(event, "event cannot be null");
        final String summary = event.getMessageSummary();
        if (!hasContent(summary)) {
            return;
        }
        colorPrintln(summary, () -> ansi().fgBrightBlack().a(summary));
    }

    /**
     * PSTREAM-10: renders an incremental text fragment from the LLM to the user inline. Writes the delta to
     * {@link System#out} without a trailing newline so subsequent deltas continue on the same visual line, and flushes
     * so the user sees each chunk as it arrives (the default line-buffered {@code println} path would hold writes back
     * until the next newline).
     *
     * <p>
     * Intentionally uses {@code System.out.print} + explicit {@code flush} rather than the {@link #colorPrintln} helper
     * because that helper appends a newline per call; deltas arrive many times per response, so we need character-level
     * streaming. When colour is on, the output is dim-green to visually distinguish streamed text from finalized output
     * (which {@link #displaySuccessResult} paints in bright green when {@code wasStreamed} is false).
     */
    public void displayAssistantTextDelta(AssistantTextDelta event) {
        Objects.requireNonNull(event, "event cannot be null");
        final String delta = event.getDelta();
        if (settings.isColorOutput()) {
            System.out.print(ansi().fgGreen().a(delta).reset());
        } else {
            System.out.print(delta);
        }
        System.out.flush();
    }

    /**
     * PSTREAM-10: signals to the user that the previous streaming attempt was discarded and a new one is starting.
     * Since partial text from the discarded attempt has already been painted inline, we emit a visible newline plus a
     * dim retry banner on its own line rather than trying to ANSI-erase the prior output (which is unreliable once the
     * text has wrapped across terminal columns). This keeps the transcript honest — the discarded text stays visible
     * but is clearly bracketed — and the next delta will start on a fresh line.
     */
    public void displayAssistantTextStreamReset(AssistantTextStreamReset event) {
        Objects.requireNonNull(event, "event cannot be null");
        // Leading \n lifts the banner off any in-progress delta line; colorPrintln supplies the single trailing
        // newline via println, so do NOT append another \n here (would yield a blank gap before the next attempt).
        String banner = "\n[Retrying stream: " + event.getReason() + "]";
        colorPrintln(banner, () -> ansi().fgBrightBlack().a(banner));
    }

    /**
     * PSTREAM-10: finalizes a streaming attempt by writing a terminating newline so the next output (iteration footer,
     * tool call, or final banner) starts on a fresh line. The event carries {@code totalLength} / {@code tokenUsage} /
     * {@code finishReason} — currently not rendered inline to avoid cluttering the stream — but kept available on the
     * public API for subclasses that want to surface them.
     */
    public void displayAssistantTextStreamCompleted(AssistantTextStreamCompleted event) {
        Objects.requireNonNull(event, "event cannot be null");
        System.out.println();
        System.out.flush();
    }

    /**
     * No-op by default: {@code ToolCallDisplayHook} already renders tool invocations. Kept on the public surface so
     * subclasses or future event-only wirings can override without touching the hook layer.
     */
    public void displayToolUseStarted(ToolUseStarted event) {
        Objects.requireNonNull(event, "event cannot be null");
    }

    /** Surfaces tool failures that the hook layer would not otherwise announce. Successes stay silent here. */
    public void displayToolResultReady(ToolResultReady event) {
        Objects.requireNonNull(event, "event cannot be null");
        if (event.isSuccess()) {
            return;
        }
        final String detail = event.getErrorMessage().orElse("(no error message)");
        displayError("Tool '" + event.getToolName() + "' failed: " + detail);
    }

    /** No-op: iteration completion is implicit in subsequent events and the final {@code displayResult} call. */
    public void displayIterationCompleted(IterationCompleted event) {
        Objects.requireNonNull(event, "event cannot be null");
    }

    /** No-op by default: compaction is an internal boundary, not directly user-facing. */
    public void displayCompactBoundary(CompactBoundary event) {
        Objects.requireNonNull(event, "event cannot be null");
    }

    /**
     * No-op: the surrounding {@link ReplSession} still calls {@link #displayResult(AgentExecutionResult)} with the
     * final result, which carries the full answer. Printing the event here would duplicate the outcome line.
     *
     * <p>
     * Including the {@link CompletionReason#INTERRUPTED} path: {@code displayResult} recognises that
     * reason and renders a dedicated "[Interrupted]" banner, so this no-op stays correct for cooperative interrupts.
     */
    public void displayExecutionCompleted(ExecutionCompleted event) {
        Objects.requireNonNull(event, "event cannot be null");
    }

    /**
     * No-op: {@link ReplSession} handles the surrounding {@code CompletionStage} failure (or non-throwing error
     * result) and renders an error banner via {@link #displayError(String)} / {@link #displayResult}. Duplicating here
     * would stack two error lines per failure.
     */
    public void displayExecutionError(ExecutionError event) {
        Objects.requireNonNull(event, "event cannot be null");
    }

    /**
     * SK-11: surfaces an atomic suspend event so the user sees the pending turn id and which skills are awaiting
     * approval. Without this, {@link SkillTurnSuspendedEvent} would fall through the dispatch chain and trigger an
     * {@link IllegalStateException} — a leftover from when the event type was added in SK-11.4 before the formatter was
     * extended.
     */
    public void displaySkillTurnSuspended(SkillTurnSuspendedEvent event) {
        Objects.requireNonNull(event, "event cannot be null");
        final String turnId = event.getPendingTurnId().value();
        final StringBuilder skills = new StringBuilder();
        for (PendingSkillRequest req : event.getPendingSkills()) {
            if (skills.length() > 0) {
                skills.append(", ");
            }
            skills.append(req.getSkillName());
        }
        displayInfo("[Skill approval required] Pending turn " + turnId + " — skills: " + skills + ". "
                + "Use '/approve " + turnId + "' or '/deny " + turnId + "'.");
    }

    /**
     * Renders a background subagent task completion notification as a single info line, so a long-running
     * fire-and-forget {@code Task} surfaces its outcome live when the parent turn is active. The guaranteed
     * model-facing path is the queued {@code <task-notification>}; this display is the best-effort observability half.
     */
    public void displaySubagentTaskCompleted(SubagentTaskCompleted event) {
        Objects.requireNonNull(event, "event cannot be null");
        final StringBuilder line = new StringBuilder("[Background task ")
                .append(event.getOutcome().name().toLowerCase(Locale.ROOT)).append("] ").append(event.getSubagentName())
                .append(" (taskId=").append(event.getTaskId()).append(')');
        event.getDetail().ifPresent(detail -> line.append(" — ").append(firstLine(detail)));
        displayInfo(line.toString());
    }

    /** Returns only the first line of {@code text}, so a multi-line detail stays a single terminal line. */
    private static String firstLine(String text) {
        final int newline = text.indexOf('\n');
        return newline >= 0 ? text.substring(0, newline) : text;
    }

    private void colorPrintln(String plainText, Supplier<Ansi> coloredAnsi) {
        if (settings.isColorOutput()) {
            System.out.println(coloredAnsi.get().reset());
        } else {
            System.out.println(plainText);
        }
    }

    private static boolean hasContent(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
