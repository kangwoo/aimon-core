package at.aimon.browser.playwright.action;

import com.microsoft.playwright.Page;

import at.aimon.browser.playwright.BrowserInput;
import at.aimon.browser.playwright.BrowserSession;
import at.aimon.browser.playwright.dom.CandidateExtractor;

/**
 * 페이지를 스크롤하는 액션 핸들러.
 */
public class ScrollActionHandler implements BrowserActionHandler {

    @Override
    public String getActionType() {
        return "scroll";
    }

    @Override
    public BrowserActionResult handle(BrowserInput input, BrowserSession session, int timeoutMs) {
        Page page = session.getActivePage();
        String direction = BrowserActionHandler.require(input.direction(), "direction");
        // Unreachable through the tool — direction declares an allowed set, so binding rejects anything else before
        // this runs. Kept because a handler is also callable directly, as its own tests do.
        if (!"up".equals(direction) && !"down".equals(direction)) {
            return BrowserActionResult.error(session.getId(), "scroll", "INVALID_PARAMETER",
                    "direction must be 'up' or 'down': " + direction);
        }
        int amount = input.amount() != null ? input.amount() : 500;

        int delta = "up".equals(direction) ? -amount : amount;

        try {
            page.mouse().wheel(0, delta);

            return BrowserActionResult.success(session.getId(), "scroll").url(page.url()).title(page.title())
                    .candidates(CandidateExtractor.extract(page)).build();
        } catch (Exception e) {
            return BrowserActionResult.error(session.getId(), "scroll", "SCROLL_FAILED",
                    "Failed to scroll: " + e.getMessage());
        }
    }
}
