package at.aimon.browser.playwright.action;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.TimeoutError;

import at.aimon.browser.playwright.BrowserInput;
import at.aimon.browser.playwright.BrowserSession;
import at.aimon.browser.playwright.dom.CandidateExtractor;

/**
 * 요소 대기 또는 시간 대기를 수행하는 액션 핸들러.
 */
public class WaitActionHandler implements BrowserActionHandler {

    @Override
    public String getActionType() {
        return "wait";
    }

    @Override
    public BrowserActionResult handle(BrowserInput input, BrowserSession session, int timeoutMs) {
        Page page = session.getActivePage();
        String selector = input.selector();
        int waitMs = Math.min(input.waitMs() != null ? input.waitMs() : 1000, timeoutMs);

        try {
            if (selector != null) {
                page.locator(selector).waitFor(new Locator.WaitForOptions().setTimeout(waitMs));
            } else {
                page.waitForTimeout(waitMs);
            }

            return BrowserActionResult.success(session.getId(), "wait").url(page.url()).title(page.title())
                    .candidates(CandidateExtractor.extract(page)).build();

        } catch (TimeoutError e) {
            return BrowserActionResult.builder().sessionId(session.getId()).action("wait").error("TIMEOUT")
                    .message("Wait timed out" + (selector != null ? " for selector: " + selector : ""))
                    .candidates(CandidateExtractor.extract(page)).build();
        }
    }
}
