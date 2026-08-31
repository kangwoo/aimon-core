package at.aimon.core.base;

/**
 * Marks a component with {@code AgentRuntime}-level lifecycle.
 *
 * <p>
 * Since {@code AgentRuntime} is <strong>agent-scoped</strong> (one instance per
 * {@code (Agent, discriminator)} pair, see {@code AgentRuntimeId}), components implementing this interface
 * share the same agent-scoped lifetime. They are created when the agent's runtime is first materialized and destroyed
 * only when that runtime is removed (typically at application shutdown or explicit agent removal) —
 * <strong>not</strong>
 * when an individual session ends.
 *
 * <p>
 * Each {@code (Agent, discriminator)} pair owns an independent instance; instances are not shared across different
 * agent runtimes.
 *
 * <p>
 * This interface extends {@link AutoCloseable} to enforce that implementations provide a {@link #close()} method.
 * The marker <strong>documents</strong> lifetime; it does <strong>not</strong> by itself register a component for
 * teardown. There is no fan-out over implementors: {@code OrcaAgentRuntime.close()} closes an explicit, hardcoded
 * list. A new agent-scoped component holding a native resource (a connection pool, a watcher thread) must be added
 * to that list explicitly, or it will never be closed.
 *
 * <p>
 * Callers must <strong>not</strong> invoke {@code AgentRuntime.close()} from per-session paths
 * (e.g.,{@code LiveSession.close()}); doing so would prematurely tear down resources still in use by other
 * sessions sharing the same agent runtime.
 *
 * <p>
 * Implementors:
 * <ul>
 * <li>{@code McpClientManager} - MCP server connections live for the agent's lifetime
 * </ul>
 *
 * <p>
 * {@code ToolRegistry} and {@code HookRegistry} are agent-scoped by <em>lifetime</em> — each agent runtime owns an
 * independent instance — but they are plain interfaces that do not implement this marker, because they hold no
 * resource that needs closing.
 *
 * @see ApplicationScoped
 * @see ExternallyManaged
 */
public interface AgentScoped extends AutoCloseable {
}
