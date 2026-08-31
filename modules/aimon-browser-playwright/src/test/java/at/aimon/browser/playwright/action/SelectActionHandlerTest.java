package at.aimon.browser.playwright.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.TimeoutError;

import at.aimon.browser.playwright.BrowserInput;
import at.aimon.browser.playwright.BrowserInputs;
import at.aimon.browser.playwright.BrowserSession;

class SelectActionHandlerTest {

    private final SelectActionHandler handler = new SelectActionHandler();

    @Test
    void shouldReturnSelectActionType() {
        assertThat(handler.getActionType()).isEqualTo("select");
    }

    @Test
    void shouldSelectOption() {
        Page page = mock(Page.class);
        Locator locator = mock(Locator.class);
        when(page.locator("#country")).thenReturn(locator);
        when(locator.selectOption("kr")).thenReturn(List.of("kr"));
        when(page.url()).thenReturn("https://example.com");
        when(page.title()).thenReturn("Test");
        when(page.evaluate(anyString())).thenReturn(Collections.emptyList());

        BrowserSession session = mock(BrowserSession.class);
        when(session.getId()).thenReturn("bs_test");
        when(session.getActivePage()).thenReturn(page);

        BrowserInput input = BrowserInputs.action("select").selector("#country").value("kr").build();
        BrowserActionResult result = handler.handle(input, session, 30000);

        assertThat(result.isError()).isFalse();
        assertThat(result.getAction()).isEqualTo("select");
        verify(locator).selectOption("kr");
    }

    @Test
    void shouldReturnErrorWhenElementNotFound() {
        Page page = mock(Page.class);
        Locator locator = mock(Locator.class);
        when(page.locator("#nonexistent")).thenReturn(locator);
        when(locator.selectOption("value")).thenThrow(new TimeoutError("Timeout 30000ms exceeded"));
        when(page.evaluate(anyString())).thenReturn(Collections.emptyList());

        BrowserSession session = mock(BrowserSession.class);
        when(session.getId()).thenReturn("bs_test");
        when(session.getActivePage()).thenReturn(page);

        BrowserInput input = BrowserInputs.action("select").selector("#nonexistent").value("value").build();
        BrowserActionResult result = handler.handle(input, session, 30000);

        assertThat(result.isError()).isTrue();
        assertThat(result.getError()).isEqualTo("ELEMENT_NOT_FOUND");
    }

    @Test
    void shouldHandleSelectFailure() {
        Page page = mock(Page.class);
        Locator locator = mock(Locator.class);
        when(page.locator("#select")).thenReturn(locator);
        when(locator.selectOption("value")).thenThrow(new RuntimeException("Not a select element"));

        BrowserSession session = mock(BrowserSession.class);
        when(session.getId()).thenReturn("bs_test");
        when(session.getActivePage()).thenReturn(page);

        BrowserInput input = BrowserInputs.action("select").selector("#select").value("value").build();
        BrowserActionResult result = handler.handle(input, session, 30000);

        assertThat(result.isError()).isTrue();
        assertThat(result.getError()).isEqualTo("SELECT_FAILED");
    }
}
