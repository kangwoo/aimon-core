package at.aimon.browser.playwright.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;

import org.junit.jupiter.api.Test;

import com.microsoft.playwright.Page;

import at.aimon.browser.playwright.BrowserInput;
import at.aimon.browser.playwright.BrowserInputs;
import at.aimon.browser.playwright.BrowserSession;
import at.aimon.core.tools.web.security.SsrfGuard;

class OpenActionHandlerTest {

    private final SsrfGuard ssrfGuard = mock(SsrfGuard.class);
    private final OpenActionHandler handler = new OpenActionHandler(ssrfGuard);

    @Test
    void shouldReturnOpenActionType() {
        assertThat(handler.getActionType()).isEqualTo("open");
    }

    @Test
    void shouldBlockUnsafeUrl() {
        when(ssrfGuard.isSafe("http://169.254.169.254/latest/meta-data")).thenReturn(false);

        BrowserSession session = mock(BrowserSession.class);
        when(session.getId()).thenReturn("bs_test");

        BrowserInput input = BrowserInputs.action("open").url("http://169.254.169.254/latest/meta-data").build();
        BrowserActionResult result = handler.handle(input, session, 30000);

        assertThat(result.isError()).isTrue();
        assertThat(result.getError()).isEqualTo("SSRF_BLOCKED");
    }

    @Test
    void shouldBlockRedirectToUnsafeUrl() {
        when(ssrfGuard.isSafe("https://example.com")).thenReturn(true);
        when(ssrfGuard.isSafe("http://192.168.1.1")).thenReturn(false);

        Page page = mock(Page.class);
        when(page.url()).thenReturn("http://192.168.1.1");

        BrowserSession session = mock(BrowserSession.class);
        when(session.getId()).thenReturn("bs_test");
        when(session.getActivePage()).thenReturn(page);

        BrowserInput input = BrowserInputs.action("open").url("https://example.com").build();
        BrowserActionResult result = handler.handle(input, session, 30000);

        assertThat(result.isError()).isTrue();
        assertThat(result.getError()).isEqualTo("SSRF_BLOCKED");
        verify(page).navigate("about:blank");
    }

    @Test
    void shouldNavigateSuccessfully() {
        when(ssrfGuard.isSafe(anyString())).thenReturn(true);

        Page page = mock(Page.class);
        when(page.url()).thenReturn("https://example.com");
        when(page.title()).thenReturn("Example Domain");
        when(page.evaluate(anyString())).thenReturn(Collections.emptyList());

        BrowserSession session = mock(BrowserSession.class);
        when(session.getId()).thenReturn("bs_test");
        when(session.getActivePage()).thenReturn(page);

        BrowserInput input = BrowserInputs.action("open").url("https://example.com").build();
        BrowserActionResult result = handler.handle(input, session, 30000);

        assertThat(result.isError()).isFalse();
        assertThat(result.getUrl()).isEqualTo("https://example.com");
        assertThat(result.getTitle()).isEqualTo("Example Domain");
        verify(page).navigate(any(String.class), any(Page.NavigateOptions.class));
    }

    @Test
    void shouldThrowWhenUrlIsMissing() {
        // url is required for open and meaningless for the other thirteen actions, so the schema cannot mark it
        // required and binding cannot reject it. The handler does, by throwing what the tool turns into an error
        // result — returning BrowserActionResult.error here would instead be serialized into a *success*.
        BrowserSession session = mock(BrowserSession.class);

        BrowserInput input = BrowserInputs.action("open").build();

        assertThatThrownBy(() -> handler.handle(input, session, 30000)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Missing required parameter: url");
    }

    @Test
    void shouldHandleNavigationFailure() {
        when(ssrfGuard.isSafe(anyString())).thenReturn(true);

        Page page = mock(Page.class);
        when(page.navigate(any(String.class), any(Page.NavigateOptions.class)))
                .thenThrow(new RuntimeException("net::ERR_NAME_NOT_RESOLVED"));

        BrowserSession session = mock(BrowserSession.class);
        when(session.getId()).thenReturn("bs_test");
        when(session.getActivePage()).thenReturn(page);

        BrowserInput input = BrowserInputs.action("open").url("https://nonexistent.test").build();
        BrowserActionResult result = handler.handle(input, session, 30000);

        assertThat(result.isError()).isTrue();
        assertThat(result.getError()).isEqualTo("NAVIGATION_FAILED");
    }
}
