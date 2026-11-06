package at.aimon.core.programmatic;

/**
 * How a program run by {@code ExecuteCode} ended ({@code docs/design/tool/programmatic-tool-calling.md} §9, §12.1).
 */
public enum CodeExecutionStatus {

    /** The program ran to its end. */
    COMPLETED(true),

    /** The program threw, or did not parse. */
    SCRIPT_ERROR(true),

    /** The wall-clock limit ran out. Retryable: the model can split the work. */
    TIMED_OUT(true),

    /** The execution the program belongs to was cancelled. */
    CANCELLED(true),

    /** A limit other than the wall clock was exceeded (nested calls, statements, output, result bytes). Retryable. */
    LIMIT_EXCEEDED(true),

    /** The engine's runtime broke the protocol with the host (shell engine). Not retryable. */
    PROTOCOL_VIOLATION(false);

    private final boolean retryable;

    CodeExecutionStatus(boolean retryable) {
        this.retryable = retryable;
    }

    /**
     * Returns whether running the program again (or a smaller one) may succeed.
     *
     * @return {@code false} only for {@link #PROTOCOL_VIOLATION}
     */
    public boolean isRetryable() {
        return retryable;
    }
}
