package at.aimon.core.agent.budget;

import java.util.Objects;

import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.StopReason;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;

/**
 * How an agent execution treats a response the provider cut off at its max-output-token limit
 * ({@link StopReason#MAX_TOKENS}).
 *
 * <p>
 * One definition for all four tool loops in the tree — {@code OrcaAgentExecutor}, which runs a turn,
 * {@code DefaultSubagentExecutor}, which runs a fork, {@code LlmSkillExecutor}, which runs a skill's own loop, and
 * {@code ReActLlmDeriver} — so they cannot give different answers to the same stop reason. It lives beside
 * {@link CompletionReason} rather than on the main executor because the fork may not reference
 * {@code at.aimon.core.agent.impl}.
 *
 * <p>
 * A cut response comes in two shapes:
 *
 * <ul>
 * <li><b>A final answer</b>, with no tool calls. On the two executors the execution ends as
 * {@link CompletionReason#TRUNCATED}: the partial text with {@link #TRUNCATION_MARKER} appended, and a WARN. A skill's
 * loop returns the same marked text as its result, which carries no completion reason. The deriver returns observations
 * rather than text, so it has no answer to mark.
 * <li><b>A response with tool calls.</b> None of them is run, on any of the four loops. Each is answered with
 * {@link #refusal(ToolUse)}, the results are committed as executed results are (every {@code tool_use} must be
 * answered), and the loop continues — on a turn, a fork and a skill's loop until {@link StalledIterationGuard} ends a
 * streak of them. A call cut short still arrives as a tool call — a streamed {@code tool_use} is registered when its
 * block starts, and arguments that never finished parse to an empty map — and the neutral {@link LlmResponse} does
 * not say which call was cut. Running any of them risks running one with none of the arguments the model was
 * writing.
 * </ul>
 *
 * <p>
 * Detection reads {@link StopReason} alone, so the answer is the same on every provider that maps its cut to
 * {@code MAX_TOKENS}, whether the call was blocking or streamed. A cut reported as {@link StopReason#UNKNOWN} is not
 * detected: that value never changes behaviour.
 *
 * <p>
 * Stateless; not instantiable.
 */
public final class TruncatedResponses {

    /**
     * Appended to the partial text of a final answer cut off at {@code max_tokens}, in the transcript and in the
     * result, so the truncation is explicit to a human reader and to any consumer inspecting the answer.
     */
    public static final String TRUNCATION_MARKER = "\n\n[System: response truncated at max_tokens]";

    /**
     * The {@code tool_result} content that answers each tool call of a response cut off at {@code max_tokens}.
     *
     * <p>
     * Phrased for the model, which reads it on the next iteration. It names {@code max_tokens}; it says the calls were
     * discarded and have no result; it does not invite sending the same calls again; and it names the two edits that
     * fit under the limit.
     *
     * <p>
     * "No call that changes anything was run" rather than "none was run", because with streaming-tool overlap on,
     * {@code OrcaAgentExecutor} may already have started a call from the response before its stop reason arrived.
     * Only a {@code CONCURRENT_SAFE} tool starts early, and that declaration says it changes nothing, so its result
     * is discarded and the sentence stays true.
     */
    public static final String REFUSED_TOOL_CALL_MESSAGE = "Cut off at max_tokens: your response reached the "
            + "max_tokens output limit before it finished, so its tool calls may be incomplete. They were "
            + "discarded: no call that changes anything was run, and none of them has a result. Sending the same "
            + "calls again will be cut off the same way. Produce less output in one response: make fewer tool "
            + "calls at once, or split a large argument across several smaller calls.";

    private TruncatedResponses() {
    }

    /**
     * Whether the provider cut this response off at its max-output-token limit.
     *
     * @param response
     *            the response to read (must not be null)
     * @return {@code true} iff the response carries a stop reason and {@link StopReason#isTruncated()} holds for it
     */
    public static boolean isTruncated(LlmResponse response) {
        Objects.requireNonNull(response, "response cannot be null");
        return response.getStopReason().filter(StopReason::isTruncated).isPresent();
    }

    /**
     * The error result that answers one tool call of a response cut off at {@code max_tokens}, in place of running it.
     *
     * @param toolUse
     *            the refused call (must not be null)
     * @return an error {@link ToolUseResult} for that call carrying {@link #REFUSED_TOOL_CALL_MESSAGE}
     */
    public static ToolUseResult refusal(ToolUse toolUse) {
        Objects.requireNonNull(toolUse, "toolUse cannot be null");
        return ToolUseResult.error(toolUse.getId(), REFUSED_TOOL_CALL_MESSAGE);
    }

    /**
     * The clause a truncation WARN ends with when the cut response's usage reports reasoning tokens, or {@code ""} when
     * it reports none.
     *
     * <p>
     * Numbers only, with no inference drawn from them. Reasoning counts against the same output allowance, so a large
     * share points at a thinking budget that left the answer little room — but there is no share past which "reasoning
     * used most of it" could be asserted, and {@code docs/design/llm/thinking-reporting-and-dialect-records.md} §16.8
     * refuses to choose that number. The operator reads both counts. The wording does not say "of", because
     * {@link TokenUsage} does not require the reasoning count to be at most the output count.
     *
     * <p>
     * Empty rather than a clause reporting zero, so a WARN from a provider or path that does not fill the counter reads
     * exactly as it did before the clause existed.
     *
     * @param usage
     *            the usage of the cut response itself, not the execution's accumulated usage (must not be null)
     * @return the clause, starting with {@code "; "}, or the empty string
     */
    public static String reasoningClause(TokenUsage usage) {
        Objects.requireNonNull(usage, "usage cannot be null");
        if (usage.getReasoningTokens() == 0) {
            return "";
        }
        return "; the response's usage reports " + usage.getCompletionTokens() + " output tokens and "
                + usage.getReasoningTokens() + " reasoning tokens";
    }
}
