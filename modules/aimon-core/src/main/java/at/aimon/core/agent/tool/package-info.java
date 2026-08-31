/**
 * Tool system for extending agent capabilities with executable functions.
 *
 * <h2>Overview</h2>
 *
 * <p>
 * This package provides a unified tool abstraction for building agent capabilities. Tools are functions that agents can
 * invoke to interact with the external world, such as executing commands, reading files, or calling APIs.
 *
 * <h2>Key Concepts</h2>
 *
 * <h3>Tool Interface</h3>
 *
 * <p>
 * The {@link at.aimon.core.agent.tool.Tool} interface represents a unified approach to tool design, combining both the
 * tool's definition (schema) and its execution logic:
 *
 * <pre>
 * {
 *     &#64;code
 *     public class BashTool extends AbstractTool {
 *         public BashTool() {
 *             super("bash", "Execute a bash command",
 *                     Map.of("type", "object", "properties", Map.of("command", Map.of("type", "string"))));
 *         }
 *
 *         &#64;Override
 *         public ToolResult execute(ToolInput input, ToolContext context) {
 *             String command = input.getRequiredString("command");
 *             // Execute command...
 *             return ToolResult.success(output);
 *         }
 *     }
 * }
 * </pre>
 *
 * <h3>Tool Registry</h3>
 *
 * <p>
 * {@link at.aimon.core.agent.tool.ToolRegistry} provides centralized management of tool instances:
 *
 * <pre>
 * {
 *     &#64;code
 *     ToolRegistry registry = new DefaultToolRegistry();
 *     registry.register(new BashTool(shell));
 *     registry.register(new ReadTool(fileSystem));
 *     registry.register(new WriteTool(fileSystem));
 *
 *     // Query tools
 *     Optional<Tool> bashTool = registry.getTool("bash");
 *     List<Tool> allTools = registry.getTools();
 * }
 * </pre>
 *
 * <h3>Tool Execution</h3>
 *
 * <p>
 * {@link at.aimon.core.agent.tool.ToolExecutionManager} orchestrates tool execution with permission validation:
 *
 * <pre>
 * {
 *     &#64;code
 *     ToolExecutionManager manager = new DefaultToolExecutionManager();
 *     ToolUse toolUse = ToolUse.of("tool_123", "bash", Map.of("command", "ls -la"));
 *     ToolContext context = ToolContext.builder().put("environment", environment).build();
 *
 *     // Execute with permission restrictions
 *     List<AllowedTool> allowedTools = List.of(AllowedTool.of("bash", Map.of("command", "ls*")));
 *     ToolExecutionResult result = manager.execute(toolUse, context, registry, allowedTools);
 * }
 * </pre>
 *
 * <h3>Tool Input and Result</h3>
 *
 * <p>
 * {@link at.aimon.core.agent.tool.ToolInput} provides type-safe parameter access:
 *
 * <pre>
 * {
 *     &#64;code
 *     // Required parameters
 *     String filePath = input.getRequiredString("file_path");
 *
 *     // Optional parameters with defaults
 *     int timeout = input.getInteger("timeout", 2000);
 *
 *     // Nullable parameters
 *     String description = input.getStringOrNull("description");
 * }
 * </pre>
 *
 * <p>
 * {@link at.aimon.core.agent.tool.ToolResult} encapsulates execution outcomes:
 *
 * <pre>
 * {
 *     &#64;code
 *     // Success
 *     return ToolResult.success("File created successfully");
 *
 *     // Error with message
 *     return ToolResult.error("Permission denied: Cannot write to /etc");
 *
 *     // Error with exception
 *     try {
 *         fileSystem.write(path, content);
 *     } catch (FileNotFoundException e) {
 *         return ToolResult.error("File not found: " + path, e);
 *     }
 * }
 * </pre>
 *
 * <h3>Tool Context</h3>
 *
 * <p>
 * {@link at.aimon.core.agent.tool.ToolContext} provides contextual information for tool execution:
 *
 * <pre>
 * {
 *     &#64;code
 *     ToolContext context = ToolContext.builder().put("fileSystem", virtualFileSystem).put("environment", environment)
 *             .put("executorType", InvokerType.MAIN_AGENT).build();
 *
 *     // Type-safe retrieval
 *     Optional<VirtualFileSystem> fs = context.get("fileSystem", VirtualFileSystem.class);
 *     Optional<Environment> env = context.get("environment", Environment.class);
 * }
 * </pre>
 *
 * <h2>Design Principles</h2>
 *
 * <ul>
 * <li><b>Single Responsibility:</b> Each tool has one well-defined capability
 * <li><b>Fail-Safe:</b> Tools never throw exceptions; all errors are captured in ToolResult
 * <li><b>Type Safety:</b> ToolInput provides type-safe parameter access with clear validation
 * <li><b>Immutability:</b> ToolInput and ToolResult are immutable value objects
 * <li><b>Statelessness:</b> Tools should be stateless to avoid concurrency issues
 * </ul>
 *
 * <h2>Built-in Tools</h2>
 *
 * <p>
 * The framework provides several built-in tools in {@code at.aimon.core.tools}:
 *
 * <ul>
 * <li><b>BashTool</b> - Execute bash commands with optional background execution
 * <li><b>BashOutputTool</b> - Monitor and retrieve output from background bash processes
 * <li><b>ReadTool</b> - Read file contents from VirtualFileSystem
 * <li><b>WriteTool</b> - Write files to VirtualFileSystem
 * <li><b>EditTool</b> - Edit existing files with find-and-replace operations
 * <li><b>GrepTool</b> - Search file contents using VirtualFileSystem
 * <li><b>TodoWriteTool</b> - Manage agent task lists for tracking progress
 * <li><b>TaskTool</b> - Launch and manage subagent execution
 * <li><b>AgentOutputTool</b> - Retrieve output from background subagent tasks
 * <li><b>SkillTool</b> - Activate and execute specialized skills
 * </ul>
 *
 * <h2>Subpackages</h2>
 *
 * <ul>
 * <li>{@link at.aimon.core.agent.tool.execution} - Tool execution interfaces and implementations
 * <li>{@link at.aimon.core.agent.tool.permission} - Permission validation for tool usage
 * <li>{@link at.aimon.core.agent.tool.exception} - Tool-specific exceptions
 * </ul>
 *
 * <h2>Custom Tool Example</h2>
 *
 * <pre>
 * {
 *     &#64;code
 *     public class HttpGetTool extends AbstractTool {
 *         private final HttpClient httpClient;
 *
 *         public HttpGetTool(HttpClient httpClient) {
 *             super("http_get", "Fetch content from a URL",
 *                     Map.of("type", "object", "properties",
 *                             Map.of("url", Map.of("type", "string", "description", "The URL to fetch"), "timeout",
 *                                     Map.of("type", "integer", "description", "Timeout in milliseconds")),
 *                             "required", List.of("url")));
 *             this.httpClient = httpClient;
 *         }
 *
 *         &#64;Override
 *         public ToolResult execute(ToolInput input, ToolContext context) {
 *             try {
 *                 String url = input.getRequiredString("url");
 *                 int timeout = input.getInteger("timeout", 5000);
 *
 *                 String content = httpClient.get(url, timeout);
 *                 return ToolResult.success(content);
 *             } catch (IllegalArgumentException e) {
 *                 return ToolResult.error("Invalid parameter: " + e.getMessage());
 *             } catch (Exception e) {
 *                 return ToolResult.error("HTTP request failed: " + e.getMessage(), e);
 *             }
 *         }
 *     }
 * }
 * </pre>
 *
 * @see at.aimon.core.agent.tool.Tool
 * @see at.aimon.core.agent.tool.ToolRegistry
 * @see at.aimon.core.agent.tool.ToolExecutionManager
 * @see at.aimon.core.agent.tool.ToolInput
 * @see at.aimon.core.agent.tool.ToolResult
 * @see at.aimon.core.agent.tool.ToolContext
 */
package at.aimon.core.agent.tool;
