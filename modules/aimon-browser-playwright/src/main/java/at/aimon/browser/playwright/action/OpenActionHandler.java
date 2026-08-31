package at.aimon.browser.playwright.action;

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitUntilState;

import at.aimon.browser.playwright.BrowserInput;
import at.aimon.browser.playwright.BrowserSession;
import at.aimon.browser.playwright.dom.CandidateExtractor;
import at.aimon.core.tools.web.security.SsrfGuard;

/**
 * URL을 열어 페이지를 네비게이션하는 액션 핸들러.
 *
 * <p>
 * SSRF 사전/사후 검증을 수행한다.
 */
public class OpenActionHandler implements BrowserActionHandler {

    private static final Logger log = LoggerFactory.getLogger(OpenActionHandler.class);

    private final SsrfGuard ssrfGuard;

    /**
     * OpenActionHandler를 생성한다.
     *
     * @param ssrfGuard
     *            SSRF 정책 검증기
     */
    public OpenActionHandler(SsrfGuard ssrfGuard) {
        this.ssrfGuard = Objects.requireNonNull(ssrfGuard, "ssrfGuard cannot be null");
    }

    @Override
    public String getActionType() {
        return "open";
    }

    @Override
    public BrowserActionResult handle(BrowserInput input, BrowserSession session, int timeoutMs) {
        String url = BrowserActionHandler.require(input.url(), "url");
        String waitUntil = input.waitUntil() != null ? input.waitUntil() : "domcontentloaded";

        // SSRF 사전 검증
        if (!ssrfGuard.isSafe(url)) {
            return BrowserActionResult.error(session.getId(), "open", "SSRF_BLOCKED",
                    "URL blocked by security policy: " + url);
        }

        Page page = session.getActivePage();

        try {
            page.navigate(url,
                    new Page.NavigateOptions().setWaitUntil(parseWaitUntil(waitUntil)).setTimeout(timeoutMs));
        } catch (Exception e) {
            log.warn("Navigation failed for URL {}: {}", url, e.getMessage());
            return BrowserActionResult.error(session.getId(), "open", "NAVIGATION_FAILED",
                    "Navigation failed: " + e.getMessage());
        }

        // redirect 후 최종 URL 재검증
        String finalUrl = page.url();
        if (!ssrfGuard.isSafe(finalUrl)) {
            log.warn("SSRF blocked after redirect: {} -> {}", url, finalUrl);
            page.navigate("about:blank");
            return BrowserActionResult.error(session.getId(), "open", "SSRF_BLOCKED",
                    "Redirect target blocked by security policy: " + finalUrl);
        }

        return BrowserActionResult.success(session.getId(), "open").url(page.url()).title(page.title())
                .candidates(CandidateExtractor.extract(page)).build();
    }

    private static WaitUntilState parseWaitUntil(String waitUntil) {
        return switch (waitUntil.toLowerCase()) {
            case "load" -> WaitUntilState.LOAD;
            case "networkidle" -> WaitUntilState.NETWORKIDLE;
            default -> WaitUntilState.DOMCONTENTLOADED;
        };
    }
}
