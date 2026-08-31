/**
 * Structured system prompt model for agent executions.
 *
 * <h2>Overview</h2>
 *
 * <p>
 * This package introduces a structured representation of an agent's system prompt as an ordered list of immutable
 * {@link at.aimon.core.agent.prompt.SystemPromptPart parts}, rather than a single concatenated {@code String}. Each
 * part
 * carries metadata describing how static it is across sessions, which lets downstream LLM providers attach cache hints
 * (for example, Anthropic's {@code cache_control} or OpenAI prompt caching markers) at fine-grained boundaries.
 *
 * <h2>Key Concepts</h2>
 *
 * <ul>
 * <li>{@link at.aimon.core.agent.prompt.SystemPromptPart} — immutable value type holding the text content, its
 * {@link at.aimon.core.agent.prompt.Staticness staticness} classification, a {@code kind} identifier (such as
 * {@code "agent-instructions"}, {@code "user-context"}, {@code "tool-registry"}), and an optional
 * {@code cacheHintKey}.
 * <li>{@link at.aimon.core.agent.prompt.Staticness} — enum distinguishing {@code STATIC}, {@code SEMI_STATIC}, and
 * {@code DYNAMIC} parts so provider adapters can decide where cache boundaries make sense.
 * <li>{@link at.aimon.core.agent.prompt.SystemPromptParts} — immutable ordered container of
 * {@link at.aimon.core.agent.prompt.SystemPromptPart} instances with a {@code concatenated()} fallback for legacy
 * single-{@code String} callers.
 * </ul>
 *
 * <h2>Scope</h2>
 *
 * <p>
 * The types in this package are pure data: they intentionally contain no provider-specific cache logic. Translating
 * {@code cacheHintKey} into provider semantics is the responsibility of the LLM client adapters and the agent executor
 * integration layer (tracked as separate tasks in the adoption plan).
 */
package at.aimon.core.agent.prompt;
