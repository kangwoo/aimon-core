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

class ClickActionHandlerTest {

    private final ClickActionHandler handler = new ClickActionHandler();

    @Test
    void shouldReturnClickActionType() {
        assertThat(handler.getActionType()).isEqualTo("click");
    }

    @Test
    void shouldClickBySelector() {
        Page page = mock(Page.class);
        Locator locator = mock(Locator.class);
        Locator firstLocator = mock(Locator.class);
        when(page.locator("#submit")).thenReturn(locator);
        when(locator.first()).thenReturn(firstLocator);
        when(page.url()).thenReturn("https://example.com");
        when(page.title()).thenReturn("Test");
        when(page.evaluate(anyString())).thenReturn(Collections.emptyList());

        BrowserSession session = mock(BrowserSession.class);
        when(session.getId()).thenReturn("bs_test");
        when(session.getActivePage()).thenReturn(page);

        BrowserInput input = BrowserInputs.action("click").selector("#submit").build();
        BrowserActionResult result = handler.handle(input, session, 30000);

        assertThat(result.isError()).isFalse();
        assertThat(result.getAction()).isEqualTo("click");
        verify(firstLocator).click(any(Locator.ClickOptions.class));
    }

    @Test
    void shouldReturnErrorWhenElementNotFound() {
        Page page = mock(Page.class);
        Locator locator = mock(Locator.class);
        Locator firstLocator = mock(Locator.class);
        when(page.locator("#nonexistent")).thenReturn(locator);
        when(locator.first()).thenReturn(firstLocator);
        doThrow(new TimeoutError("Timeout 30000ms exceeded")).when(firstLocator).click(any(Locator.ClickOptions.class));
        when(page.evaluate(anyString())).thenReturn(Collections.emptyList());

        BrowserSession session = mock(BrowserSession.class);
        when(session.getId()).thenReturn("bs_test");
        when(session.getActivePage()).thenReturn(page);

        BrowserInput input = BrowserInputs.action("click").selector("#nonexistent").build();
        BrowserActionResult result = handler.handle(input, session, 30000);

        assertThat(result.getError()).isEqualTo("ELEMENT_NOT_FOUND");
    }

    @Test
    void shouldReturnErrorWhenNoCriteriaProvided() {
        Page page = mock(Page.class);

        BrowserSession session = mock(BrowserSession.class);
        when(session.getId()).thenReturn("bs_test");
        when(session.getActivePage()).thenReturn(page);

        BrowserInput input = BrowserInputs.action("click").build();
        BrowserActionResult result = handler.handle(input, session, 30000);

        assertThat(result.isError()).isTrue();
        assertThat(result.getError()).isEqualTo("INVALID_PARAMETER");
        assertThat(result.getMessage()).contains("click requires at least one of");
    }

    @Test
    void shouldClickByText() {
        Page page = mock(Page.class);
        Locator locator = mock(Locator.class);
        when(page.getByText(anyString(), any(Page.GetByTextOptions.class))).thenReturn(locator);
        when(page.url()).thenReturn("https://example.com");
        when(page.title()).thenReturn("Test");
        when(page.evaluate(anyString())).thenReturn(Collections.emptyList());

        BrowserSession session = mock(BrowserSession.class);
        when(session.getId()).thenReturn("bs_test");
        when(session.getActivePage()).thenReturn(page);

        BrowserInput input = BrowserInputs.action("click").text("Sign in").build();
        BrowserActionResult result = handler.handle(input, session, 30000);

        assertThat(result.isError()).isFalse();
        verify(locator).click(any(Locator.ClickOptions.class));
    }
}
