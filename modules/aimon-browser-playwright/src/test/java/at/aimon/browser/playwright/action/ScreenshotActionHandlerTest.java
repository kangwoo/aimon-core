package at.aimon.browser.playwright.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import com.microsoft.playwright.Page;

import at.aimon.browser.playwright.BrowserInput;
import at.aimon.browser.playwright.BrowserInputs;
import at.aimon.browser.playwright.BrowserSession;
import at.aimon.core.filesystem.VirtualFileSystem;

class ScreenshotActionHandlerTest {

    private final ScreenshotActionHandler handler = new ScreenshotActionHandler(null);

    @Test
    void shouldReturnScreenshotActionType() {
        assertThat(handler.getActionType()).isEqualTo("screenshot");
    }

    @Test
    void shouldCaptureScreenshot() {
        Page page = mock(Page.class);
        byte[] pngBytes = new byte[]{0x50, 0x4E, 0x47};
        when(page.screenshot(any(Page.ScreenshotOptions.class))).thenReturn(pngBytes);
        when(page.url()).thenReturn("https://example.com");
        when(page.title()).thenReturn("Test");

        BrowserSession session = mock(BrowserSession.class);
        when(session.getId()).thenReturn("bs_test");
        when(session.getActivePage()).thenReturn(page);
        when(session.getResourcePolicy()).thenReturn("visual");

        BrowserInput input = BrowserInputs.action("screenshot").build();
        BrowserActionResult result = handler.handle(input, session, 30000);

        assertThat(result.isError()).isFalse();
        assertThat(result.getScreenshot()).isNotNull();
        assertThat(result.getScreenshot()).isNotEmpty();
        assertThat(result.getFilePath()).isNull();
        assertThat(result.getWarnings()).isNull();
    }

    @Test
    void shouldIncludeWarningForMinimalPolicy() {
        Page page = mock(Page.class);
        byte[] pngBytes = new byte[]{0x50, 0x4E, 0x47};
        when(page.screenshot(any(Page.ScreenshotOptions.class))).thenReturn(pngBytes);
        when(page.url()).thenReturn("https://example.com");
        when(page.title()).thenReturn("Test");

        BrowserSession session = mock(BrowserSession.class);
        when(session.getId()).thenReturn("bs_test");
        when(session.getActivePage()).thenReturn(page);
        when(session.getResourcePolicy()).thenReturn("minimal");

        BrowserInput input = BrowserInputs.action("screenshot").build();
        BrowserActionResult result = handler.handle(input, session, 30000);

        assertThat(result.isError()).isFalse();
        assertThat(result.getWarnings()).isNotNull();
        assertThat(result.getWarnings()).hasSize(1);
        assertThat(result.getWarnings().get(0)).contains("minimal");
    }

    @Test
    void shouldHandleScreenshotFailure() {
        Page page = mock(Page.class);
        when(page.screenshot(any(Page.ScreenshotOptions.class))).thenThrow(new RuntimeException("Page crashed"));

        BrowserSession session = mock(BrowserSession.class);
        when(session.getId()).thenReturn("bs_test");
        when(session.getActivePage()).thenReturn(page);
        when(session.getResourcePolicy()).thenReturn("visual");

        BrowserInput input = BrowserInputs.action("screenshot").build();
        BrowserActionResult result = handler.handle(input, session, 30000);

        assertThat(result.isError()).isTrue();
        assertThat(result.getError()).isEqualTo("SCREENSHOT_FAILED");
    }

    @Test
    void shouldSaveScreenshotToFileWhenSavePathProvided() {
        VirtualFileSystem vfs = mock(VirtualFileSystem.class);
        ScreenshotActionHandler handlerWithVfs = new ScreenshotActionHandler(vfs);

        Page page = mock(Page.class);
        byte[] pngBytes = new byte[]{0x50, 0x4E, 0x47};
        when(page.screenshot(any(Page.ScreenshotOptions.class))).thenReturn(pngBytes);
        when(page.url()).thenReturn("https://example.com");
        when(page.title()).thenReturn("Test");

        BrowserSession session = mock(BrowserSession.class);
        when(session.getId()).thenReturn("bs_test");
        when(session.getActivePage()).thenReturn(page);
        when(session.getResourcePolicy()).thenReturn("visual");

        BrowserInput input = BrowserInputs.action("screenshot").savePath("/screenshots/page.png").build();
        BrowserActionResult result = handlerWithVfs.handle(input, session, 30000);

        assertThat(result.isError()).isFalse();
        assertThat(result.getFilePath()).isEqualTo("/screenshots/page.png");
        assertThat(result.getScreenshot()).isNull();
        verify(vfs).write(eq("/screenshots/page.png"), eq(pngBytes));
    }

    @Test
    void shouldReturnSaveFailedErrorWhenVfsWriteThrows() {
        VirtualFileSystem vfs = mock(VirtualFileSystem.class);
        doThrow(new RuntimeException("Disk full")).when(vfs).write(eq("/screenshots/page.png"), any(byte[].class));
        ScreenshotActionHandler handlerWithVfs = new ScreenshotActionHandler(vfs);

        Page page = mock(Page.class);
        byte[] pngBytes = new byte[]{0x50, 0x4E, 0x47};
        when(page.screenshot(any(Page.ScreenshotOptions.class))).thenReturn(pngBytes);
        when(page.url()).thenReturn("https://example.com");
        when(page.title()).thenReturn("Test");

        BrowserSession session = mock(BrowserSession.class);
        when(session.getId()).thenReturn("bs_test");
        when(session.getActivePage()).thenReturn(page);
        when(session.getResourcePolicy()).thenReturn("visual");

        BrowserInput input = BrowserInputs.action("screenshot").savePath("/screenshots/page.png").build();
        BrowserActionResult result = handlerWithVfs.handle(input, session, 30000);

        assertThat(result.isError()).isTrue();
        assertThat(result.getError()).isEqualTo("SCREENSHOT_SAVE_FAILED");
        assertThat(result.getMessage()).contains("Disk full");
    }

    @Test
    void shouldReturnErrorWhenSavePathProvidedButFileSystemIsNull() {
        Page page = mock(Page.class);

        BrowserSession session = mock(BrowserSession.class);
        when(session.getId()).thenReturn("bs_test");
        when(session.getActivePage()).thenReturn(page);
        when(session.getResourcePolicy()).thenReturn("visual");

        BrowserInput input = BrowserInputs.action("screenshot").savePath("/screenshots/page.png").build();
        BrowserActionResult result = handler.handle(input, session, 30000);

        assertThat(result.isError()).isTrue();
        assertThat(result.getError()).isEqualTo("FILE_SYSTEM_UNAVAILABLE");
        assertThat(result.getMessage()).contains("file system is not available");
    }
}
