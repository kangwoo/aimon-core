package at.aimon.bootstrap.spec;

import java.util.Objects;

import at.aimon.bootstrap.exception.AimonBootstrapException;
import at.aimon.core.agent.AgentRuntimeId;

/**
 * Maps an {@link AgentRuntimeId} to the workspace directory that runtime works in.
 *
 * <p>
 * The layout is {@code <workspace-root>/<agentRef>/<discriminator>}, with {@value #NO_DISCRIMINATOR} standing in
 * for a runtime that has none. <b>Both axes are in the path on purpose.</b> An id is
 * {@code agent:<name>[:<discriminator>]}, so keying the directory by agent name alone would put two tenants of
 * the same agent in one directory — and unlike two agents, two tenants are usually two customers.
 *
 * <h2>Segments are validated, not sanitised</h2>
 *
 * <p>
 * An agent name arrives from a bundle directory, a property, or in a multi-agent host from a database row a
 * user can edit. A name of {@code ../../etc} would otherwise resolve to a directory outside the workspace
 * root, and a silent rewrite to {@code __etc} would hand the caller a working file system quietly pointed
 * somewhere they did not name. Both are worse than refusing to start, so an unusable segment throws.
 */
public final class AgentWorkspaceLayout {

    /** Directory name used for a runtime with no discriminator. */
    public static final String NO_DISCRIMINATOR = "_default";

    private AgentWorkspaceLayout() {
    }

    /**
     * Resolves the workspace directory for a runtime.
     *
     * @param workspaceRoot
     *            the root every agent's directory sits under (must not be null or blank)
     * @param agentRuntimeId
     *            the runtime to resolve for (must not be null)
     * @return the directory path, with no trailing separator
     * @throws AimonBootstrapException
     *             if either segment of the id cannot be used as a directory name
     */
    public static String resolve(String workspaceRoot, AgentRuntimeId agentRuntimeId) {
        Objects.requireNonNull(workspaceRoot, "workspaceRoot must not be null");
        Objects.requireNonNull(agentRuntimeId, "agentRuntimeId must not be null");
        if (workspaceRoot.isBlank()) {
            throw new IllegalArgumentException("workspaceRoot must not be blank");
        }
        final String agentSegment = requireUsableSegment(agentRuntimeId.agentName(), "agent name", agentRuntimeId);
        final String tenantSegment = agentRuntimeId.discriminator()
                .map(discriminator -> requireUsableSegment(discriminator, "discriminator", agentRuntimeId))
                .orElse(NO_DISCRIMINATOR);
        if (agentRuntimeId.discriminator().filter(NO_DISCRIMINATOR::equals).isPresent()) {
            // Not a path-traversal attempt, but the same outcome: agent:x:_default and agent:x would share a
            // directory, so one tenant would read the other's files with nothing in any log to say so.
            throw new AimonBootstrapException("The discriminator '" + NO_DISCRIMINATOR + "' is reserved: it names the"
                    + " directory used by a runtime that has no discriminator, so " + agentRuntimeId + " would share a"
                    + " workspace with agent:" + agentRuntimeId.agentName() + ". Choose a different discriminator.");
        }
        final String prefix = workspaceRoot.endsWith("/")
                ? workspaceRoot.substring(0, workspaceRoot.length() - 1)
                : workspaceRoot;
        return prefix + "/" + agentSegment + "/" + tenantSegment;
    }

    private static String requireUsableSegment(String segment, String what, AgentRuntimeId agentRuntimeId) {
        final boolean usable = segment != null && !segment.isBlank() && !".".equals(segment) && !"..".equals(segment)
                && segment.indexOf('/') < 0 && segment.indexOf('\\') < 0 && segment.indexOf('\0') < 0;
        if (!usable) {
            throw new AimonBootstrapException("The " + what + " in " + agentRuntimeId + " cannot be used as a directory"
                    + " name: '" + segment + "'. It must be non-blank, must not be '.' or '..', and must not contain"
                    + " a path separator — otherwise the agent's workspace would resolve outside the configured"
                    + " workspace root.");
        }
        return segment;
    }
}
