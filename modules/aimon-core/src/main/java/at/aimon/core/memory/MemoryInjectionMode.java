package at.aimon.core.memory;

/**
 * How a {@link MemoryContextProvider} renders the latest {@link Representation} into a system prompt part.
 *
 * <ul>
 * <li>{@link #SUMMARY_ONLY} — only the synthesized summary (3-6 sentences, bounded by the deriver prompt) plus a
 * one-line metadata header. Observations are omitted regardless of {@code maxTokens}. Default for auto-injection
 * because the cost is paid every turn.</li>
 * <li>{@link #FULL} — summary plus observations. When a positive {@code maxTokens} budget is supplied and the
 * representation's {@code tokenCount} exceeds it, observations are dropped (summary is always kept).</li>
 * </ul>
 */
public enum MemoryInjectionMode {
    SUMMARY_ONLY, FULL
}
