/**
 * Template rendering infrastructure for dynamic content generation.
 *
 * <h2>Overview</h2>
 *
 * <p>
 * This package provides template rendering capabilities for generating dynamic content such as system prompts, command
 * content, and skill descriptions. It uses the Mustache template engine for variable interpolation.
 *
 * <h2>Key Concepts</h2>
 *
 * <h3>Template Renderer</h3>
 *
 * <p>
 * {@link at.aimon.core.agent.template.TemplateRenderer} is the core interface for rendering templates:
 *
 * <pre>
 * {
 *     &#64;code
 *     TemplateRenderer renderer = new MustacheTemplateRenderer();
 *
 *     String template = "Hello, {{name}}! You are {{age}} years old.";
 *     Map&lt;String, Object&gt; variables = Map.of("name", "John", "age", 30);
 *
 *     String result = renderer.render(template, variables);
 *     // Result: "Hello, John! You are 30 years old."
 * }
 * </pre>
 *
 * <h3>Mustache Template Renderer</h3>
 *
 * <p>
 * {@link at.aimon.core.agent.template.MustacheTemplateRenderer} provides Mustache template engine integration:
 *
 * <pre>
 * {
 *     &#64;code
 *     TemplateRenderer renderer = new MustacheTemplateRenderer();
 *
 *     // Simple variable interpolation
 *     String template = "Agent: {{agentName}}";
 *     String result = renderer.render(template, Map.of("agentName", "FileAssistant"));
 *
 *     // Conditional sections
 *     template = "{{#hasError}}Error: {{errorMessage}}{{/hasError}}";
 *     result = renderer.render(template, Map.of("hasError", true, "errorMessage", "File not found"));
 *
 *     // Lists
 *     template = "Available tools: {{#tools}}{{name}}, {{/tools}}";
 *     result = renderer.render(template, Map.of("tools", List.of(Map.of("name", "bash"), Map.of("name", "read"))));
 * }
 * </pre>
 *
 * <h2>Mustache Syntax</h2>
 *
 * <h3>Variable Interpolation</h3>
 *
 * <pre>
 * Template: "Hello, {{name}}!"
 * Variables: {"name": "World"}
 * Result: "Hello, World!"
 * </pre>
 *
 * <h3>Sections (Conditionals)</h3>
 *
 * <pre>
 * Template: "{{#isAdmin}}Admin Panel{{/isAdmin}}"
 * Variables: {"isAdmin": true}
 * Result: "Admin Panel"
 * </pre>
 *
 * <h3>Inverted Sections</h3>
 *
 * <pre>
 * Template: "{{^hasError}}Success!{{/hasError}}"
 * Variables: {"hasError": false}
 * Result: "Success!"
 * </pre>
 *
 * <h3>Lists</h3>
 *
 * <pre>
 * Template: "{{#items}}Item: {{name}}\n{{/items}}"
 * Variables: {"items": [{"name": "Apple"}, {"name": "Banana"}]}
 * Result: "Item: Apple\nItem: Banana\n"
 * </pre>
 *
 * <h3>Lambdas (Not Supported)</h3>
 *
 * <p>
 * The current implementation does not support lambda functions in templates. Use simple variables and sections only.
 *
 * <h2>Usage Patterns</h2>
 *
 * <h3>Dynamic System Prompts</h3>
 *
 * <pre>
 * {
 *     &#64;code
 *     String promptTemplate = """
 *             You are {{agentName}}, a helpful assistant.
 *             Current date: {{currentDate}}
 *             {{#hasTools}}Available tools: {{#tools}}{{name}}, {{/tools}}{{/hasTools}}
 *             """;
 *
 *     Map&lt;String, Object&gt; variables = Map.of(
 *             "agentName", "FileAssistant",
 *             "currentDate", "2024-01-30",
 *             "hasTools", true, "tools",
 *             List.of(Map.of("name", "bash"),
 *                     Map.of("name", "read")));
 *
 *     String systemPrompt = renderer.render(promptTemplate, variables);
 * }
 * </pre>
 *
 * <h3>Custom Command Content</h3>
 *
 * <pre>
 * {
 *     &#64;code
 *     String commandTemplate = """
 *             Analyze the {{fileType}} file at {{filePath}}.
 *             {{#includeMetadata}}Include metadata in your analysis.{{/includeMetadata}}
 *             """;
 *
 *     Map&lt;String, Object&gt; variables = Map.of("fileType", "Java", "filePath", "/src/Main.java", "includeMetadata",
 *             true);
 *
 *     String commandContent = renderer.render(commandTemplate, variables);
 * }
 * </pre>
 *
 * <h3>Error Messages</h3>
 *
 * <pre>
 * {
 *     &#64;code
 *     try {
 *         String result = renderer.render(template, variables);
 *     } catch (TemplateRenderException e) {
 *         // Handle template rendering errors
 *         logger.error("Failed to render template: {}", e.getMessage(), e);
 *     }
 * }
 * </pre>
 *
 * <h2>Design Principles</h2>
 *
 * <ul>
 * <li><b>Abstraction:</b> TemplateRenderer interface allows different template engines
 * <li><b>Simplicity:</b> Mustache provides logic-less templates for clarity
 * <li><b>Type Safety:</b> Variables are type-checked at runtime
 * <li><b>Fail-Fast:</b> Invalid templates throw TemplateRenderException immediately
 * </ul>
 *
 * <h2>Best Practices</h2>
 *
 * <ul>
 * <li>Keep templates simple and focused on presentation
 * <li>Avoid complex logic in templates; compute values before rendering
 * <li>Use meaningful variable names that match domain concepts
 * <li>Validate template syntax early in development
 * <li>Cache compiled templates for frequently used templates
 * </ul>
 *
 * <h2>Performance Considerations</h2>
 *
 * <ul>
 * <li>MustacheTemplateRenderer creates a new Mustache instance per render call
 * <li>For high-frequency rendering, consider caching compiled templates
 * <li>Large templates with many variables may have noticeable overhead
 * <li>Keep variable maps reasonably sized (&lt; 100 entries)
 * </ul>
 *
 * <h2>Limitations</h2>
 *
 * <ul>
 * <li>No support for Mustache partials (included templates)
 * <li>No support for lambda functions
 * <li>No support for custom delimiters
 * <li>Limited error messages for template syntax errors
 * </ul>
 *
 * @see at.aimon.core.agent.template.TemplateRenderer
 * @see at.aimon.core.agent.template.MustacheTemplateRenderer
 * @see at.aimon.core.agent.template.TemplateRenderException
 */
package at.aimon.core.agent.template;
