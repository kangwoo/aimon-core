package at.aimon.browser.playwright.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.Map;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;

import at.aimon.browser.playwright.BrowserInput;
import at.aimon.browser.playwright.BrowserInputs;
import at.aimon.browser.playwright.BrowserSession;
import at.aimon.core.credential.CredentialStore;
import at.aimon.core.credential.InMemoryCredentialStore;

class TypeActionHandlerTest {

    private final TypeActionHandler handler = new TypeActionHandler(null);

    @Test
    void shouldReturnTypeActionType() {
        assertThat(handler.getActionType()).isEqualTo("type");
    }

    @Test
    void shouldTypeIntoField() {
        Page page = mock(Page.class);
        Locator locator = mock(Locator.class);
        Locator firstLocator = mock(Locator.class);
        when(page.locator("#email")).thenReturn(locator);
        when(locator.first()).thenReturn(firstLocator);
        when(page.url()).thenReturn("https://example.com");
        when(page.title()).thenReturn("Test");
        when(page.evaluate(anyString())).thenReturn(Collections.emptyList());

        BrowserSession session = mock(BrowserSession.class);
        when(session.getId()).thenReturn("bs_test");
        when(session.getActivePage()).thenReturn(page);

        BrowserInput input = BrowserInputs.action("type").selector("#email").value("test@example.com").build();
        BrowserActionResult result = handler.handle(input, session, 30000);

        assertThat(result.isError()).isFalse();
        verify(firstLocator).fill(eq("test@example.com"), any(Locator.FillOptions.class));
    }

    @Test
    void shouldClearBeforeTyping() {
        Page page = mock(Page.class);
        Locator locator = mock(Locator.class);
        Locator firstLocator = mock(Locator.class);
        when(page.locator("#input")).thenReturn(locator);
        when(locator.first()).thenReturn(firstLocator);
        when(page.url()).thenReturn("https://example.com");
        when(page.title()).thenReturn("Test");
        when(page.evaluate(anyString())).thenReturn(Collections.emptyList());

        BrowserSession session = mock(BrowserSession.class);
        when(session.getId()).thenReturn("bs_test");
        when(session.getActivePage()).thenReturn(page);

        BrowserInput input = BrowserInputs.action("type").selector("#input").value("new text").clear(true).build();
        BrowserActionResult result = handler.handle(input, session, 30000);

        assertThat(result.isError()).isFalse();
        // First fill("") to clear, then fill("new text")
        verify(firstLocator).fill(eq(""), any(Locator.FillOptions.class));
        verify(firstLocator).fill(eq("new text"), any(Locator.FillOptions.class));
    }

    @Test
    void shouldReturnErrorWithoutSelectorOrText() {
        Page page = mock(Page.class);

        BrowserSession session = mock(BrowserSession.class);
        when(session.getId()).thenReturn("bs_test");
        when(session.getActivePage()).thenReturn(page);

        BrowserInput input = BrowserInputs.action("type").value("test").build();
        BrowserActionResult result = handler.handle(input, session, 30000);

        assertThat(result.isError()).isTrue();
        assertThat(result.getError()).isEqualTo("INVALID_PARAMETER");
    }

    @Test
    void shouldResolveCredentialRef() {
        CredentialStore store = InMemoryCredentialStore.builder().profile("jira", Map.of("password", "secret123"))
                .build();
        TypeActionHandler handlerWithStore = new TypeActionHandler(store);

        Page page = mock(Page.class);
        Locator locator = mock(Locator.class);
        Locator firstLocator = mock(Locator.class);
        when(page.locator("#password")).thenReturn(locator);
        when(locator.first()).thenReturn(firstLocator);
        when(page.url()).thenReturn("https://example.com");
        when(page.title()).thenReturn("Test");
        when(page.evaluate(anyString())).thenReturn(Collections.emptyList());

        BrowserSession session = mock(BrowserSession.class);
        when(session.getId()).thenReturn("bs_test");
        when(session.getActivePage()).thenReturn(page);

        BrowserInput input = BrowserInputs.action("type").selector("#password").credentialRef("jira.password").build();
        BrowserActionResult result = handlerWithStore.handle(input, session, 30000);

        assertThat(result.isError()).isFalse();
        verify(firstLocator).fill(eq("secret123"), any(Locator.FillOptions.class));
    }

    @Test
    void shouldReturnErrorWhenCredentialNotFound() {
        CredentialStore store = InMemoryCredentialStore.builder().profile("jira", Map.of("username", "admin")).build();
        TypeActionHandler handlerWithStore = new TypeActionHandler(store);

        Page page = mock(Page.class);

        BrowserSession session = mock(BrowserSession.class);
        when(session.getId()).thenReturn("bs_test");
        when(session.getActivePage()).thenReturn(page);

        BrowserInput input = BrowserInputs.action("type").selector("#password").credentialRef("jira.password").build();
        BrowserActionResult result = handlerWithStore.handle(input, session, 30000);

        assertThat(result.isError()).isTrue();
        assertThat(result.getError()).isEqualTo("CREDENTIAL_ERROR");
        assertThat(result.getMessage()).contains("Credential not found");
    }

    @Test
    void shouldReturnErrorWhenCredentialStoreNotConfigured() {
        TypeActionHandler handlerNoStore = new TypeActionHandler(null);

        Page page = mock(Page.class);

        BrowserSession session = mock(BrowserSession.class);
        when(session.getId()).thenReturn("bs_test");
        when(session.getActivePage()).thenReturn(page);

        BrowserInput input = BrowserInputs.action("type").selector("#password").credentialRef("jira.password").build();
        BrowserActionResult result = handlerNoStore.handle(input, session, 30000);

        assertThat(result.isError()).isTrue();
        assertThat(result.getError()).isEqualTo("CREDENTIAL_ERROR");
        assertThat(result.getMessage()).contains("CredentialStore");
    }

    @Test
    void shouldReturnErrorForInvalidCredentialRefFormat() {
        CredentialStore store = InMemoryCredentialStore.builder().build();
        TypeActionHandler handlerWithStore = new TypeActionHandler(store);

        Page page = mock(Page.class);

        BrowserSession session = mock(BrowserSession.class);
        when(session.getId()).thenReturn("bs_test");
        when(session.getActivePage()).thenReturn(page);

        BrowserInput input = BrowserInputs.action("type").selector("#password").credentialRef("invalid_format").build();
        BrowserActionResult result = handlerWithStore.handle(input, session, 30000);

        assertThat(result.isError()).isTrue();
        assertThat(result.getError()).isEqualTo("INVALID_PARAMETER");
        assertThat(result.getMessage()).contains("profile.field");
    }

    @Test
    void shouldReturnErrorWhenCredentialRefAndValueBothProvided() {
        CredentialStore store = InMemoryCredentialStore.builder().profile("jira", Map.of("password", "secret123"))
                .build();
        TypeActionHandler handlerWithStore = new TypeActionHandler(store);

        Page page = mock(Page.class);

        BrowserSession session = mock(BrowserSession.class);
        when(session.getId()).thenReturn("bs_test");
        when(session.getActivePage()).thenReturn(page);

        BrowserInput input = BrowserInputs.action("type").selector("#password").credentialRef("jira.password")
                .value("plain_text").build();
        BrowserActionResult result = handlerWithStore.handle(input, session, 30000);

        assertThat(result.isError()).isTrue();
        assertThat(result.getError()).isEqualTo("INVALID_PARAMETER");
        assertThat(result.getMessage()).contains("Cannot specify both");
    }

    @Test
    void shouldReturnErrorForMultiDotCredentialRef() {
        CredentialStore store = InMemoryCredentialStore.builder().build();
        TypeActionHandler handlerWithStore = new TypeActionHandler(store);

        Page page = mock(Page.class);

        BrowserSession session = mock(BrowserSession.class);
        when(session.getId()).thenReturn("bs_test");
        when(session.getActivePage()).thenReturn(page);

        BrowserInput input = BrowserInputs.action("type").selector("#field").credentialRef("aws.s3.secret_key").build();
        BrowserActionResult result = handlerWithStore.handle(input, session, 30000);

        assertThat(result.isError()).isTrue();
        assertThat(result.getError()).isEqualTo("INVALID_PARAMETER");
        assertThat(result.getMessage()).contains("exactly one dot");
    }

    @Test
    void shouldNotExposeCredentialRefInErrorMessage() {
        CredentialStore store = InMemoryCredentialStore.builder().profile("jira", Map.of("username", "admin")).build();
        TypeActionHandler handlerWithStore = new TypeActionHandler(store);

        Page page = mock(Page.class);

        BrowserSession session = mock(BrowserSession.class);
        when(session.getId()).thenReturn("bs_test");
        when(session.getActivePage()).thenReturn(page);

        BrowserInput input = BrowserInputs.action("type").selector("#password").credentialRef("jira.password").build();
        BrowserActionResult result = handlerWithStore.handle(input, session, 30000);

        assertThat(result.isError()).isTrue();
        assertThat(result.getMessage()).doesNotContain("jira.password");
    }

    @Nested
    class IsSensitiveTest {

        @Test
        void shouldReturnFalseForNull() {
            assertThat(TypeActionHandler.isSensitive(null)).isFalse();
        }

        @Test
        void shouldReturnFalseForNonSensitiveSelector() {
            assertThat(TypeActionHandler.isSensitive("#email")).isFalse();
            assertThat(TypeActionHandler.isSensitive("#username")).isFalse();
            assertThat(TypeActionHandler.isSensitive("input[name=search]")).isFalse();
        }

        @Test
        void shouldDetectPassword() {
            assertThat(TypeActionHandler.isSensitive("#password")).isTrue();
            assertThat(TypeActionHandler.isSensitive("input[name=Password]")).isTrue();
        }

        @Test
        void shouldDetectSecret() {
            assertThat(TypeActionHandler.isSensitive("#secret_key")).isTrue();
        }

        @Test
        void shouldDetectToken() {
            assertThat(TypeActionHandler.isSensitive("#api_token")).isTrue();
        }

        @Test
        void shouldDetectPin() {
            assertThat(TypeActionHandler.isSensitive("#pin_code")).isTrue();
            assertThat(TypeActionHandler.isSensitive("input[name=PIN]")).isTrue();
        }

        @Test
        void shouldDetectSsn() {
            assertThat(TypeActionHandler.isSensitive("#ssn")).isTrue();
            assertThat(TypeActionHandler.isSensitive("#SSN_field")).isTrue();
        }

        @Test
        void shouldDetectCreditCard() {
            assertThat(TypeActionHandler.isSensitive("#credit_card_number")).isTrue();
            assertThat(TypeActionHandler.isSensitive("#card_number")).isTrue();
        }

        @Test
        void shouldDetectCvv() {
            assertThat(TypeActionHandler.isSensitive("#cvv")).isTrue();
        }

        @Test
        void shouldDetectOtp() {
            assertThat(TypeActionHandler.isSensitive("#otp_input")).isTrue();
        }

        @Test
        void shouldDetectPassphrase() {
            assertThat(TypeActionHandler.isSensitive("#passphrase")).isTrue();
        }

        @Test
        void shouldDetectAuth() {
            assertThat(TypeActionHandler.isSensitive("#auth_code")).isTrue();
        }

        @Test
        void shouldBeCaseInsensitive() {
            assertThat(TypeActionHandler.isSensitive("#PASSWORD")).isTrue();
            assertThat(TypeActionHandler.isSensitive("#SecretKey")).isTrue();
            assertThat(TypeActionHandler.isSensitive("#OTP_Input")).isTrue();
        }
    }
}
