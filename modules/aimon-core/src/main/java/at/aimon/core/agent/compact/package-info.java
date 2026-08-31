/**
 * Transcript compaction primitives.
 *
 * <p>
 * Components in this package implement L3 Full Compaction: when an agent's conversation history approaches the
 * underlying model's context window, the {@link at.aimon.core.agent.compact.CompactionEngine} produces a
 * provider-neutral LLM summary that replaces the older messages, allowing the ReAct loop to continue.
 *
 * <p>
 * The package follows AIMON's interface-first principle: {@link at.aimon.core.agent.compact.CompactionEngine} and
 * {@link at.aimon.core.agent.compact.CompactionGuard} are interfaces with default in-memory implementations. A
 * {@link at.aimon.core.agent.compact.NoOpCompactionGuard} is provided so compaction remains opt-in for backward
 * compatibility.
 */
package at.aimon.core.agent.compact;
