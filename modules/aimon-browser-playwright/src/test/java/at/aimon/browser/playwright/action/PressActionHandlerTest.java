package at.aimon.browser.playwright.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;

import org.junit.jupiter.api.Test;

import com.microsoft.playwright.Keyboard;
import com.microsoft.playwright.Page;

import at.aimon.browser.playwright.BrowserInput;
import at.aimon.browser.playwright.BrowserInputs;
import at.aimon.browser.playwright.BrowserSession;

class PressActionHandlerTest {

    private final PressActionHandler handler = new PressActionHandler();

    @Test
    void shouldReturnPressActionType() {
        assertThat(handler.getActionType()).isEqualTo("press");
    }

    @Test
    void shouldPressKey() {
        Page page = mock(Page.class);
        Keyboard keyboard = mock(Keyboard.class);
        when(page.keyboard()).thenReturn(keyboard);
        when(page.url()).thenReturn("https://example.com");
        when(page.title()).thenReturn("Test");
        when(page.evaluate(anyString())).thenReturn(Collections.emptyList());

        BrowserSession session = mock(BrowserSession.class);
        when(session.getId()).thenReturn("bs_test");
        when(session.getActivePage()).thenReturn(page);

        BrowserInput input = BrowserInputs.action("press").key("Enter").build();
        BrowserActionResult result = handler.handle(input, session, 30000);

        assertThat(result.isError()).isFalse();
        assertThat(result.getAction()).isEqualTo("press");
        verify(keyboard).press("Enter");
    }

    @Test
    void shouldThrowWhenKeyMissing() {
        Page page = mock(Page.class);

        BrowserSession session = mock(BrowserSession.class);
        when(session.getId()).thenReturn("bs_test");
        when(session.getActivePage()).thenReturn(page);

        BrowserInput input = BrowserInputs.action("press").build();

        // getRequiredString("key") throws IllegalArgumentException before try block
        assertThatThrownBy(() -> handler.handle(input, session, 30000)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldHandlePressFailure() {
        Page page = mock(Page.class);
        Keyboard keyboard = mock(Keyboard.class);
        when(page.keyboard()).thenReturn(keyboard);
        doThrow(new RuntimeException("Invalid key")).when(keyboard).press("InvalidKey");

        BrowserSession session = mock(BrowserSession.class);
        when(session.getId()).thenReturn("bs_test");
        when(session.getActivePage()).thenReturn(page);

        BrowserInput input = BrowserInputs.action("press").key("InvalidKey").build();
        BrowserActionResult result = handler.handle(input, session, 30000);

        assertThat(result.isError()).isTrue();
        assertThat(result.getError()).isEqualTo("PRESS_FAILED");
    }
}
