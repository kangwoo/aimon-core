package at.aimon.core.agent.prompt;

/**
 * Classification of how static a {@link SystemPromptPart} is across agent executions.
 *
 * <p>
 * This metadata lets LLM provider adapters place cache boundaries (e.g., Anthropic {@code cache_control} markers or
 * OpenAI prompt caching hints) at sensible points. It does not itself trigger any caching behaviour — it is pure data
 * describing the part.
 */
public enum Staticness {
    /**
     * Content is expected to be identical across sessions and over long time windows.
     *
     * <p>
     * Typical examples include the immutable portion of the agent instructions or embedded policy text. Provider
     * adapters may treat {@code STATIC} parts as ideal cache anchors.
     */
    STATIC,

    /**
     * Content rarely changes but is not guaranteed stable across sessions.
     *
     * <p>
     * Typical examples include the tool registry description or environment descriptors that only update when
     * configuration changes. Provider adapters may still cache these but should be prepared for occasional misses.
     */
    SEMI_STATIC,

    /**
     * Content is expected to change on every execution or frequently within a session.
     *
     * <p>
     * Typical examples include the current user context, per-turn variables, or freshly retrieved knowledge. Provider
     * adapters should generally avoid using {@code DYNAMIC} parts as cache boundaries.
     */
    DYNAMIC
}
