package at.aimon.browser.playwright.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;

import at.aimon.browser.playwright.BrowserInput;
import at.aimon.browser.playwright.BrowserInputs;
import at.aimon.browser.playwright.BrowserSession;

class SaveAuthActionHandlerTest {

    private final SaveAuthActionHandler handler = new SaveAuthActionHandler();

    @Test
    void shouldReturnSaveAuthActionType() {
        assertThat(handler.getActionType()).isEqualTo("save_auth");
    }

    @Test
    void shouldReturnStorageStateAsContent() {
        String storageStateJson = "{\"cookies\":[],\"origins\":[]}";

        BrowserContext context = mock(BrowserContext.class);
        when(context.storageState()).thenReturn(storageStateJson);

        Page page = mock(Page.class);
        when(page.url()).thenReturn("https://example.com/dashboard");
        when(page.title()).thenReturn("Dashboard");

        BrowserSession session = mock(BrowserSession.class);
        when(session.getId()).thenReturn("bs_test");
        when(session.getContext()).thenReturn(context);
        when(session.getActivePage()).thenReturn(page);

        BrowserInput input = BrowserInputs.action("save_auth").build();
        BrowserActionResult result = handler.handle(input, session, 30000);

        assertThat(result.isError()).isFalse();
        assertThat(result.getAction()).isEqualTo("save_auth");
        assertThat(result.getContent()).isEqualTo(storageStateJson);
        assertThat(result.getMessage()).contains("Storage state saved");
        assertThat(result.getUrl()).isEqualTo("https://example.com/dashboard");
        assertThat(result.getTitle()).isEqualTo("Dashboard");
    }

    @Test
    void shouldReturnErrorWhenStorageStateFails() {
        BrowserContext context = mock(BrowserContext.class);
        when(context.storageState()).thenThrow(new RuntimeException("Browser disconnected"));

        Page page = mock(Page.class);

        BrowserSession session = mock(BrowserSession.class);
        when(session.getId()).thenReturn("bs_test");
        when(session.getContext()).thenReturn(context);
        when(session.getActivePage()).thenReturn(page);

        BrowserInput input = BrowserInputs.action("save_auth").build();
        BrowserActionResult result = handler.handle(input, session, 30000);

        assertThat(result.isError()).isTrue();
        assertThat(result.getError()).isEqualTo("STORAGE_STATE_ERROR");
        assertThat(result.getMessage()).contains("Browser disconnected");
    }
}
