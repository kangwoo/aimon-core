package at.aimon.browser.playwright;

import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.databind.ObjectMapper;

import at.aimon.browser.playwright.action.BrowserActionHandler;
import at.aimon.browser.playwright.action.ClickActionHandler;
import at.aimon.browser.playwright.action.CloseActionHandler;
import at.aimon.browser.playwright.action.ExtractActionHandler;
import at.aimon.browser.playwright.action.NavigationActionHandler;
import at.aimon.browser.playwright.action.OpenActionHandler;
import at.aimon.browser.playwright.action.PressActionHandler;
import at.aimon.browser.playwright.action.SaveAuthActionHandler;
import at.aimon.browser.playwright.action.ScreenshotActionHandler;
import at.aimon.browser.playwright.action.ScrollActionHandler;
import at.aimon.browser.playwright.action.SelectActionHandler;
import at.aimon.browser.playwright.action.TypeActionHandler;
import at.aimon.browser.playwright.action.WaitActionHandler;
import at.aimon.browser.playwright.artifact.ArtifactAwareBrowserTool;
import at.aimon.core.agent.orca.tool.OrcaToolProvider;
import at.aimon.core.agent.orca.tool.OrcaToolProviderContext;
import at.aimon.core.agent.tool.Tool;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.tools.web.fetch.ContentExtractor;
import at.aimon.core.tools.web.security.SsrfGuard;

/**
 * Browser Tool을 Orca 에이전트에 등록하는 프로바이더.
 *
 * <p>
 * 공유 가능한 인스턴스({@link SsrfGuard}, {@link ContentExtractor},
 * {@link ObjectMapper}, {@link PlaywrightWorkerPool},
 * {@link BrowserSessionStore})는 생성자를 통해 주입받는다.
 * 인증 정보({@code CredentialStore})는 {@link OrcaToolProviderContext}를 통해 제공된다.
 * 이를 통해 WebFetch/WebSearch 등 다른 Tool과 동일한 인스턴스를
 * 공유할 수 있다.
 *
 * <p>
 * 사용 예:
 *
 * <pre>
 * {@code
 * SsrfGuard ssrfGuard = new SsrfGuard();
 * ContentExtractor extractor = new JsoupContentExtractor();
 * ObjectMapper objectMapper = new ObjectMapper();
 * PlaywrightWorkerPool pool = new PlaywrightWorkerPool(2, true);
 * BrowserSessionStore store =
 *         new InMemoryBrowserSessionStore(10,
 *                 Duration.ofMinutes(30), pool);
 *
 * // CLI 환경 (아티팩트 비활성화)
 * OrcaBrowserToolProvider provider =
 *         new OrcaBrowserToolProvider(ssrfGuard, extractor,
 *                 objectMapper, pool, store);
 *
 * // Web API 환경 (아티팩트 활성화)
 * OrcaBrowserToolProvider provider =
 *         new OrcaBrowserToolProvider(ssrfGuard, extractor,
 *                 objectMapper, pool, store, true);
 * }
 * </pre>
 */
public class OrcaBrowserToolProvider implements OrcaToolProvider {

    private final SsrfGuard ssrfGuard;

    private final ContentExtractor contentExtractor;

    private final ObjectMapper objectMapper;

    private final PlaywrightWorkerPool workerPool;

    private final BrowserSessionStore sessionStore;

    private final boolean artifactEnabled;

    /**
     * OrcaBrowserToolProvider를 생성한다. 아티팩트 기능은 비활성화된다.
     *
     * @param ssrfGuard
     *            SSRF 보호 (null 불가)
     * @param contentExtractor
     *            콘텐츠 추출기 (null 불가)
     * @param objectMapper
     *            JSON 직렬화 (null 불가)
     * @param workerPool
     *            Playwright Worker Pool (null 불가)
     * @param sessionStore
     *            브라우저 세션 저장소 (null 불가)
     * @throws NullPointerException
     *             파라미터가 null인 경우
     */
    public OrcaBrowserToolProvider(SsrfGuard ssrfGuard, ContentExtractor contentExtractor, ObjectMapper objectMapper,
            PlaywrightWorkerPool workerPool, BrowserSessionStore sessionStore) {
        this(ssrfGuard, contentExtractor, objectMapper, workerPool, sessionStore, false);
    }

    /**
     * OrcaBrowserToolProvider를 생성한다.
     *
     * @param ssrfGuard
     *            SSRF 보호 (null 불가)
     * @param contentExtractor
     *            콘텐츠 추출기 (null 불가)
     * @param objectMapper
     *            JSON 직렬화 (null 불가)
     * @param workerPool
     *            Playwright Worker Pool (null 불가)
     * @param sessionStore
     *            브라우저 세션 저장소 (null 불가)
     * @param artifactEnabled
     *            아티팩트 지원 활성화 여부 — {@code true}이면 스크린샷을 {@code FileArtifact}로 등록하는
     *            {@link ArtifactAwareBrowserTool} 데코레이터를 사용한다
     * @throws NullPointerException
     *             필수 파라미터가 null인 경우
     */
    public OrcaBrowserToolProvider(SsrfGuard ssrfGuard, ContentExtractor contentExtractor, ObjectMapper objectMapper,
            PlaywrightWorkerPool workerPool, BrowserSessionStore sessionStore, boolean artifactEnabled) {
        this.ssrfGuard = Objects.requireNonNull(ssrfGuard, "ssrfGuard must not be null");
        this.contentExtractor = Objects.requireNonNull(contentExtractor, "contentExtractor must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.workerPool = Objects.requireNonNull(workerPool, "workerPool must not be null");
        this.sessionStore = Objects.requireNonNull(sessionStore, "sessionStore must not be null");
        this.artifactEnabled = artifactEnabled;
    }

    @Override
    public void registerTools(ToolRegistry registry, OrcaToolProviderContext context) {
        Objects.requireNonNull(registry, "registry must not be null");
        Objects.requireNonNull(context, "context must not be null");

        List<BrowserActionHandler> handlers = List.of(new OpenActionHandler(ssrfGuard), new ClickActionHandler(),
                new TypeActionHandler(context.getCredentialStore()), new PressActionHandler(),
                new SelectActionHandler(), new ScrollActionHandler(), new WaitActionHandler(),
                new ExtractActionHandler(contentExtractor), new ScreenshotActionHandler(context.getFileSystem()),
                new NavigationActionHandler("back"), new NavigationActionHandler("forward"),
                new NavigationActionHandler("reload"), new CloseActionHandler(sessionStore),
                new SaveAuthActionHandler());

        BrowserActionDispatcher dispatcher = new BrowserActionDispatcher(handlers);
        BrowserTool baseTool = new BrowserTool(dispatcher, sessionStore, workerPool, objectMapper,
                context.getCredentialStore());

        Tool tool;
        if (artifactEnabled) {
            Objects.requireNonNull(context.getFileSystem(), "fileSystem is required when artifact is enabled");
            tool = new ArtifactAwareBrowserTool(baseTool, context.getFileSystem());
        } else {
            tool = baseTool;
        }
        registry.register(tool);
    }
}
