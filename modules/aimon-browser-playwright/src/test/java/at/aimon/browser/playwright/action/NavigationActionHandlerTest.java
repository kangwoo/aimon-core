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
import com.microsoft.playwright.Response;

import at.aimon.browser.playwright.BrowserInputs;
import at.aimon.browser.playwright.BrowserSession;

class NavigationActionHandlerTest {

    @Test
    void shouldHandleBackAction() {
        NavigationActionHandler handler = new NavigationActionHandler("back");
        assertThat(handler.getActionType()).isEqualTo("back");

        Page page = mock(Page.class);
        when(page.goBack(any(Page.GoBackOptions.class))).thenReturn(mock(Response.class));
        when(page.url()).thenReturn("https://example.com");
        when(page.title()).thenReturn("Test");
        when(page.evaluate(anyString())).thenReturn(Collections.emptyList());

        BrowserSession session = mock(BrowserSession.class);
        when(session.getId()).thenReturn("bs_test");
        when(session.getActivePage()).thenReturn(page);

        BrowserActionResult result = handler.handle(BrowserInputs.action("back").build(), session, 30000);

        assertThat(result.isError()).isFalse();
        verify(page).goBack(any(Page.GoBackOptions.class));
    }

    @Test
    void shouldHandleForwardAction() {
        NavigationActionHandler handler = new NavigationActionHandler("forward");
        assertThat(handler.getActionType()).isEqualTo("forward");

        Page page = mock(Page.class);
        when(page.goForward(any(Page.GoForwardOptions.class))).thenReturn(mock(Response.class));
        when(page.url()).thenReturn("https://example.com");
        when(page.title()).thenReturn("Test");
        when(page.evaluate(anyString())).thenReturn(Collections.emptyList());

        BrowserSession session = mock(BrowserSession.class);
        when(session.getId()).thenReturn("bs_test");
        when(session.getActivePage()).thenReturn(page);

        BrowserActionResult result = handler.handle(BrowserInputs.action("forward").build(), session, 30000);

        assertThat(result.isError()).isFalse();
        verify(page).goForward(any(Page.GoForwardOptions.class));
    }

    @Test
    void shouldHandleReloadAction() {
        NavigationActionHandler handler = new NavigationActionHandler("reload");
        assertThat(handler.getActionType()).isEqualTo("reload");

        Page page = mock(Page.class);
        when(page.reload(any(Page.ReloadOptions.class))).thenReturn(mock(Response.class));
        when(page.url()).thenReturn("https://example.com");
        when(page.title()).thenReturn("Test");
        when(page.evaluate(anyString())).thenReturn(Collections.emptyList());

        BrowserSession session = mock(BrowserSession.class);
        when(session.getId()).thenReturn("bs_test");
        when(session.getActivePage()).thenReturn(page);

        BrowserActionResult result = handler.handle(BrowserInputs.action("reload").build(), session, 30000);

        assertThat(result.isError()).isFalse();
        verify(page).reload(any(Page.ReloadOptions.class));
    }

    @Test
    void shouldRejectInvalidActionType() {
        assertThatThrownBy(() -> new NavigationActionHandler("invalid")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("actionType must be back, forward, or reload");
    }

    @Test
    void shouldHandleNavigationError() {
        NavigationActionHandler handler = new NavigationActionHandler("back");

        Page page = mock(Page.class);
        when(page.goBack(any(Page.GoBackOptions.class))).thenThrow(new RuntimeException("Navigation failed"));

        BrowserSession session = mock(BrowserSession.class);
        when(session.getId()).thenReturn("bs_test");
        when(session.getActivePage()).thenReturn(page);

        BrowserActionResult result = handler.handle(BrowserInputs.action("back").build(), session, 30000);

        assertThat(result.isError()).isTrue();
        assertThat(result.getError()).isEqualTo("NAVIGATION_FAILED");
    }
}
