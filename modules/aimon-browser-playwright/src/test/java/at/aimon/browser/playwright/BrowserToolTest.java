package at.aimon.browser.playwright;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;

import at.aimon.browser.playwright.action.BrowserActionResult;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.credential.InMemoryCredentialStore;

class BrowserToolTest {

    private BrowserActionDispatcher dispatcher;
    private BrowserSessionStore sessionStore;
    private PlaywrightWorkerPool workerPool;
    private ObjectMapper objectMapper;
    private BrowserTool browserTool;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        dispatcher = mock(BrowserActionDispatcher.class);
        sessionStore = mock(BrowserSessionStore.class);
        workerPool = mock(PlaywrightWorkerPool.class);
        objectMapper = new ObjectMapper();

        // Default sessionStore behavior
        when(sessionStore.maxSessions()).thenReturn(10);
        when(sessionStore.size()).thenReturn(0);

        // executeOnWorker delegates to the callable directly
        when(workerPool.executeOnWorker(anyInt(), any(Callable.class), anyInt())).thenAnswer(invocation -> {
            Callable<?> callable = invocation.getArgument(1);
            return callable.call();
        });

        browserTool = new BrowserTool(dispatcher, sessionStore, workerPool, objectMapper, null);
    }

    @Test
    void shouldHaveCorrectToolName() {
        assertThat(browserTool.getDefinition().getName()).isEqualTo("Browser");
    }

    @Test
    void shouldExecuteActionOnExistingSession() throws Exception {
        BrowserSession session = mock(BrowserSession.class);
        when(session.getId()).thenReturn("bs_test");
        when(session.getWorkerIndex()).thenReturn(0);

        when(sessionStore.get("bs_test")).thenReturn(Optional.of(session));
        when(dispatcher.dispatch(any(), any(), any(), anyInt()))
                .thenReturn(BrowserActionResult.success("bs_test", "click").build());

        ToolInput input = ToolInput.of("action", "click", "session_id", "bs_test", "selector", "#btn");
        ToolResult result = browserTool.execute(input, ToolContext.empty());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("\"sessionId\":\"bs_test\"");
        assertThat(result.getContent()).contains("\"action\":\"click\"");
    }

    @Test
    void shouldReturnErrorForMissingSession() {
        when(sessionStore.get("bs_nonexistent")).thenReturn(Optional.empty());

        ToolInput input = ToolInput.of("action", "click", "session_id", "bs_nonexistent");
        ToolResult result = browserTool.execute(input, ToolContext.empty());

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("Session not found");
    }

    @Test
    void shouldReturnErrorForMissingAction() {
        ToolInput input = ToolInput.of("session_id", "bs_test");
        ToolResult result = browserTool.execute(input, ToolContext.empty());

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("Parameter 'action' is required");
    }

    @Test
    void shouldGenerateSchemaFromInputRecord() {
        Map<String, Object> schema = browserTool.getDefinition().getInputSchema();

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertThat(properties).hasSize(26).containsKeys("session_id", "action", "wait_until", "credential_ref",
                "storage_state");
        assertThat(schema.get("required")).asList().containsExactly("action");
        assertThat(schema.get("additionalProperties")).isEqualTo(false);
    }

    @Test
    void shouldReturnErrorForUndeclaredParameter() {
        ToolInput input = ToolInput.of("action", "click", "selectr", "#btn");
        ToolResult result = browserTool.execute(input, ToolContext.empty());

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("selectr");
    }

    @Test
    void shouldReturnErrorForActionOutsideAllowedSet() {
        ToolInput input = ToolInput.of("action", "teleport");
        ToolResult result = browserTool.execute(input, ToolContext.empty());

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("Parameter 'action' must be one of");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldSurfaceActionSpecificParameterFromHandler() throws Exception {
        // JSON Schema's flat "required" cannot say "url is required when action=open", so binding lets such a call
        // through and the owning handler reports it. The handler runs inside the worker, so its throw reaches
        // execute() already wrapped — the same path it took before the parameters were bound to a record.
        BrowserSession session = mock(BrowserSession.class);
        when(session.getId()).thenReturn("bs_test");
        when(session.getWorkerIndex()).thenReturn(0);
        when(sessionStore.get("bs_test")).thenReturn(Optional.of(session));
        when(workerPool.executeOnWorker(anyInt(), any(Callable.class), anyInt()))
                .thenThrow(new ExecutionException(new IllegalArgumentException("Missing required parameter: url")));

        ToolInput input = ToolInput.of("action", "open", "session_id", "bs_test");
        ToolResult result = browserTool.execute(input, ToolContext.empty());

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).isEqualTo("Action execution failed: Missing required parameter: url");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldReturnErrorOnTimeout() throws Exception {
        BrowserSession session = mock(BrowserSession.class);
        when(session.getId()).thenReturn("bs_test");
        when(session.getWorkerIndex()).thenReturn(0);

        when(sessionStore.get("bs_test")).thenReturn(Optional.of(session));
        when(workerPool.executeOnWorker(anyInt(), any(Callable.class), anyInt()))
                .thenThrow(new TimeoutException("Timed out"));

        ToolInput input = ToolInput.of("action", "click", "session_id", "bs_test");
        ToolResult result = browserTool.execute(input, ToolContext.empty());

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("timed out");
    }

    @Test
    void shouldReturnErrorWhenSessionLimitReached() {
        when(sessionStore.isFull()).thenReturn(true);

        ToolInput input = ToolInput.of("action", "open", "url", "https://example.com");
        ToolResult result = browserTool.execute(input, ToolContext.empty());

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("Maximum session limit");
    }

    @Test
    void shouldImplementCustomToolPermissionAware() {
        assertThat(browserTool.getCustomPermissionRule()).isNotNull();
        assertThat(browserTool.getCustomPermissionRule()).isInstanceOf(BrowserToolPermissionRule.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldCreateSessionWithStorageState() throws Exception {
        // storage_state가 유효한 JSON이면 세션 생성이 성공해야 한다.
        // Browser.NewContextOptions에 getter가 없어 setStorageState 호출을 직접 검증할 수 없으므로,
        // 세션 생성 흐름 전체가 성공하는 것으로 간접 검증한다.
        String storageStateJson = "{\"cookies\":[],\"origins\":[]}";

        when(sessionStore.isFull()).thenReturn(false);
        when(workerPool.selectWorker()).thenReturn(0);

        BrowserContext ctx = mock(BrowserContext.class);
        Page page = mock(Page.class);
        when(ctx.newPage()).thenReturn(page);
        when(workerPool.createContext(anyInt(), any())).thenReturn(ctx);

        when(dispatcher.dispatch(any(), any(), any(), anyInt()))
                .thenReturn(BrowserActionResult.success("bs_test", "open").build());

        ToolInput input = ToolInput.of("action", "open", "url", "https://example.com", "storage_state",
                storageStateJson);
        ToolResult result = browserTool.execute(input, ToolContext.empty());

        assertThat(result.isSuccess()).isTrue();
        verify(workerPool).createContext(anyInt(), any());
    }

    @Test
    void shouldReturnErrorForInvalidStorageStateJson() {
        when(sessionStore.isFull()).thenReturn(false);

        ToolInput input = ToolInput.of("action", "open", "url", "https://example.com", "storage_state",
                "not-valid-json");
        ToolResult result = browserTool.execute(input, ToolContext.empty());

        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("Invalid storage_state JSON");
    }

    @Test
    void shouldIncludeCredentialProfilesInDescription() {
        InMemoryCredentialStore store = InMemoryCredentialStore.builder()
                .profile("jira", Map.of("username", "admin", "password", "secret"))
                .profile("github", Map.of("token", "ghp_abc")).build();

        BrowserTool toolWithCredentials = new BrowserTool(dispatcher, sessionStore, workerPool, objectMapper, store);

        String description = toolWithCredentials.getDefinition().getDescription();
        assertThat(description).contains("Available credential profiles");
        assertThat(description).contains("jira");
        assertThat(description).contains("username");
        assertThat(description).contains("password");
        assertThat(description).contains("github");
        assertThat(description).contains("token");
    }

    @Test
    void shouldNotIncludeCredentialProfilesWhenStoreIsNull() {
        BrowserTool toolWithoutStore = new BrowserTool(dispatcher, sessionStore, workerPool, objectMapper, null);

        String description = toolWithoutStore.getDefinition().getDescription();
        assertThat(description).doesNotContain("Available credential profiles");
    }

    @Test
    void shouldNotIncludeCredentialProfilesWhenStoreIsEmpty() {
        InMemoryCredentialStore emptyStore = InMemoryCredentialStore.builder().build();

        BrowserTool toolWithEmptyStore = new BrowserTool(dispatcher, sessionStore, workerPool, objectMapper,
                emptyStore);

        String description = toolWithEmptyStore.getDefinition().getDescription();
        assertThat(description).doesNotContain("Available credential profiles");
    }
}
