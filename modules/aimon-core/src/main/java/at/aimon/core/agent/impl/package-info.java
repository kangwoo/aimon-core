/**
 * Concrete agent implementation packages.
 *
 * <p>
 * This package contains concrete implementations of the {@link at.aimon.core.agent.Agent} interface. Each sub-package
 * represents a specific agent implementation with its own architecture and capabilities.
 *
 * <h2>Package Structure</h2>
 *
 * <ul>
 * <li>{@link at.aimon.core.agent.impl.orca} - Orca agent implementation using the ReAct (Reasoning and Acting) pattern
 * </ul>
 *
 * <h2>Design Philosophy</h2>
 *
 * <p>
 * The agents package follows a clear separation of concerns:
 *
 * <ul>
 * <li><b>Core Package</b> - Defines abstractions (Agent, AgentExecutor, Environment, etc.)
 * <li><b>Agents Package</b> - Provides concrete implementations
 * <li><b>Extensions Package</b> - Provides pluggable features (commands, subagents, hooks, skills, tools)
 * </ul>
 *
 * <p>
 * This structure enables:
 *
 * <ul>
 * <li>Multiple agent implementations with different architectures
 * <li>Clear dependency boundaries (implementations depend on core, not vice versa)
 * <li>Easy testing and mocking through well-defined interfaces
 * <li>Runtime agent selection and configuration
 * </ul>
 *
 * <h2>Adding New Agent Implementations</h2>
 *
 * <p>
 * To add a new agent implementation:
 *
 * <ol>
 * <li>Create a new sub-package (e.g., {@code at.aimon.core.agent.impl.myagent})
 * <li>Implement the {@link at.aimon.core.agent.AgentExecutor} interface
 * <li>Define the agent's runtime, request, and result classes
 * <li>Create factories for constructing the agent and its context
 * <li>Implement providers for tools and commands specific to the agent
 * <li>Document the agent's architecture and usage in the package-info
 * </ol>
 *
 * @see at.aimon.core.agent
 * @see at.aimon.core.agent.impl.orca
 */
package at.aimon.core.agent.impl;
