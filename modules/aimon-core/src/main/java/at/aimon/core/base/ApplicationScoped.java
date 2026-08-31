package at.aimon.core.base;

/**
 * Marks a component with application-level lifecycle.
 *
 * <p>
 * Components implementing this interface are created at application startup and destroyed at application shutdown. They
 * are shared across multiple {@code AgentRuntime} instances and must NOT be closed when an {@code AgentRuntime} is
 * destroyed.
 *
 * <p>
 * Examples:
 * <ul>
 * <li>{@code SchedulingEngine} - scheduled tasks survive agent-runtime destruction
 * <li>{@code McpClientFactory} - stateless, reused across agent runtimes
 * <li>{@code McpServerConfigProvider} - configuration source, reused across agent runtimes
 * </ul>
 *
 * <p>
 * A store is application-scoped even when every value it holds is scoped more narrowly: {@code SessionRecordStore}
 * outlives every session whose state it stores. Scope describes the component's own lifetime, not its contents'.
 *
 * @see AgentScoped
 * @see ExternallyManaged
 */
public interface ApplicationScoped {
}
