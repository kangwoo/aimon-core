package at.aimon.browser.playwright.action;

import java.util.Objects;

import com.microsoft.playwright.Page;

import at.aimon.browser.playwright.BrowserInput;
import at.aimon.browser.playwright.BrowserSession;
import at.aimon.core.tools.web.fetch.ContentExtractor;

/**
 * 페이지 콘텐츠를 추출하는 액션 핸들러.
 *
 * <p>
 * text, html, markdown 세 가지 모드를 지원한다.
 * markdown 모드는 aimon-core의 {@link ContentExtractor}를 재사용한다.
 */
public class ExtractActionHandler implements BrowserActionHandler {

    private final ContentExtractor contentExtractor;

    /**
     * ExtractActionHandler를 생성한다.
     *
     * @param contentExtractor
     *            aimon-core의 ContentExtractor 재사용
     */
    public ExtractActionHandler(ContentExtractor contentExtractor) {
        this.contentExtractor = Objects.requireNonNull(contentExtractor, "contentExtractor cannot be null");
    }

    @Override
    public String getActionType() {
        return "extract";
    }

    @Override
    public BrowserActionResult handle(BrowserInput input, BrowserSession session, int timeoutMs) {
        Page page = session.getActivePage();
        String mode = input.mode() != null ? input.mode() : "text";
        int maxChars = input.maxChars() != null ? input.maxChars() : 50000;
        String selector = input.selector();

        try {
            String extracted;
            switch (mode) {
                case "text" -> {
                    extracted = (selector != null) ? page.locator(selector).innerText() : page.innerText("body");
                }
                case "html" -> {
                    extracted = (selector != null) ? page.locator(selector).innerHTML() : page.content();
                }
                case "markdown" -> {
                    String html = (selector != null) ? page.locator(selector).innerHTML() : page.content();
                    extracted = contentExtractor.extract(html, page.url(), "markdown");
                }
                // Unreachable through the tool — mode declares an allowed set, so binding rejects anything else
                // before this runs. Kept because a handler is also callable directly, as its own tests do.
                default -> {
                    return BrowserActionResult.error(session.getId(), "extract", "INVALID_MODE",
                            "Unknown extract mode: " + mode);
                }
            }

            // 결과 절삭
            if (extracted != null && extracted.length() > maxChars) {
                extracted = extracted.substring(0, maxChars);
            }

            return BrowserActionResult.success(session.getId(), "extract").url(page.url()).title(page.title())
                    .content(extracted).build();
        } catch (Exception e) {
            return BrowserActionResult.error(session.getId(), "extract", "EXTRACT_FAILED",
                    "Failed to extract content: " + e.getMessage());
        }
    }
}
