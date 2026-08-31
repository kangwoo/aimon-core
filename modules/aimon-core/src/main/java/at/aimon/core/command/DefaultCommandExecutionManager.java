package at.aimon.core.command;

import java.util.Objects;

import at.aimon.core.agent.AgentExecutionRequest;
import at.aimon.core.agent.CommandInputParser;
import at.aimon.core.agent.CommandInputParser.ParsedCommand;
import at.aimon.core.agent.session.transcript.TranscriptBuffer;
import at.aimon.core.agent.tool.DefaultToolExecutionManager;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolExecutionManager;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.agent.tool.schema.SchemaValidationMode;
import at.aimon.core.command.exception.CommandException;
import at.aimon.core.command.execution.CommandExecutionContext;
import at.aimon.core.command.execution.CommandExecutionRequest;
import at.aimon.core.command.execution.CommandExecutionResult;
import at.aimon.core.command.execution.CommandExecutor;
import at.aimon.core.command.execution.CompositeCommandExecutor;
import at.aimon.core.command.execution.direct.DirectCommandExecutor;
import at.aimon.core.command.execution.skill.SkillBackedCommandExecutor;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.skill.execution.SkillExecutor;
import at.aimon.core.skill.execution.llm.LlmSkillExecutor;
import at.aimon.core.skill.render.DefaultSkillContentRenderer;

/**
 * Default implementation of {@link CommandExecutionManager}.
 *
 * <p>
 * Manages the complete command execution lifecycle:
 *
 * <ul>
 * <li>Detecting command input (starts with '/')
 * <li>Parsing command name and arguments using {@link CommandInputParser}
 * <li>Looking up commands in the {@link CommandRegistry}
 * <li>Building {@link CommandExecutionContext} from agent context
 * <li>Delegating execution to {@link CommandExecutor}
 * <li>Handling errors and returning {@link CommandExecutionResult}
 * </ul>
 *
 * <p>
 * Command format: {@code /command-name [arguments...]}
 *
 * <p>
 * <b>Constructor options:</b>
 *
 * <ul>
 * <li>{@link #DefaultCommandExecutionManager(CommandExecutor)} - supply a custom {@link CommandExecutor}
 * <li>{@link #DefaultCommandExecutionManager(LlmClient)} - auto-wires a {@link CompositeCommandExecutor} with
 * {@link DirectCommandExecutor} and a {@link SkillBackedCommandExecutor} backed by {@link LlmSkillExecutor}
 * <li>{@link #DefaultCommandExecutionManager(LlmClient, ToolExecutionManager)} - same, but the skill executor runs
 * tools through the caller's manager instead of a fresh default one
 * <li>{@link #DefaultCommandExecutionManager(LlmClient, SkillExecutor)} - same as above with a caller-supplied
 * {@link SkillExecutor}
 * </ul>
 *
 * <p>
 * Thread-safe if the underlying {@link CommandExecutor} is thread-safe.
 */
public final class DefaultCommandExecutionManager implements CommandExecutionManager {
    private final CommandExecutor commandExecutor;

    /**
     * Creates a new manager with a caller-supplied {@link CommandExecutor}.
     *
     * @param commandExecutor
     *            The command executor (must not be null)
     * @throws NullPointerException
     *             if commandExecutor is null
     */
    public DefaultCommandExecutionManager(CommandExecutor commandExecutor) {
        this.commandExecutor = Objects.requireNonNull(commandExecutor, "Command executor cannot be null");
    }

    /**
     * Creates a new manager with automatic executor setup.
     *
     * <p>
     * Wires a {@link CompositeCommandExecutor} that combines:
     *
     * <ul>
     * <li>{@link DirectCommandExecutor} for system commands
     * <li>{@link SkillBackedCommandExecutor} backed by {@link LlmSkillExecutor} with a
     * {@link DefaultSkillContentRenderer} for user-invocable skills, so {@code $ARGUMENTS} / {@code !`cmd`} /
     * {@code @file} placeholders are rendered through {@link at.aimon.core.skill.render.SkillContentRenderer}
     * </ul>
     *
     * <p>
     * The skill executor is given a fresh {@link DefaultToolExecutionManager}, which imposes no side-effect ceiling.
     * A host that configured one on its agent must use {@link #DefaultCommandExecutionManager(LlmClient,
     * ToolExecutionManager)} instead, or the ceiling will not reach skills invoked as slash commands.
     *
     * @param llmClient
     *            The LLM client used by the skill executor (must not be null)
     * @throws NullPointerException
     *             if llmClient is null
     */
    public DefaultCommandExecutionManager(LlmClient llmClient) {
        this(llmClient, new DefaultToolExecutionManager());
    }

    /**
     * Creates a new manager whose skill executor dispatches tools through {@code toolExecutionManager}.
     *
     * <p>
     * Wires the same {@link CompositeCommandExecutor} as {@link #DefaultCommandExecutionManager(LlmClient)}, but hands
     * the {@link LlmSkillExecutor} a manager the caller already owns rather than minting an unconfigured one. That
     * matters for anything the manager enforces rather than the skill: a side-effect ceiling above all. A skill
     * invoked as {@code /my-skill} runs against the agent's real {@link ToolRegistry}, so a skill executor holding a
     * default manager could run tools the agent's own ceiling refuses.
     *
     * @param llmClient
     *            The LLM client used by the skill executor (must not be null)
     * @param toolExecutionManager
     *            The manager user-invoked skills dispatch tools through (must not be null)
     * @throws NullPointerException
     *             if any argument is null
     */
    public DefaultCommandExecutionManager(LlmClient llmClient, ToolExecutionManager toolExecutionManager) {
        this(llmClient,
                new LlmSkillExecutor(Objects.requireNonNull(llmClient, "LLM client cannot be null"),
                        new DefaultSkillContentRenderer(),
                        Objects.requireNonNull(toolExecutionManager, "Tool execution manager cannot be null")));
    }

    /**
     * Creates a manager whose skill executor holds tool calls to their declared schemas in the given mode.
     *
     * <p>
     * Exists so that a host choosing {@link SchemaValidationMode#ENFORCE} gets it on this path too. A skill's tool
     * calls go through their own {@link DefaultToolExecutionManager}, built here rather than shared with the agent's,
     * and without this the command path would have stayed on the default while the agent enforced.
     *
     * @param llmClient
     *            The LLM client used by the skill executor (must not be null)
     * @param validationMode
     *            how to react to a call that does not match the tool's schema (must not be null)
     * @throws NullPointerException
     *             if any argument is null
     */
    public DefaultCommandExecutionManager(LlmClient llmClient, SchemaValidationMode validationMode) {
        this(llmClient,
                new LlmSkillExecutor(Objects.requireNonNull(llmClient, "LLM client cannot be null"),
                        new DefaultSkillContentRenderer(), new DefaultToolExecutionManager(
                                Objects.requireNonNull(validationMode, "Validation mode cannot be null"))));
    }

    /**
     * Creates a new manager with a caller-supplied {@link SkillExecutor}.
     *
     * @param llmClient
     *            The LLM client (must not be null; reserved for future wiring symmetry)
     * @param skillExecutor
     *            The skill executor that handles {@link at.aimon.core.command.skill.SkillBackedCommand} dispatch
     *            (must not be null)
     * @throws NullPointerException
     *             if any argument is null
     */
    public DefaultCommandExecutionManager(LlmClient llmClient, SkillExecutor skillExecutor) {
        Objects.requireNonNull(llmClient, "LLM client cannot be null");
        Objects.requireNonNull(skillExecutor, "Skill executor cannot be null");
        final DirectCommandExecutor directCommandExecutor = new DirectCommandExecutor();
        final SkillBackedCommandExecutor skillBackedCommandExecutor = new SkillBackedCommandExecutor(skillExecutor);
        this.commandExecutor = new CompositeCommandExecutor(directCommandExecutor, skillBackedCommandExecutor);
    }

    @Override
    public boolean isCommand(String userInput) {
        return userInput != null && userInput.trim().startsWith("/");
    }

    @Override
    public boolean isCommand(AgentExecutionRequest agentExecutionRequest) {
        if (agentExecutionRequest == null) {
            return false;
        }
        try {
            final String userInput = agentExecutionRequest.getUserInput().asText();
            return isCommand(userInput);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public CommandExecutionResult execute(AgentExecutionRequest agentExecutionRequest,
            TranscriptBuffer transcriptBuffer, CommandRegistry commandRegistry, ToolRegistry toolRegistry,
            LlmModel model) {
        return execute(agentExecutionRequest, transcriptBuffer, commandRegistry, toolRegistry, model,
                ToolContext.empty());
    }

    @Override
    public CommandExecutionResult execute(AgentExecutionRequest agentExecutionRequest,
            TranscriptBuffer transcriptBuffer, CommandRegistry commandRegistry, ToolRegistry toolRegistry,
            LlmModel model, ToolContext toolContext) {
        Objects.requireNonNull(commandRegistry, "Command registry cannot be null");
        Objects.requireNonNull(toolContext, "Tool context cannot be null");

        final String inputText = agentExecutionRequest.getUserInput().asText();
        final String trimmed = inputText.trim();
        if (!trimmed.startsWith("/")) {
            throw new IllegalArgumentException("Command must start with '/'");
        }

        try {
            final ParsedCommand parsed = CommandInputParser.parse(trimmed);
            final Command command = commandRegistry.getCommandOrThrow(parsed.name());

            final CommandExecutionContext context = CommandExecutionContext.builder().command(command)
                    .defaultModel(model).toolRegistry(toolRegistry).transcriptBuffer(transcriptBuffer)
                    .toolContext(toolContext).build();

            final CommandExecutionRequest request = CommandExecutionRequest.builder()
                    .rawArguments(parsed.rawArguments()).arguments(parsed.arguments())
                    .principal(agentExecutionRequest.getPrincipal().orElse(null))
                    .previousSnapshot(transcriptBuffer.toSnapshot()).build();

            return commandExecutor.execute(context, request);
        } catch (CommandException e) {
            return CommandExecutionResult.failure(e);
        } catch (Exception e) {
            return CommandExecutionResult.failure("Command execution error: " + e.getMessage(), e);
        }
    }

    /**
     * @return The command executor (never null)
     */
    public CommandExecutor getCommandExecutor() {
        return commandExecutor;
    }
}
