package at.aimon.core.command.execution;

import java.util.List;
import java.util.Objects;

import at.aimon.core.agent.session.transcript.TranscriptBuffer;
import at.aimon.core.agent.tool.Tool;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.command.Command;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.ToolDefinition;

/**
 * Encapsulates the runtime configuration for command execution.
 *
 * <p>
 * This class allows injecting tools and configuration at execution time, providing flexibility for dynamic tools
 * selection and LLM model configuration.
 *
 * <p>
 * By storing {@link Tool} objects instead of just {@link ToolDefinition}s, this context ensures that tools definitions
 * and their execution logic stay synchronized, particularly important for tools with dynamic definitions.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     CommandExecutionContext context = CommandExecutionContext.builder().modelConfig(modelConfig).maxIterations(10)
 *             .availableTools(tools).build();
 *
 *     CommandExecutionResult result = executor.execute(command, context);
 * }
 * </pre>
 */
public final class CommandExecutionContext {
    /**
     * Creates a new builder.
     *
     * @return A new CommandExecutionContext.Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    private final Command command;
    private final LlmModel defaultModel;
    private final ToolRegistry toolRegistry;
    private final TranscriptBuffer transcriptBuffer;
    private final ToolContext toolContext;

    private CommandExecutionContext(Command command, LlmModel defaultModel, ToolRegistry toolRegistry,
            TranscriptBuffer transcriptBuffer, ToolContext toolContext) {
        this.command = Objects.requireNonNull(command, "Command cannot be null");
        this.defaultModel = Objects.requireNonNull(defaultModel, "DefaultModel config cannot be null");
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "Tool registry cannot be null");
        this.transcriptBuffer = transcriptBuffer;
        this.toolContext = toolContext == null ? ToolContext.empty() : toolContext;
    }

    public Command getCommand() {
        return command;
    }

    /**
     * Gets the LLM model configuration.
     *
     * @return The model configuration (never null)
     */
    public LlmModel getDefaultModel() {
        return defaultModel;
    }

    public ToolRegistry getToolRegistry() {
        return toolRegistry;
    }

    /**
     * Gets the available tools.
     *
     * @return An immutable list of available tools (never null)
     */
    public List<Tool> getAvailableTools() {
        return toolRegistry.findAll();
    }

    /**
     * Gets the transcript buffer.
     *
     * @return The transcript buffer (can be null)
     */
    public TranscriptBuffer getTranscriptBuffer() {
        return transcriptBuffer;
    }

    /**
     * Gets the {@link ToolContext} forwarded with this command execution.
     *
     * <p>
     * Carries parent agent-runtime info (agent runtime ID, execution attributes, LLM call metadata) downstream
     * to the {@link at.aimon.core.command.execution.skill.SkillBackedCommandExecutor}, so user-slash invocations of
     * fork-mode skills can propagate attribution to the spawned subagent. Defaults to {@link ToolContext#empty()} when
     * no context is supplied.
     *
     * @return The tool context (never null; defaults to {@link ToolContext#empty()})
     */
    public ToolContext getToolContext() {
        return toolContext;
    }

    /** Builder for CommandExecutionContext. */
    public static final class Builder {
        private Command command;
        private LlmModel defaultModel;
        private ToolRegistry toolRegistry;
        private TranscriptBuffer transcriptBuffer;
        private ToolContext toolContext;

        private Builder() {
        }

        /**
         * Sets the command to execute.
         *
         * @param command
         *            The command (must not be null)
         * @return This builder
         */
        public Builder command(Command command) {
            this.command = command;
            return this;
        }

        /**
         * Sets the LLM model configuration.
         *
         * @param defaultModel
         *            The model configuration (must not be null)
         * @return This builder
         */
        public Builder defaultModel(LlmModel defaultModel) {
            this.defaultModel = defaultModel;
            return this;
        }

        /**
         * Sets the tool registry.
         *
         * @param toolRegistry
         *            The tool registry (must not be null)
         * @return This builder
         */
        public Builder toolRegistry(ToolRegistry toolRegistry) {
            this.toolRegistry = toolRegistry;
            return this;
        }

        /**
         * Sets the transcript buffer.
         *
         * @param transcriptBuffer
         *            The transcript buffer (can be null)
         * @return This builder
         */
        public Builder transcriptBuffer(TranscriptBuffer transcriptBuffer) {
            this.transcriptBuffer = transcriptBuffer;
            return this;
        }

        /**
         * Sets the {@link ToolContext} forwarded to the executor (optional).
         *
         * <p>
         * When the command resolves to a {@link at.aimon.core.command.skill.SkillBackedCommand} the
         * {@link at.aimon.core.command.execution.skill.SkillBackedCommandExecutor} forwards this context into the
         * downstream {@link at.aimon.core.skill.execution.SkillExecutor} so fork-mode skills can propagate
         * attribution to the spawned subagent. Defaults to {@link ToolContext#empty()} when not supplied.
         *
         * @param toolContext
         *            The tool context (may be null; treated as {@link ToolContext#empty()})
         * @return This builder
         */
        public Builder toolContext(ToolContext toolContext) {
            this.toolContext = toolContext;
            return this;
        }

        /**
         * Builds the CommandExecutionContext.
         *
         * @return A new CommandExecutionContext
         * @throws NullPointerException
         *             if modelConfig or availableTools is null
         * @throws IllegalArgumentException
         *             if maxIterations is not positive
         */
        public CommandExecutionContext build() {
            return new CommandExecutionContext(command, defaultModel, toolRegistry, transcriptBuffer, toolContext);
        }
    }
}
