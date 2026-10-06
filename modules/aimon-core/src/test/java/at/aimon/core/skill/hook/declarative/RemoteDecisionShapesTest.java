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
import java.util.List;
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
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import at.aimon.core.agent.InvokerType;
import at.aimon.core.hook.DefaultHookExecutionManager;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.HookEventType;
import at.aimon.core.hook.HookFeedback;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.hook.event.PostToolContext;
import at.aimon.core.hook.event.PreToolContext;
import at.aimon.core.hook.execution.AskPromptHandler;
import at.aimon.core.hook.execution.Decision;
import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.hook.execution.HookStatus;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;
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
 * An {@code http} endpoint may be one written for Claude Code: it spells a refusal
 * {@code hookSpecificOutput.permissionDecision: "deny"}, {@code decision: "block"} or {@code continue: false}. Reading
 * only the native {@code decision} turned every one of those into an allow. An {@code mcp} tool result is not written
 * for Claude Code, so only the native {@code decision} ({@code block} included) is read from it: a result that
 * happens to carry a field named {@code continue} is not a verdict. Each document in the table goes through both real
 * executors — a loopback HTTP server, and an MCP client answering the same text — with the reading expected of each.
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

    /** A row whose reading is the same on both transports: the native spelling. */
    private static Arguments both(String document, Kind kind, String text) {
        return Arguments.of(document, kind, text, kind, text);
    }

    /** A row in a Claude Code spelling: read on {@code http}, not a decision at all on {@code mcp}. */
    private static Arguments httpOnly(String document, Kind kind, String text) {
        return Arguments.of(document, kind, text, Kind.ALLOW, "");
    }

    static Stream<Arguments> documents() {
        return Stream.of(
                // --- the native spelling, the same on both transports
                both("{\"decision\":\"deny\",\"reason\":\"policy says no\"}", Kind.DENY, "policy says no"),
                both("{\"decision\":\"allow\"}", Kind.ALLOW, ""), both("{\"decision\":\"defer\"}", Kind.ALLOW, ""),
                both("{\"feedback\":\"noted\"}", Kind.ALLOW, ""),
                // --- top-level decision: block is a synonym of deny, on both
                both("{\"decision\":\"block\",\"reason\":\"cc style\"}", Kind.DENY, "cc style"),
                both("{\"decision\":\"block\"}", Kind.DENY, "Denied by"),
                both("{\"decision\":\"approve\"}", Kind.NO_VERDICT, ""), both("{\"decision\":7}", Kind.NO_VERDICT, ""),
                // --- hookSpecificOutput.permissionDecision: http only
                httpOnly(CC_DENY, Kind.DENY, "prod is frozen"),
                httpOnly("{\"hookSpecificOutput\":{\"permissionDecision\":\"DENY\"}}", Kind.DENY, "Denied by"),
                httpOnly("{\"hookSpecificOutput\":{\"permissionDecision\":\"allow\"}}", Kind.ALLOW, ""),
                httpOnly(CC_ASK, Kind.ASK, "confirm prod"),
                httpOnly("{\"hookSpecificOutput\":{\"permissionDecision\":\"ask\"}}", Kind.ASK, "confirmation"),
                httpOnly("{\"hookSpecificOutput\":{\"permissionDecision\":\"defer\"}}", Kind.NO_VERDICT, ""),
                httpOnly("{\"hookSpecificOutput\":{\"permissionDecision\":\"maybe\"}}", Kind.NO_VERDICT, ""),
                httpOnly("{\"hookSpecificOutput\":{\"permissionDecision\":7}}", Kind.NO_VERDICT, ""),
                httpOnly("{\"hookSpecificOutput\":{\"additionalContext\":\"ctx\"}}", Kind.ALLOW, ""),
                // --- a hookSpecificOutput that is not an object cannot be read (it read as silent: allow)
                httpOnly("{\"hookSpecificOutput\":[{\"permissionDecision\":\"deny\"}]}", Kind.NO_VERDICT, ""),
                httpOnly("{\"hookSpecificOutput\":\"deny\"}", Kind.NO_VERDICT, ""),
                httpOnly("{\"hookSpecificOutput\":7}", Kind.NO_VERDICT, ""),
                httpOnly("{\"hookSpecificOutput\":null}", Kind.ALLOW, ""),
                both("{\"decision\":\"deny\",\"reason\":\"native no\",\"hookSpecificOutput\":\"x\"}", Kind.DENY,
                        "native no"),
                // --- continue: http only. An MCP tool result with a field of that name is not a verdict.
                httpOnly("{\"continue\":false,\"stopReason\":\"halt\"}", Kind.DENY, "halt"),
                httpOnly("{\"continue\":false}", Kind.DENY, "Denied by"),
                httpOnly("{\"continue\":true}", Kind.ALLOW, ""), httpOnly("{\"continue\":\"no\"}", Kind.NO_VERDICT, ""),
                httpOnly("{\"items\":[],\"continue\":false}", Kind.DENY, "Denied by"),
                httpOnly("{\"items\":[],\"continue\":\"token-123\"}", Kind.NO_VERDICT, ""),
                // --- both spellings, disagreeing: on http the stricter wins, on mcp only the native one is read
                httpOnly("{\"decision\":\"allow\",\"hookSpecificOutput\":{\"permissionDecision\":\"deny\","
                        + "\"permissionDecisionReason\":\"cc no\"}}", Kind.DENY, "cc no"),
                both("{\"decision\":\"deny\",\"reason\":\"native no\","
                        + "\"hookSpecificOutput\":{\"permissionDecision\":\"allow\"}}", Kind.DENY, "native no"),
                httpOnly("{\"decision\":\"allow\",\"continue\":false,\"stopReason\":\"halt\"}", Kind.DENY, "halt"),
                httpOnly("{\"decision\":\"allow\",\"hookSpecificOutput\":{\"permissionDecision\":\"ask\"}}", Kind.ASK,
                        "confirmation"),
                httpOnly("{\"decision\":\"allow\",\"hookSpecificOutput\":{\"permissionDecision\":\"bogus\"}}",
                        Kind.NO_VERDICT, ""),
                Arguments.of("{\"decision\":\"bogus\",\"hookSpecificOutput\":{\"permissionDecision\":\"deny\","
                        + "\"permissionDecisionReason\":\"cc no\"}}", Kind.DENY, "cc no", Kind.NO_VERDICT, ""),
                both("{\"decision\":\"bogus\",\"hookSpecificOutput\":{\"permissionDecision\":\"ask\"}}",
                        Kind.NO_VERDICT, ""),
                // --- an input rewrite that cannot be read is no verdict, unless the document denies
                both("{\"updatedInput\":\"x\"}", Kind.NO_VERDICT, ""),
                both("{\"decision\":\"deny\",\"updatedInput\":\"x\"}", Kind.DENY, "Denied by"),
                httpOnly("{\"hookSpecificOutput\":{\"permissionDecision\":\"allow\",\"updatedInput\":[1]}}",
                        Kind.NO_VERDICT, ""),
                httpOnly("{\"updatedInput\":{\"a\":1},\"hookSpecificOutput\":{\"updatedInput\":{\"a\":2}}}",
                        Kind.NO_VERDICT, ""),
                httpOnly("{\"hookSpecificOutput\":{\"permissionDecision\":\"deny\",\"updatedInput\":[1]}}", Kind.DENY,
                        "Denied by"));
    }

    @ParameterizedTest(name = "http: {0} -> {1}")
    @MethodSource("documents")
    void http_readsTheDocument(String document, Kind http, String httpText, Kind mcp, String mcpText) {
        assertOutcome(overHttp(document), http, httpText);
    }

    @ParameterizedTest(name = "mcp: {0} -> {3}")
    @MethodSource("documents")
    void mcp_readsTheDocument(String document, Kind http, String httpText, Kind mcp, String mcpText) {
        assertOutcome(overMcp(document), mcp, mcpText);
    }

    @Test
    void aDenyReasonComesFromTheReasonFieldOnly() {
        final String document = "{\"hookSpecificOutput\":{\"permissionDecision\":\"deny\","
                + "\"additionalContext\":\"ctx-text\"},\"systemMessage\":\"sys-text\",\"feedback\":\"fb-text\"}";

        final HookResult verdict = overHttp(document).getVerdict().orElseThrow();

        assertThat(verdict.getStatus()).isEqualTo(HookStatus.BLOCKED);
        assertThat(verdict.getFeedback().orElseThrow()).startsWith("Denied by").doesNotContain("ctx-text")
                .doesNotContain("sys-text").doesNotContain("fb-text");
    }

    @Test
    void aDenyTakesTheReasonOfTheSpellingThatDenied() {
        final String document = "{\"decision\":\"allow\",\"reason\":\"looks fine\",\"continue\":false,"
                + "\"stopReason\":\"halt\"}";

        assertThat(overHttp(document).getVerdict().orElseThrow().getFeedback()).contains("halt");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"decision\":\"allow\",\"reason\":\"looks fine to me\",\"continue\":false}",
            "{\"reason\":\"looks fine to me\",\"hookSpecificOutput\":{\"permissionDecision\":\"deny\"}}",
            "{\"decision\":\"defer\",\"reason\":\"looks fine to me\",\"continue\":false,\"stopReason\":7}"})
    void aDenyNeverCarriesTheReasonOfAStatementThatDidNotDeny(String document) {
        final HookResult verdict = overHttp(document).getVerdict().orElseThrow();

        assertThat(verdict.getStatus()).isEqualTo(HookStatus.BLOCKED);
        assertThat(verdict.getFeedback().orElseThrow()).isEqualTo("Denied by HTTP hook");
    }

    @Test
    void aNativeDenyWithoutAReasonTakesTheReasonOfAnotherStatementThatDeniedToo() {
        final String document = "{\"decision\":\"deny\",\"hookSpecificOutput\":{\"permissionDecision\":\"deny\","
                + "\"permissionDecisionReason\":\"cc no\"}}";

        assertThat(overHttp(document).getVerdict().orElseThrow().getFeedback()).contains("cc no");
    }

    // --- updatedInput: at the top level (native) and inside hookSpecificOutput (Claude Code) ----------------------

    @Test
    void http_readsAnInputRewriteInsideHookSpecificOutput() {
        final HookResult verdict = overHttp("{\"hookSpecificOutput\":{\"hookEventName\":\"PreToolUse\","
                + "\"permissionDecision\":\"allow\",\"updatedInput\":{\"command\":\"ls -la\"}}}").getVerdict()
                .orElseThrow();

        assertThat(verdict.getStatus()).isEqualTo(HookStatus.SUCCESS);
        assertThat(verdict.getUpdatedInput().orElseThrow().toMap()).containsEntry("command", "ls -la");
    }

    @Test
    void http_readsAnInputRewriteInsideHookSpecificOutput_withoutAPermissionDecision() {
        final HookResult verdict = overHttp("{\"hookSpecificOutput\":{\"updatedInput\":{\"command\":\"ls\"}}}")
                .getVerdict().orElseThrow();

        assertThat(verdict.getUpdatedInput().orElseThrow().toMap()).containsEntry("command", "ls");
    }

    @Test
    void http_theSameRewriteInBothPlaces_isOneRewrite() {
        final HookResult verdict = overHttp("{\"updatedInput\":{\"command\":\"ls\"},"
                + "\"hookSpecificOutput\":{\"permissionDecision\":\"allow\",\"updatedInput\":{\"command\":\"ls\"}}}")
                .getVerdict().orElseThrow();

        assertThat(verdict.getUpdatedInput().orElseThrow().toMap()).containsEntry("command", "ls");
    }

    @Test
    void http_anAskCarriesTheRewriteOfEitherPlace() {
        final HookResult verdict = overHttp(
                "{\"hookSpecificOutput\":{\"permissionDecision\":\"ask\"," + "\"updatedInput\":{\"command\":\"ls\"}}}")
                .getVerdict().orElseThrow();

        assertThat(verdict.getDecision()).isEqualTo(Decision.ASK);
        assertThat(verdict.getUpdatedInput().orElseThrow().toMap()).containsEntry("command", "ls");
    }

    @Test
    void aDenyCarriesNoRewrite() {
        final HookResult verdict = overHttp("{\"hookSpecificOutput\":{\"permissionDecision\":\"deny\","
                + "\"updatedInput\":{\"command\":\"ls\"}},\"updatedInput\":{\"command\":\"ls\"}}").getVerdict()
                .orElseThrow();

        assertThat(verdict.getStatus()).isEqualTo(HookStatus.BLOCKED);
        assertThat(verdict.getUpdatedInput()).isEmpty();
    }

    @Test
    void mcp_readsTheTopLevelRewriteOnly() {
        final HookResult nested = overMcp(
                "{\"hookSpecificOutput\":{\"permissionDecision\":\"allow\",\"updatedInput\":{\"command\":\"ls\"}}}")
                .getVerdict().orElseThrow();
        final HookResult topLevel = overMcp("{\"updatedInput\":{\"command\":\"ls\"},"
                + "\"hookSpecificOutput\":{\"updatedInput\":{\"command\":\"rm\"}}}").getVerdict().orElseThrow();

        assertThat(nested.getUpdatedInput()).isEmpty();
        assertThat(topLevel.getUpdatedInput().orElseThrow().toMap()).containsEntry("command", "ls");
    }

    @Test
    void preTool_aClaudeCodeRewrite_reachesTheToolInput() throws Exception {
        final HookResult result = fire(
                httpHook(
                        "{\"hookSpecificOutput\":{\"hookEventName\":\"PreToolUse\","
                                + "\"permissionDecision\":\"allow\",\"updatedInput\":{\"command\":\"ls -la\"}}}",
                        false),
                AskPromptHandler.denyAll());

        assertThat(result.getStatus()).isEqualTo(HookStatus.SUCCESS);
        assertThat(result.getUpdatedInput().orElseThrow().toMap()).containsEntry("command", "ls -la");
    }

    // --- feedback: the Claude Code spellings are read on http only ------------------------------------------------

    @Test
    void feedback_claudeCodeSpellings_areReadOnHttpOnly() {
        for (String document : new String[]{"{\"systemMessage\":\"note\"}",
                "{\"hookSpecificOutput\":{\"additionalContext\":\"note\"}}"}) {
            assertThat(overHttp(document).getVerdict().orElseThrow().getFeedback()).contains("note");
            assertThat(overMcp(document).getVerdict().orElseThrow().getFeedback()).isEmpty();
        }
        assertThat(overMcp("{\"feedback\":\"note\"}").getVerdict().orElseThrow().getFeedback()).contains("note");
    }

    // --- postTool: the advisory reading ---------------------------------------------------------------------------

    @Test
    void postTool_ask_showsTheModelTheEndpointsFeedback_notAPrompt() throws Exception {
        final String withFeedback = "{\"hookSpecificOutput\":{\"permissionDecision\":\"ask\"},"
                + "\"systemMessage\":\"note\"}";
        final String withReason = "{\"hookSpecificOutput\":{\"permissionDecision\":\"ask\","
                + "\"permissionDecisionReason\":\"confirm prod\"}}";

        assertThat(firePostTool(postToolHook(withFeedback))).containsExactly("note");
        assertThat(firePostTool(postToolHook(withReason))).isEmpty();
    }

    @Test
    void postTool_ask_isASuccess_notAnAsk() {
        answer.set(CC_ASK);

        final HookResult result = HttpActionExecutor.createDefault().run(httpAction(), null, Map.of(), Map.of());

        assertThat(result.getDecision()).isEqualTo(Decision.ALLOW);
        assertThat(result.getStatus()).isEqualTo(HookStatus.SUCCESS);
        assertThat(result.getFeedback()).isEmpty();
    }

    @Test
    void postTool_mcpToolResultWithAContinueField_saysNothing() throws Exception {
        final DeclarativePostToolHook hook = new DeclarativePostToolHook("policy", NameOnlyPredicate.ANY, mcpAction(),
                NoOpShellActionExecutor.INSTANCE, null, mcpExecutor("{\"items\":[],\"continue\":false}"), Map.of());

        assertThat(firePostTool(hook)).isEmpty();
    }

    // --- through the hook and the manager: what the guard does with each verdict ---------------------------------

    @Test
    void preTool_claudeCodeDeny_blocksWithTheServersReason_evenWithFailOpen() throws Exception {
        for (boolean failOpen : new boolean[]{false, true}) {
            final HookResult result = fire(httpHook(CC_DENY, failOpen), AskPromptHandler.allowAll());
            assertThat(result.getStatus()).isEqualTo(HookStatus.BLOCKED);
            assertThat(result.getFeedback()).contains("prod is frozen");
        }
    }

    @Test
    void preTool_nativeDeny_blocksOnBothTransports_evenWithFailOpen() throws Exception {
        final String document = "{\"decision\":\"block\",\"reason\":\"prod is frozen\"}";
        for (boolean failOpen : new boolean[]{false, true}) {
            for (HookResult result : new HookResult[]{fire(httpHook(document, failOpen), AskPromptHandler.allowAll()),
                    fire(mcpHook(document, failOpen), AskPromptHandler.allowAll())}) {
                assertThat(result.getStatus()).isEqualTo(HookStatus.BLOCKED);
                assertThat(result.getFeedback()).contains("prod is frozen");
            }
        }
    }

    @Test
    void preTool_mcpToolResultWithAContinueField_isNotAVerdict() throws Exception {
        for (String document : new String[]{"{\"items\":[],\"continue\":false}",
                "{\"items\":[],\"continue\":\"token-123\"}", CC_DENY, CC_ASK}) {
            assertThat(fire(mcpHook(document, false), AskPromptHandler.denyAll()).getStatus()).as(document)
                    .isEqualTo(HookStatus.SUCCESS);
        }
    }

    @Test
    void preTool_claudeCodeAsk_isAnsweredByTheAskPromptHandler() throws Exception {
        for (boolean failOpen : new boolean[]{false, true}) {
            assertThat(fire(httpHook(CC_ASK, failOpen), AskPromptHandler.denyAll()).getStatus())
                    .isEqualTo(HookStatus.BLOCKED);
            assertThat(fire(httpHook(CC_ASK, failOpen), AskPromptHandler.allowAll()).getStatus())
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

    private DeclarativePostToolHook postToolHook(String document) {
        answer.set(document);
        return new DeclarativePostToolHook("policy", NameOnlyPredicate.ANY, httpAction(),
                NoOpShellActionExecutor.INSTANCE, HttpActionExecutor.createDefault(), null, Map.of());
    }

    /** What the model is shown after a tool ran: the advisory feedback of the postTool chain. */
    private static List<String> firePostTool(DeclarativePostToolHook hook) throws Exception {
        final HookRegistry registry = new DefaultHookRegistry();
        registry.register(HookEventType.POST_TOOL, hook);
        try (DefaultHookExecutionManager manager = DefaultHookExecutionManager.builder().build()) {
            return HookFeedback.collectAdvisory(manager.executePostTool(
                    PostToolContext.builder().executorType(InvokerType.MAIN_AGENT).invokerName("default-agent")
                            .hookRegistry(registry).toolUse(ToolUse.of("call-1", "Bash", Map.of()))
                            .toolUseResult(ToolUseResult.success("call-1", "ok")).iterationCount(3).build()));
        }
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
