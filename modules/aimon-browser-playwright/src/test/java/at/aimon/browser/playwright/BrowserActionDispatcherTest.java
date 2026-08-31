package at.aimon.browser.playwright;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import at.aimon.browser.playwright.action.BrowserActionHandler;
import at.aimon.browser.playwright.action.BrowserActionResult;

class BrowserActionDispatcherTest {

    @Test
    void shouldDispatchToCorrectHandler() {
        BrowserActionHandler openHandler = mock(BrowserActionHandler.class);
        when(openHandler.getActionType()).thenReturn("open");

        BrowserSession session = mock(BrowserSession.class);
        when(session.getId()).thenReturn("bs_test");

        BrowserActionResult expectedResult = BrowserActionResult.success("bs_test", "open").build();
        when(openHandler.handle(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt())).thenReturn(expectedResult);

        BrowserActionDispatcher dispatcher = new BrowserActionDispatcher(List.of(openHandler));
        BrowserActionResult result = dispatcher.dispatch("open", BrowserInputs.action("open").build(), session, 30000);

        assertThat(result.getSessionId()).isEqualTo("bs_test");
        assertThat(result.getAction()).isEqualTo("open");
    }

    @Test
    void shouldReturnUnknownActionErrorForUnregisteredAction() {
        BrowserActionHandler openHandler = mock(BrowserActionHandler.class);
        when(openHandler.getActionType()).thenReturn("open");

        BrowserSession session = mock(BrowserSession.class);
        when(session.getId()).thenReturn("bs_test");

        BrowserActionDispatcher dispatcher = new BrowserActionDispatcher(List.of(openHandler));
        BrowserActionResult result = dispatcher.dispatch("nonexistent", BrowserInputs.action("nonexistent").build(),
                session, 30000);

        assertThat(result.isError()).isTrue();
        assertThat(result.getError()).isEqualTo("UNKNOWN_ACTION");
        assertThat(result.getMessage()).contains("nonexistent");
    }

    @Test
    void shouldRejectDuplicateHandlers() {
        BrowserActionHandler handler1 = mock(BrowserActionHandler.class);
        when(handler1.getActionType()).thenReturn("open");

        BrowserActionHandler handler2 = mock(BrowserActionHandler.class);
        when(handler2.getActionType()).thenReturn("open");

        assertThatThrownBy(() -> new BrowserActionDispatcher(List.of(handler1, handler2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate handler for action: open");
    }

    @Test
    void shouldAcceptNullHandlerList() {
        assertThatThrownBy(() -> new BrowserActionDispatcher(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldHandleEmptyHandlerList() {
        BrowserSession session = mock(BrowserSession.class);
        when(session.getId()).thenReturn("bs_test");

        BrowserActionDispatcher dispatcher = new BrowserActionDispatcher(List.of());
        BrowserActionResult result = dispatcher.dispatch("open", BrowserInputs.action("open").build(), session, 30000);

        assertThat(result.isError()).isTrue();
        assertThat(result.getError()).isEqualTo("UNKNOWN_ACTION");
    }
}
