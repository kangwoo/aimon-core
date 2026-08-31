package at.aimon.browser.playwright;

import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;

import at.aimon.browser.playwright.action.BrowserActionResult;
import at.aimon.core.agent.tool.ToolCategories;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.agent.tool.exception.ToolExecutionException;
import at.aimon.core.agent.tool.generic.GenericTool;
import at.aimon.core.agent.tool.permission.CustomToolPermissionAware;
import at.aimon.core.agent.tool.permission.CustomToolPermissionRule;
import at.aimon.core.credential.CredentialStore;

/**
 * 웹 브라우저 자동화 Tool.
 *
 * <p>
 * 단일 Tool에서 {@code action} 파라미터로 분기하는 Action Dispatch 방식을 사용한다.
 * BashTool의 foreground/background 분기 패턴과 유사하되, 14개 액션 타입을
 * Map 기반으로 디스패치한다.
 *
 * <p>
 * 모든 Playwright 호출은 {@link PlaywrightWorkerPool}을 통해
 * 세션이 할당된 Worker의 전용 스레드에서 실행된다.
 *
 * <p>
 * 파라미터 26개의 스키마는 손으로 쓰지 않고 {@link BrowserInput} 에서 생성된다. description 은 등록된
 * credential 프로필에 따라 달라지므로 {@link GenericTool} 의 supplier 생성자를 쓴다 — 달라지는 것은 description
 * 뿐이고 스키마는 레코드에서 한 번 파생된다.
 */
public class BrowserTool extends GenericTool<BrowserInput, String> implements CustomToolPermissionAware {

    public static final String TOOL_NAME = "Browser";

    private static final Logger log = LoggerFactory.getLogger(BrowserTool.class);
    private static final String DESCRIPTION = "Automates a web browser. "
            + "Supports actions: open, click, type, press, select, scroll, "
            + "wait, extract, screenshot, back, forward, reload, close, save_auth. "
            + "Each call performs one action and returns structured JSON "
            + "with page state and interactive element candidates.";

    private static final int WORKER_OVERHEAD_MS = 5000;
    private static final int SESSION_CREATION_TIMEOUT_MS = 15000;
    private static final int MAX_SESSION_ID_RETRIES = 3;

    private static final Set<String> BLOCKED_RESOURCE_TYPES = Set.of("image", "font", "media", "stylesheet");

    private final BrowserActionDispatcher dispatcher;
    private final BrowserSessionStore sessionStore;
    private final PlaywrightWorkerPool workerPool;
    private final ObjectMapper objectMapper;
    private final BrowserToolPermissionRule permissionRule;

    /**
     * BrowserTool을 생성한다.
     *
     * @param dispatcher
     *            액션 디스패처
     * @param sessionStore
     *            세션 저장소
     * @param workerPool
     *            Playwright Worker Pool
     * @param objectMapper
     *            JSON 직렬화용 ObjectMapper
     * @param credentialStore
     *            인증 정보 저장소 (null 허용 — null이면 credential_ref 프로필 안내 생략)
     */
    public BrowserTool(BrowserActionDispatcher dispatcher, BrowserSessionStore sessionStore,
            PlaywrightWorkerPool workerPool, ObjectMapper objectMapper, CredentialStore credentialStore) {
        super(TOOL_NAME, ToolCategories.EXECUTION, () -> buildDescription(credentialStore), BrowserInput.class);
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher cannot be null");
        this.sessionStore = Objects.requireNonNull(sessionStore, "sessionStore cannot be null");
        this.workerPool = Objects.requireNonNull(workerPool, "workerPool cannot be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper cannot be null");
        this.permissionRule = new BrowserToolPermissionRule();
    }

    @Override
    protected String doExecute(BrowserInput input, ToolContext context) throws Exception {
        final String action = input.action();
        final int timeoutMs = Math.max(1000, Math.min(input.timeoutMs() != null ? input.timeoutMs() : 30000, 120000));

        try {
            // 세션 조회 또는 생성
            final BrowserSession session = resolveSession(input.sessionId(), input);

            // 세션이 할당된 Worker에서 액션 디스패치
            final BrowserActionResult result = workerPool.executeOnWorker(session.getWorkerIndex(),
                    () -> dispatcher.dispatch(action, input, session, timeoutMs), timeoutMs + WORKER_OVERHEAD_MS);

            // close 액션 시 세션 정리는 CloseActionHandler가 sessionStore.remove()와
            // workerPool.closeSessionAsync()를 통해 처리한다. 여기서는 touch()만 스킵한다.
            if (!"close".equals(action)) {
                session.touch();
            }

            return objectMapper.writeValueAsString(result);

        } catch (IllegalArgumentException e) {
            log.warn("Invalid parameter: {}", e.getMessage());
            throw new ToolExecutionException("Invalid parameter: " + e.getMessage(), e);
        } catch (IllegalStateException e) {
            log.error("Session creation failed: {}", e.getMessage(), e);
            throw new ToolExecutionException("Session creation failed: " + e.getMessage(), e);
        } catch (TimeoutException e) {
            log.warn("Action timed out: {}", e.getMessage());
            throw new ToolExecutionException("Action timed out", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.error("Action execution failed: {}", cause.getMessage(), cause);
            throw new ToolExecutionException("Action execution failed: " + cause.getMessage(), e);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize action result: {}", e.getMessage(), e);
            throw new ToolExecutionException("Failed to serialize action result: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Action interrupted");
            throw new ToolExecutionException("Action interrupted", e);
        }
        // 마지막 "Unexpected error: " 절은 없앴다 — GenericTool.execute 가 예상 못 한 예외에 대해 정확히 그
        // 메시지를 만든다.
    }

    @Override
    protected ToolResult render(String output) {
        return ToolResult.success(output);
    }

    /**
     * 기존 세션을 조회하거나 새 세션을 생성한다.
     *
     * @param sessionId
     *            기존 세션 ID (null이면 새 세션 생성)
     * @param input
     *            새 세션 생성 시 옵션 파라미터
     * @return BrowserSession (never null)
     */
    private BrowserSession resolveSession(String sessionId, BrowserInput input) {
        if (sessionId != null) {
            return sessionStore.get(sessionId)
                    .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));
        }
        return createNewSession(input);
    }

    /**
     * 새 브라우저 세션을 생성한다.
     *
     * <p>
     * 세션 생성 과정:
     * <ol>
     * <li>세션 용량 검사
     * <li>고유 세션 ID 생성 (충돌 시 재시도)
     * <li>입력에서 세션 옵션 추출 및 검증
     * <li>Worker 스레드에서 BrowserContext + Page 생성
     * <li>세션 저장소에 등록
     * </ol>
     *
     * @param input
     *            세션 옵션 파라미터
     * @return 새로 생성된 BrowserSession (never null)
     */
    private BrowserSession createNewSession(BrowserInput input) {
        if (sessionStore.isFull()) {
            throw new IllegalArgumentException("Maximum session limit reached: " + sessionStore.maxSessions());
        }

        final String newId = generateUniqueSessionId();
        final int workerIndex = workerPool.selectWorker();

        // 세션 옵션 추출
        final String locale = input.locale() != null ? input.locale() : "en-US";
        final String userAgent = input.userAgent();
        final int viewportWidth = input.viewportWidth() != null ? input.viewportWidth() : 1280;
        final int viewportHeight = input.viewportHeight() != null ? input.viewportHeight() : 720;
        final String resourcePolicy = input.resourcePolicy() != null ? input.resourcePolicy() : "minimal";
        final String storageState = input.storageState();

        validateStorageState(storageState);

        // Worker의 Playwright 스레드에서 BrowserContext + Page 생성
        try {
            BrowserSession session = workerPool.executeOnWorker(workerIndex, () -> {
                Browser.NewContextOptions options = new Browser.NewContextOptions().setLocale(locale)
                        .setViewportSize(viewportWidth, viewportHeight);
                if (userAgent != null) {
                    options.setUserAgent(userAgent);
                }
                if (storageState != null) {
                    options.setStorageState(storageState);
                }

                BrowserContext ctx = workerPool.createContext(workerIndex, options);
                try {
                    Page page = ctx.newPage();

                    if ("minimal".equals(resourcePolicy)) {
                        applyMinimalResourcePolicy(page);
                    }

                    return new BrowserSession(newId, ctx, page, resourcePolicy, workerIndex);
                } catch (Exception e) {
                    ctx.close();
                    throw e;
                }
            }, SESSION_CREATION_TIMEOUT_MS);

            sessionStore.put(newId, session);
            return session;

        } catch (Exception e) {
            throw new IllegalStateException("Failed to create session: " + e.getMessage(), e);
        }
    }

    /**
     * 고유한 세션 ID를 생성한다. "bs_" 접두사 + UUID 앞 8자.
     *
     * <p>
     * 기존 세션과의 충돌 시 최대 {@value #MAX_SESSION_ID_RETRIES}회 재시도한다.
     */
    private String generateUniqueSessionId() {
        for (int i = 0; i < MAX_SESSION_ID_RETRIES; i++) {
            final String id = "bs_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
            if (sessionStore.get(id).isEmpty()) {
                return id;
            }
            log.debug("Session ID collision detected: {}, retrying ({}/{})", id, i + 1, MAX_SESSION_ID_RETRIES);
        }
        // 충돌이 반복되면 전체 UUID를 사용하여 충돌을 회피
        return "bs_" + UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * storage_state JSON 유효성을 사전 검증한다.
     *
     * @param storageState
     *            검증할 JSON 문자열 (null이면 무시)
     */
    private void validateStorageState(String storageState) {
        if (storageState != null) {
            try {
                objectMapper.readTree(storageState);
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid storage_state JSON: " + e.getMessage());
            }
        }
    }

    /**
     * 텍스트 추출 최적화를 위해 불필요한 리소스를 차단한다.
     */
    private void applyMinimalResourcePolicy(Page page) {
        page.route("**/*", route -> {
            String resourceType = route.request().resourceType();
            if (BLOCKED_RESOURCE_TYPES.contains(resourceType)) {
                route.abort();
            } else {
                route.resume();
            }
        });
    }

    @Override
    public CustomToolPermissionRule getCustomPermissionRule() {
        return permissionRule;
    }

    /**
     * Tool description을 동적으로 생성한다.
     *
     * <p>
     * {@link CredentialStore}에 등록된 프로필이 있으면
     * 사용 가능한 credential_ref 목록을 description에 포함하여
     * LLM이 올바른 참조값을 사용할 수 있게 한다.
     *
     * @param credentialStore
     *            인증 정보 저장소 (null 허용)
     * @return 생성된 Tool description
     */
    private static String buildDescription(CredentialStore credentialStore) {
        if (credentialStore == null) {
            return DESCRIPTION;
        }

        try {
            Set<String> profiles = credentialStore.getProfiles();
            if (profiles.isEmpty()) {
                return DESCRIPTION;
            }

            StringBuilder desc = new StringBuilder(DESCRIPTION);
            desc.append("\n\nAvailable credential profiles for credential_ref (action=type):");

            for (String profile : new TreeSet<>(profiles)) {
                Set<String> fields = credentialStore.getFields(profile);
                desc.append("\n- ").append(profile);
                if (!fields.isEmpty()) {
                    desc.append(": ").append(String.join(", ", new TreeSet<>(fields)));
                }
            }

            desc.append("\nUsage example: credential_ref='<profile>.<field>'");
            return desc.toString();

        } catch (Exception e) {
            log.warn("Failed to build credential profiles description: {}", e.getMessage());
            return DESCRIPTION;
        }
    }
}
