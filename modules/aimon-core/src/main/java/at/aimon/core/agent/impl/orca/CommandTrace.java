package at.aimon.core.agent.impl.orca;

import java.util.LinkedHashMap;
import java.util.Map;

import at.aimon.core.agent.CommandInputParser;
import at.aimon.core.command.execution.CommandExecutionResult;
import at.aimon.core.llm.LlmCallMetadata;
import at.aimon.core.tracing.SpanType;
import at.aimon.core.tracing.Spans;
import at.aimon.core.tracing.TracePayloadPolicy;
import at.aimon.core.tracing.Tracer;

/**
 * The {@link SpanType#COMMAND COMMAND} span of one slash-command turn, and the metadata the command's tools are handed
 * so that whatever the command starts — a fork-mode skill's subagent — nests under it.
 *
 * <p>
 * A slash command runs instead of the ReAct loop, so there is no tool call above a fork it starts; without this span
 * the fork's SUBAGENT would hang straight off the turn and the trace would not say which command launched it. Every
 * slash command gets one, not only fork-mode skills: whether a command leaves a span should not depend on its kind.
 *
 * <p>
 * With no parent to nest under (tracing off) nothing is opened, no input map is built, and {@link #metadata()} is the
 * turn's metadata itself.
 */
final class CommandTrace {

    /** The command span, or {@code null} when there is no parent to nest under. */
    private final Tracer.Span span;
    private final LlmCallMetadata metadata;
    private CommandExecutionResult result;

    private CommandTrace(Tracer.Span span, LlmCallMetadata metadata) {
        this.span = span;
        this.metadata = metadata;
    }

    /**
     * Opens the command span under the span {@code turnMetadata} points at.
     *
     * @param tracer
     *            the tracer snapshot (must not be null)
     * @param turnMetadata
     *            the turn's call metadata, pointing at the TURN span (must not be null)
     * @param commandInput
     *            the user input that named the command
     * @param policy
     *            the payload policy snapshot; the raw arguments are captured only when it captures content
     * @return the command trace (never null)
     */
    static CommandTrace open(Tracer tracer, LlmCallMetadata turnMetadata, String commandInput,
            TracePayloadPolicy policy) {
        final String trimmed = commandInput == null ? "" : commandInput.trim();
        final CommandInputParser.ParsedCommand parsed = parse(trimmed);
        final String name = parsed == null ? "command" : "command:/" + parsed.name();
        final Tracer.Span span = Spans
                .startChildOfLazily(tracer, turnMetadata, SpanType.COMMAND, name, () -> inputs(parsed, policy))
                .orElse(null);
        if (span == null) {
            return new CommandTrace(null, turnMetadata);
        }
        return new CommandTrace(span, Spans.enrichSafely(span, turnMetadata));
    }

    /**
     * Returns the metadata the command's tools carry: rotated under the command span, or the turn's own when there is
     * none.
     *
     * @return the metadata (never null)
     */
    LlmCallMetadata metadata() {
        return metadata;
    }

    /**
     * Records the command's result, which {@link #close()} turns into the span's outputs and status.
     *
     * @param commandResult
     *            the result the command produced
     * @return {@code commandResult}, so a return statement can pass through
     */
    CommandExecutionResult record(CommandExecutionResult commandResult) {
        this.result = commandResult;
        return commandResult;
    }

    /**
     * Closes the span. A command that never produced a result — an {@link Error} unwound it — ends in error, as the
     * SUBAGENT span does.
     */
    void close() {
        if (span == null) {
            return;
        }
        if (result == null) {
            span.error("Command execution did not complete");
        } else {
            final Map<String, Object> outputs = new LinkedHashMap<>();
            outputs.put("success", result.isSuccess());
            outputs.put("responseChars", result.getResponse().length());
            span.setOutputs(outputs);
            if (result.isFailure()) {
                span.error(result.getResponse());
            }
        }
        span.close();
    }

    private static CommandInputParser.ParsedCommand parse(String trimmed) {
        try {
            return CommandInputParser.parse(trimmed);
        } catch (IllegalArgumentException e) {
            // The command flow reports the same parse failure as a failed result; the span only loses its name.
            return null;
        }
    }

    private static Map<String, Object> inputs(CommandInputParser.ParsedCommand parsed, TracePayloadPolicy policy) {
        final Map<String, Object> inputs = new LinkedHashMap<>();
        final String rawArguments = parsed == null ? "" : parsed.rawArguments();
        inputs.put("command", parsed == null ? "" : parsed.name());
        inputs.put("argsChars", rawArguments.length());
        if (policy.capturesContent()) {
            inputs.put("rawArguments", policy.truncate(rawArguments));
        }
        return inputs;
    }
}
