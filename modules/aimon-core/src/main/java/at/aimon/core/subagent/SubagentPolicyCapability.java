package at.aimon.core.subagent;

/**
 * One kind of limit or context setting a {@link SubagentExecutionPolicy} can ask for.
 *
 * <p>
 * A policy names the capabilities it uses ({@link SubagentExecutionPolicy#getCapabilities()}) and an executor names
 * the ones it enforces ({@link at.aimon.core.subagent.execution.SubagentExecutor#supportedPolicyCapabilities()}). A
 * launch whose policy asks for one the executor does not enforce is refused before the fork starts: a limit that is
 * silently not applied is a limit of infinity, which is the opposite of what the caller asked for.
 */
public enum SubagentPolicyCapability {
    /** A cap on ReAct iterations ({@link SubagentExecutionPolicy#getMaxIterations()}). */
    MAX_ITERATIONS,
    /** A cap on cumulative provider-reported tokens ({@link SubagentExecutionPolicy#getMaxTokens()}). */
    MAX_TOKENS,
    /** A cap on tool calls actually run ({@link SubagentExecutionPolicy#getMaxToolCalls()}). */
    MAX_TOOL_CALLS,
    /** A deadline measured from launch ({@link SubagentExecutionPolicy#getMaxWallClock()}). */
    MAX_WALL_CLOCK,
    /** A cumulative-token compaction hint ({@link SubagentExecutionPolicy#getCompactionTokenThreshold()}). */
    COMPACTION_TOKEN_THRESHOLD,
    /** A per-call input-size compaction hint ({@link SubagentExecutionPolicy#getContextTargetTokens()}). */
    CONTEXT_TARGET_TOKENS,
    /**
     * Instructions handed to the summary of an AUTO compaction
     * ({@link SubagentExecutionPolicy#getCompactionInstructions()}).
     */
    COMPACTION_INSTRUCTIONS,
    /**
     * Running the fork with its spawner's context engine ({@link SubagentExecutionPolicy#isInheritContextEngine()}).
     */
    INHERIT_CONTEXT_ENGINE
}
