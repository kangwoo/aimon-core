package at.aimon.browser.playwright.action;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.browser.playwright.BrowserInput;
import at.aimon.browser.playwright.BrowserSession;

/**
 * 현재 브라우저 세션의 인증 상태(cookies + localStorage)를 저장하는 액션 핸들러.
 *
 * <p>
 * Playwright의 {@code BrowserContext.storageState()}를 호출하여
 * JSON 문자열을 반환한다. 이 JSON을 새 세션 생성 시 {@code storage_state}
 * 파라미터로 전달하면 인증 상태를 복원할 수 있다.
 *
 * <p>
 * 사용 흐름:
 * <ol>
 * <li>로그인 절차 수행 (open → type → click)
 * <li>{@code save_auth} 액션으로 storageState JSON 획득
 * <li>이후 세션 생성 시 {@code storage_state} 파라미터에 JSON 전달
 * </ol>
 */
public class SaveAuthActionHandler implements BrowserActionHandler {

    private static final Logger log = LoggerFactory.getLogger(SaveAuthActionHandler.class);

    @Override
    public String getActionType() {
        return "save_auth";
    }

    @Override
    public BrowserActionResult handle(BrowserInput input, BrowserSession session, int timeoutMs) {
        try {
            String storageStateJson = session.getContext().storageState();

            log.debug("Storage state saved for session: {}", session.getId());

            return BrowserActionResult.success(session.getId(), "save_auth").url(session.getActivePage().url())
                    .title(session.getActivePage().title()).content(storageStateJson)
                    .message("Storage state saved successfully. "
                            + "Use the content as storage_state parameter when creating a new session.")
                    .build();

        } catch (Exception e) {
            log.warn("Failed to save storage state for session {}: {}", session.getId(), e.getMessage());
            return BrowserActionResult.error(session.getId(), "save_auth", "STORAGE_STATE_ERROR",
                    "Failed to save storage state: " + e.getMessage());
        }
    }
}
