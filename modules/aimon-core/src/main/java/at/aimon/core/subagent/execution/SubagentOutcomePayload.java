package at.aimon.core.subagent.execution;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import at.aimon.core.agent.budget.CompletionReason;
import at.aimon.core.subagent.SubagentExecutionPolicy;

/**
 * The structured outcome of one {@code Task} call, attached to its {@code ToolResult} as the render payload
 * ({@code ToolResult#getRenderPayload()}) beside the text the model reads.
 *
 * <p>
 * The text is for the model; this is for code. A wrapper that has to tell a finished verification from one a budget
 * stopped, or a delivered result from a completed run, reads these keys rather than parsing the text. The payload is
 * not sent to
 * the model and is not part of the core conversation persistence; like any render payload it is handed to
 * {@code PostToolHook}s, which see it under {@link #KIND} {@code = }{@link #KIND_VALUE} and may ignore it.
 *
 * <ul>
 * <li>{@link #KIND} — always {@link #KIND_VALUE}.
 * <li>{@link #TASK_ID}, {@link #SUBAGENT} — which run.
 * <li>{@link #BACKGROUND} — {@code true} for a background launch, which carries nothing else: the run has not ended.
 * <li>{@link #COMPLETION_REASON} — the {@code CompletionReason} name. Only {@code COMPLETED} is a completion.
 * <li>{@link #SUCCESS} — the result's success flag; {@code true} also for {@code TRUNCATED} and
 * {@code MAX_ITERATIONS_GRACE}, so read the reason.
 * <li>{@link #EXIT_CONFIRMED} — {@code false} when the run had not exited when the result was handed back
 * ({@link SubagentExecutionResult#isExitConfirmed()}).
 * <li>{@link #TRUNCATED} — the final answer was cut off at {@code max_tokens}.
 * <li>{@link #ITERATIONS}, {@link #TOOL_CALLS}, {@link #TOTAL_TOKENS} — what the run spent.
 * <li>{@link #POLICY_ROLE} — the execution policy it ran under, when there was one.
 * <li>{@link #STRUCTURED_RESULT} — the structured result the run reported, whole, when its policy asked for one and
 * it reported one ({@link SubagentExecutionResult#getStructuredResult()}).
 * <li>{@link #STRUCTURED_RESULT_MISSING} — present only when the policy required a structured result: {@code true}
 * when the run reported none, which makes its work incomplete whatever the completion reason says.
 * </ul>
 *
 * <p>
 * Values are strings, booleans and integers, except {@link #STRUCTURED_RESULT}, which is the reported payload: an
 * unmodifiable map of JSON values. A run without a result schema in its policy carries neither of the last two keys,
 * so its payload is what it always was.
 */
public final class SubagentOutcomePayload {

    /** The key naming what kind of payload this is. */
    public static final String KIND = "kind";
    /** The value of {@link #KIND}. */
    public static final String KIND_VALUE = "subagent-outcome";
    /** The task id (string). */
    public static final String TASK_ID = "taskId";
    /** The subagent name (string). */
    public static final String SUBAGENT = "subagent";
    /** Whether the call launched a background task (boolean). */
    public static final String BACKGROUND = "background";
    /** The completion reason name (string). */
    public static final String COMPLETION_REASON = "completionReason";
    /** The result's success flag (boolean). */
    public static final String SUCCESS = "success";
    /** Whether the run had exited (boolean). */
    public static final String EXIT_CONFIRMED = "exitConfirmed";
    /** Whether the final answer was cut off at {@code max_tokens} (boolean). */
    public static final String TRUNCATED = "truncated";
    /** Iterations run (integer). */
    public static final String ITERATIONS = "iterations";
    /** Tool calls run (integer). */
    public static final String TOOL_CALLS = "toolCalls";
    /** Provider-reported tokens spent (integer). */
    public static final String TOTAL_TOKENS = "totalTokens";
    /** The execution policy's role, when the run had a policy (string). */
    public static final String POLICY_ROLE = "policyRole";
    /** The reported structured result (map of JSON values), when there is one. */
    public static final String STRUCTURED_RESULT = "structuredResult";
    /** Whether a required structured result is missing (boolean), when one was required. */
    public static final String STRUCTURED_RESULT_MISSING = "structuredResultMissing";

    private SubagentOutcomePayload() {
    }

    /**
     * The payload of a foreground run that has returned.
     *
     * @param taskId
     *            the task id (must not be null)
     * @param subagentName
     *            the subagent (must not be null)
     * @param policy
     *            the policy it ran under, or null
     * @param result
     *            its result (must not be null)
     * @return the payload (immutable)
     */
    public static Map<String, Object> of(String taskId, String subagentName, SubagentExecutionPolicy policy,
            SubagentExecutionResult result) {
        Objects.requireNonNull(result, "result cannot be null");
        final Map<String, Object> payload = base(taskId, subagentName, policy, false);
        payload.put(COMPLETION_REASON, result.getCompletionReason().name());
        payload.put(SUCCESS, result.isSuccess());
        payload.put(EXIT_CONFIRMED, result.isExitConfirmed());
        payload.put(TRUNCATED, result.getCompletionReason() == CompletionReason.TRUNCATED);
        payload.put(ITERATIONS, result.getIterationCount());
        payload.put(TOOL_CALLS, result.getToolCallCount());
        payload.put(TOTAL_TOKENS, result.getMetadata().getTokenUsage().getTotalTokens());
        if (requiresStructuredResult(policy, result)) {
            result.getStructuredResult().ifPresent(structured -> payload.put(STRUCTURED_RESULT, structured));
            payload.put(STRUCTURED_RESULT_MISSING, result.getStructuredResult().isEmpty());
        }
        return Map.copyOf(payload);
    }

    /**
     * The payload of a background launch: which task, nothing about its end.
     *
     * @param taskId
     *            the task id (must not be null)
     * @param subagentName
     *            the subagent (must not be null)
     * @param policy
     *            the policy it runs under, or null
     * @return the payload (immutable)
     */
    public static Map<String, Object> launched(String taskId, String subagentName, SubagentExecutionPolicy policy) {
        return Map.copyOf(base(taskId, subagentName, policy, true));
    }

    /**
     * Whether a structured result was required of a run: its policy has a result schema, or its result says so. The
     * policy is read too because a result the manager made without the fork returning — one whose exit is unconfirmed
     * — has no executor to have said so.
     *
     * @param policy
     *            the policy it ran under, or null
     * @param result
     *            its result (must not be null)
     * @return whether a structured result was required
     */
    public static boolean requiresStructuredResult(SubagentExecutionPolicy policy, SubagentExecutionResult result) {
        Objects.requireNonNull(result, "result cannot be null");
        return result.isStructuredResultRequired() || policy != null && policy.requiresStructuredResult();
    }

    private static Map<String, Object> base(String taskId, String subagentName, SubagentExecutionPolicy policy,
            boolean background) {
        final Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(KIND, KIND_VALUE);
        payload.put(TASK_ID, Objects.requireNonNull(taskId, "taskId cannot be null"));
        payload.put(SUBAGENT, Objects.requireNonNull(subagentName, "subagentName cannot be null"));
        payload.put(BACKGROUND, background);
        if (policy != null) {
            payload.put(POLICY_ROLE, policy.getRole());
        }
        return payload;
    }
}
