package at.aimon.browser.playwright.action;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.AriaRole;

import at.aimon.browser.playwright.BrowserInput;
import at.aimon.browser.playwright.BrowserSession;
import at.aimon.browser.playwright.dom.CandidateExtractor;

/**
 * 요소를 클릭하는 액션 핸들러.
 *
 * <p>
 * Locator 결정 우선순위: selector &gt; role+name &gt; text
 */
public class ClickActionHandler implements BrowserActionHandler {

    @Override
    public String getActionType() {
        return "click";
    }

    @Override
    public BrowserActionResult handle(BrowserInput input, BrowserSession session, int timeoutMs) {
        Page page = session.getActivePage();
        String selector = input.selector();
        String text = input.text();
        String role = input.role();
        boolean exact = Boolean.TRUE.equals(input.exact());

        try {
            Locator locator = resolveLocator(page, selector, text, role, exact);
            locator.click(new Locator.ClickOptions().setTimeout(timeoutMs));

            return BrowserActionResult.success(session.getId(), "click").url(page.url()).title(page.title())
                    .candidates(CandidateExtractor.extract(page)).build();

        } catch (TimeoutError e) {
            return BrowserActionResult.builder().sessionId(session.getId()).action("click").error("ELEMENT_NOT_FOUND")
                    .message("No element found for the given criteria").candidates(CandidateExtractor.extract(page))
                    .build();
        } catch (IllegalArgumentException e) {
            return BrowserActionResult.error(session.getId(), "click", "INVALID_PARAMETER", e.getMessage());
        }
    }

    /**
     * selector / text / role 기반으로 Locator를 결정한다.
     */
    static Locator resolveLocator(Page page, String selector, String text, String role, boolean exact) {
        if (selector != null) {
            return page.locator(selector).first();
        }
        if (role != null && text != null) {
            return page.getByRole(AriaRole.valueOf(role.toUpperCase()),
                    new Page.GetByRoleOptions().setName(text).setExact(exact));
        }
        if (text != null) {
            return page.getByText(text, new Page.GetByTextOptions().setExact(exact));
        }
        throw new IllegalArgumentException("click requires at least one of: selector, text, role");
    }
}
