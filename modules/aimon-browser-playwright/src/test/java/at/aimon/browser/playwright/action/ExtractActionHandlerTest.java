package at.aimon.browser.playwright.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;

import at.aimon.browser.playwright.BrowserInput;
import at.aimon.browser.playwright.BrowserInputs;
import at.aimon.browser.playwright.BrowserSession;
import at.aimon.core.tools.web.fetch.ContentExtractor;

class ExtractActionHandlerTest {

    private final ContentExtractor contentExtractor = mock(ContentExtractor.class);
    private final ExtractActionHandler handler = new ExtractActionHandler(contentExtractor);

    @Test
    void shouldReturnExtractActionType() {
        assertThat(handler.getActionType()).isEqualTo("extract");
    }

    @Test
    void shouldExtractTextContent() {
        Page page = mock(Page.class);
        when(page.innerText("body")).thenReturn("Hello World");
        when(page.url()).thenReturn("https://example.com");
        when(page.title()).thenReturn("Test");

        BrowserSession session = mock(BrowserSession.class);
        when(session.getId()).thenReturn("bs_test");
        when(session.getActivePage()).thenReturn(page);

        BrowserInput input = BrowserInputs.action("extract").mode("text").build();
        BrowserActionResult result = handler.handle(input, session, 30000);

        assertThat(result.isError()).isFalse();
        assertThat(result.getContent()).isEqualTo("Hello World");
    }

    @Test
    void shouldExtractHtmlContent() {
        Page page = mock(Page.class);
        when(page.content()).thenReturn("<html><body>Hello</body></html>");
        when(page.url()).thenReturn("https://example.com");
        when(page.title()).thenReturn("Test");

        BrowserSession session = mock(BrowserSession.class);
        when(session.getId()).thenReturn("bs_test");
        when(session.getActivePage()).thenReturn(page);

        BrowserInput input = BrowserInputs.action("extract").mode("html").build();
        BrowserActionResult result = handler.handle(input, session, 30000);

        assertThat(result.isError()).isFalse();
        assertThat(result.getContent()).contains("<html>");
    }

    @Test
    void shouldExtractMarkdownContent() {
        Page page = mock(Page.class);
        when(page.content()).thenReturn("<html><body><h1>Title</h1></body></html>");
        when(page.url()).thenReturn("https://example.com");
        when(page.title()).thenReturn("Test");
        when(contentExtractor.extract(anyString(), anyString(), eq("markdown"))).thenReturn("# Title");

        BrowserSession session = mock(BrowserSession.class);
        when(session.getId()).thenReturn("bs_test");
        when(session.getActivePage()).thenReturn(page);

        BrowserInput input = BrowserInputs.action("extract").mode("markdown").build();
        BrowserActionResult result = handler.handle(input, session, 30000);

        assertThat(result.isError()).isFalse();
        assertThat(result.getContent()).isEqualTo("# Title");
    }

    @Test
    void shouldExtractFromSelector() {
        Page page = mock(Page.class);
        Locator locator = mock(Locator.class);
        when(page.locator("#main")).thenReturn(locator);
        when(locator.innerText()).thenReturn("Main content");
        when(page.url()).thenReturn("https://example.com");
        when(page.title()).thenReturn("Test");

        BrowserSession session = mock(BrowserSession.class);
        when(session.getId()).thenReturn("bs_test");
        when(session.getActivePage()).thenReturn(page);

        BrowserInput input = BrowserInputs.action("extract").mode("text").selector("#main").build();
        BrowserActionResult result = handler.handle(input, session, 30000);

        assertThat(result.isError()).isFalse();
        assertThat(result.getContent()).isEqualTo("Main content");
    }

    @Test
    void shouldTruncateContent() {
        Page page = mock(Page.class);
        String longText = "x".repeat(60000);
        when(page.innerText("body")).thenReturn(longText);
        when(page.url()).thenReturn("https://example.com");
        when(page.title()).thenReturn("Test");

        BrowserSession session = mock(BrowserSession.class);
        when(session.getId()).thenReturn("bs_test");
        when(session.getActivePage()).thenReturn(page);

        BrowserInput input = BrowserInputs.action("extract").mode("text").build();
        BrowserActionResult result = handler.handle(input, session, 30000);

        assertThat(result.getContent()).hasSize(50000);
    }

    @Test
    void shouldReturnErrorForInvalidMode() {
        Page page = mock(Page.class);

        BrowserSession session = mock(BrowserSession.class);
        when(session.getId()).thenReturn("bs_test");
        when(session.getActivePage()).thenReturn(page);

        BrowserInput input = BrowserInputs.action("extract").mode("invalid").build();
        BrowserActionResult result = handler.handle(input, session, 30000);

        assertThat(result.isError()).isTrue();
        assertThat(result.getError()).isEqualTo("INVALID_MODE");
    }

    @Test
    void shouldDefaultToTextMode() {
        Page page = mock(Page.class);
        when(page.innerText("body")).thenReturn("Default text");
        when(page.url()).thenReturn("https://example.com");
        when(page.title()).thenReturn("Test");

        BrowserSession session = mock(BrowserSession.class);
        when(session.getId()).thenReturn("bs_test");
        when(session.getActivePage()).thenReturn(page);

        BrowserInput input = BrowserInputs.action("extract").build();
        BrowserActionResult result = handler.handle(input, session, 30000);

        assertThat(result.isError()).isFalse();
        assertThat(result.getContent()).isEqualTo("Default text");
    }
}
