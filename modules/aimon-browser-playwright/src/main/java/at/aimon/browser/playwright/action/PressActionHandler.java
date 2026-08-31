package at.aimon.browser.playwright.action;

import com.microsoft.playwright.Page;

import at.aimon.browser.playwright.BrowserInput;
import at.aimon.browser.playwright.BrowserSession;
import at.aimon.browser.playwright.dom.CandidateExtractor;

/**
 * 키보드 키를 누르는 액션 핸들러.
 */
public class PressActionHandler implements BrowserActionHandler {

    @Override
    public String getActionType() {
        return "press";
    }

    @Override
    public BrowserActionResult handle(BrowserInput input, BrowserSession session, int timeoutMs) {
        Page page = session.getActivePage();
        String key = BrowserActionHandler.require(input.key(), "key");

        try {
            page.keyboard().press(key);

            return BrowserActionResult.success(session.getId(), "press").url(page.url()).title(page.title())
                    .candidates(CandidateExtractor.extract(page)).build();
        } catch (Exception e) {
            return BrowserActionResult.error(session.getId(), "press", "PRESS_FAILED",
                    "Failed to press key '" + key + "': " + e.getMessage());
        }
    }
}
