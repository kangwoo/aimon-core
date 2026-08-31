package at.aimon.core.agent.context;

import java.util.List;

import at.aimon.core.agent.Environment;

/**
 * {@link ContextProvider} that emits the working-directory / platform / OS-version block.
 *
 * <p>
 * Produces exactly the text the executor's built-in environment block emits, so it can stand in for that block when a
 * caller assembles all system context through a {@link ContextAssembler} rather than relying on the executor's
 * hard-wired segment. It yields nothing when no {@link Environment} is bound on the request.
 *
 * <p>
 * Because the executor still emits its own environment segment by default, this provider is <b>not</b> wired in by
 * default — wiring both would duplicate the block. It exists for callers that centralise context assembly.
 */
public final class EnvironmentContextProvider implements ContextProvider {

    /** Stable block key. */
    public static final String BLOCK_KEY = "environment";

    @Override
    public List<ContextBlock> provide(ContextAssemblyRequest request) {
        final Environment environment = request.getEnvironment().orElse(null);
        if (environment == null) {
            return List.of();
        }
        final String body = "Here is useful information about the environment you are running in:\n\n"
                + "**Environment:**\n" + "```\n" + "Working directory: " + environment.getWorkingDirectory() + '\n'
                + "Platform: " + environment.getPlatform() + '\n' + "OS Version: " + environment.getOsVersion() + '\n'
                + "```";
        return List.of(ContextBlock.system(BLOCK_KEY, body));
    }
}
