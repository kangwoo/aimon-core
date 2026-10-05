package at.aimon.core.skill.hook.declarative;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import at.aimon.core.agent.InvokerType;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.event.PreToolContext;
import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.hook.execution.HookStatus;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.mcp.McpCallResult;
import at.aimon.core.mcp.McpClient;
import at.aimon.core.mcp.McpClientManager;
import at.aimon.core.mcp.exception.McpTransportException;
import at.aimon.core.skill.hook.action.HookAction;
import at.aimon.core.skill.hook.action.HttpAction;
import at.aimon.core.skill.hook.action.McpToolAction;
import at.aimon.core.skill.hook.declarative.predicate.NameOnlyPredicate;

/**
 * What a {@code preTool} guard backed by an {@code http} or {@code mcp} action does when it gets no verdict (EE-65).
 *
 * <p>
 * The HTTP half talks to a real loopback server through the real {@link HttpActionExecutor}, because the question the
 * backlog item left open is what the executor returns for a refused connection, a non-2xx status, a timeout and a
 * response it cannot read. The rule under test is the shell guards' rule: a policy server that answers "deny" gave a
 * verdict; one that could not be asked, or whose answer cannot be read, gave none, and a guard with no verdict blocks
 * unless it declared {@code failOpen}.
 */
@DisplayName("preTool http / mcp guards: no verdict blocks unless failOpen")
class DeclarativePreToolHookRemoteGuardTest {

    private static final String SECRET = "s3cret-token";

    private HttpServer server;
    private final CountDownLatch release = new CountDownLatch(1);

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        respond("/deny", 200, "application/json", "{\"decision\":\"deny\",\"reason\":\"policy says no\"}");
        respond("/allow", 200, "application/json", "{\"decision\":\"allow\"}");
        respond("/defer", 200, "application/json", "{\"decision\":\"defer\"}");
        respond("/no-decision", 200, "application/json", "{\"feedback\":\"noted\"}");
        respond("/empty", 204, null, "");
        respond("/plain-ok", 200, "text/plain", "ok");
        respond("/unavailable", 503, "application/json", "{\"decision\":\"allow\"}");
        respond("/forbidden", 403, "application/json", "{\"decision\":\"deny\",\"reason\":\"not a verdict\"}");
        respond("/truncated-json", 200, "application/json", "{\"decision\":\"den");
        respond("/unknown-decision", 200, "application/json", "{\"decision\":\"veto\",\"reason\":\"cc style\"}");
        respond("/decision-not-text", 200, "application/json", "{\"decision\":true}");
        respond("/bad-updated-input", 200, "application/json", "{\"decision\":\"allow\",\"updatedInput\":\"x\"}");
        server.createContext("/hang", exchange -> {
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool(r -> {
            final Thread t = new Thread(r, "remote-guard-test-server");
            t.setDaemon(true);
            return t;
        }));
        server.start();
    }

    @AfterEach
    void stopServer() {
        release.countDown();
        server.stop(0);
    }

    // --- http -------------------------------------------------------------------------------------------------

    @Test
    void http_executorNotWired_blocks() {
        HookResult result = httpHook("/allow", null, false).execute(contextFor("Bash"));

        assertBlockedWithoutLeaks(result, "action executor not wired");
    }

    @Test
    void http_executorNotWired_failOpen_passes() {
        assertThat(httpHook("/allow", null, true).execute(contextFor("Bash")).getStatus())
                .isEqualTo(HookStatus.SUCCESS);
    }

    @Test
    void http_connectionRefused_blocks() throws IOException {
        final int closedPort;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = socket.getLocalPort();
        }
        HttpAction action = HttpAction.builder().url(URI.create("http://127.0.0.1:" + closedPort + "/policy"))
                .addHeader("X-Token", SECRET).timeout(Duration.ofSeconds(5)).build();

        HookResult result = hook(action, HttpActionExecutor.createDefault(), null, false).execute(contextFor("Bash"));

        assertBlockedWithoutLeaks(result, "call failed");
        assertThat(result.getFeedback().orElseThrow()).doesNotContain(Integer.toString(closedPort));
    }

    @Test
    void http_timeout_blocks() {
        HttpAction action = HttpAction.builder().url(url("/hang")).addHeader("X-Token", SECRET)
                .timeout(Duration.ofMillis(300)).build();

        HookResult result = hook(action, HttpActionExecutor.createDefault(), null, false).execute(contextFor("Bash"));

        assertBlockedWithoutLeaks(result, "timed out");
    }

    @ParameterizedTest
    @CsvSource({"/unavailable, call failed, HTTP 503", "/forbidden, call failed, HTTP 403",
            "/truncated-json, response could not be read, ''", "/unknown-decision, response could not be read, ''",
            "/decision-not-text, response could not be read, ''", "/bad-updated-input, response could not be read, ''"})
    void http_answerThatIsNotAVerdict_blocks(String path, String cause, String detail) {
        HookResult result = httpHook(path, HttpActionExecutor.createDefault(), false).execute(contextFor("Bash"));

        assertBlockedWithoutLeaks(result, cause);
        assertThat(result.getFeedback().orElseThrow()).contains(detail).doesNotContain("cc style")
                .doesNotContain("not a verdict");
    }

    @ParameterizedTest
    @CsvSource({"/unavailable", "/forbidden", "/truncated-json", "/unknown-decision", "/decision-not-text",
            "/bad-updated-input", "/hang"})
    void http_answerThatIsNotAVerdict_failOpen_passes(String path) {
        HttpAction action = HttpAction.builder().url(url(path)).timeout(Duration.ofMillis(300)).build();

        assertThat(hook(action, HttpActionExecutor.createDefault(), null, true).execute(contextFor("Bash")).getStatus())
                .isEqualTo(HookStatus.SUCCESS);
    }

    @Test
    void http_denyVerdict_blocksWithTheServersReason_evenWithFailOpen() {
        for (boolean failOpen : new boolean[]{false, true}) {
            HookResult result = httpHook("/deny", HttpActionExecutor.createDefault(), failOpen)
                    .execute(contextFor("Bash"));

            assertThat(result.getStatus()).isEqualTo(HookStatus.BLOCKED);
            assertThat(result.getFeedback()).contains("policy says no");
        }
    }

    @ParameterizedTest
    @CsvSource({"/allow", "/defer", "/no-decision", "/empty", "/plain-ok"})
    void http_allowVerdictOrSideEffectAnswer_passes(String path) {
        assertThat(httpHook(path, HttpActionExecutor.createDefault(), false).execute(contextFor("Bash")).getStatus())
                .isEqualTo(HookStatus.SUCCESS);
    }

    @Test
    void http_nonMatchingTool_isNotBlockedByAGuardThatCannotAsk() {
        DeclarativePreToolHook hook = new DeclarativePreToolHook("policy", NameOnlyPredicate.of("Bash"),
                HttpAction.builder().url(url("/allow")).build(), NoOpShellActionExecutor.INSTANCE, null, null, Map.of(),
                DeclarativeHookOptions.none());

        assertThat(hook.execute(contextFor("Read")).getStatus()).isEqualTo(HookStatus.SUCCESS);
        assertThat(hook.execute(contextFor("Bash")).getStatus()).isEqualTo(HookStatus.BLOCKED);
    }

    // --- mcp --------------------------------------------------------------------------------------------------

    @Test
    void mcp_executorNotWired_blocks() {
        HookResult result = hook(mcpAction("policy"), null, null, false).execute(contextFor("Bash"));

        assertBlockedWithoutLeaks(result, "action executor not wired");
    }

    @Test
    void mcp_serverNotRegistered_blocks() {
        McpClientManager manager = mock(McpClientManager.class);
        when(manager.getClient("policy")).thenReturn(Optional.empty());

        HookResult result = hook(mcpAction("policy"), null, mcpExecutor(manager), false).execute(contextFor("Bash"));

        assertBlockedWithoutLeaks(result, "call failed");
        assertThat(result.getFeedback().orElseThrow()).contains("MCP server not registered");
    }

    @Test
    void mcp_serverNotConnected_blocks() {
        McpClient client = mock(McpClient.class);
        when(client.isConnected()).thenReturn(false);

        HookResult result = hook(mcpAction("policy"), null, mcpExecutor(managerFor(client)), false)
                .execute(contextFor("Bash"));

        assertBlockedWithoutLeaks(result, "call failed");
        assertThat(result.getFeedback().orElseThrow()).contains("MCP server not connected");
    }

    @Test
    void mcp_transportFailure_blocksWithTheTypeOnly() {
        McpClient client = connectedClient();
        when(client.callTool(any(), any())).thenThrow(new McpTransportException("pipe to " + SECRET + " broke"));

        HookResult result = hook(mcpAction("policy"), null, mcpExecutor(managerFor(client)), false)
                .execute(contextFor("Bash"));

        assertBlockedWithoutLeaks(result, "call failed");
        assertThat(result.getFeedback().orElseThrow()).contains("McpTransportException");
    }

    @Test
    void mcp_toolError_blocksWithoutTheContent() {
        McpClient client = connectedClient();
        when(client.callTool(any(), any())).thenReturn(McpCallResult.error("internal: " + SECRET));

        HookResult result = hook(mcpAction("policy"), null, mcpExecutor(managerFor(client)), false)
                .execute(contextFor("Bash"));

        assertBlockedWithoutLeaks(result, "call failed");
        assertThat(result.getFeedback().orElseThrow()).contains("tool returned an error");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"{\"decision\":\"veto\"}", "{\"decision\":7}",
            "{\"decision\":\"allow\",\"updatedInput\":[1]}"})
    void mcp_decisionThatCannotBeRead_blocks(String content) {
        McpClient client = connectedClient();
        when(client.callTool(any(), any())).thenReturn(McpCallResult.success(content));

        HookResult result = hook(mcpAction("policy"), null, mcpExecutor(managerFor(client)), false)
                .execute(contextFor("Bash"));

        assertBlockedWithoutLeaks(result, "response could not be read");
    }

    @Test
    void mcp_everyNoVerdictCase_failOpen_passes() {
        McpClient failing = connectedClient();
        when(failing.callTool(any(), any())).thenThrow(new McpTransportException("gone"));
        McpClient erroring = connectedClient();
        when(erroring.callTool(any(), any())).thenReturn(McpCallResult.error("boom"));
        McpClient unreadable = connectedClient();
        when(unreadable.callTool(any(), any())).thenReturn(McpCallResult.success("{\"decision\":\"veto\"}"));
        McpClient disconnected = mock(McpClient.class);

        assertThat(hook(mcpAction("policy"), null, null, true).execute(contextFor("Bash")).getStatus())
                .isEqualTo(HookStatus.SUCCESS);
        for (McpClient client : new McpClient[]{failing, erroring, unreadable, disconnected}) {
            assertThat(hook(mcpAction("policy"), null, mcpExecutor(managerFor(client)), true)
                    .execute(contextFor("Bash")).getStatus()).isEqualTo(HookStatus.SUCCESS);
        }
    }

    @Test
    void mcp_denyVerdict_blocks_evenWithFailOpen() {
        McpClient client = connectedClient();
        when(client.callTool(any(), any()))
                .thenReturn(McpCallResult.success("{\"decision\":\"deny\",\"reason\":\"forbidden\"}"));

        for (boolean failOpen : new boolean[]{false, true}) {
            HookResult result = hook(mcpAction("policy"), null, mcpExecutor(managerFor(client)), failOpen)
                    .execute(contextFor("Bash"));

            assertThat(result.getStatus()).isEqualTo(HookStatus.BLOCKED);
            assertThat(result.getFeedback()).contains("forbidden");
        }
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"''", "posted", "{\"decision\":\"allow\"}", "{\"decision\":\"defer\"}",
            "{\"feedback\":\"noted\"}", "[1,2]"})
    void mcp_allowVerdictOrSideEffectAnswer_passes(String content) {
        McpClient client = connectedClient();
        when(client.callTool(any(), any())).thenReturn(McpCallResult.success(content));

        assertThat(hook(mcpAction("policy"), null, mcpExecutor(managerFor(client)), false).execute(contextFor("Bash"))
                .getStatus()).isEqualTo(HookStatus.SUCCESS);
    }

    // --- helpers ----------------------------------------------------------------------------------------------

    private static void assertBlockedWithoutLeaks(HookResult result, String cause) {
        assertThat(result.getStatus()).isEqualTo(HookStatus.BLOCKED);
        assertThat(result.getFeedback().orElseThrow()).contains("guard hook 'policy' (preTool)").contains(cause)
                .contains("fail-closed")
                // Neither the way to switch the guard off, nor the endpoint, nor a header value is in the reason.
                .doesNotContain("failOpen").doesNotContain(SECRET).doesNotContain("127.0.0.1")
                .doesNotContain("http://");
    }

    private DeclarativePreToolHook httpHook(String path, HttpActionExecutor executor, boolean failOpen) {
        return hook(
                HttpAction.builder().url(url(path)).addHeader("X-Token", SECRET).timeout(Duration.ofSeconds(5)).build(),
                executor, null, failOpen);
    }

    private static DeclarativePreToolHook hook(HookAction action, HttpActionExecutor http, McpActionExecutor mcp,
            boolean failOpen) {
        return new DeclarativePreToolHook("policy", NameOnlyPredicate.ANY, action, NoOpShellActionExecutor.INSTANCE,
                http, mcp, Map.of(), DeclarativeHookOptions.builder().failOpen(failOpen).build());
    }

    private static McpToolAction mcpAction(String serverName) {
        return McpToolAction.builder().serverName(serverName).toolName("evaluate").build();
    }

    private static McpActionExecutor mcpExecutor(McpClientManager manager) {
        return new McpActionExecutor(manager, new ObjectMapper());
    }

    private static McpClient connectedClient() {
        McpClient client = mock(McpClient.class);
        when(client.isConnected()).thenReturn(true);
        return client;
    }

    private static McpClientManager managerFor(McpClient client) {
        McpClientManager manager = mock(McpClientManager.class);
        when(manager.getClient("policy")).thenReturn(Optional.of(client));
        return manager;
    }

    private URI url(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    private void respond(String path, int status, String contentType, String body) {
        server.createContext(path, exchange -> {
            final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            if (contentType != null) {
                exchange.getResponseHeaders().add("Content-Type", contentType);
            }
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
    }

    private static PreToolContext contextFor(String toolName) {
        return PreToolContext.builder().executorType(InvokerType.MAIN_AGENT).invokerName("default-agent")
                .hookRegistry(new DefaultHookRegistry()).toolUse(ToolUse.of("call-1", toolName, Map.of()))
                .iterationCount(3).build();
    }
}
