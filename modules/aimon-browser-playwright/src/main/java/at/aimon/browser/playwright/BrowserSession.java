package at.aimon.browser.playwright;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;

/**
 * 하나의 브라우저 세션 상태를 담는 클래스.
 *
 * <p>
 * sessionId를 Playwright BrowserContext + 활성 Page + Worker에 매핑한다.
 * Playwright에서 클릭 등으로 새 탭(Page)이 열릴 수 있으므로,
 * 단일 Page 대신 활성 Page를 추적한다.
 * {@link BrowserContext#onPage} 리스너로 새 Page 생성을 감지하여
 * 활성 Page를 자동 전환한다.
 *
 * <p>
 * {@code workerIndex}는 이 세션이 할당된 Worker를 식별한다.
 * 세션의 모든 Playwright 호출은 이 Worker의 전용 스레드에서 실행되어야 한다.
 * activePage는 항상 해당 Worker 스레드에서만 변경되므로
 * volatile이 불필요하나, touch()는 호출 스레드에서 실행될 수 있으므로
 * lastUsedAt만 volatile로 유지한다.
 */
public class BrowserSession {

    private static final Logger log = LoggerFactory.getLogger(BrowserSession.class);

    private final String id;
    private final BrowserContext context;
    private final String resourcePolicy;
    private final int workerIndex;
    private final Instant createdAt;
    private Page activePage;
    private volatile Instant lastUsedAt;

    /**
     * 새 BrowserSession을 생성한다.
     *
     * @param id
     *            세션 ID
     * @param context
     *            Playwright BrowserContext
     * @param initialPage
     *            초기 Page
     * @param resourcePolicy
     *            리소스 로딩 정책 ("minimal" 또는 "visual")
     * @param workerIndex
     *            할당된 Worker 인덱스
     */
    public BrowserSession(String id, BrowserContext context, Page initialPage, String resourcePolicy, int workerIndex) {
        this.id = Objects.requireNonNull(id, "id cannot be null");
        this.context = Objects.requireNonNull(context, "context cannot be null");
        this.activePage = Objects.requireNonNull(initialPage, "initialPage cannot be null");
        this.resourcePolicy = Objects.requireNonNull(resourcePolicy, "resourcePolicy cannot be null");
        this.workerIndex = workerIndex;
        this.createdAt = Instant.now();
        this.lastUsedAt = this.createdAt;

        // 새 탭 열림 감지 -> 활성 Page 전환
        context.onPage(newPage -> {
            log.debug("New page opened in session {}: {}", id, newPage.url());
            this.activePage = newPage;
        });
    }

    public Page getActivePage() {
        return activePage;
    }

    /**
     * 세션 사용 시간을 갱신한다.
     */
    public void touch() {
        this.lastUsedAt = Instant.now();
    }

    /**
     * 세션이 TTL을 초과하여 만료되었는지 확인한다.
     *
     * @param ttl
     *            세션 유효 기간
     * @return 만료 여부
     */
    public boolean isExpired(Duration ttl) {
        return Instant.now().isAfter(lastUsedAt.plus(ttl));
    }

    /**
     * 세션 리소스를 해제한다. context.close()는 모든 page도 함께 닫는다.
     *
     * <p>
     * 이 메서드는 반드시 Playwright 스레드에서 호출해야 한다.
     * 외부에서는 {@link PlaywrightWorkerPool#closeSessionAsync}를 사용한다.
     */
    void closeInternal() {
        try {
            context.close();
        } catch (Exception e) {
            log.debug("Error closing browser context for session {} (may already be closed): {}", id, e.getMessage());
        }
    }

    public String getId() {
        return id;
    }

    public BrowserContext getContext() {
        return context;
    }

    public String getResourcePolicy() {
        return resourcePolicy;
    }

    public int getWorkerIndex() {
        return workerIndex;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }
}
