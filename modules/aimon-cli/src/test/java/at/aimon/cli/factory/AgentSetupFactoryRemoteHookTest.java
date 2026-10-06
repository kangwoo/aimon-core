package at.aimon.cli.factory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;

import at.aimon.cli.config.AgentConfig;
import at.aimon.cli.config.CliConfig;
import at.aimon.cli.config.CliSettings;
import at.aimon.cli.config.LlmProviderConfig;
import at.aimon.cli.config.McpConfig;
import at.aimon.cli.config.McpServerEntry;
import at.aimon.cli.exception.ConfigurationException;
import at.aimon.core.agent.InvokerType;
import at.aimon.core.hook.event.PreToolContext;
import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.hook.execution.HookStatus;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.skill.Skill;
import at.aimon.core.skill.parser.SkillParser;

/**
 * The CLI runs {@code http} and {@code mcp} hook handlers.
 *
 * <p>
 * Before this the CLI wired the shell executor only: an {@code http} / {@code mcp} handler in {@code hooks.json} was
 * registered and never called, since EE-72 one under {@code preTool} stopped the CLI from starting, and a skill
 * declaring one failed to load. These tests go through the real {@link AgentSetupFactory#create(CliConfig)} and fire
 * {@code preTool} through the runtime's own hook registry and execution manager, against a loopback
 * {@link HttpServer} and a stdio MCP server that is a shell script.
 *
 * <p>
 * {@code user.home} points at a temporary directory for the duration, so the file under test is the USER layer and
 * no developer's own {@code ~/.aimon/hooks.json} is involved. The real Anthropic client is built with a placeholder
 * key and sends nothing.
 */
@DisplayName("AgentSetupFactory.create: http and mcp hook handlers run in the CLI")
class AgentSetupFactoryRemoteHookTest {

    private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    private final PrintStream originalOut = System.out;
    private final String originalUserHome = System.getProperty("user.home");
    private final List<LlmClient> clients = new ArrayList<>();
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private final List<String> tokens = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> answer = new AtomicReference<>("{\"decision\":\"allow\"}");
    private HttpServer policy;

    @TempDir
    Path home;

    @BeforeEach
    void setUp() throws IOException {
        System.setOut(new PrintStream(stdout));
        System.setProperty("user.home", home.toAbsolutePath().toString());
        policy = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        policy.createContext("/decide", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            tokens.add(String.valueOf(exchange.getRequestHeaders().getFirst("X-Hook-Path")));
            final byte[] bytes = answer.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        policy.start();
    }

    @AfterEach
    void restore() throws Exception {
        policy.stop(0);
        System.setProperty("user.home", originalUserHome);
        System.setOut(originalOut);
        for (LlmClient client : clients) {
            if (client instanceof AutoCloseable closeable) {
                closeable.close();
            }
        }
    }

    private String policyUrl() {
        return "http://127.0.0.1:" + policy.getAddress().getPort() + "/decide";
    }

    /** A loopback port nothing listens on. */
    private static String unreachableUrl() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return "http://127.0.0.1:" + socket.getLocalPort() + "/decide";
        }
    }

    private void writeHooksJson(String json) throws IOException {
        Files.writeString(Files.createDirectories(home.resolve(".aimon")).resolve("hooks.json"), json);
    }

    private AgentSetupFactory factory() {
        return new AgentSetupFactory(new LlmClientFactory() {
            @Override
            public LlmClient create(LlmProviderConfig config) {
                final LlmClient client = super.create(config);
                clients.add(client);
                return client;
            }
        }, null);
    }

    private static CliConfig config(McpConfig mcp) {
        final LlmProviderConfig llm = new LlmProviderConfig();
        llm.setProvider("anthropic");
        llm.setApiKey("sk-ant-placeholder");
        final AgentConfig agent = new AgentConfig();
        agent.setName("default-anthropic");
        final CliSettings settings = new CliSettings();
        settings.setColorOutput(false);
        final CliConfig config = new CliConfig();
        config.setLlmConfig(llm);
        config.setAgentConfig(agent);
        config.setCliSettings(settings);
        config.setMcpConfig(mcp);
        return config;
    }

    /** Fires {@code preTool} the way a tool call does: the runtime's registry, the executor's hook manager. */
    private static HookResult preTool(AgentSetupFactory.AgentSetup setup, String tool, Map<String, Object> input) {
        final PreToolContext context = PreToolContext.builder().executorType(InvokerType.MAIN_AGENT)
                .invokerName("default-anthropic").hookRegistry(setup.getAgentRuntime().getHookRegistry())
                .toolUse(ToolUse.of("call-1", tool, input)).iterationCount(1).build();
        return HookResult.merge(setup.getAgentExecutor().getHookExecutionManager().executePreTool(context));
    }

    // --- http in hooks.json --------------------------------------------------------------------------------------

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    @DisplayName("an http preTool guard in hooks.json: the CLI starts, the endpoint is asked, allow passes and deny blocks")
    void httpGuardInHooksJson_isAsked_allowPasses_denyBlocks() throws Exception {
        writeHooksJson("""
                {"hooks":{"PreToolUse":[{"matcher":"Bash","hooks":[{
                  "type":"http","url":"%s","timeout":5,
                  "headers":{"X-Hook-Path":"${env.PATH}"},"allowedEnvVars":["PATH"],
                  "body":"{\\"tool\\":\\"${context.tool_name}\\",\\"command\\":\\"${tool_input.command}\\"}"
                }]}]}}""".formatted(policyUrl()));

        try (AgentSetupFactory.AgentSetup setup = factory().create(config(null))) {
            final HookResult allowed = preTool(setup, "Bash", Map.of("command", "ls"));

            assertThat(allowed.getStatus()).isEqualTo(HookStatus.SUCCESS);
            assertThat(bodies).containsExactly("{\"tool\":\"Bash\",\"command\":\"ls\"}");
            // ${env.X} is rendered from the CLI's own environment, for the names the handler whitelists.
            assertThat(tokens).containsExactly(System.getenv("PATH"));

            answer.set("{\"decision\":\"deny\",\"reason\":\"policy says no\"}");
            final HookResult denied = preTool(setup, "Bash", Map.of("command", "ls"));

            assertThat(denied.getStatus()).isEqualTo(HookStatus.BLOCKED);
            assertThat(denied.getFeedback().orElseThrow()).contains("policy says no");
            // The matcher still decides who is asked at all.
            assertThat(preTool(setup, "Read", Map.of("file_path", "a.txt")).getStatus()).isEqualTo(HookStatus.SUCCESS);
            assertThat(bodies).hasSize(2);
        }
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    @DisplayName("an http preTool guard whose endpoint is unreachable blocks; the same handler with failOpen passes")
    void unreachableHttpGuard_blocks_andFailOpenPasses() throws Exception {
        final String nowhere = unreachableUrl();
        writeHooksJson("""
                {"hooks":{"PreToolUse":[
                  {"matcher":"Write","hooks":[{"type":"http","url":"%s","timeout":5}]},
                  {"matcher":"Edit","hooks":[{"type":"http","url":"%s","timeout":5,"failOpen":true}]}
                ]}}""".formatted(nowhere, nowhere));

        try (AgentSetupFactory.AgentSetup setup = factory().create(config(null))) {
            final HookResult blocked = preTool(setup, "Write", Map.of("file_path", "a.txt"));

            assertThat(blocked.getStatus()).isEqualTo(HookStatus.BLOCKED);
            assertThat(blocked.getFeedback().orElseThrow()).contains("could not get a verdict from its http call")
                    .contains("call failed").doesNotContain(nowhere);
            assertThat(preTool(setup, "Edit", Map.of("file_path", "a.txt")).getStatus()).isEqualTo(HookStatus.SUCCESS);
        }
    }

    // --- mcp in hooks.json ---------------------------------------------------------------------------------------

    /** A stdio MCP server in a shell script: answers initialize and tools/list, and every tool call with a deny. */
    private static McpConfig scriptedPolicyServer(Path dir) throws IOException {
        final Path script = dir.resolve("policy-mcp.sh");
        Files.writeString(script,
                """
                        while IFS= read -r line; do
                          id=$(printf '%s' "$line" | sed -n 's/.*"id":\\([0-9][0-9]*\\).*/\\1/p')
                          [ -z "$id" ] && continue
                          case "$line" in
                            *'"method":"initialize"'*)
                              printf '{"jsonrpc":"2.0","id":%s,"result":{"protocolVersion":"2024-11-05","capabilities":{},"serverInfo":{"name":"policy","version":"1"}}}\\n' "$id";;
                            *'"method":"tools/list"'*)
                              printf '{"jsonrpc":"2.0","id":%s,"result":{"tools":[]}}\\n' "$id";;
                            *)
                              printf '{"jsonrpc":"2.0","id":%s,"result":{"content":[{"type":"text","text":"{\\\\"decision\\\\":\\\\"deny\\\\",\\\\"reason\\\\":\\\\"mcp says no\\\\"}"}]}}\\n' "$id";;
                          esac
                        done
                        """);
        final McpServerEntry entry = new McpServerEntry();
        entry.setName("policy");
        entry.setTransportType("STDIO");
        entry.setCommand("/bin/sh");
        entry.setArgs(List.of(script.toString()));
        entry.setRequestTimeout(10);
        final McpConfig mcp = new McpConfig();
        mcp.setServers(List.of(entry));
        return mcp;
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    @DisplayName("an mcp preTool guard in hooks.json asks the agent's MCP server; a server nobody configured blocks")
    void mcpGuardInHooksJson_isAsked(@TempDir Path work) throws Exception {
        writeHooksJson("""
                {"hooks":{"PreToolUse":[
                  {"matcher":"Write","hooks":[{"type":"mcp","server":"policy","tool":"evaluate_write",
                     "args":{"path":"${tool_input.file_path}"},"timeout":5}]},
                  {"matcher":"Edit","hooks":[{"type":"mcp","server":"not-configured","tool":"evaluate","timeout":5}]}
                ]}}""");

        try (AgentSetupFactory.AgentSetup setup = factory().create(config(scriptedPolicyServer(work)))) {
            final HookResult denied = preTool(setup, "Write", Map.of("file_path", "a.txt"));

            assertThat(denied.getStatus()).isEqualTo(HookStatus.BLOCKED);
            assertThat(denied.getFeedback().orElseThrow()).contains("mcp says no");

            final HookResult unknown = preTool(setup, "Edit", Map.of("file_path", "a.txt"));

            assertThat(unknown.getStatus()).isEqualTo(HookStatus.BLOCKED);
            assertThat(unknown.getFeedback().orElseThrow()).contains("could not get a verdict from its mcp call");
            // The executor borrows the runtime's MCP clients; closing the CLI must still find them open to close.
            assertThat(setup.getAgentRuntime().getMcpClientManager().orElseThrow().isClosed()).isFalse();
        }
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    @DisplayName("an mcp preTool guard stops the CLI when the CLI configures no MCP server at all")
    void mcpGuardWithoutAnyMcpServer_stopsStartup() throws Exception {
        writeHooksJson("""
                {"hooks":{"preTool":[{"hooks":[{"type":"mcp","server":"policy","tool":"evaluate"}]}]}}""");

        assertThatThrownBy(() -> factory().create(config(null))).isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("preTool entry #0, handler #0").hasMessageContaining("type=mcp cannot run");
    }

    // --- skill frontmatter ---------------------------------------------------------------------------------------

    private static String skill(String hooks) {
        return "---\nname: sample\ndescription: sample skill\nhooks:\n" + hooks + "---\n\n# sample\n";
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("a skill's http preTool hook loads with the CLI's skill parser and asks its endpoint")
    void skillDeclaredHttpHook_loadsAndIsAsked() {
        final SkillParser parser = AgentSetupFactory.createSkillParser(HookActionExecutors.create(false));
        answer.set("{\"decision\":\"deny\",\"reason\":\"skill policy says no\"}");

        final Skill parsed = parser.parse("sample", skill("  preTool:\n    - matcher: Bash\n      action: { type: http,"
                + " url: \"" + policyUrl() + "\", timeoutMs: 5000 }\n"));
        final HookResult result = parsed.getMetadata().getHooks().getPreToolHooks().get(0)
                .execute(PreToolContext.builder().executorType(InvokerType.SUBAGENT).invokerName("sample")
                        .hookRegistry(new at.aimon.core.hook.DefaultHookRegistry())
                        .toolUse(ToolUse.of("call-1", "Bash", Map.of("command", "ls"))).iterationCount(1).build());

        assertThat(result.getStatus()).isEqualTo(HookStatus.BLOCKED);
        assertThat(result.getFeedback().orElseThrow()).contains("skill policy says no");
    }

    @Test
    @DisplayName("a skill's mcp hook loads when the CLI configures MCP servers, and is refused at load when it does not")
    void skillDeclaredMcpHook_followsWhetherMcpIsConfigured() {
        final String hooks = "  preTool:\n    - matcher: Bash\n      action: { type: mcp, server: policy,"
                + " tool: evaluate }\n";

        final Skill parsed = AgentSetupFactory.createSkillParser(HookActionExecutors.create(true)).parse("sample",
                skill(hooks));

        assertThat(parsed.getMetadata().getHooks().getPreToolHooks()).hasSize(1);
        assertThatThrownBy(() -> AgentSetupFactory.createSkillParser(HookActionExecutors.create(false)).parse("sample",
                skill(hooks))).hasMessageContaining("'mcp' hooks are not supported in this configuration");
    }
}
