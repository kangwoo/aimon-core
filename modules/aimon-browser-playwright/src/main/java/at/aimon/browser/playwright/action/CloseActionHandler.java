package at.aimon.browser.playwright.action;

import java.util.Objects;

import at.aimon.browser.playwright.BrowserInput;
import at.aimon.browser.playwright.BrowserSession;
import at.aimon.browser.playwright.BrowserSessionStore;

/**
 * 브라우저 세션을 닫는 액션 핸들러.
 *
 * <p>
 * 다른 핸들러와 달리 {@link BrowserSessionStore}를 생성자 주입받는다.
 * {@code sessionStore.remove()} 내부에서 {@code workerPool.closeSessionAsync()}를
 * 통해 BrowserContext/Page 리소스가 해제된다.
 */
public class CloseActionHandler implements BrowserActionHandler {

    private final BrowserSessionStore sessionStore;

    /**
     * CloseActionHandler를 생성한다.
     *
     * @param sessionStore
     *            세션 저장소
     */
    public CloseActionHandler(BrowserSessionStore sessionStore) {
        this.sessionStore = Objects.requireNonNull(sessionStore, "sessionStore cannot be null");
    }

    @Override
    public String getActionType() {
        return "close";
    }

    @Override
    public BrowserActionResult handle(BrowserInput input, BrowserSession session, int timeoutMs) {
        sessionStore.remove(session.getId());

        return BrowserActionResult.success(session.getId(), "close").message("Session closed: " + session.getId())
                .build();
    }
}
