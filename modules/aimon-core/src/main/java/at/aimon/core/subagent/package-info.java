/**
 * Subagent system for hierarchical agent execution.
 *
 * <p>
 * This package enables building hierarchical agent systems where specialized subagents handle specific subtasks. The
 * main agent can spawn subagents, delegate work, and aggregate results.
 *
 * <h2>Key Concepts</h2>
 *
 * <ul>
 * <li><b>Subagent</b> - A specialized agent with specific capabilities and constraints
 * <li><b>Permission Management</b> - Tool access restrictions for subagents via an allowed-tools allowlist
 * <li><b>Task Delegation</b> - Main agent delegates subtasks to subagents through {@code TaskTool}
 * </ul>
 *
 * <h2>Architecture</h2>
 *
 * <p>
 * Key components:
 *
 * <ul>
 * <li>{@link at.aimon.core.subagent.SubagentRegistry} - Registry for subagent definitions
 * <li>{@link at.aimon.core.subagent.execution.SubagentExecutor} - Executes subagents
 * <li>{@link at.aimon.core.subagent.parser.SubagentParser} - Parses {@code agents/*.md} definitions
 * <li>{@link at.aimon.core.subagent.repository.SubagentRepository} - Storage abstraction
 * </ul>
 *
 * <h2>Subagent Definition</h2>
 *
 * <p>
 * Subagents are defined in Markdown files (agents/*.md):
 *
 * <pre>
 * ---
 * name: code-reviewer
 * description: Reviews code for best practices and issues
 * when-to-use: When you need code review or quality analysis
 * allowed-tools: Read, Grep, Write
 * max-iterations: 50
 * ---
 *
 * You are a code reviewer specializing in:
 * - Code quality and best practices
 * - Security vulnerabilities
 * - Performance issues
 * ...
 * </pre>
 *
 * <p>
 * The same definition can be built in code with {@link at.aimon.core.subagent.Subagent#builder()}, which produces a
 * value object identical to one parsed from the equivalent markdown file.
 *
 * <h2>Example Usage</h2>
 *
 * <pre>
 * {
 *     &#64;code
 *     // Define a subagent in code (equivalent to an agents/*.md file)
 *     Subagent codeReviewer = Subagent.builder().name("code-reviewer")
 *             .description("Reviews code for best practices and issues")
 *             .whenToUse("When you need code review or quality analysis").tools(List.of("Read", "Grep", "Write"))
 *             .model("sonnet").maxIterations(50).systemPrompt("You are a code reviewer...").build();
 *
 *     // Register it so the main agent can discover and delegate to it via TaskTool
 *     SubagentRegistry registry = new InMemorySubagentRegistry();
 *     registry.register(codeReviewer);
 *
 *     // The main agent advertises registered subagents through TaskTool and delegates subtasks to them.
 * }
 * </pre>
 *
 * <h2>Best Practices</h2>
 *
 * <ul>
 * <li>Use subagents for complex, independent subtasks
 * <li>Define clear goals and success criteria in the description / when-to-use
 * <li>Minimize tool permissions to what's necessary
 * <li>Handle subagent failures gracefully
 * <li>Aggregate results from multiple subagents when needed
 * </ul>
 *
 * @see at.aimon.core.subagent.SubagentRegistry
 * @see at.aimon.core.subagent.execution.SubagentExecutor
 * @see at.aimon.core.tools.task.TaskTool
 */
package at.aimon.core.subagent;
