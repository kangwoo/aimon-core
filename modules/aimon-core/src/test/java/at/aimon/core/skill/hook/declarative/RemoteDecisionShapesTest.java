package at.aimon.core.skill.hook.declarative;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import at.aimon.core.agent.InvokerType;
import at.aimon.core.hook.DefaultHookExecutionManager;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.HookEventType;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.hook.event.PreToolContext;
import at.aimon.core.hook.execution.AskPromptHandler;
import at.aimon.core.hook.execution.Decision;
import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.hook.execution.HookStatus;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.mcp.McpCallResult;
import at.aimon.core.mcp.McpClient;
import at.aimon.core.mcp.McpClientManager;
import at.aimon.core.skill.hook.action.HookAction;
import at.aimon.core.skill.hook.action.HttpAction;
import at.aimon.core.skill.hook.action.McpToolAction;
import at.aimon.core.skill.hook.declarative.predicate.NameOnlyPredicate;

/**
 * Which decision documents an {@code http} / {@code mcp} policy endpoint may answer with, and what each is read as.
 *
 * <p>
 * The endpoint may be one written for Claude Code: it spells a refusal
 * {@code hookSpecificOutput.permissionDecision: "deny"}, {@code decision: "block"} or {@code continue: false}. Reading
 * only the native {@code decision} turned every one of those into an allow. Each document in the table goes through
 * both real executors — a loopback HTTP server, and an MCP client answering the same text.
 */
@DisplayName("http / mcp decision documents: native and Claude Code spellings")
class RemoteDecisionShapesTest {

    private enum Kind {
        ALLOW, ASK, DENY, NO_VERDICT
    }

    private static final String CC_DENY = "{\"hookSpecificOutput\":{\"permissionDecision\":\"deny\","
            + "\"permissionDecisionReason\":\"prod is frozen\",\"additionalContext\":\"ctx\"}}";
    private static final String CC_ASK = "{\"hookSpecificOutput\":{\"permissionDecision\":\"ask\","
            + "\"permissionDecisionReason\":\"confirm prod\"}}";

    private HttpServer server;
    private final AtomicReference<String> answer = new AtomicReference<>("");

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/policy", exchange -> {
            final byte[] bytes = answer.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    static Stream<Arguments> documents() {
        return Stream.of(
                // --- the native spelling, unchanged
                Arguments.of("{\"decision\":\"deny\",\"reason\":\"policy says no\"}", Kind.DENY, "policy says no"),
                Arguments.of("{\"decision\":\"allow\"}", Kind.ALLOW, ""),
                Arguments.of("{\"decision\":\"defer\"}", Kind.ALLOW, ""),
                Arguments.of("{\"feedback\":\"noted\"}", Kind.ALLOW, ""),
                // --- hookSpecificOutput.permissionDecision
                Arguments.of(CC_DENY, Kind.DENY, "prod is frozen"),
                Arguments.of("{\"hookSpecificOutput\":{\"permissionDecision\":\"DENY\"}}", Kind.DENY, "Denied by"),
                Arguments.of("{\"hookSpecificOutput\":{\"permissionDecision\":\"allow\"}}", Kind.ALLOW, ""),
                Arguments.of(CC_ASK, Kind.ASK, "confirm prod"),
                Arguments.of("{\"hookSpecificOutput\":{\"permissionDecision\":\"ask\"}}", Kind.ASK, "confirmation"),
                Arguments.of("{\"hookSpecificOutput\":{\"permissionDecision\":\"defer\"}}", Kind.NO_VERDICT, ""),
                Arguments.of("{\"hookSpecificOutput\":{\"permissionDecision\":\"maybe\"}}", Kind.NO_VERDICT, ""),
                Arguments.of("{\"hookSpecificOutput\":{\"permissionDecision\":7}}", Kind.NO_VERDICT, ""),
                Arguments.of("{\"hookSpecificOutput\":{\"additionalContext\":\"ctx\"}}", Kind.ALLOW, ""),
                // --- top-level decision: block
                Arguments.of("{\"decision\":\"block\",\"reason\":\"cc style\"}", Kind.DENY, "cc style"),
                Arguments.of("{\"decision\":\"block\"}", Kind.DENY, "Denied by"),
                Arguments.of("{\"decision\":\"approve\"}", Kind.NO_VERDICT, ""),
                // --- continue: false
                Arguments.of("{\"continue\":false,\"stopReason\":\"halt\"}", Kind.DENY, "halt"),
                Arguments.of("{\"continue\":false}", Kind.DENY, "Denied by"),
                Arguments.of("{\"continue\":true}", Kind.ALLOW, ""),
                Arguments.of("{\"continue\":\"no\"}", Kind.NO_VERDICT, ""),
                // --- both spellings, disagreeing: the stricter wins
                Arguments.of("{\"decision\":\"allow\",\"hookSpecificOutput\":{\"permissionDecision\":\"deny\","
                        + "\"permissionDecisionReason\":\"cc no\"}}", Kind.DENY, "cc no"),
                Arguments.of("{\"decision\":\"deny\",\"reason\":\"native no\","
                        + "\"hookSpecificOutput\":{\"permissionDecision\":\"allow\"}}", Kind.DENY, "native no"),
                Arguments.of("{\"decision\":\"allow\",\"continue\":false,\"stopReason\":\"halt\"}", Kind.DENY, "halt"),
                Arguments.of("{\"decision\":\"allow\",\"hookSpecificOutput\":{\"permissionDecision\":\"ask\"}}",
                        Kind.ASK, "confirmation"),
                Arguments.of("{\"decision\":\"allow\",\"hookSpecificOutput\":{\"permissionDecision\":\"bogus\"}}",
                        Kind.NO_VERDICT, ""),
                Arguments.of("{\"decision\":\"bogus\",\"hookSpecificOutput\":{\"permissionDecision\":\"deny\","
                        + "\"permissionDecisionReason\":\"cc no\"}}", Kind.DENY, "cc no"),
                Arguments.of("{\"decision\":\"bogus\",\"hookSpecificOutput\":{\"permissionDecision\":\"ask\"}}",
                        Kind.NO_VERDICT, ""));
    }

    @ParameterizedTest(name = "http: {0} -> {1}")
    @MethodSource("documents")
    void http_readsTheDocument(String document, Kind expected, String text) {
        assertOutcome(overHttp(document), expected, text);
    }

    @ParameterizedTest(name = "mcp: {0} -> {1}")
    @MethodSource("documents")
    void mcp_readsTheDocument(String document, Kind expected, String text) {
        assertOutcome(overMcp(document), expected, text);
    }

    @Test
    void aDenyReasonComesFromTheReasonFieldOnly() {
        final String document = "{\"hookSpecificOutput\":{\"permissionDecision\":\"deny\","
                + "\"additionalContext\":\"ctx-text\"},\"systemMessage\":\"sys-text\",\"feedback\":\"fb-text\"}";

        for (ActionCallOutcome outcome : new ActionCallOutcome[]{overHttp(document), overMcp(document)}) {
            final HookResult verdict = outcome.getVerdict().orElseThrow();
            assertThat(verdict.getStatus()).isEqualTo(HookStatus.BLOCKED);
            assertThat(verdict.getFeedback().orElseThrow()).startsWith("Denied by").doesNotContain("ctx-text")
                    .doesNotContain("sys-text").doesNotContain("fb-text");
        }
    }

    @Test
    void aDenyTakesTheReasonOfTheSpellingThatDenied() {
        final String document = "{\"decision\":\"allow\",\"reason\":\"looks fine\",\"continue\":false,"
                + "\"stopReason\":\"halt\"}";

        assertThat(overHttp(document).getVerdict().orElseThrow().getFeedback()).contains("halt");
        assertThat(overMcp(document).getVerdict().orElseThrow().getFeedback()).contains("halt");
    }

    // --- through the hook and the manager: what the guard does with each verdict ---------------------------------

    @Test
    void preTool_claudeCodeDeny_blocksWithTheServersReason_evenWithFailOpen() throws Exception {
        for (boolean failOpen : new boolean[]{false, true}) {
            for (HookResult result : new HookResult[]{fire(httpHook(CC_DENY, failOpen), AskPromptHandler.allowAll()),
                    fire(mcpHook(CC_DENY, failOpen), AskPromptHandler.allowAll())}) {
                assertThat(result.getStatus()).isEqualTo(HookStatus.BLOCKED);
                assertThat(result.getFeedback()).contains("prod is frozen");
            }
        }
    }

    @Test
    void preTool_claudeCodeAsk_isAnsweredByTheAskPromptHandler() throws Exception {
        for (boolean failOpen : new boolean[]{false, true}) {
            assertThat(fire(httpHook(CC_ASK, failOpen), AskPromptHandler.denyAll()).getStatus())
                    .isEqualTo(HookStatus.BLOCKED);
            assertThat(fire(mcpHook(CC_ASK, failOpen), AskPromptHandler.denyAll()).getStatus())
                    .isEqualTo(HookStatus.BLOCKED);
            assertThat(fire(httpHook(CC_ASK, failOpen), AskPromptHandler.allowAll()).getStatus())
                    .isEqualTo(HookStatus.SUCCESS);
            assertThat(fire(mcpHook(CC_ASK, failOpen), AskPromptHandler.allowAll()).getStatus())
                    .isEqualTo(HookStatus.SUCCESS);
        }
    }

    @Test
    void preTool_claudeCodeAsk_promptsWithTheServersReason() throws Exception {
        final AtomicReference<String> prompt = new AtomicReference<>();

        fire(httpHook(CC_ASK, false), asked -> {
            prompt.set(asked);
            return Decision.DENY;
        });

        assertThat(prompt.get()).isEqualTo("confirm prod");
    }

    // --- helpers -------------------------------------------------------------------------------------------------

    private static void assertOutcome(ActionCallOutcome outcome, Kind expected, String text) {
        if (expected == Kind.NO_VERDICT) {
            assertThat(outcome.getVerdict()).as(outcome.toString()).isEmpty();
            assertThat(outcome.getUnrun().orElseThrow().getUnrunCause())
                    .contains(ShellHookOutcome.Unrun.INVALID_RESPONSE);
            return;
        }
        final HookResult verdict = outcome.getVerdict().orElseThrow();
        switch (expected) {
            case DENY -> {
                assertThat(verdict.getStatus()).isEqualTo(HookStatus.BLOCKED);
                assertThat(verdict.getFeedback().orElseThrow()).contains(text);
            }
            case ASK -> {
                assertThat(verdict.getDecision()).isEqualTo(Decision.ASK);
                assertThat(verdict.isBlocked()).isFalse();
                assertThat(verdict.getFeedback().orElseThrow()).contains(text);
            }
            default -> assertThat(verdict.getStatus()).isEqualTo(HookStatus.SUCCESS);
        }
    }

    private ActionCallOutcome overHttp(String document) {
        answer.set(document);
        return HttpActionExecutor.createDefault().attempt(httpAction(), null, Map.of(), Map.of());
    }

    private static ActionCallOutcome overMcp(String document) {
        return mcpExecutor(document).attempt(mcpAction(), null, Map.of());
    }

    private HttpAction httpAction() {
        return HttpAction.builder().url(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/policy"))
                .timeout(Duration.ofSeconds(5)).build();
    }

    private static McpToolAction mcpAction() {
        return McpToolAction.builder().serverName("policy").toolName("evaluate").build();
    }

    private static McpActionExecutor mcpExecutor(String document) {
        final McpClient client = mock(McpClient.class);
        when(client.isConnected()).thenReturn(true);
        when(client.callTool(any(), any())).thenReturn(McpCallResult.success(document));
        final McpClientManager manager = mock(McpClientManager.class);
        when(manager.getClient("policy")).thenReturn(Optional.of(client));
        return new McpActionExecutor(manager, new ObjectMapper());
    }

    private DeclarativePreToolHook httpHook(String document, boolean failOpen) {
        answer.set(document);
        return hook(httpAction(), HttpActionExecutor.createDefault(), null, failOpen);
    }

    private static DeclarativePreToolHook mcpHook(String document, boolean failOpen) {
        return hook(mcpAction(), null, mcpExecutor(document), failOpen);
    }

    private static DeclarativePreToolHook hook(HookAction action, HttpActionExecutor http, McpActionExecutor mcp,
            boolean failOpen) {
        return new DeclarativePreToolHook("policy", NameOnlyPredicate.ANY, action, NoOpShellActionExecutor.INSTANCE,
                http, mcp, Map.of(), DeclarativeHookOptions.builder().failOpen(failOpen).build());
    }

    private static HookResult fire(DeclarativePreToolHook hook, AskPromptHandler handler) throws Exception {
        final HookRegistry registry = new DefaultHookRegistry();
        registry.register(HookEventType.PRE_TOOL, hook);
        try (DefaultHookExecutionManager manager = DefaultHookExecutionManager.builder().askPromptHandler(handler)
                .build()) {
            return HookResult.merge(manager.executePreTool(PreToolContext.builder().executorType(InvokerType.MAIN_AGENT)
                    .invokerName("default-agent").hookRegistry(registry).toolUse(ToolUse.of("call-1", "Bash", Map.of()))
                    .iterationCount(3).build()));
        }
    }
}
