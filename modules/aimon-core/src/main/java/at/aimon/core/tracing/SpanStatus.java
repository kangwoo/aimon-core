package at.aimon.core.tracing;

/**
 * Terminal status of a {@link TraceSpan}.
 */
public enum SpanStatus {

    /** The span completed normally. */
    OK,

    /** The span ended because an error was raised. */
    ERROR,

    /** The span ended because the execution was interrupted/cancelled. */
    INTERRUPTED
}
