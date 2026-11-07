package at.aimon.core.tools.code;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import at.aimon.core.programmatic.CodeExecutionLimits;
import at.aimon.core.programmatic.CodeExecutionOutcome;
import at.aimon.core.programmatic.CodeExecutionStatus;

/**
 * Formats what the model observes of one program ({@code docs/design/tool/programmatic-tool-calling.md} §12.1): the
 * standard output (cut at the limit), a JavaScript return value, then the side channels the bridge collected — notices,
 * hook feedback, logs — and a one-line summary of the calls.
 *
 * <p>
 * The summary is the last line of every observation, success or error, and names the program's language
 * ({@code [ExecuteCode (Python): …]}): it is the persisted marker {@link ExecuteCodeLanguageNotice} reads to tell
 * whether the conversation's earlier programs were in another language (§6.2).
 *
 * <p>
 * Stateless.
 */
final class ExecuteCodeObservation {

    /** The most hook-feedback entries an observation carries. */
    static final int MAX_HOOK_FEEDBACK = 20;

    private ExecuteCodeObservation() {
        throw new AssertionError("This class should not be instantiated");
    }

    /** The text of a completed program. */
    // Every argument is a distinct part of the observation; bundling them would only move the list.
    @SuppressWarnings("checkstyle:ParameterNumber")
    static String success(String language, CodeExecutionOutcome outcome, List<NestedToolCall> calls,
            List<String> notices, List<String> hookFeedback, List<String> stillRunning, Duration elapsed,
            CodeExecutionLimits limits) {
        final int maxObservationChars = limits.getMaxObservationChars();
        final int maxLogChars = limits.getMaxLogChars();
        final StringBuilder out = new StringBuilder();
        out.append(cut(outcome.getStdout(), maxObservationChars));
        // Output and return value share one budget, so the observation stays within the limit (§13.1).
        final int left = Math.max(0, maxObservationChars - Math.min(outcome.getStdout().length(), maxObservationChars));
        outcome.getReturnValue().ifPresent(value -> {
            if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') {
                out.append('\n');
            }
            out.append(cut(value, left));
        });
        appendSideChannels(out, notices, hookFeedback, outcome.getLogs(), maxLogChars);
        appendSummary(out, language, calls, stillRunning, elapsed);
        return out.toString();
    }

    /**
     * The error text of a program that did not complete. A program stopped from outside — {@code TIMED_OUT} or
     * {@code LIMIT_EXCEEDED} — keeps the standard output it printed before the stop, cut at the same limit as a
     * completed program's; the other statuses carry none (§12.1).
     */
    // Every argument is a distinct part of the observation; bundling them would only move the list.
    @SuppressWarnings("checkstyle:ParameterNumber")
    static String failure(String language, CodeExecutionOutcome outcome, List<NestedToolCall> calls,
            List<String> notices, List<String> hookFeedback, List<String> stillRunning, Duration elapsed,
            CodeExecutionLimits limits) {
        final int maxLogChars = limits.getMaxLogChars();
        final StringBuilder out = new StringBuilder("ExecuteCode ").append(describe(outcome.getStatus()));
        outcome.getErrorMessage().map(ExecuteCodeObservation::lastLine)
                .ifPresent(message -> out.append(": ").append(message));
        if (outcome.getStatus() == CodeExecutionStatus.TIMED_OUT
                || outcome.getStatus() == CodeExecutionStatus.LIMIT_EXCEEDED) {
            out.append("\nSplit the work into smaller programs and run them one at a time.");
            // The program was stopped from outside, not broken: what it printed says how far it got, which the model
            // needs to split the rest — and not to redo calls whose effects were already applied (§12.1).
            if (!outcome.getStdout().isEmpty()) {
                out.append("\n\n[output before the stop]\n")
                        .append(cut(outcome.getStdout(), limits.getMaxObservationChars()));
            }
        }
        appendSideChannels(out, notices, hookFeedback, outcome.getLogs(), maxLogChars);
        appendSummary(out, language, calls, stillRunning, elapsed);
        return out.toString();
    }

    private static String describe(CodeExecutionStatus status) {
        return switch (status) {
            case COMPLETED -> "completed";
            case SCRIPT_ERROR -> "script error";
            case TIMED_OUT -> "timed out";
            case CANCELLED -> "cancelled";
            case LIMIT_EXCEEDED -> "stopped at a limit";
            case PROTOCOL_VIOLATION -> "protocol violation";
        };
    }

    private static void appendSideChannels(StringBuilder out, List<String> notices, List<String> hookFeedback,
            String logs, int maxLogChars) {
        if (!notices.isEmpty()) {
            out.append("\n\n[environment]\n").append(String.join("\n", notices));
        }
        if (!hookFeedback.isEmpty()) {
            out.append("\n\n[hook feedback]\n");
            out.append(String.join("\n", hookFeedback.subList(0, Math.min(hookFeedback.size(), MAX_HOOK_FEEDBACK))));
            if (hookFeedback.size() > MAX_HOOK_FEEDBACK) {
                out.append("\n[").append(hookFeedback.size() - MAX_HOOK_FEEDBACK).append(" more]");
            }
        }
        if (!logs.isEmpty()) {
            out.append("\n\n[logs]\n").append(tail(logs, maxLogChars));
        }
    }

    private static void appendSummary(StringBuilder out, String language, List<NestedToolCall> calls,
            List<String> stillRunning, Duration elapsed) {
        if (!stillRunning.isEmpty()) {
            out.append("\n\nstill running when the script ended: ").append(String.join(", ", stillRunning));
        }
        out.append(out.length() == 0 ? "" : "\n\n").append(summary(language, calls, elapsed));
    }

    /** {@code [ExecuteCode (Python): 14 tool calls (Read x12, Grep x2), 1 error, 2 refused by write cap, 3.2s]}. */
    static String summary(String language, List<NestedToolCall> calls, Duration elapsed) {
        final Map<String, Integer> byTool = new LinkedHashMap<>();
        int errors = 0;
        final Map<String, Integer> refusals = new LinkedHashMap<>();
        for (NestedToolCall call : calls) {
            byTool.merge(call.toolName(), 1, Integer::sum);
            if (call.refusal() != null) {
                refusals.merge(call.refusal(), 1, Integer::sum);
            } else if (call.isError()) {
                errors++;
            }
        }
        final StringBuilder out = new StringBuilder("[ExecuteCode (")
                .append(ExecuteCodeDescription.displayName(language)).append("): ").append(calls.size())
                .append(calls.size() == 1 ? " tool call" : " tool calls");
        if (!byTool.isEmpty()) {
            final StringBuilder tools = new StringBuilder();
            byTool.forEach((name, count) -> tools.append(tools.length() == 0 ? "" : ", ").append(name).append(" x")
                    .append(count));
            out.append(" (").append(tools).append(')');
        }
        if (errors > 0) {
            out.append(", ").append(errors).append(errors == 1 ? " error" : " errors");
        }
        refusals.forEach((kind, count) -> out.append(", ").append(count).append(" refused by ").append(kind));
        out.append(", ").append(String.format(Locale.ROOT, "%.1fs", elapsed.toMillis() / 1000.0)).append(']');
        return out.toString();
    }

    private static String cut(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + "\n[truncated " + (text.length() - max) + " chars]";
    }

    private static String tail(String text, int max) {
        return text.length() <= max ? text : "…" + text.substring(text.length() - max);
    }

    private static String lastLine(String message) {
        final String trimmed = message.strip();
        final int newline = trimmed.lastIndexOf('\n');
        return newline < 0 ? trimmed : trimmed.substring(newline + 1);
    }
}
