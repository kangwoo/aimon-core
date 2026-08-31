package at.aimon.browser.playwright.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;

import org.junit.jupiter.api.Test;

import com.microsoft.playwright.Mouse;
import com.microsoft.playwright.Page;

import at.aimon.browser.playwright.BrowserInput;
import at.aimon.browser.playwright.BrowserInputs;
import at.aimon.browser.playwright.BrowserSession;

class ScrollActionHandlerTest {

    private final ScrollActionHandler handler = new ScrollActionHandler();

    @Test
    void shouldReturnScrollActionType() {
        assertThat(handler.getActionType()).isEqualTo("scroll");
    }

    @Test
    void shouldScrollDown() {
        Page page = mock(Page.class);
        Mouse mouse = mock(Mouse.class);
        when(page.mouse()).thenReturn(mouse);
        when(page.url()).thenReturn("https://example.com");
        when(page.title()).thenReturn("Test");
        when(page.evaluate(anyString())).thenReturn(Collections.emptyList());

        BrowserSession session = mock(BrowserSession.class);
        when(session.getId()).thenReturn("bs_test");
        when(session.getActivePage()).thenReturn(page);

        BrowserInput input = BrowserInputs.action("scroll").direction("down").amount(300).build();
        BrowserActionResult result = handler.handle(input, session, 30000);

        assertThat(result.isError()).isFalse();
        verify(mouse).wheel(eq(0.0), eq(300.0));
    }

    @Test
    void shouldScrollUp() {
        Page page = mock(Page.class);
        Mouse mouse = mock(Mouse.class);
        when(page.mouse()).thenReturn(mouse);
        when(page.url()).thenReturn("https://example.com");
        when(page.title()).thenReturn("Test");
        when(page.evaluate(anyString())).thenReturn(Collections.emptyList());

        BrowserSession session = mock(BrowserSession.class);
        when(session.getId()).thenReturn("bs_test");
        when(session.getActivePage()).thenReturn(page);

        BrowserInput input = BrowserInputs.action("scroll").direction("up").build();
        BrowserActionResult result = handler.handle(input, session, 30000);

        assertThat(result.isError()).isFalse();
        verify(mouse).wheel(eq(0.0), eq(-500.0));
    }

    @Test
    void shouldRejectInvalidDirection() {
        Page page = mock(Page.class);

        BrowserSession session = mock(BrowserSession.class);
        when(session.getId()).thenReturn("bs_test");
        when(session.getActivePage()).thenReturn(page);

        BrowserInput input = BrowserInputs.action("scroll").direction("left").build();
        BrowserActionResult result = handler.handle(input, session, 30000);

        assertThat(result.isError()).isTrue();
        assertThat(result.getError()).isEqualTo("INVALID_PARAMETER");
        assertThat(result.getMessage()).contains("direction must be 'up' or 'down'");
    }

    @Test
    void shouldHandleScrollFailure() {
        Page page = mock(Page.class);
        Mouse mouse = mock(Mouse.class);
        when(page.mouse()).thenReturn(mouse);
        doThrow(new RuntimeException("Scroll failed")).when(mouse).wheel(eq(0.0), eq(500.0));

        BrowserSession session = mock(BrowserSession.class);
        when(session.getId()).thenReturn("bs_test");
        when(session.getActivePage()).thenReturn(page);

        BrowserInput input = BrowserInputs.action("scroll").direction("down").build();
        BrowserActionResult result = handler.handle(input, session, 30000);

        assertThat(result.isError()).isTrue();
        assertThat(result.getError()).isEqualTo("SCROLL_FAILED");
    }
}
