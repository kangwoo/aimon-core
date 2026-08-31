/**
 * Parsers for converting agent definition files into {@link at.aimon.core.agent.definition.AgentDefinition} objects.
 *
 * <h2>Overview</h2>
 *
 * <p>
 * This package provides parser implementations that convert various file formats into structured
 * {@link at.aimon.core.agent.definition.AgentDefinition} objects. The core abstraction is
 * {@link at.aimon.core.agent.definition.parser.AgentDefinitionParser}, which allows for multiple format support through
 * the Strategy pattern.
 *
 * <h2>Parser Interface</h2>
 *
 * <p>
 * {@link at.aimon.core.agent.definition.parser.AgentDefinitionParser} defines the contract for all parsers:
 *
 * <pre>
 * {
 *     &#64;code
 *     public interface AgentDefinitionParser {
 *         AgentDefinition parse(InputStream input);
 *     }
 * }
 * </pre>
 *
 * <h2>Markdown Parser</h2>
 *
 * <p>
 * {@link at.aimon.core.agent.definition.parser.MarkdownAgentDefinitionParser} is the primary implementation, supporting
 * Markdown files with YAML frontmatter. The format structure is:
 *
 * <pre>
 * ---
 * name: agent-name
 * version: 1.0.0
 * maxIterations: 50
 * model:
 *   name: gpt-4
 *   temperature: 0.7
 *   maxTokens: 4096
 *   topP: 1.0
 * variables:
 *   key1: value1
 *   key2: value2
 * ---
 * System prompt content goes here...
 * Can use {{key1}} and {{key2}} for template substitution.
 * </pre>
 *
 * <h3>Frontmatter Fields</h3>
 *
 * <p>
 * Required fields:
 * <ul>
 * <li><strong>name:</strong> Agent identifier (string)</li>
 * </ul>
 *
 * <p>
 * Optional fields with defaults:
 * <ul>
 * <li><strong>version:</strong> Agent version (string, default: "1.0.0")</li>
 * <li><strong>maxIterations:</strong> Maximum ReAct loop iterations (integer, default: Integer.MAX_VALUE)</li>
 * <li><strong>model:</strong> LLM model configuration (object, default: default model config)</li>
 * <li><strong>variables:</strong> Template variables (map, default: empty map)</li>
 * </ul>
 *
 * <h3>Model Configuration</h3>
 *
 * <p>
 * The model configuration supports:
 * <ul>
 * <li><strong>name:</strong> Model identifier (e.g., "gpt-4", "claude-3")</li>
 * <li><strong>temperature:</strong> Sampling temperature (0.0-2.0)</li>
 * <li><strong>maxTokens:</strong> Maximum tokens in response</li>
 * <li><strong>topP:</strong> Nucleus sampling parameter (0.0-1.0)</li>
 * </ul>
 *
 * <h2>Usage Examples</h2>
 *
 * <h3>Parsing from InputStream</h3>
 *
 * <pre>
 * {
 *     &#64;code
 *     AgentDefinitionParser parser = new MarkdownAgentDefinitionParser();
 *     try (InputStream input = Files.newInputStream(Path.of("agent.md"))) {
 *         AgentDefinition definition = parser.parse(input);
 *     }
 * }
 * </pre>
 *
 * <h2>Error Handling</h2>
 *
 * <p>
 * Parsing errors throw {@link at.aimon.core.agent.definition.exception.AgentDefinitionParseException} with specific
 * error messages for:
 * <ul>
 * <li>Missing frontmatter delimiters</li>
 * <li>Empty or invalid YAML</li>
 * <li>Missing required fields</li>
 * <li>Invalid version format</li>
 * <li>Invalid model configuration</li>
 * </ul>
 *
 * <h2>Template Variables</h2>
 *
 * <p>
 * Variables defined in frontmatter can be used in the system prompt with Mustache-style syntax:
 *
 * <pre>
 * ---
 * name: my-agent
 * variables:
 *   language: Java
 *   framework: Spring Boot
 * ---
 * You are a {{language}} expert specializing in {{framework}}.
 * </pre>
 *
 * <p>
 * Note: The parser does not perform template substitution - it only extracts and stores the variables. Template
 * rendering is handled by {@link at.aimon.core.agent.template.TemplateRenderer}.
 *
 * <h2>Extending with New Parsers</h2>
 *
 * <p>
 * To support a new format (e.g., JSON, YAML, XML):
 * <ol>
 * <li>Implement {@link at.aimon.core.agent.definition.parser.AgentDefinitionParser}</li>
 * <li>Parse the format-specific structure</li>
 * <li>Extract required and optional fields</li>
 * <li>Build {@link at.aimon.core.agent.definition.AgentDefinition} using the builder</li>
 * <li>Throw {@link at.aimon.core.agent.definition.exception.AgentDefinitionParseException} on errors</li>
 * </ol>
 *
 * <h2>Thread Safety</h2>
 *
 * <p>
 * All parser implementations are thread-safe and stateless, making them suitable for concurrent use and reuse across
 * multiple parsing operations.
 *
 * @see at.aimon.core.agent.definition.parser.AgentDefinitionParser
 * @see at.aimon.core.agent.definition.parser.MarkdownAgentDefinitionParser
 * @see at.aimon.core.agent.definition.AgentDefinition
 */
package at.aimon.core.agent.definition.parser;
