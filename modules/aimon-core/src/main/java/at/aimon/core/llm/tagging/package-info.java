/**
 * Tagging support for {@link at.aimon.core.llm.LlmClient} calls.
 *
 * <p>
 * Two complementary decorators with different sources of attribution:
 * <ul>
 * <li>{@link at.aimon.core.llm.tagging.BoundMetadataLlmClient} — binds a fixed
 * {@link at.aimon.core.llm.LlmCallMetadata} at construction time. Use when a component owns its identity tags
 * (e.g. {@code component=memory}) and wants them attached unconditionally without ThreadLocal indirection.</li>
 * <li>{@link at.aimon.core.llm.tagging.TaggingLlmClient} — reads the per-thread
 * {@link at.aimon.core.llm.tagging.LlmCallMetadataHolder}. Use to flow caller-supplied context tags
 * (tenant, traceId, etc.) through code paths that cannot thread metadata explicitly.</li>
 * </ul>
 *
 * <p>
 * Both decorators share the same merge contract: caller-supplied metadata wins on every set field/tag, the decorator's
 * source (bound or ambient) fills in unset fields via
 * {@link at.aimon.core.llm.LlmCallMetadata#withDefaults(at.aimon.core.llm.LlmCallMetadata)}. They compose; a typical
 * stack is {@code Tagging( Bound( Logging( provider ) ) )}.
 *
 * <p>
 * Pair this with {@link at.aimon.core.llm.logging.LoggingLlmClient} and the metering decorator to attribute usage to
 * the calling tenant/feature/principal.
 */
package at.aimon.core.llm.tagging;
