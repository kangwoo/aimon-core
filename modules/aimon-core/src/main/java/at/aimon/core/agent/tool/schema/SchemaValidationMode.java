package at.aimon.core.agent.tool.schema;

/**
 * How the tool executor reacts to a schema violation.
 *
 * <p>
 * The gate tightens a contract the model already observes, so switching it on everywhere at once would make a sudden
 * wave of rejections hard to attribute — a rejection could mean the model got the call wrong, or it could mean one of
 * our own schemas is wrong. The three modes exist so that the two can be told apart in that order: observe first,
 * reject second.
 *
 * <p>
 * {@link #WARN} is the initial default. {@link #ENFORCE} is the intended end state and is promoted separately, once a
 * cycle of {@code WARN} logs has shown that the violations being reported are genuinely the model's mistakes.
 *
 * @see at.aimon.core.agent.tool.execution.DefaultToolExecutor
 */
public enum SchemaValidationMode {

    /**
     * Skip validation entirely — the kill switch.
     *
     * <p>
     * The validator is not consulted at all, so this costs nothing and cannot itself reject a call. Use it to rule the
     * gate out when diagnosing a tool-call failure.
     */
    OFF,

    /**
     * Validate and log violations at {@code WARN}, but execute the tool anyway.
     *
     * <p>
     * The model sees no difference from {@link #OFF}; only the logs do. This is the mode this feature ships in.
     */
    WARN,

    /**
     * Validate and refuse to execute, returning the violations to the model as a tool error.
     */
    ENFORCE
}
