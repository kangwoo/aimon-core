package at.aimon.core.command.execution.llm;

import java.util.List;

import at.aimon.core.agent.tool.permission.AllowedTool;
import at.aimon.core.command.Command;
import at.aimon.core.command.CommandContent;
import at.aimon.core.command.execution.direct.DirectExecutable;

/**
 * Marker interface for commands that execute through LLM interaction.
 *
 * <p>
 * LlmExecutable represents the execution strategy for commands that:
 *
 * <ul>
 * <li>Require LLM processing to execute
 * <li>Have content (markdown body with context tokens)
 * <li>May have permission restrictions (allowed-tools)
 * <li>Execute asynchronously through LLM reasoning and tools calls
 * </ul>
 *
 * <p>
 * This interface defines HOW a command executes, not WHAT kind of command it is. Commands implement this interface
 * alongside {@link Command} to indicate they use LLM-based execution rather than direct execution.
 *
 * <p>
 * Typical use cases include:
 *
 * <ul>
 * <li>User-invocable skills exposed as commands via
 * {@link at.aimon.core.command.skill.SkillBackedCommand}
 * <li>Commands requiring tools execution and LLM reasoning
 * <li>Complex workflows that need AI assistance
 * </ul>
 *
 * <p>
 * Contrast with {@link DirectExecutable} implementations that:
 *
 * <ul>
 * <li>Execute immediately using Java code
 * <li>Do not require LLM processing
 * <li>Return results synchronously
 * </ul>
 *
 * <p>
 * Thread-safety: Implementations should be thread-safe or explicitly document their threading requirements.
 *
 * @see Command
 * @see DirectExecutable
 * @see at.aimon.core.command.skill.SkillBackedCommand
 */
public interface LlmExecutable {

    /**
     * Returns the command content.
     *
     * <p>
     * Content includes the markdown body and extracted context tokens. This content is sent to the LLM for processing
     * and execution.
     *
     * @return The content (never null)
     */
    CommandContent getContent();

    /**
     * Returns the list of allowed tools for this command.
     *
     * <p>
     * If non-empty, only the specified tools can be used when executing this command. If empty, all tools are
     * permitted.
     *
     * @return An immutable list of allowed tools (never null, may be empty)
     */
    List<AllowedTool> getAllowedTools();

    /**
     * Returns whether this command has permission restrictions.
     *
     * <p>
     * If true, only the tools in {@link #getAllowedTools()} can be used. If false, all tools are permitted.
     *
     * @return true if allowed-tools is non-empty, false otherwise
     */
    boolean hasPermissionRestrictions();
}
