package at.aimon.core.command;

import at.aimon.core.agent.AgentExecutionRequest;
import at.aimon.core.agent.session.transcript.TranscriptBuffer;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.command.execution.CommandExecutionResult;
import at.aimon.core.llm.LlmModel;

/**
 * Manages command detection and execution for agent systems.
 *
 * <p>
 * This interface provides operations for:
 *
 * <ul>
 * <li>Detecting whether user input is a command (starts with '/')
 * <li>Executing commands through the appropriate executor
 * <li>Managing command execution context (transcript, tools, model)
 * </ul>
 *
 * <p>
 * Implementations are responsible for:
 *
 * <ul>
 * <li>Parsing command input to extract command name and arguments
 * <li>Looking up commands in the registry
 * <li>Building execution context from the agent runtime
 * <li>Delegating to appropriate CommandExecutor
 * <li>Handling execution errors gracefully
 * </ul>
 *
 * <p>
 * Thread-safety: Implementations should be thread-safe if used in concurrent environments.
 */
public interface CommandExecutionManager {
    /**
     * Checks if the given user input is a command.
     *
     * <p>
     * A command is any input that starts with a '/' character.
     *
     * @param userInput
     *            The user input to check (can be null)
     * @return true if the input is a command, false otherwise (including when input is null)
     */
    boolean isCommand(String userInput);

    /**
     * Checks if the given agent execution request contains a command.
     *
     * <p>
     * A command is any input that starts with a '/' character. This is a convenience method that extracts the text from
     * the request's user input.
     *
     * @param agentExecutionRequest
     *            The agent execution request to check (can be null)
     * @return true if the request contains a command, false otherwise (including when request is null)
     */
    boolean isCommand(AgentExecutionRequest agentExecutionRequest);

    /**
     * Executes a command from an agent execution request.
     *
     * <p>
     * Implementations should:
     *
     * <ol>
     * <li>Parse the command name and arguments
     * <li>Look up the command in the registry
     * <li>Build the execution context
     * <li>Execute the command through the appropriate executor
     * <li>Return the result (success or failure)
     * </ol>
     *
     * @param agentExecutionRequest
     *            The agent execution request containing the command input (must not be null)
     * @param transcriptBuffer
     *            The transcript buffer for maintaining context (must not be null)
     * @param commandRegistry
     *            The command registry to lookup commands (must not be null)
     * @param toolRegistry
     *            The tool registry for tool access during execution (must not be null)
     * @param model
     *            The LLM model to use for LLM-based commands (must not be null)
     * @return The command execution result containing response or error information
     */
    CommandExecutionResult execute(AgentExecutionRequest agentExecutionRequest, TranscriptBuffer transcriptBuffer,
            CommandRegistry commandRegistry, ToolRegistry toolRegistry, LlmModel model);

    /**
     * Executes a command from an agent execution request with an explicit {@link ToolContext}.
     *
     * <p>
     * The {@link ToolContext} is forwarded into the {@link at.aimon.core.command.execution.CommandExecutionContext}
     * so that downstream executors — most notably the {@link
     * at.aimon.core.command.execution.skill.SkillBackedCommandExecutor} routing to fork-mode skills — can read
     * parent agent-runtime attribution (agent runtime ID, execution attributes, LLM call metadata).
     *
     * <p>
     * The default implementation delegates to {@link #execute(AgentExecutionRequest, TranscriptBuffer,
     * CommandRegistry, ToolRegistry, LlmModel)} for backwards compatibility, ignoring the supplied {@link
     * ToolContext}. Implementations that wish to honour fork-mode skill invocations through the user-slash path should
     * override this method to thread the context through.
     *
     * @param agentExecutionRequest
     *            The agent execution request containing the command input (must not be null)
     * @param transcriptBuffer
     *            The transcript buffer for maintaining context (must not be null)
     * @param commandRegistry
     *            The command registry to lookup commands (must not be null)
     * @param toolRegistry
     *            The tool registry for tool access during execution (must not be null)
     * @param model
     *            The LLM model to use for LLM-based commands (must not be null)
     * @param toolContext
     *            The tool context forwarded into the {@link
     *            at.aimon.core.command.execution.CommandExecutionContext} (must not be null; pass
     *            {@link ToolContext#empty()} when no context is available)
     * @return The command execution result containing response or error information
     */
    default CommandExecutionResult execute(AgentExecutionRequest agentExecutionRequest,
            TranscriptBuffer transcriptBuffer, CommandRegistry commandRegistry, ToolRegistry toolRegistry,
            LlmModel model, ToolContext toolContext) {
        return execute(agentExecutionRequest, transcriptBuffer, commandRegistry, toolRegistry, model);
    }
}
