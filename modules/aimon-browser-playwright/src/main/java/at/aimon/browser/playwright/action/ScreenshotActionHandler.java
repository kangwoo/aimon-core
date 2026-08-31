package at.aimon.browser.playwright.action;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.microsoft.playwright.Page;

import at.aimon.browser.playwright.BrowserInput;
import at.aimon.browser.playwright.BrowserSession;
import at.aimon.core.filesystem.VirtualFileSystem;

/**
 * 스크린샷을 캡처하는 액션 핸들러.
 *
 * <p>
 * {@code save_path}가 제공되면 {@link VirtualFileSystem}을 통해 파일로 저장하고,
 * 제공되지 않으면 base64 문자열로 반환한다.
 *
 * <p>
 * resource_policy가 "minimal"인 세션에서는 경고를 포함한다.
 */
public class ScreenshotActionHandler implements BrowserActionHandler {

    private static final Logger log = LoggerFactory.getLogger(ScreenshotActionHandler.class);

    private final VirtualFileSystem fileSystem;

    /**
     * ScreenshotActionHandler를 생성한다.
     *
     * @param fileSystem
     *            파일 저장용 VirtualFileSystem (null 허용 — null이면 save_path 사용 불가)
     */
    public ScreenshotActionHandler(VirtualFileSystem fileSystem) {
        this.fileSystem = fileSystem;
    }

    @Override
    public String getActionType() {
        return "screenshot";
    }

    @Override
    public BrowserActionResult handle(BrowserInput input, BrowserSession session, int timeoutMs) {
        Page page = session.getActivePage();
        boolean fullPage = Boolean.TRUE.equals(input.fullPage());
        String selector = input.selector();
        String savePath = input.savePath();

        // resource_policy가 "minimal"인 세션에서 스크린샷 시 경고
        List<String> warnings = new ArrayList<>();
        if ("minimal".equals(session.getResourcePolicy())) {
            warnings.add("Session uses 'minimal' resource policy. " + "Images and stylesheets are blocked. "
                    + "For accurate screenshots, create a session with resource_policy='visual'.");
        }

        // save_path가 제공되었으나 VFS가 없으면 에러
        if (savePath != null && fileSystem == null) {
            return BrowserActionResult.error(session.getId(), "screenshot", "FILE_SYSTEM_UNAVAILABLE",
                    "Cannot save screenshot: file system is not available. Remove save_path to get base64 instead.");
        }

        try {
            byte[] bytes;
            if (selector != null) {
                bytes = page.locator(selector).screenshot();
            } else {
                bytes = page.screenshot(new Page.ScreenshotOptions().setFullPage(fullPage));
            }

            if (savePath != null) {
                try {
                    fileSystem.write(savePath, bytes);
                } catch (Exception ex) {
                    return BrowserActionResult.error(session.getId(), "screenshot", "SCREENSHOT_SAVE_FAILED",
                            "Screenshot captured but failed to save to " + savePath + ": " + ex.getMessage());
                }
                log.debug("Screenshot saved to: {}", savePath);

                return BrowserActionResult.success(session.getId(), "screenshot").url(page.url()).title(page.title())
                        .filePath(savePath).warnings(warnings).build();
            }

            String base64 = Base64.getEncoder().encodeToString(bytes);

            return BrowserActionResult.success(session.getId(), "screenshot").url(page.url()).title(page.title())
                    .screenshot(base64).warnings(warnings).build();
        } catch (Exception e) {
            return BrowserActionResult.error(session.getId(), "screenshot", "SCREENSHOT_FAILED",
                    "Failed to capture screenshot: " + e.getMessage());
        }
    }
}
