package at.aimon.core.mcp;

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.interrupt.CancellationSignal;
import at.aimon.core.agent.interrupt.InterruptBehavior;
import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.DestructiveBehavior;
import at.aimon.core.agent.tool.InterruptAccess;
import at.aimon.core.agent.tool.SideEffectLevel;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;

/**
 * Adapter that wraps an MCP server's tool as an AIMON Tool.
 *
 * <p>
 * From the Agent's perspective, this is used identically to local Tools. Tool names follow the
 * {@code mcp__<serverName>__<toolName>} format.
 *
 * <h2>Thread Safety</h2>
 * <p>
 * This class is thread-safe. It does not modify internal state and relies on the thread-safety of the shared
 * {@link McpClient}.
 *
 * <h2>Cancellation</h2>
 * <p>
 * A call waits for its server for as long as the server's {@code requestTimeout} allows, which is the wrong length of
 * time for an execution that has been cancelled. While the call is in flight the tool listens to the execution's
 * {@link CancellationSignal} and, when it trips, interrupts the thread making the call; the transport answers an
 * interrupt by giving the request up. The request itself is not taken back: over stdio it was already written, so the
 * server may still run it.
 *
 * <p>
 * Usage example:
 *
 * <pre>
 * {@code
 * McpClient client = new DefaultMcpClient(transport, "github");
 * McpToolSchema schema = McpToolSchema.of("create_issue", "Create a GitHub issue", inputSchema);
 * Tool tool = new McpTool("github", schema, client);
 * // tool.getDefinition().getName() -> "mcp__github__create_issue"
 * }
 * </pre>
 */
public class McpTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(McpTool.class);

    private final McpClient mcpClient;
    private final String mcpToolName;
    private final McpToolTraits traits;

    /**
     * Creates an McpTool that declares the conservative end of both side-effect axes, whatever the server claimed.
     *
     * @param serverName
     *            MCP server identifying name
     * @param schema
     *            tool definition provided by the MCP server
     * @param mcpClient
     *            MCP client (must be thread-safe)
     */
    public McpTool(String serverName, McpToolSchema schema, McpClient mcpClient) {
        this(serverName, schema, mcpClient, McpToolTraits.untrusted());
    }

    /**
     * Creates an McpTool with pre-resolved declarations.
     *
     * <p>
     * The traits are resolved by the caller rather than derived from {@code schema} here, because the deciding input is
     * not the schema but the trust configured for the server it came from — which this class has no reference to. See
     * {@link McpToolTraits#resolve(McpToolAnnotations, McpServerConfig.AnnotationTrust)}.
     *
     * @param serverName
     *            MCP server identifying name
     * @param schema
     *            tool definition provided by the MCP server
     * @param mcpClient
     *            MCP client (must be thread-safe)
     * @param traits
     *            the declarations this tool will make (must not be null)
     */
    public McpTool(String serverName, McpToolSchema schema, McpClient mcpClient, McpToolTraits traits) {
        super(formatToolName(serverName, schema.getName()), schema.getDescription(), schema.getInputSchema());
        this.mcpClient = Objects.requireNonNull(mcpClient, "mcpClient cannot be null");
        this.mcpToolName = schema.getName();
        this.traits = Objects.requireNonNull(traits, "traits cannot be null");
    }

    private static String formatToolName(String serverName, String toolName) {
        return "mcp__" + serverName + "__" + toolName;
    }

    @Override
    public SideEffectLevel getSideEffectLevel() {
        return traits.getSideEffectLevel();
    }

    @Override
    public DestructiveBehavior getDestructiveBehavior() {
        return traits.getDestructiveBehavior();
    }

    /**
     * Declares {@link InterruptBehavior#COOPERATIVE}: the tool watches the {@link CancellationSignal} itself and ends
     * its call when it trips.
     *
     * <p>
     * Not {@link InterruptBehavior#THREAD_INTERRUPT}, although an interrupt is what ends the call. That declaration
     * has the executor register a terminator bound to the executing thread, which the parallel dispatcher refuses on
     * its shared workers. An MCP tool is not dispatched in parallel today &mdash; it keeps the default
     * {@code ConcurrencyBehavior.SEQUENTIAL} &mdash; so the two declarations would behave alike; this one is chosen
     * so that the interrupt stays the tool's own business and is no obstacle if a tool is ever declared safe to run
     * concurrently. It is sent only while the call is in flight and taken back before {@code execute} returns (see
     * {@link CallInterrupt}), so the thread never carries it into what it runs next.
     */
    @Override
    public InterruptBehavior getInterruptBehavior() {
        return InterruptBehavior.COOPERATIVE;
    }

    @Override
    public ToolResult execute(ToolInput input, ToolContext context) {
        Objects.requireNonNull(input, "Input cannot be null");
        Objects.requireNonNull(context, "Context cannot be null");

        try {
            final CancellationSignal signal = InterruptAccess.signalOf(context);
            if (signal.isCancelled()) {
                return interruptedResult(signal);
            }
            if (!mcpClient.isConnected()) {
                return ToolResult.error("MCP server '" + mcpClient.getServerName() + "' is not connected");
            }

            final CallInterrupt callInterrupt = new CallInterrupt();
            // A signal that tripped since the check above runs the listener here and now, and the call below then ends
            // at its first wait.
            final CancellationSignal.Registration registration = signal.onCancel(callInterrupt::fire);
            McpCallResult result = null;
            Exception failure = null;
            boolean cancelled = false;
            try {
                result = mcpClient.callTool(mcpToolName, input.toMap());
            } catch (Exception e) {
                failure = e;
            } finally {
                // In a finally so that a call leaving through an Error ends its listener too: the signal outlives the
                // call, and a listener left on it would interrupt this thread in the middle of other work.
                registration.remove();
                cancelled = callInterrupt.disarm();
            }

            if (failure != null) {
                if (cancelled) {
                    return interruptedResult(signal);
                }
                throw failure;
            }
            // A call that came back anyway is reported as what it was: the server ran it.

            if (result.isError()) {
                log.warn("MCP tool '{}' returned error: {}", mcpToolName, result.getContent());
                return ToolResult.error(result.getContent());
            }

            log.debug("MCP tool '{}' executed successfully", mcpToolName);
            return ToolResult.success(result.getContent());

        } catch (Exception e) {
            log.error("MCP tool '{}' execution failed: {}", mcpToolName, e.getMessage(), e);
            return ToolResult.error("MCP tool execution failed: " + e.getMessage());
        }
    }

    private ToolResult interruptedResult(CancellationSignal signal) {
        final String reason = signal.getReason().map(InterruptReason::name).orElse("UNKNOWN");
        log.debug("MCP tool '{}' interrupted: {}", mcpToolName, reason);
        return ToolResult.error("MCP tool interrupted: " + reason);
    }

    /**
     * The interrupt this tool sends its own thread to end a call when the execution is cancelled.
     *
     * <p>
     * {@link #fire()} and {@link #disarm()} are mutually exclusive, so once {@code disarm} has returned no interrupt
     * from this call can arrive any more, and one that already did is taken back there: the flag is cleared, and set
     * again when the thread was already interrupted as the call began. So the thread's flag still only ever means an
     * interrupt of that thread from outside, whoever owns the thread. Clearing can swallow such an outside interrupt
     * when it lands during the call in the same
     * instant the signal trips; the execution is cancelled either way.
     *
     * <p>
     * One instance serves one call on one thread.
     */
    private static final class CallInterrupt {

        private final Thread caller = Thread.currentThread();
        private final boolean interruptedBefore = caller.isInterrupted();
        private boolean armed = true;
        private boolean fired;

        synchronized void fire() {
            if (armed) {
                fired = true;
                caller.interrupt();
            }
        }

        /**
         * Ends the call's exposure to the signal.
         *
         * @return true when the signal tripped while the call was in flight (the interrupt has been taken back)
         */
        synchronized boolean disarm() {
            armed = false;
            if (fired) {
                Thread.interrupted();
                if (interruptedBefore) {
                    caller.interrupt();
                }
            }
            return fired;
        }
    }

}
