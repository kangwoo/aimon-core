package at.aimon.core.mcp;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.tool.Tool;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.base.AgentScoped;
import at.aimon.core.mcp.McpServerConfig.AnnotationTrust;

/**
 * Manages the lifecycle of multiple McpClient instances.
 *
 * <p>
 * Shares the same lifecycle as {@code AgentRuntime}. When the Context is destroyed, {@link #closeAll()} is
 * called to clean up all MCP server connections.
 *
 * <h2>Server Name Uniqueness</h2>
 * <p>
 * Registering an MCP server with a duplicate name throws {@link IllegalArgumentException}. Server names are part of MCP
 * Tool names ({@code mcp__<server>__<tool>}), so uniqueness prevents Tool name conflicts.
 *
 * <h2>Thread Safety</h2>
 * <p>
 * This class is thread-safe. Internally uses {@link ConcurrentHashMap} for safe concurrent access.
 *
 * <h2>Lifecycle</h2>
 *
 * <pre>
 * {@code
 * // When AgentRuntime is created
 * McpClientManager manager = new McpClientManager(mcpClientFactory);
 *
 * // Connect and initialize multiple servers in parallel (recommended)
 * List<String> failed = manager.createClients(List.of(githubConfig, slackConfig));
 *
 * // Or connect a single server sequentially
 * manager.createClient(githubConfig);
 *
 * // Register tools
 * manager.registerAllTools(toolRegistry);
 *
 * // Agent execution (across multiple sessions)
 * ...
 *
 * // When AgentRuntime is destroyed
 * manager.closeAll();  // Closes all MCP server connections
 * }
 * </pre>
 *
 * @see McpClientFactory
 * @see AgentScoped
 * @see at.aimon.core.agent.impl.orca.OrcaAgentRuntime
 */
public class McpClientManager implements AgentScoped {

    private static final Logger log = LoggerFactory.getLogger(McpClientManager.class);

    /**
     * Slack added on top of the longest configured {@code requestTimeout} when bounding {@link #createClients}.
     *
     * <p>
     * It covers the one part of startup that nothing else bounds: spawning the server process, which happens before
     * the {@code initialize} request the transport does bound. It is not a startup budget an operator tunes — see
     * {@link #startupNetNanos(List)} for why that is a different question.
     */
    private static final Duration STARTUP_SPAWN_ALLOWANCE = Duration.ofSeconds(30);

    /** How long to let workers finish after the pool is shut down before abandoning them. */
    private static final Duration EXECUTOR_SHUTDOWN_GRACE = Duration.ofSeconds(5);

    private final McpClientFactory clientFactory;
    private final Duration startupSpawnAllowance;
    private final ConcurrentHashMap<String, McpClient> clients = new ConcurrentHashMap<>();
    /**
     * Per-server trust, kept because {@link McpClient} carries only the connection and the config is the only place the
     * setting exists. Written next to every {@link #clients} put and read in {@link #registerAllTools(ToolRegistry)}; a
     * server missing from here is trusted no further than {@link AnnotationTrust#IGNORE}.
     */
    private final ConcurrentHashMap<String, AnnotationTrust> annotationTrust = new ConcurrentHashMap<>();
    private volatile boolean closed = false;

    /**
     * Creates a McpClientManager.
     *
     * @param clientFactory
     *            the factory for creating McpClient instances
     */
    public McpClientManager(McpClientFactory clientFactory) {
        this(clientFactory, STARTUP_SPAWN_ALLOWANCE);
    }

    /**
     * Creates a McpClientManager with a non-default spawn allowance.
     *
     * <p>
     * Package-private and test-only. It exists so the bounded wait in {@link #createClients(List)} can be exercised
     * without a 30-second test, and is deliberately not public: letting an operator configure how long startup may
     * take is the budget question this class does not answer — see {@link #startupNetNanos(List)}.
     *
     * @param clientFactory
     *            the factory for creating McpClient instances
     * @param startupSpawnAllowance
     *            replaces {@link #STARTUP_SPAWN_ALLOWANCE}
     */
    McpClientManager(McpClientFactory clientFactory, Duration startupSpawnAllowance) {
        this.clientFactory = Objects.requireNonNull(clientFactory, "clientFactory cannot be null");
        this.startupSpawnAllowance = Objects.requireNonNull(startupSpawnAllowance,
                "startupSpawnAllowance cannot be null");
    }

    /**
     * Connects to an MCP server and creates an initialized McpClient.
     *
     * <p>
     * Uses a synchronized block to prevent check-then-act races between {@code ConcurrentHashMap.containsKey()} and
     * {@code put()}. {@code computeIfAbsent()} is not used because {@code clientFactory.create()} involves network I/O
     * that can take a long time, and long-blocking compute operations inside ConcurrentHashMap can block other bucket
     * operations.
     *
     * @param config
     *            MCP server connection configuration
     * @return the created McpClient
     * @throws IllegalStateException
     *             if close() has already been called
     * @throws IllegalArgumentException
     *             if a server with the same name is already registered
     * @throws at.aimon.core.mcp.exception.McpInitializeException
     *             if connection or initialization fails
     */
    public synchronized McpClient createClient(McpServerConfig config) {
        Objects.requireNonNull(config, "config cannot be null");

        if (closed) {
            throw new IllegalStateException("McpClientManager is already closed");
        }

        String serverName = config.getName();
        if (clients.containsKey(serverName)) {
            throw new IllegalArgumentException("MCP server with name '" + serverName
                    + "' is already registered. Each MCP server must have a unique name.");
        }

        McpClient client = clientFactory.create(config);
        clients.put(serverName, client);
        annotationTrust.put(serverName, config.getAnnotationTrust());
        log.info("Created and initialized MCP client for server '{}'", serverName);
        return client;
    }

    /**
     * Connects to and initializes multiple MCP servers in parallel.
     *
     * <p>
     * Each server's connection/initialization runs in a separate thread for faster startup. For example, if 3 servers
     * each take 2 seconds, sequential processing takes 6 seconds but parallel processing takes about 2 seconds.
     *
     * <h2>Execution Strategy</h2>
     * <p>
     * Uses a {@link Executors#newCachedThreadPool() cached thread pool}. MCP server connections are I/O-bound
     * operations (network communication, process startup); the pool grows on demand for the bounded number of servers
     * passed in and idle threads are released after 60 seconds.
     *
     * <h2>Partial Failure Handling</h2>
     * <p>
     * Individual server connection failures do not abort the entire initialization. Only successful servers are
     * registered, and the list of failed servers is returned for the caller to log or handle.
     *
     * <h2>Bounded Wait</h2>
     * <p>
     * The wait for those threads is bounded — see {@link #startupNetNanos(List)} for the bound and for why it is a net
     * rather than a startup budget. A server still running when it expires is reported as failed and abandoned. No
     * transport in this tree can reach that: {@code StdioMcpTransport} enforces the per-request deadline itself, so
     * every worker already returns on its own. The bound exists so that one which does not cannot turn agent startup
     * into an indefinite hang.
     *
     * <h2>Concurrency Safety</h2>
     * <p>
     * During parallel execution, puts to {@link #clients} (ConcurrentHashMap) are safe because server names are
     * checked for uniqueness before anything is submitted — a name already registered, or repeated within
     * {@code configs}, is reported as failed instead of overwriting the client that holds it. The {@link #closed} flag
     * is checked once at method entry.
     *
     * @param configs
     *            list of MCP server configurations
     * @return list of failed server names (empty list if all succeeded)
     * @throws IllegalStateException
     *             if close() has already been called
     */
    public List<String> createClients(List<McpServerConfig> configs) {
        Objects.requireNonNull(configs, "configs cannot be null");

        if (closed) {
            throw new IllegalStateException("McpClientManager is already closed");
        }

        if (configs.isEmpty()) {
            return List.of();
        }

        List<String> failedServers = new CopyOnWriteArrayList<>();
        List<McpServerConfig> submitted = new ArrayList<>(configs.size());
        Set<String> claimedNames = new LinkedHashSet<>(clients.keySet());

        for (McpServerConfig config : configs) {
            if (claimedNames.add(config.getName())) {
                submitted.add(config);
            } else {
                failedServers.add(config.getName());
                log.error("MCP server name '{}' is already registered; each server must have a unique name",
                        config.getName());
            }
        }
        if (submitted.isEmpty()) {
            return List.copyOf(new LinkedHashSet<>(failedServers));
        }

        // Servers given up on at the deadline, as opposed to ones that failed on their own — only these can still
        // finish behind our back, so only these need reconciling afterwards.
        final List<String> abandoned = new ArrayList<>();

        ExecutorService executor = Executors.newCachedThreadPool();
        try {
            List<Future<?>> futures = new ArrayList<>();

            for (McpServerConfig config : submitted) {
                futures.add(executor.submit(() -> {
                    try {
                        McpClient client = clientFactory.create(config);
                        clients.put(config.getName(), client);
                        annotationTrust.put(config.getName(), config.getAnnotationTrust());
                        log.info("Created and initialized MCP client for server '{}'", config.getName());
                    } catch (Exception e) {
                        failedServers.add(config.getName());
                        log.error("Failed to connect to MCP server '{}': {}", config.getName(), e.getMessage(), e);
                    }
                }));
            }

            // Wait for all server initializations to complete, under one deadline shared by every future.
            final long netNanos = startupNetNanos(submitted);
            final long deadline = System.nanoTime() + netNanos;

            for (int i = 0; i < futures.size(); i++) {
                final Future<?> future = futures.get(i);
                try {
                    future.get(Math.max(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("MCP client initialization interrupted");
                    break;
                } catch (TimeoutException e) {
                    final String serverName = submitted.get(i).getName();
                    future.cancel(true);
                    failedServers.add(serverName);
                    abandoned.add(serverName);
                    log.error("MCP server '{}' did not finish initializing within {} ms; giving up on it", serverName,
                            TimeUnit.NANOSECONDS.toMillis(netNanos));
                } catch (ExecutionException e) {
                    // Individual task exceptions are already handled internally
                    log.error("Unexpected error during MCP client initialization: {}", e.getMessage(), e);
                }
            }
        } finally {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(EXECUTOR_SHUTDOWN_GRACE.toMillis(), TimeUnit.MILLISECONDS)) {
                    log.warn("MCP initialization worker(s) ignored cancellation; abandoning them");
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                executor.shutdownNow();
            }
        }

        discardRacedClients(abandoned);
        return List.copyOf(new LinkedHashSet<>(failedServers));
    }

    /**
     * Computes how long {@link #createClients} may wait for all of its workers.
     *
     * <p>
     * This is a net, not a startup budget. The workers run fully concurrently — a
     * {@link Executors#newCachedThreadPool() cached pool} hands every submitted task its own thread at once, and
     * {@code McpClient.initialize()} makes exactly one request — so the wall-clock of a healthy startup is the
     * <em>longest</em> per-server {@link McpServerConfig#getRequestTimeout() requestTimeout}, not the sum of them.
     * Adding servers does not make it longer.
     *
     * <p>
     * Whether an operator should also be able to cap startup with a configurable ceiling — a real budget, lower than
     * this and enforced as policy — is a separate question that nobody has decided. This value deliberately does not
     * answer it: it is set loose enough that a healthy server can never trip it, and exists only so that a transport
     * which fails to bound itself cannot turn agent startup into an indefinite hang.
     *
     * @param configs
     *            the configurations being initialized (must not be empty)
     * @return the wait in nanoseconds, saturating rather than overflowing
     */
    private long startupNetNanos(List<McpServerConfig> configs) {
        long longestNanos = 0L;
        for (McpServerConfig config : configs) {
            longestNanos = Math.max(longestNanos, toNanosSaturating(config.getRequestTimeout()));
        }
        final long allowanceNanos = toNanosSaturating(startupSpawnAllowance);
        return Math.min(longestNanos, Long.MAX_VALUE - allowanceNanos) + allowanceNanos;
    }

    private static long toNanosSaturating(Duration duration) {
        try {
            return duration.toNanos();
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }

    /**
     * Drops and closes any client that registered itself under a name already reported as failed.
     *
     * <p>
     * A worker we gave up on at the deadline can still finish in the window between the timeout and its cancellation
     * taking effect. The list {@link #createClients(List)} returns is what the caller acts on, so {@link #clients}
     * must agree with it — otherwise a server the caller was told had failed would still have its tools registered.
     * Nothing in the tree reaches this today.
     *
     * <p>
     * Only names abandoned at the deadline belong here. A server that failed on its own, or one rejected for a
     * duplicate name, has no worker still running — and in the duplicate case the entry under that name belongs to
     * the client that claimed it first, which must not be touched.
     *
     * @param abandoned
     *            names given up on at the deadline
     */
    private void discardRacedClients(List<String> abandoned) {
        for (String serverName : abandoned) {
            McpClient raced = clients.remove(serverName);
            if (raced == null) {
                continue;
            }
            log.warn("MCP server '{}' finished initializing after it was given up on; discarding it", serverName);
            try {
                raced.close();
            } catch (Exception e) {
                log.warn("Failed to close discarded MCP client for server '{}': {}", serverName, e.getMessage(), e);
            }
        }
    }

    /**
     * Registers all MCP server tools to the ToolRegistry.
     *
     * <p>
     * This is where a server's {@link McpToolAnnotations} become the tool's own declarations, or fail to: the
     * {@link AnnotationTrust} configured for the server is applied here, once per tool, and nothing downstream can tell
     * the result apart from a local tool's declaration. Under the default {@link AnnotationTrust#IGNORE} every MCP tool
     * declares {@code MUTATING} + {@code DESTRUCTIVE}, exactly as before annotations were read.
     *
     * @param registry
     *            the tool registry to register tools to
     */
    public void registerAllTools(ToolRegistry registry) {
        Objects.requireNonNull(registry, "registry cannot be null");

        for (McpClient client : clients.values()) {
            List<McpToolSchema> schemas = client.listTools();
            String serverName = client.getServerName();
            AnnotationTrust trust = annotationTrust.getOrDefault(serverName, AnnotationTrust.IGNORE);

            for (McpToolSchema schema : schemas) {
                Tool tool = new McpTool(serverName, schema, client,
                        McpToolTraits.resolve(schema.getAnnotations(), trust));
                registry.register(tool);
            }

            log.info("Registered {} tools from MCP server '{}' (annotation trust: {})", schemas.size(), serverName,
                    trust);
        }
    }

    /**
     * Looks up a specific MCP server's client.
     *
     * <p>
     * Always returns empty after the Manager is closed to prevent returning a client that is being or has been closed
     * during {@link #closeAll()}.
     *
     * @param serverName
     *            server name
     * @return McpClient (empty if not found or Manager is closed)
     */
    public Optional<McpClient> getClient(String serverName) {
        if (closed) {
            return Optional.empty();
        }
        return Optional.ofNullable(clients.get(serverName));
    }

    /**
     * Returns the names of all registered MCP servers.
     *
     * @return server name list (immutable)
     */
    public List<String> getServerNames() {
        return List.copyOf(clients.keySet());
    }

    /**
     * Closes all MCP server connections.
     *
     * <p>
     * Individual server close failures are logged and do not prevent closing the remaining servers.
     */
    @Override
    public void close() {
        closeAll();
    }

    /**
     * Closes all MCP server connections.
     *
     * <p>
     * Individual server close failures are logged and do not prevent closing the remaining servers.
     *
     * <h2>Concurrency Notes</h2>
     * <p>
     * While closeAll() is running, other threads may call {@code McpTool.callTool()}. In this case, closed McpClient's
     * {@link McpClient#isConnected()} returns {@code false}, and {@link McpTool#execute} checks connection status first
     * so it safely returns {@code ToolResult.error()}. Therefore, {@link McpClient} implementations must guarantee that
     * {@code isConnected()} returns false immediately after {@code close()}.
     */
    public synchronized void closeAll() {
        if (closed) {
            return;
        }
        closed = true;

        for (Map.Entry<String, McpClient> entry : clients.entrySet()) {
            try {
                entry.getValue().close();
                log.info("Closed MCP client for server '{}'", entry.getKey());
            } catch (Exception e) {
                log.warn("Failed to close MCP client for server '{}': {}", entry.getKey(), e.getMessage(), e);
            }
        }
        clients.clear();
        annotationTrust.clear();
    }

    /**
     * Checks whether the manager is closed.
     *
     * @return true if close() has been called
     */
    public boolean isClosed() {
        return closed;
    }

}
