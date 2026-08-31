package at.aimon.browser.playwright;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;

class BrowserSessionTest {

    private BrowserContext context;
    private Page initialPage;

    @BeforeEach
    void setUp() {
        context = mock(BrowserContext.class);
        initialPage = mock(Page.class);
    }

    // --- Constructor null validation ---

    @Test
    void shouldThrowNpeWhenIdIsNull() {
        assertThatNullPointerException().isThrownBy(() -> new BrowserSession(null, context, initialPage, "minimal", 0))
                .withMessageContaining("id");
    }

    @Test
    void shouldThrowNpeWhenContextIsNull() {
        assertThatNullPointerException().isThrownBy(() -> new BrowserSession("bs_1", null, initialPage, "minimal", 0))
                .withMessageContaining("context");
    }

    @Test
    void shouldThrowNpeWhenInitialPageIsNull() {
        assertThatNullPointerException().isThrownBy(() -> new BrowserSession("bs_1", context, null, "minimal", 0))
                .withMessageContaining("initialPage");
    }

    @Test
    void shouldThrowNpeWhenResourcePolicyIsNull() {
        assertThatNullPointerException().isThrownBy(() -> new BrowserSession("bs_1", context, initialPage, null, 0))
                .withMessageContaining("resourcePolicy");
    }

    // --- Initialization ---

    @Test
    void shouldInitializeWithCorrectValues() {
        BrowserSession session = new BrowserSession("bs_test", context, initialPage, "visual", 2);

        assertThat(session.getId()).isEqualTo("bs_test");
        assertThat(session.getContext()).isSameAs(context);
        assertThat(session.getActivePage()).isSameAs(initialPage);
        assertThat(session.getResourcePolicy()).isEqualTo("visual");
        assertThat(session.getWorkerIndex()).isEqualTo(2);
    }

    @Test
    void shouldSetCreatedAtAndLastUsedAtOnConstruction() {
        Instant before = Instant.now();
        BrowserSession session = new BrowserSession("bs_test", context, initialPage, "minimal", 0);
        Instant after = Instant.now();

        assertThat(session.getCreatedAt()).isBetween(before, after);
        assertThat(session.getLastUsedAt()).isEqualTo(session.getCreatedAt());
    }

    // --- touch() ---

    @Test
    void shouldUpdateLastUsedAtOnTouch() throws Exception {
        BrowserSession session = new BrowserSession("bs_test", context, initialPage, "minimal", 0);
        Instant initialLastUsed = session.getLastUsedAt();

        Thread.sleep(10);
        session.touch();

        assertThat(session.getLastUsedAt()).isAfter(initialLastUsed);
    }

    // --- isExpired() ---

    @Test
    void shouldNotBeExpiredWithLongTtl() {
        BrowserSession session = new BrowserSession("bs_test", context, initialPage, "minimal", 0);

        assertThat(session.isExpired(Duration.ofHours(1))).isFalse();
    }

    @Test
    void shouldBeExpiredWithZeroDurationTtl() throws Exception {
        BrowserSession session = new BrowserSession("bs_test", context, initialPage, "minimal", 0);

        Thread.sleep(10);

        assertThat(session.isExpired(Duration.ZERO)).isTrue();
    }

    @Test
    void shouldResetExpiryOnTouch() throws Exception {
        BrowserSession session = new BrowserSession("bs_test", context, initialPage, "minimal", 0);

        Thread.sleep(10);
        // Should be expired with very short TTL
        assertThat(session.isExpired(Duration.ofMillis(5))).isTrue();

        // Touch resets the timer
        session.touch();
        assertThat(session.isExpired(Duration.ofHours(1))).isFalse();
    }

    // --- closeInternal() ---

    @Test
    void shouldCloseContext() {
        BrowserSession session = new BrowserSession("bs_test", context, initialPage, "minimal", 0);

        session.closeInternal();

        verify(context).close();
    }

    @Test
    void shouldAbsorbExceptionOnClose() {
        doThrow(new RuntimeException("already closed")).when(context).close();
        BrowserSession session = new BrowserSession("bs_test", context, initialPage, "minimal", 0);

        // Should not throw
        session.closeInternal();

        verify(context).close();
    }

    // --- onPage listener ---

    @SuppressWarnings("unchecked")
    @Test
    void shouldRegisterOnPageListener() {
        new BrowserSession("bs_test", context, initialPage, "minimal", 0);

        verify(context).onPage(org.mockito.ArgumentMatchers.any(Consumer.class));
    }

    @SuppressWarnings("unchecked")
    @Test
    void shouldSwitchActivePageOnNewPage() {
        ArgumentCaptor<Consumer<Page>> captor = ArgumentCaptor.forClass(Consumer.class);
        BrowserSession session = new BrowserSession("bs_test", context, initialPage, "minimal", 0);
        verify(context).onPage(captor.capture());

        assertThat(session.getActivePage()).isSameAs(initialPage);

        // Simulate new page opened
        Page newPage = mock(Page.class);
        captor.getValue().accept(newPage);

        assertThat(session.getActivePage()).isSameAs(newPage);
    }
}
