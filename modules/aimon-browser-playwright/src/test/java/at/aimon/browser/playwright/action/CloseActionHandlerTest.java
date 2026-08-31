package at.aimon.browser.playwright.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import at.aimon.browser.playwright.BrowserInput;
import at.aimon.browser.playwright.BrowserInputs;
import at.aimon.browser.playwright.BrowserSession;
import at.aimon.browser.playwright.BrowserSessionStore;

class CloseActionHandlerTest {

    private final BrowserSessionStore sessionStore = mock(BrowserSessionStore.class);
    private final CloseActionHandler handler = new CloseActionHandler(sessionStore);

    @Test
    void shouldReturnCloseActionType() {
        assertThat(handler.getActionType()).isEqualTo("close");
    }

    @Test
    void shouldCloseSessionAndRemoveFromStore() {
        BrowserSession session = mock(BrowserSession.class);
        when(session.getId()).thenReturn("bs_test");

        BrowserInput input = BrowserInputs.action("close").build();
        BrowserActionResult result = handler.handle(input, session, 30000);

        assertThat(result.isError()).isFalse();
        assertThat(result.getMessage()).contains("Session closed: bs_test");
        verify(sessionStore).remove("bs_test");
    }
}
