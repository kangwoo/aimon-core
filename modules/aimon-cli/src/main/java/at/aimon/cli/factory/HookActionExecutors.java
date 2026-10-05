package at.aimon.cli.factory;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;

import at.aimon.core.agent.impl.orca.OrcaAgentRuntime;
import at.aimon.core.mcp.McpClientManager;
import at.aimon.core.skill.hook.declarative.HttpActionExecutor;
import at.aimon.core.skill.hook.declarative.McpActionExecutor;

/**
 * The executors behind the CLI's {@code http} and {@code mcp} hook actions, shared by both places a declarative hook
 * comes from: {@code hooks.json} (the hot-reload bootstrap) and skill frontmatter (the skill parser).
 *
 * <p>
 * <b>Why one object, built early.</b> The skill parser is built before the stack, because the agent bundle is loaded
 * with it, and a parsed hook keeps the executors it was parsed with. The MCP executor needs the agent runtime's
 * {@link McpClientManager}, which does not exist until the stack has built the runtime. So the MCP executor is
 * created late-bound here and {@link #bind(OrcaAgentRuntime)} supplies the manager once the runtime is up.
 *
 * <p>
 * <b>Nothing here is closed, because nothing here is owned.</b> The {@link McpClientManager} is agent-scoped: the
 * runtime created it and closes it in {@code TeardownPhase.AGENT_RUNTIMES}. This object only reads it, and a hook that
 * fires after that phase finds its server disconnected and gets no verdict. The HTTP executor's
 * {@link java.net.http.HttpClient} has no {@code close()} on the Java 17 baseline &mdash; its selector thread is a
 * daemon that ends once the client is unreachable &mdash; so there is no entry for it on the teardown plan either.
 *
 * <p>
 * <b>MCP follows the configuration.</b> When the CLI configures no MCP server there is no manager to ask, and the
 * MCP executor is absent rather than present and always failing: an {@code mcp} guard in {@code hooks.json} then
 * stops startup ("cannot run in this assembly") and a skill declaring one fails to load, instead of both loading and
 * blocking every call they match.
 *
 * <p>
 * <b>Both sources get both executors.</b> A skill file is not more trusted than {@code hooks.json}, but in the CLI it
 * is not less able either: its {@code shell} hook action already runs on the user's machine with the CLI's
 * environment and network. What a skill may do at all is decided where it always was, by the skill approval policy.
 */
final class HookActionExecutors {

    private final HttpActionExecutor http;
    private final McpActionExecutor mcp;
    private final AtomicReference<McpClientManager> mcpClientManager = new AtomicReference<>();

    private HookActionExecutors(boolean mcpConfigured) {
        this.http = HttpActionExecutor.createDefault();
        this.mcp = mcpConfigured
                ? McpActionExecutor.lateBound(() -> Optional.ofNullable(mcpClientManager.get()), new ObjectMapper())
                : null;
    }

    /**
     * Creates the executors for one CLI assembly.
     *
     * @param mcpConfigured
     *            whether the CLI configuration declares at least one MCP server, i.e. whether the runtime will have
     *            an {@link McpClientManager}
     * @return the executors (never null)
     */
    static HookActionExecutors create(boolean mcpConfigured) {
        return new HookActionExecutors(mcpConfigured);
    }

    /**
     * @return the executor for {@code http} actions (never null)
     */
    HttpActionExecutor http() {
        return http;
    }

    /**
     * @return the executor for {@code mcp} actions, or {@code null} when the CLI configures no MCP server &mdash; the
     *         form the hook applier and the skill parser both take for "not available here"
     */
    McpActionExecutor mcpOrNull() {
        return mcp;
    }

    /**
     * Points the MCP executor at the runtime's MCP clients. Called once, after the stack has built the runtime and
     * before anything can fire a hook.
     *
     * @param runtime
     *            the CLI's agent runtime (must not be null)
     */
    void bind(OrcaAgentRuntime runtime) {
        runtime.getMcpClientManager().ifPresent(mcpClientManager::set);
    }
}
