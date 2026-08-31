package at.aimon.browser.playwright.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;

import org.junit.jupiter.api.Test;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.TimeoutError;

import at.aimon.browser.playwright.BrowserInput;
import at.aimon.browser.playwright.BrowserInputs;
import at.aimon.browser.playwright.BrowserSession;

class WaitActionHandlerTest {

    private final WaitActionHandler handler = new WaitActionHandler();

    @Test
    void shouldReturnWaitActionType() {
        assertThat(handler.getActionType()).isEqualTo("wait");
    }

    @Test
    void shouldWaitForTimeout() {
        Page page = mock(Page.class);
        when(page.url()).thenReturn("https://example.com");
        when(page.title()).thenReturn("Test");
        when(page.evaluate(anyString())).thenReturn(Collections.emptyList());

        BrowserSession session = mock(BrowserSession.class);
        when(session.getId()).thenReturn("bs_test");
        when(session.getActivePage()).thenReturn(page);

        BrowserInput input = BrowserInputs.action("wait").waitMs(500).build();
        BrowserActionResult result = handler.handle(input, session, 30000);

        assertThat(result.isError()).isFalse();
        assertThat(result.getAction()).isEqualTo("wait");
        verify(page).waitForTimeout(500);
    }

    @Test
    void shouldWaitForSelector() {
        Page page = mock(Page.class);
        Locator locator = mock(Locator.class);
        when(page.locator("#loading")).thenReturn(locator);
        when(page.url()).thenReturn("https://example.com");
        when(page.title()).thenReturn("Test");
        when(page.evaluate(anyString())).thenReturn(Collections.emptyList());

        BrowserSession session = mock(BrowserSession.class);
        when(session.getId()).thenReturn("bs_test");
        when(session.getActivePage()).thenReturn(page);

        BrowserInput input = BrowserInputs.action("wait").selector("#loading").waitMs(2000).build();
        BrowserActionResult result = handler.handle(input, session, 30000);

        assertThat(result.isError()).isFalse();
        verify(locator).waitFor(any(Locator.WaitForOptions.class));
    }

    @Test
    void shouldReturnTimeoutErrorWhenSelectorNotFound() {
        Page page = mock(Page.class);
        Locator locator = mock(Locator.class);
        when(page.locator("#never-appears")).thenReturn(locator);
        doThrow(new TimeoutError("Timeout 1000ms exceeded")).when(locator).waitFor(any(Locator.WaitForOptions.class));
        when(page.evaluate(anyString())).thenReturn(Collections.emptyList());

        BrowserSession session = mock(BrowserSession.class);
        when(session.getId()).thenReturn("bs_test");
        when(session.getActivePage()).thenReturn(page);

        BrowserInput input = BrowserInputs.action("wait").selector("#never-appears").build();
        BrowserActionResult result = handler.handle(input, session, 30000);

        assertThat(result.isError()).isTrue();
        assertThat(result.getError()).isEqualTo("TIMEOUT");
        assertThat(result.getMessage()).contains("#never-appears");
    }

    @Test
    void shouldCapWaitMsToTimeout() {
        Page page = mock(Page.class);
        when(page.url()).thenReturn("https://example.com");
        when(page.title()).thenReturn("Test");
        when(page.evaluate(anyString())).thenReturn(Collections.emptyList());

        BrowserSession session = mock(BrowserSession.class);
        when(session.getId()).thenReturn("bs_test");
        when(session.getActivePage()).thenReturn(page);

        // wait_ms (60000) > timeoutMs (5000) → capped to 5000
        BrowserInput input = BrowserInputs.action("wait").waitMs(60000).build();
        BrowserActionResult result = handler.handle(input, session, 5000);

        assertThat(result.isError()).isFalse();
        verify(page).waitForTimeout(5000);
    }
}
