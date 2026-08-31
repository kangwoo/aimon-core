package at.aimon.browser.playwright.action;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.TimeoutError;

import at.aimon.browser.playwright.BrowserInput;
import at.aimon.browser.playwright.BrowserSession;
import at.aimon.browser.playwright.dom.CandidateExtractor;
import at.aimon.core.credential.CredentialStore;

/**
 * 입력 필드에 텍스트를 입력하는 액션 핸들러.
 *
 * <p>
 * {@code fill()}을 기본으로 사용 (타이핑보다 안정적).
 * 비밀번호 입력 시 로그에 value를 마스킹한다.
 *
 * <p>
 * {@code credential_ref} 파라미터를 사용하면 {@link CredentialStore}에서
 * 실제 값을 조회하여 LLM이 민감 정보를 직접 다루지 않도록 한다.
 * 형식: {@code "profile.field"} (예: {@code "jira.password"}).
 */
public class TypeActionHandler implements BrowserActionHandler {

    private static final Logger log = LoggerFactory.getLogger(TypeActionHandler.class);

    private final CredentialStore credentialStore;

    /**
     * TypeActionHandler를 생성한다.
     *
     * @param credentialStore
     *            인증 정보 저장소 (null 허용 — null이면 credential_ref 미지원)
     */
    public TypeActionHandler(CredentialStore credentialStore) {
        this.credentialStore = credentialStore;
    }

    @Override
    public String getActionType() {
        return "type";
    }

    @Override
    public BrowserActionResult handle(BrowserInput input, BrowserSession session, int timeoutMs) {
        Page page = session.getActivePage();
        String selector = input.selector();
        String text = input.text();
        String credentialRef = input.credentialRef();
        boolean clear = Boolean.TRUE.equals(input.clear());

        try {
            // credential_ref와 value 동시 사용 금지
            String rawValue = input.value();
            if (credentialRef != null && rawValue != null) {
                throw new IllegalArgumentException("Cannot specify both 'credential_ref' and 'value'");
            }

            // credential_ref 또는 value 중 하나로 실제 입력값 결정
            final String value;
            final boolean sensitive;

            if (credentialRef != null) {
                value = resolveCredentialRef(credentialRef);
                sensitive = true;
            } else {
                value = BrowserActionHandler.require(rawValue, "value");
                sensitive = isSensitive(selector) || isSensitive(text);
            }

            String logValue = sensitive ? "***" : value;
            log.debug("Typing into: {} (value: {})", selector != null ? selector : text, logValue);

            Locator locator = resolveLocator(page, selector, text);

            if (clear) {
                locator.fill("", new Locator.FillOptions().setTimeout(timeoutMs));
            }
            locator.fill(value, new Locator.FillOptions().setTimeout(timeoutMs));

            return BrowserActionResult.success(session.getId(), "type").url(page.url()).title(page.title())
                    .candidates(CandidateExtractor.extract(page)).build();

        } catch (TimeoutError e) {
            return BrowserActionResult.builder().sessionId(session.getId()).action("type").error("ELEMENT_NOT_FOUND")
                    .message("No element found for the given criteria").candidates(CandidateExtractor.extract(page))
                    .build();
        } catch (IllegalArgumentException e) {
            return BrowserActionResult.error(session.getId(), "type", "INVALID_PARAMETER", e.getMessage());
        } catch (IllegalStateException e) {
            return BrowserActionResult.error(session.getId(), "type", "CREDENTIAL_ERROR", e.getMessage());
        }
    }

    /**
     * credential_ref ("profile.field") 형식을 파싱하여 CredentialStore에서 값을 조회한다.
     *
     * @param credentialRef
     *            "profile.field" 형식의 참조 키
     * @return 실제 credential 값
     * @throws IllegalStateException
     *             CredentialStore 미설정, 프로필/필드 미발견
     * @throws IllegalArgumentException
     *             잘못된 형식
     */
    private String resolveCredentialRef(String credentialRef) {
        if (credentialStore == null) {
            throw new IllegalStateException("credential_ref requires a CredentialStore, but none is configured");
        }

        int firstDot = credentialRef.indexOf('.');
        if (firstDot <= 0 || firstDot >= credentialRef.length() - 1) {
            throw new IllegalArgumentException(
                    "credential_ref must be in 'profile.field' format, got: " + credentialRef);
        }

        // dot은 정확히 하나여야 한다 (e.g., "jira.password")
        int secondDot = credentialRef.indexOf('.', firstDot + 1);
        if (secondDot >= 0) {
            throw new IllegalArgumentException(
                    "credential_ref must contain exactly one dot (profile.field), got: " + credentialRef);
        }

        String profile = credentialRef.substring(0, firstDot);
        String field = credentialRef.substring(firstDot + 1);

        return credentialStore.get(profile, field)
                .orElseThrow(() -> new IllegalStateException("Credential not found for the given reference"));
    }

    private static Locator resolveLocator(Page page, String selector, String text) {
        if (selector != null) {
            return page.locator(selector).first();
        }
        if (text != null) {
            return page.getByText(text);
        }
        throw new IllegalArgumentException("type requires at least one of: selector, text");
    }

    private static final List<String> SENSITIVE_KEYWORDS = List.of("password", "secret", "token", "pin", "ssn",
            "credit", "card", "cvv", "otp", "passphrase", "auth");

    static boolean isSensitive(String selector) {
        if (selector == null) {
            return false;
        }
        String lower = selector.toLowerCase();
        return SENSITIVE_KEYWORDS.stream().anyMatch(lower::contains);
    }
}
