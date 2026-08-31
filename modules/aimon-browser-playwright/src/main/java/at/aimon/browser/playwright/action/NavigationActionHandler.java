package at.aimon.browser.playwright.action;

import java.util.Objects;

import com.microsoft.playwright.Page;

import at.aimon.browser.playwright.BrowserInput;
import at.aimon.browser.playwright.BrowserSession;
import at.aimon.browser.playwright.dom.CandidateExtractor;

/**
 * 브라우저 네비게이션(back, forward, reload) 액션 핸들러.
 *
 * <p>
 * 생성자에서 action type을 주입받아 하나의 클래스로 세 가지 액션을 처리한다.
 */
public class NavigationActionHandler implements BrowserActionHandler {

    private final String actionType;

    /**
     * NavigationActionHandler를 생성한다.
     *
     * @param actionType
     *            "back", "forward", "reload" 중 하나
     */
    public NavigationActionHandler(String actionType) {
        this.actionType = Objects.requireNonNull(actionType, "actionType cannot be null");
        if (!"back".equals(actionType) && !"forward".equals(actionType) && !"reload".equals(actionType)) {
            throw new IllegalArgumentException("actionType must be back, forward, or reload: " + actionType);
        }
    }

    @Override
    public String getActionType() {
        return actionType;
    }

    @Override
    public BrowserActionResult handle(BrowserInput input, BrowserSession session, int timeoutMs) {
        Page page = session.getActivePage();

        try {
            switch (actionType) {
                case "back" -> page.goBack(new Page.GoBackOptions().setTimeout(timeoutMs));
                case "forward" -> page.goForward(new Page.GoForwardOptions().setTimeout(timeoutMs));
                case "reload" -> page.reload(new Page.ReloadOptions().setTimeout(timeoutMs));
                default -> throw new AssertionError("Unreachable: constructor validates actionType");
            }

            return BrowserActionResult.success(session.getId(), actionType).url(page.url()).title(page.title())
                    .candidates(CandidateExtractor.extract(page)).build();
        } catch (Exception e) {
            return BrowserActionResult.error(session.getId(), actionType, "NAVIGATION_FAILED",
                    "Navigation failed: " + e.getMessage());
        }
    }
}
