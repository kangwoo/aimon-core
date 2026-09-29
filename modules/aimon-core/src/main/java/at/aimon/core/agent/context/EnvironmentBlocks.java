package at.aimon.core.agent.context;

import java.util.Objects;

import at.aimon.core.environment.EnvironmentDescriptor;

/**
 * Renders the prompt's environment block from an execution's {@link EnvironmentDescriptor} — the one text the main
 * agent's system prompt, a fork's system prompt and {@link EnvironmentContextProvider} all emit.
 *
 * <p>
 * The block describes where the execution's commands run, not the JVM host (execution-environment design §10). On a
 * local environment the text is identical to the block rendered from the host values before. A field the descriptor
 * does not know is left out, and its notes (e.g. why the environment is unavailable) follow as one more line.
 */
public final class EnvironmentBlocks {

    private EnvironmentBlocks() {
        throw new AssertionError("This class should not be instantiated");
    }

    /**
     * Renders the environment block.
     *
     * @param descriptor
     *            the execution environment's descriptor (must not be null)
     * @return the block
     */
    public static String render(EnvironmentDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor must not be null");
        final StringBuilder sb = new StringBuilder(
                "Here is useful information about the environment you are running in:\n\n**Environment:**\n```\n");
        if (!descriptor.workingDirectory().isEmpty()) {
            sb.append("Working directory: ").append(descriptor.workingDirectory()).append('\n');
        }
        descriptor.platform().ifPresent(platform -> sb.append("Platform: ").append(platform).append('\n'));
        descriptor.osVersion().ifPresent(version -> sb.append("OS Version: ").append(version).append('\n'));
        descriptor.notes().ifPresent(notes -> sb.append("Notes: ").append(notes).append('\n'));
        return sb.append("```").toString();
    }
}
