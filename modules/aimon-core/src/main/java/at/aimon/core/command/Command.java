package at.aimon.core.command;

import at.aimon.core.command.execution.direct.DirectExecutable;
import at.aimon.core.command.execution.llm.LlmExecutable;

/**
 * Contract for all command types in the system.
 *
 * <p>
 * A command represents a user-invocable workflow with:
 *
 * <ul>
 * <li>A unique name (e.g., "commit", "deploy", "help")
 * <li>Metadata (description, allowed tools)
 * <li>A type (SYSTEM or CUSTOM)
 * </ul>
 *
 * <p>
 * Commands can be either:
 *
 * <ul>
 * <li>{@link CommandType#SYSTEM} - Built-in commands implemented as Java classes
 * <li>{@link CommandType#CUSTOM} - User-invocable skills exposed via
 * {@link at.aimon.core.command.skill.SkillBackedCommand}
 * </ul>
 *
 * <p>
 * Commands can also be categorized by execution strategy:
 *
 * <ul>
 * <li>{@link DirectExecutable} - Execute immediately using Java code
 * <li>{@link LlmExecutable} - Execute through LLM processing with content and permissions
 * </ul>
 *
 * <p>
 * Thread-safe implementations are recommended.
 *
 * <p>
 * Command names must:
 *
 * <ul>
 * <li>Contain only lowercase letters, numbers, and hyphens
 * <li>Not be empty or whitespace-only
 * </ul>
 *
 * @see SystemCommand
 * @see at.aimon.core.command.skill.SkillBackedCommand
 * @see DirectExecutable
 * @see LlmExecutable
 */
public interface Command {

    /**
     * Returns the command name.
     *
     * <p>
     * Command names are unique within the system and must match [a-z0-9-]+.
     *
     * @return The command name (never null or empty)
     */
    String getName();

    /**
     * Returns the command metadata.
     *
     * <p>
     * Metadata includes the command description and allowed tools specifications.
     *
     * @return The metadata (never null)
     */
    CommandMetadata getMetadata();

    /**
     * Returns the command type.
     *
     * <p>
     * Commands can be either SYSTEM (built-in) or CUSTOM (skill-backed). System commands take precedence over
     * skill-backed commands in case of name conflicts.
     *
     * @return The command type (never null)
     */
    CommandType getType();

}
