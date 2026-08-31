package at.aimon.core.status;

/**
 * Provider interface for system status information.
 *
 * <p>
 * Abstracts the collection of system status data so that it can be queried from any context -- not just from within a
 * command. This follows the Dependency Inversion Principle: the {@code StatusCommand} depends on this abstraction
 * rather than on concrete registries.
 *
 * <p>
 * Implementations typically aggregate information from multiple registries (commands, tools, agents, skills) and other
 * runtime components to produce a comprehensive {@link SystemStatus}.
 *
 * <p>
 * Example implementation:
 *
 * <pre>
 * {@code
 * public class DefaultSystemStatusProvider implements SystemStatusProvider {
 *     private final CommandRegistry commandRegistry;
 *     private final ToolRegistry toolRegistry;
 *
 *     public DefaultSystemStatusProvider(CommandRegistry commandRegistry, ToolRegistry toolRegistry) {
 *         this.commandRegistry = commandRegistry;
 *         this.toolRegistry = toolRegistry;
 *     }
 *
 *     &#64;Override
 *     public SystemStatus getStatus() {
 *         return SystemStatus.builder()
 *                 .section(StatusSection.builder("Components")
 *                         .entry("Commands", String.valueOf(commandRegistry.getAllCommands().size()))
 *                         .entry("Tools", String.valueOf(toolRegistry.findAll().size())).build())
 *                 .build();
 *     }
 * }
 * }
 * </pre>
 *
 * @see SystemStatus
 * @see at.aimon.core.command.system.StatusCommand
 */
public interface SystemStatusProvider {

    /**
     * Collects and returns the current system status.
     *
     * @return The current system status (never null)
     */
    SystemStatus getStatus();
}
