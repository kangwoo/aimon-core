package at.aimon.browser.playwright.action;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.TimeoutError;

import at.aimon.browser.playwright.BrowserInput;
import at.aimon.browser.playwright.BrowserSession;
import at.aimon.browser.playwright.dom.CandidateExtractor;

/**
 * select 요소에서 옵션을 선택하는 액션 핸들러.
 */
public class SelectActionHandler implements BrowserActionHandler {

    @Override
    public String getActionType() {
        return "select";
    }

    @Override
    public BrowserActionResult handle(BrowserInput input, BrowserSession session, int timeoutMs) {
        Page page = session.getActivePage();
        String selector = BrowserActionHandler.require(input.selector(), "selector");
        String value = BrowserActionHandler.require(input.value(), "value");

        try {
            page.locator(selector).selectOption(value);

            return BrowserActionResult.success(session.getId(), "select").url(page.url()).title(page.title())
                    .candidates(CandidateExtractor.extract(page)).build();

        } catch (TimeoutError e) {
            return BrowserActionResult.builder().sessionId(session.getId()).action("select").error("ELEMENT_NOT_FOUND")
                    .message("No element matching selector: " + selector).candidates(CandidateExtractor.extract(page))
                    .build();
        } catch (Exception e) {
            return BrowserActionResult.error(session.getId(), "select", "SELECT_FAILED",
                    "Failed to select option: " + e.getMessage());
        }
    }
}
