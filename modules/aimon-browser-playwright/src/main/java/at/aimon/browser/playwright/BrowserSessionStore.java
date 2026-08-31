package at.aimon.browser.playwright;

import java.util.List;
import java.util.Optional;

/**
 * 브라우저 세션 저장소 인터페이스.
 *
 * <p>
 * 기본 구현은 {@link InMemoryBrowserSessionStore}.
 * 분산 환경에서는 sticky session 라우팅 + 외부 저장소 구현으로 교체 가능.
 *
 * <p>
 * 주의: {@link BrowserSession}은 Playwright BrowserContext를 포함하므로
 * 직렬화할 수 없다. 분산 환경에서는 storageState만 저장하고
 * context를 재생성하거나, remote browser에 연결하는 전략이 필요하다.
 */
public interface BrowserSessionStore {

    /**
     * 세션을 저장한다.
     *
     * @param sessionId
     *            세션 ID
     * @param session
     *            브라우저 세션
     */
    void put(String sessionId, BrowserSession session);

    /**
     * 세션을 조회한다.
     *
     * @param sessionId
     *            세션 ID
     * @return 세션 (만료되었거나 없으면 empty)
     */
    Optional<BrowserSession> get(String sessionId);

    /**
     * 세션을 제거한다. 실제 Playwright 리소스 해제는
     * {@link PlaywrightWorkerPool}을 통해 수행한다.
     *
     * @param sessionId
     *            세션 ID
     * @return 제거된 세션 (없으면 empty)
     */
    Optional<BrowserSession> remove(String sessionId);

    /**
     * 현재 활성 세션 수를 반환한다.
     *
     * @return 활성 세션 수
     */
    int size();

    /**
     * 허용되는 최대 세션 수를 반환한다.
     *
     * @return 최대 세션 수
     */
    int maxSessions();

    /**
     * 만료된 세션을 Map에서 제거하고, 제거된 세션 목록을 반환한다.
     * 호출자가 반환된 세션의 리소스 해제를 책임진다.
     *
     * @return 만료되어 제거된 세션 목록
     */
    List<BrowserSession> evictExpired();

    /**
     * 모든 세션을 Map에서 제거하고, 제거된 세션 목록을 반환한다.
     * 호출자가 반환된 세션의 리소스 해제를 책임진다.
     *
     * @return 모든 제거된 세션 목록
     */
    List<BrowserSession> removeAll();

    /**
     * 저장소가 가득 찼는지 확인한다.
     *
     * <p>
     * 기본 구현은 단순히 {@code size() >= maxSessions()}를 비교한다.
     * 만료된 세션의 정리(eviction)가 필요한 구현체는 이 메서드를 오버라이드하여
     * {@link #evictExpired()}를 호출한 후 판단해야 한다.
     *
     * @return 최대 세션 수에 도달했으면 true
     */
    default boolean isFull() {
        return size() >= maxSessions();
    }
}
