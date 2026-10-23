package at.aimon.core.subagent;

/**
 * Which part of a subagent's answer the {@code Task} tool keeps when the answer is longer than its result budget
 * ({@link SubagentExecutionPolicy#getResultTruncation()}). The part left out is replaced by a marker that says how
 * many characters were omitted, so the spawning model knows the answer is not whole.
 */
public enum SubagentResultTruncation {
    /** Keep the end: where an answer's conclusion usually is. The default, and what a fork without a policy gets. */
    TAIL,
    /**
     * Keep the beginning: for an answer that puts what matters first, such as findings before a list of what was read.
     */
    HEAD,
    /** Keep the beginning and the end, half the budget each, and omit the middle. */
    HEAD_AND_TAIL
}
