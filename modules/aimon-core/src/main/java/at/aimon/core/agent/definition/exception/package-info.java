/**
 * Exception types for agent definition loading and parsing errors.
 *
 * <h2>Overview</h2>
 *
 * <p>
 * This package defines specific exception types for failures that occur during agent definition loading and parsing.
 * All exceptions extend {@link at.aimon.core.agent.exception.AgentException}, integrating with the broader agent
 * exception hierarchy.
 *
 * <h2>Exception Hierarchy</h2>
 *
 * <pre>
 * AgentException (from core)
 *   └── AgentDefinitionLoadException    (generic loading failure)
 *       └── AgentDefinitionNotFoundException (definition not found)
 *   └── AgentDefinitionParseException   (parsing failure)
 * </pre>
 *
 * <h2>Exception Types</h2>
 *
 * <h3>AgentDefinitionLoadException</h3>
 *
 * <p>
 * {@link at.aimon.core.agent.definition.exception.AgentDefinitionLoadException} is thrown when loading fails for
 * reasons other than the definition not being found. Common causes:
 * <ul>
 * <li>I/O errors reading the definition file</li>
 * <li>Permission issues accessing the file</li>
 * <li>Network errors for remote sources</li>
 * <li>Parsing errors (wraps AgentDefinitionParseException)</li>
 * </ul>
 *
 * <p>
 * Example:
 *
 * <pre>
 * {
 *     &#64;code
 *     try {
 *         AgentDefinition definition = loader.load("my-agent");
 *     } catch (AgentDefinitionLoadException e) {
 *         log.error("Failed to load agent definition", e);
 *         // Handle loading failure
 *     }
 * }
 * </pre>
 *
 * <h3>AgentDefinitionNotFoundException</h3>
 *
 * <p>
 * {@link at.aimon.core.agent.definition.exception.AgentDefinitionNotFoundException} is thrown when the requested agent
 * definition cannot be found in the configured source. This is a more specific subtype of
 * {@code AgentDefinitionLoadException}.
 *
 * <p>
 * Common causes:
 * <ul>
 * <li>Definition file does not exist</li>
 * <li>File path is incorrect</li>
 * <li>File exists but is not a regular file (e.g., is a directory)</li>
 * <li>Resource not found on classpath</li>
 * </ul>
 *
 * <p>
 * Example:
 *
 * <pre>
 * {
 *     &#64;code
 *     try {
 *         AgentDefinition definition = loader.load("non-existent-agent");
 *     } catch (AgentDefinitionNotFoundException e) {
 *         log.warn("Agent definition not found: {}", e.getMessage());
 *         // Use default agent or prompt user
 *     }
 * }
 * </pre>
 *
 * <h3>AgentDefinitionParseException</h3>
 *
 * <p>
 * {@link at.aimon.core.agent.definition.exception.AgentDefinitionParseException} is thrown when parsing of an agent
 * definition file fails. This indicates the file was found but has invalid content or format.
 *
 * <p>
 * Common causes:
 * <ul>
 * <li>Missing frontmatter delimiters (---)</li>
 * <li>Invalid YAML syntax in frontmatter</li>
 * <li>Missing required fields (e.g., 'name')</li>
 * <li>Invalid version format</li>
 * <li>Invalid model configuration values</li>
 * </ul>
 *
 * <p>
 * Example:
 *
 * <pre>
 * {
 *     &#64;code
 *     try {
 *         AgentDefinition definition = parser.parse(inputStream);
 *     } catch (AgentDefinitionParseException e) {
 *         log.error("Invalid agent definition format: {}", e.getMessage());
 *         // Notify user of format errors
 *     }
 * }
 * </pre>
 *
 * <h2>Error Handling Best Practices</h2>
 *
 * <h3>Specific Exception Handling</h3>
 *
 * <p>
 * Handle specific exceptions when different recovery strategies are needed:
 *
 * <pre>
 * {
 *     &#64;code
 *     try {
 *         AgentDefinition definition = loader.load(agentName);
 *     } catch (AgentDefinitionNotFoundException e) {
 *         // Use default or fallback agent
 *         definition = getDefaultAgent();
 *     } catch (AgentDefinitionParseException e) {
 *         // Invalid format - log details and fail fast
 *         throw new IllegalStateException("Invalid agent definition: " + agentName, e);
 *     } catch (AgentDefinitionLoadException e) {
 *         // Generic loading error - retry or fail
 *         throw new RuntimeException("Failed to load agent: " + agentName, e);
 *     }
 * }
 * </pre>
 *
 * <h3>Generic Exception Handling</h3>
 *
 * <p>
 * When recovery strategy is the same for all failures:
 *
 * <pre>
 * {
 *     &#64;code
 *     try {
 *         return loader.load(agentName);
 *     } catch (AgentDefinitionLoadException e) {
 *         // Catches both loading and "not found" errors
 *         log.error("Could not load agent definition: {}", agentName, e);
 *         return getDefaultAgent();
 *     }
 * }
 * </pre>
 *
 * <h3>Preserving Context</h3>
 *
 * <p>
 * Always preserve the original exception as the cause:
 *
 * <pre>
 * {
 *     &#64;code
 *     try {
 *         // ... parsing logic ...
 *     } catch (IOException e) {
 *         throw new AgentDefinitionLoadException("Failed to read agent definition file", e);
 *     } catch (YAMLException e) {
 *         throw new AgentDefinitionParseException("Invalid YAML syntax in agent definition", e);
 *     }
 * }
 * </pre>
 *
 * <h2>Exception Messages</h2>
 *
 * <p>
 * Exception messages should be:
 * <ul>
 * <li><strong>Specific:</strong> Include file paths, field names, or other identifying details</li>
 * <li><strong>Actionable:</strong> Suggest what might be wrong or how to fix it</li>
 * <li><strong>Consistent:</strong> Follow a standard format across the package</li>
 * </ul>
 *
 * <p>
 * Examples of good exception messages:
 * <ul>
 * <li>"Definition not found: /agents/coding-agent.md"</li>
 * <li>"Required key 'name' not found in metadata"</li>
 * <li>"Invalid version format: 1.0 (expected format: major.minor.patch)"</li>
 * <li>"Unsupported file extension: .yaml"</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 *
 * <p>
 * All exception classes are immutable and thread-safe. They can be safely thrown and caught in multi-threaded
 * environments.
 *
 * @see at.aimon.core.agent.definition.exception.AgentDefinitionLoadException
 * @see at.aimon.core.agent.definition.exception.AgentDefinitionNotFoundException
 * @see at.aimon.core.agent.definition.exception.AgentDefinitionParseException
 * @see at.aimon.core.agent.exception.AgentException
 */
package at.aimon.core.agent.definition.exception;
