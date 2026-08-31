/**
 * Agent definition system for loading and configuring agents from various sources.
 *
 * <h2>Overview</h2>
 *
 * <p>
 * This package provides a flexible system for defining agents externally (e.g., in Markdown files) and loading them at
 * runtime. An agent definition includes all configuration needed to instantiate and execute an agent:
 * <ul>
 * <li>Agent metadata (name, version)</li>
 * <li>LLM model configuration (model name, temperature, max tokens, etc.)</li>
 * <li>Execution limits (max iterations)</li>
 * <li>System prompt that defines agent behavior</li>
 * <li>Template variables for prompt customization</li>
 * </ul>
 *
 * <h2>Core Components</h2>
 *
 * <h3>AgentDefinition</h3>
 *
 * <p>
 * {@link at.aimon.core.agent.definition.AgentDefinition} is the central immutable value object representing a complete
 * agent configuration. It is constructed using the builder pattern:
 *
 * <pre>
 * {
 *     &#64;code
 *     AgentDefinition definition = AgentDefinition.builder().name("coding-agent").version(new Version(1, 0, 0))
 *             .model(LlmModel.builder().name("gpt-4").temperature(0.7).build()).maxIterations(50)
 *             .systemPrompt("You are a Java expert...").variables(Map.of("language", "Java")).build();
 * }
 * </pre>
 *
 * <h3>AgentDefinitionParser</h3>
 *
 * <p>
 * {@link at.aimon.core.agent.definition.parser.AgentDefinitionParser} handles parsing of agent definition files.
 * {@link at.aimon.core.agent.definition.parser.MarkdownAgentDefinitionParser} parses Markdown files with YAML
 * frontmatter.
 *
 * <h2>Definition File Format</h2>
 *
 * <p>
 * Agent definitions are typically stored in Markdown files with YAML frontmatter:
 *
 * <pre>
 * ---
 * name: coding-agent
 * version: 1.0.0
 * maxIterations: 50
 * model:
 *   name: gpt-4
 *   temperature: 0.7
 *   maxTokens: 4096
 * variables:
 *   language: Java
 *   framework: Spring Boot
 * ---
 * You are an expert {{language}} developer specializing in {{framework}}.
 * Your role is to help users with coding tasks...
 * </pre>
 *
 * <h2>Design Principles</h2>
 *
 * <p>
 * The agent definition system follows several key design principles:
 * <ul>
 * <li><strong>Separation of Concerns:</strong> Agent configuration is separated from code</li>
 * <li><strong>Immutability:</strong> All definitions are immutable value objects</li>
 * <li><strong>Strategy Pattern:</strong> Different loaders for different sources</li>
 * <li><strong>Open/Closed Principle:</strong> Easy to add new loader or parser implementations</li>
 * <li><strong>Fail Fast:</strong> Validation occurs at construction/load time</li>
 * </ul>
 *
 * <h2>Exception Handling</h2>
 *
 * <p>
 * The package defines specific exceptions in the {@link at.aimon.core.agent.definition.exception} sub-package:
 * <ul>
 * <li>{@link at.aimon.core.agent.definition.exception.AgentDefinitionLoadException} - Generic loading failure</li>
 * <li>{@link at.aimon.core.agent.definition.exception.AgentDefinitionNotFoundException} - Definition not found</li>
 * <li>{@link at.aimon.core.agent.definition.exception.AgentDefinitionParseException} - Parsing failure</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 *
 * <p>
 * {@link AgentDefinition} instances are immutable and thus inherently thread-safe.
 *
 * @see at.aimon.core.agent.definition.AgentDefinition
 * @see at.aimon.core.agent.definition.parser.AgentDefinitionParser
 */
package at.aimon.core.agent.definition;
