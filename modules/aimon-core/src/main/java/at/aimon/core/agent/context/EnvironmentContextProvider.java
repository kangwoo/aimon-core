package at.aimon.core.agent.context;

import java.util.List;

import at.aimon.core.environment.EnvironmentDescriptor;
import at.aimon.core.environment.ExecutionEnvironment;

/**
 * {@link ContextProvider} that emits the working-directory / platform / OS-version block.
 *
 * <p>
 * Produces exactly the text the executor's built-in environment block emits, so it can stand in for that block when a
 * caller assembles all system context through a {@link ContextAssembler} rather than relying on the executor's
 * hard-wired segment. The block describes the execution's {@link ExecutionEnvironment} — its descriptor, not the JVM
 * host (execution-environment design §10). It yields nothing when no environment is bound on the request.
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
        final EnvironmentDescriptor descriptor = request.getExecutionEnvironment().map(ExecutionEnvironment::descriptor)
                .orElse(null);
        if (descriptor == null) {
            return List.of();
        }
        final String body = EnvironmentBlocks.render(descriptor);
        return List.of(ContextBlock.system(BLOCK_KEY, body));
    }
}
